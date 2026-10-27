package app.bulwark.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileCipherTest {
    private val p = TestSupport.primitives
    private val cipher = FileCipher(p)
    private val small = FileCipher(p, chunkSize = 4096) // many chunks with little data

    private fun encrypt(c: FileCipher, key: SecretBytes, id: FileId, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val n = c.encrypt(key, id, ByteArrayInputStream(data), data.size.toLong(), out)
        assertEquals(n, out.size().toLong())
        return out.toByteArray()
    }

    private fun decrypt(c: FileCipher, key: SecretBytes, id: FileId, blob: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        c.decrypt(key, id, ByteArrayInputStream(blob), out)
        return out.toByteArray()
    }

    private fun key() = SecretBytes.adopt(p.randomBytes(32))

    @Test
    fun `round trips across chunk and padding boundaries`() {
        val sizes = listOf(0, 1, 7, 8, 100, 4087, 4088, 4089, 4096, 4097, 8192, 65_528, 65_536, 65_537, 200_003, 1_000_000)
        for (c in listOf(cipher, small)) for (size in sizes) {
            val data = Random(size).nextBytes(size)
            val k = key(); val id = FileId.random(p)
            val blob = encrypt(c, k, id, data)
            assertEquals(c.encryptedSize(size.toLong()), blob.size.toLong(), "size prediction for $size")
            assertContentEquals(data, decrypt(c, k, id, blob), "round trip for $size")
        }
    }

    @Test
    fun `stored size is padded so it does not reveal the exact length`() {
        val k = key(); val id = FileId.random(p)
        val a = encrypt(cipher, k, id, ByteArray(1_000_001))
        val b = encrypt(cipher, k, id, ByteArray(1_000_900))
        assertEquals(a.size, b.size)
    }

    @Test
    fun `same file encrypts differently every time`() {
        val k = key(); val id = FileId.random(p); val data = ByteArray(5000) { 7 }
        val a = encrypt(cipher, k, id, data); val b = encrypt(cipher, k, id, data)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun `wrong key is rejected`() {
        val id = FileId.random(p)
        val blob = encrypt(cipher, key(), id, ByteArray(1000))
        assertFailsWith<CorruptDataException> { decrypt(cipher, key(), id, blob) }
    }

    @Test
    fun `any flipped bit is detected, in header or body`() {
        val k = key(); val id = FileId.random(p)
        val blob = encrypt(small, k, id, Random(1).nextBytes(10_000))
        val positions = listOf(0, 4, 5, 8, 30, 40, 51, 52, 60, blob.size / 2, blob.size - 1)
        for (pos in positions) {
            val bad = blob.copyOf(); bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
            assertFailsWith<BulwarkCryptoException>("flip at $pos") { decrypt(small, k, id, bad) }
        }
    }

    @Test
    fun `truncation is detected at every cut point class`() {
        val k = key(); val id = FileId.random(p)
        val blob = encrypt(small, k, id, Random(2).nextBytes(20_000))
        val chunk = 4096 + Primitives.STREAM_ABYTES
        val cuts = listOf(0, 10, FileCipher.HEADER_BYTES, FileCipher.HEADER_BYTES + chunk, FileCipher.HEADER_BYTES + 2 * chunk, blob.size - 1)
        for (cut in cuts) {
            assertFailsWith<BulwarkCryptoException>("cut at $cut") { decrypt(small, k, id, blob.copyOf(cut)) }
        }
    }

    @Test
    fun `extra bytes after the end are rejected`() {
        val k = key(); val id = FileId.random(p)
        val blob = encrypt(cipher, k, id, ByteArray(300))
        assertFailsWith<CorruptDataException> { decrypt(cipher, k, id, blob + byteArrayOf(0)) }
    }

    @Test
    fun `reordered chunks are rejected`() {
        val k = key(); val id = FileId.random(p)
        val blob = encrypt(small, k, id, Random(3).nextBytes(20_000))
        val h = FileCipher.HEADER_BYTES; val c = 4096 + Primitives.STREAM_ABYTES
        val swapped = blob.copyOf()
        blob.copyInto(swapped, h, h + c, h + 2 * c)
        blob.copyInto(swapped, h + c, h, h + c)
        assertFailsWith<CorruptDataException> { decrypt(small, k, id, swapped) }
    }

    @Test
    fun `a file cannot be passed off as another record`() {
        val k = key(); val id = FileId.random(p)
        val blob = encrypt(cipher, k, id, ByteArray(10))
        assertFailsWith<CorruptDataException> { decrypt(cipher, k, FileId.random(p), blob) }
    }

    @Test
    fun `chunks from another file with the same key do not splice in`() {
        val k = key(); val id1 = FileId.random(p); val id2 = FileId.random(p)
        val a = encrypt(small, k, id1, Random(4).nextBytes(10_000))
        val b = encrypt(small, k, id2, Random(5).nextBytes(10_000))
        val mixed = a.copyOf(); b.copyInto(mixed, FileCipher.HEADER_BYTES, FileCipher.HEADER_BYTES, FileCipher.HEADER_BYTES + 4113)
        assertFailsWith<CorruptDataException> { decrypt(small, k, id1, mixed) }
    }

    @Test
    fun `source length must match what was declared`() {
        val k = key(); val id = FileId.random(p)
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(k, id, ByteArrayInputStream(ByteArray(10)), 11, ByteArrayOutputStream())
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(k, id, ByteArrayInputStream(ByteArray(12)), 11, ByteArrayOutputStream())
        }
    }

    @Test
    fun `slow sources that return a few bytes at a time still work`() {
        val data = Random(6).nextBytes(70_000)
        val trickle = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < data.size) data[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= data.size) return -1
                val n = minOf(len, 3, data.size - i); data.copyInto(b, off, i, i + n); i += n; return n
            }
        }
        val k = key(); val id = FileId.random(p); val out = ByteArrayOutputStream()
        cipher.encrypt(k, id, trickle, data.size.toLong(), out)
        assertContentEquals(data, decrypt(cipher, k, id, out.toByteArray()))
    }

    @Test
    fun `garbage input fails cleanly with a Bulwark error`() {
        val k = key(); val id = FileId.random(p)
        repeat(200) { i ->
            val junk = Random(i).nextBytes(Random(i).nextInt(0, 300))
            assertFailsWith<BulwarkCryptoException> { decrypt(cipher, k, id, junk) }
        }
    }
}
