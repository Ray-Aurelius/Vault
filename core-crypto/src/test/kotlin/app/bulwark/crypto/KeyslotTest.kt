package app.bulwark.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyslotTest {
    private val p = TestSupport.primitives
    private val fast = TestSupport.fastParams

    private fun MasterKey.raw() = withBytes { it.copyOf() }

    @Test
    fun `password slot opens with the right password only`() {
        val mk = MasterKey.generate(p)
        val slot = Keyslot.createPassword(p, mk, "correct horse battery".toCharArray(), fast, allowBelowFloor = true)
        val parsed = Keyslot.parse(slot.serialize())
        parsed.unlockWithPassword(p, "correct horse battery".toCharArray()).use { assertContentEquals(mk.raw(), it.raw()) }
        assertFailsWith<WrongCredentialsException> { parsed.unlockWithPassword(p, "correct horse batterY".toCharArray()) }
    }

    @Test
    fun `real vaults refuse cost settings below the floor`() {
        val mk = MasterKey.generate(p)
        assertFailsWith<IllegalArgumentException> { Keyslot.createPassword(p, mk, "pw".toCharArray(), fast) }
        assertTrue(Argon2Params.FLOOR.meetsProductionFloor)
    }

    @Test
    fun `unicode passwords unlock however the accents were typed`() {
        val mk = MasterKey.generate(p)
        val composed = "café crème" // é and è as single characters
        val decomposed = "café crème" // e + combining accent
        val slot = Keyslot.createPassword(p, mk, composed.toCharArray(), fast, allowBelowFloor = true)
        slot.unlockWithPassword(p, decomposed.toCharArray()).use { assertContentEquals(mk.raw(), it.raw()) }
    }

    @Test
    fun `tampering with stored cost settings or type makes the slot fail`() {
        val mk = MasterKey.generate(p)
        val bytes = Keyslot.createPassword(p, mk, "pw".toCharArray(), fast, type = Keyslot.Type.PASSWORD, allowBelowFloor = true).serialize()
        // Layout: magic4 ver1 type1 id16 hasKdf1 salt16 ops4 mem4 ...  ops starts at offset 39.
        val lowerOps = bytes.copyOf(); lowerOps[42] = 2
        assertFailsWith<WrongCredentialsException> { Keyslot.parse(lowerOps).unlockWithPassword(p, "pw".toCharArray()) }
        val asPin = bytes.copyOf(); asPin[5] = Keyslot.Type.PIN.code.toByte()
        assertFailsWith<WrongCredentialsException> { Keyslot.parse(asPin).unlockWithPassword(p, "pw".toCharArray()) }
    }

    @Test
    fun `recovery key slot round trips through the 24 words`() {
        val mk = MasterKey.generate(p)
        val rk = RecoveryKey.generate(p)
        val words = RecoveryKey.toWords(rk)
        val ring = Keyring(listOf(Keyslot.createWithSecret(p, mk, Keyslot.Type.RECOVERY_KEY, rk)))
        val reparsed = Keyring.parse(ring.serialize())
        RecoveryKey.fromWords(words.joinToString(" ")).use { typed ->
            reparsed.unlockWithSecret(p, Keyslot.Type.RECOVERY_KEY, typed).use { assertContentEquals(mk.raw(), it.raw()) }
        }
        RecoveryKey.generate(p).use { other ->
            assertFailsWith<WrongCredentialsException> { reparsed.unlockWithSecret(p, Keyslot.Type.RECOVERY_KEY, other) }
        }
    }

    @Test
    fun `a secret for one slot type cannot open another type`() {
        val mk = MasterKey.generate(p)
        val secret = SecretBytes.adopt(p.randomBytes(32))
        val hw = Keyslot.createWithSecret(p, mk, Keyslot.Type.HARDWARE_KEY, secret)
        val asRecovery = hw.serialize().also { it[5] = Keyslot.Type.RECOVERY_KEY.code.toByte() }
        assertFailsWith<WrongCredentialsException> { Keyslot.parse(asRecovery).unlockWithSecret(p, secret) }
    }

    @Test
    fun `keyring holds independent slots and revoking one leaves the rest`() {
        val mk = MasterKey.generate(p)
        val pw = Keyslot.createPassword(p, mk, "one".toCharArray(), fast, allowBelowFloor = true)
        val pin = Keyslot.createPassword(p, mk, "12345678".toCharArray(), fast, type = Keyslot.Type.PIN, allowBelowFloor = true)
        val rk = RecoveryKey.generate(p)
        val rec = Keyslot.createWithSecret(p, mk, Keyslot.Type.RECOVERY_KEY, rk)
        val ring = Keyring.parse(Keyring(listOf(pw, pin, rec)).serialize())
        assertEquals(3, ring.slots.size)
        ring.unlockWithPassword(p, "12345678".toCharArray(), Keyslot.Type.PIN).close()
        val revoked = ring.without(rec.slotIdHex)
        assertFailsWith<WrongCredentialsException> { revoked.unlockWithSecret(p, Keyslot.Type.RECOVERY_KEY, rk) }
        revoked.unlockWithPassword(p, "one".toCharArray()).close()
    }

    @Test
    fun `malformed keyslot bytes are rejected cleanly`() {
        val mk = MasterKey.generate(p)
        val good = Keyslot.createPassword(p, mk, "pw".toCharArray(), fast, allowBelowFloor = true).serialize()
        for (cut in 0 until good.size) {
            assertFailsWith<BulwarkCryptoException>("cut $cut") { Keyslot.parse(good.copyOf(cut)) }
        }
        assertFailsWith<BulwarkCryptoException> { Keyslot.parse(good + 1) }
        val hugeMem = good.copyOf().also { it[43] = 0x7f; it[44] = 0x7f }
        assertFailsWith<CorruptDataException> { Keyslot.parse(hugeMem) }
    }

    @Test
    fun `file keys are bound to their file id`() {
        val mk = MasterKey.generate(p)
        FileKeyWrapper(mk, p).use { w ->
            val fk = w.newFileKey(); val id = FileId.random(p)
            val wrapped = w.wrap(fk, id)
            w.unwrap(wrapped, id).use { u -> assertContentEquals(fk.withBytes { it.copyOf() }, u.withBytes { it.copyOf() }) }
            assertFailsWith<CorruptDataException> { w.unwrap(wrapped, FileId.random(p)) }
        }
    }

    @Test
    fun `subkeys differ by purpose and are stable`() {
        val mk = MasterKey.generate(p)
        val keys = MasterKey.Purpose.entries.map { pur -> mk.deriveSubkey(pur).use { s -> s.withBytes { it.toHex() } } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys[0], mk.deriveSubkey(MasterKey.Purpose.INDEX).use { s -> s.withBytes { it.toHex() } })
    }

    @Test
    fun `locked capture seals with the public key and opens only with the master key`() {
        val mk = MasterKey.generate(p)
        val seal = SealKeys.parse(SealKeys.generate(p, mk).serialize())
        val fk = SecretBytes.adopt(p.randomBytes(32))
        val sealed = seal.seal(p, fk)
        seal.open(p, mk, sealed).use { assertContentEquals(fk.withBytes { it.copyOf() }, it.withBytes { b -> b.copyOf() }) }
        assertFailsWith<CorruptDataException> { seal.open(p, MasterKey.generate(p), sealed) }
    }

    @Test
    fun `secrets are wiped on close`() {
        val s = SecretBytes.adopt(byteArrayOf(1, 2, 3))
        var raw: ByteArray? = null
        s.withBytes { raw = it }
        s.close()
        assertContentEquals(byteArrayOf(0, 0, 0), raw)
        assertFailsWith<IllegalStateException> { s.size }
    }

    @Test
    fun `calibration never goes below the floor`() {
        // Pretend Argon2 is very slow so calibration wants fewer passes than allowed.
        var t = 0L
        val params = Argon2Calibrator.calibrate(p, availableMemBytes = 64L * 1024 * 1024, floor = fast, clock = { t.also { t += 10_000_000_000 } })
        assertTrue(params.opsLimit >= fast.opsLimit && params.memLimitBytes >= fast.memLimitBytes)
    }
}
