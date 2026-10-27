package app.bulwark.vault

import app.bulwark.crypto.Argon2Params
import app.bulwark.crypto.BulwarkCryptoException
import app.bulwark.crypto.CorruptDataException
import app.bulwark.crypto.Primitives
import app.bulwark.crypto.RecoveryKey
import app.bulwark.crypto.WrongCredentialsException
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultStoreTest {
    private val p = Primitives(LazySodiumJava(SodiumJava()))
    private val fast = Argon2Params(1, 8L * 1024 * 1024)
    private val dir: File = Files.createTempDirectory("bulwark-vault").toFile()
    private var now = 1_000_000L
    private fun store() = VaultStore(File(dir, "vault"), p, NoDeviceLayer, clock = { now })
    private val pw = "orbit-velvet-candle-harbor".toCharArray()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun newVault(recovery: Boolean = false) = store().create(pw, fast, if (recovery) RecoveryKey.generate(p) else null, allowBelowFloor = true)

    private fun UnlockedVault.put(name: String, data: ByteArray, parent: app.bulwark.crypto.FileId? = null) =
        importFile(name, "application/octet-stream", data.size.toLong(), ByteArrayInputStream(data), parent)

    private fun UnlockedVault.contents(e: VaultEntry) = ByteArrayOutputStream().also { readFile(e.id, it) }.toByteArray()

    @Test
    fun `create, lock, unlock with password and get files back`() {
        val data = Random(1).nextBytes(300_000)
        val id = newVault().use { v -> v.put("photo.jpg", data).id }
        val s = store()
        assertTrue(s.exists())
        s.unlock(pw).use { v ->
            val e = v.get(id)!!
            assertEquals("photo.jpg", e.name)
            assertEquals(300_000, e.size)
            assertContentEquals(data, v.contents(e))
        }
        assertFailsWith<WrongCredentialsException> { s.unlock("wrong".toCharArray()) }
    }

    @Test
    fun `recovery key unlocks, can be replaced, and the old one stops working`() {
        val rk = RecoveryKey.generate(p)
        val rkCopy = rk.copy()
        store().create(pw, fast, rk, allowBelowFloor = true).use { v -> v.createFolder("Taxes", null) }
        assertTrue(store().hasRecoveryKey())
        val words = RecoveryKey.toWords(rkCopy)
        store().unlockWithRecoveryKey(RecoveryKey.fromWords(words.joinToString(" "))).use { v ->
            assertEquals(listOf("Taxes"), v.children(null).map { it.name })
            v.setRecoveryKey(RecoveryKey.generate(p))
        }
        assertFailsWith<WrongCredentialsException> { store().unlockWithRecoveryKey(RecoveryKey.fromWords(words.joinToString(" "))) }
    }

    @Test
    fun `changing the password keeps the files and retires the old password`() {
        val id = newVault().use { v -> v.put("a.txt", "hello".toByteArray()).id.also { v.changePassword("new-correct-horse".toCharArray(), fast, true) } }
        assertFailsWith<WrongCredentialsException> { store().unlock(pw) }
        store().unlock("new-correct-horse".toCharArray()).use { v -> assertEquals("hello", String(v.contents(v.get(id)!!))) }
    }

    @Test
    fun `nothing readable is written to disk`() {
        val marker = "SECRET-MARKER-7731"
        newVault().use { v ->
            val folder = v.createFolder("Folder $marker", null)
            v.importFile("Name $marker.pdf", "application/pdf", 40, ByteArrayInputStream((marker + "x".repeat(22)).toByteArray()), folder.id)
        }
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(marker in text, "plaintext found in ${f.name}")
            assertFalse("pdf" in f.name)
        }
    }

    @Test
    fun `folders, moves and breadcrumbs`() {
        newVault().use { v ->
            val a = v.createFolder("A", null)
            val b = v.createFolder("B", a.id)
            val f = v.put("file", ByteArray(10), b.id)
            assertEquals(listOf("A", "B"), v.pathTo(b.id).map { it.name })
            assertFailsWith<IllegalArgumentException> { v.move(a.id, b.id) }
            v.move(f.id, null)
            assertEquals(listOf("A", "file"), v.children(null).map { it.name })
            v.rename(a.id, "Archive")
            assertEquals("Archive", v.get(a.id)!!.name)
        }
    }

    @Test
    fun `trash, restore, empty and 30-day expiry`() {
        newVault().use { v ->
            val folder = v.createFolder("Old", null)
            val inner = v.put("inner", ByteArray(5), folder.id)
            val loose = v.put("loose", ByteArray(5))
            v.moveToTrash(folder.id)
            assertEquals(listOf("loose"), v.children(null).map { it.name })
            assertEquals(listOf("Old"), v.trash().map { it.name })
            v.restore(folder.id)
            assertEquals(listOf("inner"), v.children(folder.id).map { it.name })

            v.moveToTrash(loose.id)
            now += 31L * 24 * 3600 * 1000
            assertEquals(1, v.purgeExpiredTrash(UnlockedVault.DEFAULT_TRASH_RETENTION))
            assertNull(v.get(loose.id))
            assertFalse(store().blobFile(loose.id).exists())

            v.moveToTrash(folder.id)
            assertEquals(1, v.emptyTrash())
            assertNull(v.get(inner.id))
            assertEquals(0, v.fileCount())
        }
    }

    @Test
    fun `restoring an item whose folder is gone puts it at the top level`() {
        newVault().use { v ->
            val folder = v.createFolder("F", null)
            val f = v.put("x", ByteArray(3), folder.id)
            v.moveToTrash(f.id); now += 1
            v.moveToTrash(folder.id)
            v.restore(f.id)
            assertEquals(null, v.get(f.id)!!.parent)
        }
    }

    @Test
    fun `a crash during import leaves the vault intact and is cleaned up on unlock`() {
        val s = store()
        newVault().use { v ->
            v.put("keep", ByteArray(100))
            val failing = object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 100_000) 1 else throw java.io.IOException("card removed")
                override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
            }
            assertFailsWith<java.io.IOException> { v.importFile("big", "x/y", 500_000, failing, null) }
            assertEquals(listOf("keep"), v.children(null).map { it.name })
        }
        // Simulate files left by a crash: staging debris and an orphan blob.
        File(s.stagingDir, "deadbeef.part").writeBytes(ByteArray(10))
        File(s.blobDir, "0".repeat(32)).writeBytes(ByteArray(10))
        s.unlock(pw).use { v -> assertEquals(1, v.fileCount()) }
        assertTrue(s.stagingDir.listFiles()!!.isEmpty())
        assertEquals(1, s.blobDir.listFiles()!!.size)
    }

    @Test
    fun `a source shorter than declared is refused and nothing is added`() {
        newVault().use { v ->
            assertFailsWith<IllegalArgumentException> { v.importFile("short", "x/y", 1000, ByteArrayInputStream(ByteArray(10)), null) }
            assertEquals(0, v.fileCount())
        }
    }

    @Test
    fun `tampered index or file is detected, never silently accepted`() {
        val id = newVault().use { v -> v.put("doc", Random(2).nextBytes(5000)).id }
        val s = store()
        val blob = s.blobFile(id)
        val original = blob.readBytes()
        blob.writeBytes(original.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        s.unlock(pw).use { v -> assertFailsWith<CorruptDataException> { v.readFile(id, ByteArrayOutputStream()) } }
        blob.writeBytes(original)

        val idx = s.indexFile.readBytes()
        s.indexFile.writeBytes(idx.copyOf().also { it[70] = (it[70].toInt() xor 1).toByte() })
        assertFailsWith<BulwarkCryptoException> { s.unlock(pw) }
    }

    @Test
    fun `swapping two encrypted files on disk is detected`() {
        val (a, b) = newVault().use { v -> v.put("a", ByteArray(2000) { 1 }).id to v.put("b", ByteArray(2000) { 2 }).id }
        val s = store()
        val fa = s.blobFile(a).readBytes(); val fb = s.blobFile(b).readBytes()
        s.blobFile(a).writeBytes(fb); s.blobFile(b).writeBytes(fa)
        s.unlock(pw).use { v -> assertFailsWith<BulwarkCryptoException> { v.readFile(a, ByteArrayOutputStream()) } }
    }

    @Test
    fun `locking wipes access immediately`() {
        val v = newVault()
        val f = v.put("x", ByteArray(10))
        v.close()
        assertFailsWith<VaultLockedException> { v.children(null) }
        assertFailsWith<VaultLockedException> { v.readFile(f.id, ByteArrayOutputStream()) }
        assertFailsWith<VaultLockedException> { v.put("y", ByteArray(1)) }
    }

    @Test
    fun `names are cleaned of path separators and control characters`() {
        newVault().use { v ->
            assertEquals("a  b c", v.createFolder("a/\u0000b\\c", null).name)
            assertEquals("Untitled", v.createFolder("   ", null).name)
        }
    }

    @Test
    fun `erase removes everything`() {
        newVault().close()
        store().eraseEverything()
        assertFalse(store().exists())
        assertFalse(File(dir, "vault").exists())
    }
}

