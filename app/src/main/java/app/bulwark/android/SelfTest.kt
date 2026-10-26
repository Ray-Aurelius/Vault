package app.bulwark.android

import app.bulwark.crypto.Argon2Params
import app.bulwark.crypto.CorruptDataException
import app.bulwark.crypto.FileCipher
import app.bulwark.crypto.FileId
import app.bulwark.crypto.FileKeyWrapper
import app.bulwark.crypto.Keyring
import app.bulwark.crypto.Keyslot
import app.bulwark.crypto.MasterKey
import app.bulwark.crypto.Primitives
import app.bulwark.crypto.RecoveryKey
import app.bulwark.crypto.SealKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A quick on-device check that libsodium loaded for this phone's CPU and that the crypto core
 * behaves correctly here, including one Argon2id run at the real production floor.
 */
object SelfTest {
    sealed interface Result {
        data class Passed(val millis: Long) : Result
        data class Failed(val reason: String) : Result
    }

    fun run(p: Primitives): Result = try {
        val start = System.nanoTime()
        MasterKey.generate(p).use { mk ->
            // One real-cost derivation (64 MiB, 3 passes) to prove this phone can afford it.
            p.argon2id("self-test".toByteArray(), p.randomBytes(Argon2Params.SALT_BYTES), Argon2Params.FLOOR).fill(0)

            val fast = Argon2Params(1, 8L * 1024 * 1024)
            val pw = Keyslot.createPassword(p, mk, "self-test password".toCharArray(), fast, allowBelowFloor = true)
            val rk = RecoveryKey.generate(p)
            val words = RecoveryKey.toWords(rk)
            val ring = Keyring.parse(Keyring(listOf(pw, Keyslot.createWithSecret(p, mk, Keyslot.Type.RECOVERY_KEY, rk))).serialize())
            ring.unlockWithPassword(p, "self-test password".toCharArray()).close()
            RecoveryKey.fromWords(words.joinToString(" ")).use { ring.unlockWithSecret(p, Keyslot.Type.RECOVERY_KEY, it).close() }

            FileKeyWrapper(mk, p).use { w ->
                val id = FileId.random(p)
                val fk = w.newFileKey()
                val data = p.randomBytes(1 shl 20)
                val enc = ByteArrayOutputStream()
                val cipher = FileCipher(p)
                cipher.encrypt(fk, id, ByteArrayInputStream(data), data.size.toLong(), enc)
                val blob = enc.toByteArray()
                w.unwrap(w.wrap(fk, id), id).use { fk2 ->
                    val dec = ByteArrayOutputStream()
                    cipher.decrypt(fk2, id, ByteArrayInputStream(blob), dec)
                    check(dec.toByteArray().contentEquals(data)) { "Round trip mismatch" }
                    blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 1).toByte()
                    val tamperCaught = try {
                        cipher.decrypt(fk2, id, ByteArrayInputStream(blob), ByteArrayOutputStream()); false
                    } catch (_: CorruptDataException) { true }
                    check(tamperCaught) { "Tampering was not detected" }
                }
                fk.close()
            }

            val seal = SealKeys.generate(p, mk)
            val secret = app.bulwark.crypto.SecretBytes.adopt(p.randomBytes(32))
            seal.open(p, mk, seal.seal(p, secret)).close()
        }
        Result.Passed((System.nanoTime() - start) / 1_000_000)
    } catch (t: Throwable) {
        // Never include data in the message; the class name is enough to diagnose.
        Result.Failed(t.javaClass.simpleName)
    }
}
