package app.bulwark.crypto

import java.io.InputStream
import java.io.OutputStream

/**
 * Bulwark encrypted file format, version 1. See docs/file-format.md for the full specification.
 *
 *     Header (52 bytes, plaintext but authenticated):
 *       magic "BWFE" | version u8 = 1 | suite u8 = 1 | reserved u16 = 0 | fileId[16]
 *       chunkSize u32 | secretstreamHeader[24]
 *     Body: secretstream chunks of (chunkSize + 17) bytes; the last may be shorter and carries the
 *       FINAL tag. Every chunk is authenticated with the whole header as associated data.
 *     Plaintext stream inside the chunks:
 *       length u64 | data[length] | zero padding up to Padmé(8 + length)
 *
 * Guarantees: any change, reordering, truncation, extension or swap between files is detected;
 * nothing is returned to the caller from a chunk before that chunk has been authenticated.
 * Decryption writes plaintext progressively, so a caller that must not show partial data on
 * failure should buffer or discard its output when an exception is thrown.
 */
class FileCipher(private val primitives: Primitives, private val chunkSize: Int = DEFAULT_CHUNK_SIZE) {

    init {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) { "Chunk size out of range" }
    }

    /**
     * Encrypts exactly [length] bytes from [source] into [sink]. Throws if [source] holds a
     * different number of bytes, in which case the output must be discarded.
     * Returns the number of ciphertext bytes written.
     */
    fun encrypt(fileKey: SecretBytes, fileId: FileId, source: InputStream, length: Long, sink: OutputStream): Long {
        require(length >= 0) { "Negative length" }
        val total = Padme.paddedLength(LENGTH_PREFIX + length)
        val (state, streamHeader) = fileKey.withBytes { primitives.streamInitPush(it) }
        val header = header(fileId, streamHeader)
        sink.write(header)
        var written = header.size.toLong()

        val plain = PlaintextStream(source, length, total)
        val buf = ByteArray(chunkSize)
        try {
            var produced = 0L
            while (true) {
                val n = plain.fill(buf)
                produced += n
                val final = produced == total
                val ct = primitives.streamPush(state, buf, n, header, final)
                sink.write(ct)
                written += ct.size
                if (final) break
            }
        } finally {
            SecretBytes.wipe(buf)
            state.wipe()
        }
        return written
    }

    /**
     * Decrypts into [sink] and returns the original length. [expectedFileId] must match the
     * header, so a file swapped in from another record is refused.
     */
    fun decrypt(fileKey: SecretBytes, expectedFileId: FileId, source: InputStream, sink: OutputStream): Long {
        val header = try { source.readExactly(HEADER_BYTES) } catch (e: TruncatedDataException) {
            throw TruncatedDataException("File header is incomplete")
        }
        val parsed = parseHeader(header)
        if (parsed.fileId != expectedFileId) throw CorruptDataException("File ID does not match its record")
        val state = fileKey.withBytes { primitives.streamInitPull(it, parsed.streamHeader) }

        val cbuf = ByteArray(parsed.chunkSize + Primitives.STREAM_ABYTES)
        var length = -1L
        var emitted = 0L
        var seen = 0L // plaintext stream bytes seen, including prefix and padding
        try {
            while (true) {
                val n = source.readUpTo(cbuf)
                if (n == 0) throw TruncatedDataException("File ends before its final chunk")
                val (pt, final) = primitives.streamPull(state, cbuf, n, header)
                if (!final && n < cbuf.size) throw TruncatedDataException("Short chunk before the end")
                try {
                    var offset = 0
                    if (length < 0) {
                        if (pt.size < LENGTH_PREFIX) throw CorruptDataException("Missing length")
                        length = BinaryReader(pt.copyOfRange(0, LENGTH_PREFIX)).u64()
                        offset = LENGTH_PREFIX
                    }
                    seen += pt.size
                    val dataEndInStream = LENGTH_PREFIX + length
                    // Bytes of this chunk that are real data (the rest is padding).
                    val chunkStart = seen - pt.size
                    val dataUntil = (dataEndInStream - chunkStart).coerceIn(offset.toLong(), pt.size.toLong()).toInt()
                    if (dataUntil > offset) {
                        sink.write(pt, offset, dataUntil - offset)
                        emitted += dataUntil - offset
                    }
                    for (i in dataUntil until pt.size) {
                        if (pt[i] != 0.toByte()) throw CorruptDataException("Non-zero padding")
                    }
                } finally {
                    SecretBytes.wipe(pt)
                }
                if (final) {
                    if (source.read() != -1) throw CorruptDataException("Data after the final chunk")
                    if (seen != Padme.paddedLength(LENGTH_PREFIX + length)) throw CorruptDataException("Padding length mismatch")
                    if (emitted != length) throw CorruptDataException("Length mismatch")
                    return length
                }
            }
        } finally {
            SecretBytes.wipe(cbuf)
            state.wipe()
        }
    }

    /** Ciphertext size for a file of [length] bytes, useful for free-space checks before import. */
    fun encryptedSize(length: Long): Long {
        val total = Padme.paddedLength(LENGTH_PREFIX + length)
        val chunks = (total + chunkSize - 1) / chunkSize
        return HEADER_BYTES + total + chunks * Primitives.STREAM_ABYTES
    }

    private fun header(fileId: FileId, streamHeader: ByteArray): ByteArray = BinaryWriter()
        .bytes(MAGIC).u8(VERSION).u8(SUITE).u16(0).bytes(fileId.bytes).u32(chunkSize.toLong()).bytes(streamHeader)
        .toByteArray()
        .also { check(it.size == HEADER_BYTES) }

    private class ParsedHeader(val fileId: FileId, val chunkSize: Int, val streamHeader: ByteArray)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val r = BinaryReader(bytes)
        if (!r.bytes(4).contentEquals(MAGIC)) throw CorruptDataException("Not a Bulwark file")
        val version = r.u8()
        if (version != VERSION) throw UnsupportedFormatException("File format version $version")
        val suite = r.u8()
        if (suite != SUITE) throw UnsupportedFormatException("Cipher suite $suite")
        if (r.u16() != 0) throw CorruptDataException("Reserved bits set")
        val id = FileId(r.bytes(FileId.SIZE))
        val cs = r.u32()
        if (cs !in MIN_CHUNK_SIZE.toLong()..MAX_CHUNK_SIZE.toLong()) throw CorruptDataException("Chunk size out of range")
        return ParsedHeader(id, cs.toInt(), r.bytes(Primitives.STREAM_HEADER_BYTES))
    }

    /** length prefix, then the caller's bytes, then zeros, as one continuous stream. */
    private class PlaintextStream(private val source: InputStream, private val length: Long, private val total: Long) {
        private var position = 0L // position in the logical plaintext stream
        private val prefix = BinaryWriter().u64(length).toByteArray()
        private var checkedEnd = false

        fun fill(buf: ByteArray): Int {
            var n = 0
            while (n < buf.size && position < total) {
                when {
                    position < LENGTH_PREFIX -> {
                        buf[n++] = prefix[position.toInt()]; position++
                    }
                    position < LENGTH_PREFIX + length -> {
                        val want = minOf((buf.size - n).toLong(), LENGTH_PREFIX + length - position).toInt()
                        val got = source.read(buf, n, want)
                        if (got < 0) throw IllegalArgumentException("Source ended after ${position - LENGTH_PREFIX} of $length bytes")
                        n += got; position += got
                    }
                    else -> {
                        val zeros = minOf((buf.size - n).toLong(), total - position).toInt()
                        buf.fill(0, n, n + zeros)
                        n += zeros; position += zeros
                    }
                }
            }
            if (position == total && !checkedEnd) {
                checkedEnd = true
                if (source.read() != -1) throw IllegalArgumentException("Source holds more than $length bytes")
            }
            return n
        }
    }

    companion object {
        private val MAGIC = "BWFE".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val SUITE = 1
        const val HEADER_BYTES = 52
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MIN_CHUNK_SIZE = 4 * 1024
        const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
        private const val LENGTH_PREFIX = 8
    }
}