class PasswordStrengthTest {
    private val p = Primitives(LazySodiumJava(SodiumJava()))

    @Test
    fun `weak and common passwords are refused`() {
        for (weak in listOf("password", "123456789", "qwerty123", "aaaaaaaaaaaaaaaa", "abcd1234", "Summer2026"))
            assertFalse(PasswordStrength.meetsRequirement(weak), weak)
    }

    @Test
    fun `long varied passwords and random passphrases pass`() {
        assertTrue(PasswordStrength.meetsRequirement("Tq7#vLm2!pRw9xZ"))
        repeat(20) { assertTrue(PasswordStrength.meetsRequirement(Passphrase.generate(p)), "generated passphrase") }
    }

    @Test
    fun `generated passphrases use six list words`() {
        val words = Passphrase.generate(p).split("-")
        assertEquals(6, words.size)
        assertTrue(words.all { it in RecoveryKey.wordlist })
    }
}

class SecretSlotTest {
    private val p = Primitives(LazySodiumJava(SodiumJava()))
    private val fast = Argon2Params(1, 8L * 1024 * 1024)
    private val dir: File = Files.createTempDirectory("bulwark-slots").toFile()
    private var retention = UnlockedVault.DEFAULT_TRASH_RETENTION
    private var now = 1_000_000L
    private fun store() = VaultStore(File(dir, "v"), p, NoDeviceLayer, { now }, { retention })
    private val pw = "orbit-velvet-candle-harbor".toCharArray()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `a biometric slot unlocks, is independent of the password, and can be removed`() {
        val bio = app.bulwark.crypto.SecretBytes.adopt(p.randomBytes(32))
        store().create(pw, fast, null, allowBelowFloor = true).use { v ->
            v.setSecretSlot(app.bulwark.crypto.Keyslot.Type.BIOMETRIC, bio.copy())
            v.changePassword("another-long-passphrase".toCharArray(), fast, true)
        }
        assertTrue(store().hasSlot(app.bulwark.crypto.Keyslot.Type.BIOMETRIC))
        store().unlockWithSecret(app.bulwark.crypto.Keyslot.Type.BIOMETRIC, bio.copy()).use { v ->
            assertTrue(v.verifyPassword("another-long-passphrase".toCharArray()))
            assertFalse(v.verifyPassword(pw))
            v.setSecretSlot(app.bulwark.crypto.Keyslot.Type.BIOMETRIC, null)
        }
        assertFalse(store().hasSlot(app.bulwark.crypto.Keyslot.Type.BIOMETRIC))
        assertFailsWith<WrongCredentialsException> { store().unlockWithSecret(app.bulwark.crypto.Keyslot.Type.BIOMETRIC, bio) }
    }

    @Test
    fun `trash retention setting is applied on unlock`() {
        val id = store().create(pw, fast, null, allowBelowFloor = true).use { v ->
            v.importFile("x", "a/b", 3, ByteArrayInputStream(ByteArray(3)), null).id.also { v.moveToTrash(it) }
        }
        now += 40L * 24 * 3600 * 1000
        retention = 90L * 24 * 3600 * 1000
        store().unlock(pw).use { v -> assertTrue(v.get(id) != null, "kept under a 90-day setting") }
        retention = 7L * 24 * 3600 * 1000
        store().unlock(pw).use { v -> assertNull(v.get(id)) }
    }
}
