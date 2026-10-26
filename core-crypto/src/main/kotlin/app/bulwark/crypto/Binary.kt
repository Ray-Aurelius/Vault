package app.bulwark.crypto

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Big-endian writer for the small fixed binary formats. */
internal class BinaryWriter {
    private val out = ByteArrayOutputStream()
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun u8(v: Int) = apply { require(v in 0..0xFF); out.write(v) }
    fun u16(v: Int) = apply { require(v in 0..0xFFFF); out.write(v ushr 8); out.write(v and 0xFF) }
    fun u32(v: Long) = apply { require(v in 0..0xFFFF_FFFFL); for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
    fun u64(v: Long) = apply { require(v >= 0); for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Big-endian reader that reports malformed input as [CorruptDataException], never other errors. */
internal class BinaryReader(private val data: ByteArray) {
    var position = 0
        private set
    val remaining get() = data.size - position

    fun bytes(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw CorruptDataException("Unexpected end of data")
        return data.copyOfRange(position, position + n).also { position += n }
    }
    fun u8(): Int = bytes(1)[0].toInt() and 0xFF
    fun u16(): Int = bytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    fun u32(): Long = bytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    fun u64(): Long {
        val v = bytes(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        if (v < 0) throw CorruptDataException("Length out of range")
        return v
    }
    fun requireEnd() { if (remaining != 0) throw CorruptDataException("Unexpected trailing data") }
}

/** Reads until [buf] is full or the stream ends; returns the number of bytes read. */
internal fun InputStream.readUpTo(buf: ByteArray, length: Int = buf.size): Int {
    var total = 0
    while (total < length) {
        val n = read(buf, total, length - total)
        if (n < 0) break
        total += n
    }
    return total
}

internal fun InputStream.readExactly(n: Int): ByteArray {
    val buf = ByteArray(n)
    if (readUpTo(buf) != n) throw TruncatedDataException("Data ended early")
    return buf
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
