package app.bulwark.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong

/**
 * The only class that talks to libsodium. Everything else in the core goes through these few,
 * narrowly typed operations, which keeps the audited surface small.
 *
 * The app passes a LazySodiumAndroid instance; tests pass LazySodiumJava.
 */
class Primitives(private val sodium: LazySodium) {

    fun randomBytes(count: Int): ByteArray {
        require(count in 1..MAX_RANDOM) { "Bad random length $count" }
        return sodium.randomBytesBuf(count)
    }

    /** Argon2id. Returns [outLength] bytes derived from [password] and [salt]. */
    fun argon2id(password: ByteArray, salt: ByteArray, params: Argon2Params, outLength: Int = KEY_BYTES): ByteArray {
        require(salt.size == Argon2Params.SALT_BYTES) { "Salt must be ${Argon2Params.SALT_BYTES} bytes" }
        val out = ByteArray(outLength)
        val ok = sodium.cryptoPwHash(
            out, outLength, password, password.size, salt,
            params.opsLimit, NativeLong(params.memLimitBytes), PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        if (!ok) {
            SecretBytes.wipe(out)
            throw CorruptDataException("Key derivation failed (not enough memory, or invalid parameters)")
        }
        return out
    }

    /** XChaCha20-Poly1305 with a fresh random nonce. Output: nonce || ciphertext || tag. */
    fun aeadSeal(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AEAD key must be $KEY_BYTES bytes" }
        val nonce = randomBytes(AEAD_NONCE_BYTES)
        val ct = ByteArray(plaintext.size + AEAD_TAG_BYTES)
        val ctLen = LongArray(1)
        check(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                ct, ctLen, plaintext, plaintext.size.toLong(), associatedData, associatedData.size.toLong(), null, nonce, key,
            ),
        ) { "AEAD encryption failed" }
        return nonce + ct
    }

    /** Opens [aeadSeal] output, or throws [CorruptDataException] if anything was altered. */
    fun aeadOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "AEAD key must be $KEY_BYTES bytes" }
        if (sealed.size < AEAD_NONCE_BYTES + AEAD_TAG_BYTES) throw CorruptDataException("Sealed data too short")
        val nonce = sealed.copyOfRange(0, AEAD_NONCE_BYTES)
        val ct = sealed.copyOfRange(AEAD_NONCE_BYTES, sealed.size)
        val pt = ByteArray(ct.size - AEAD_TAG_BYTES)
        val ptLen = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            pt, ptLen, null, ct, ct.size.toLong(), associatedData, associatedData.size.toLong(), nonce, key,
        )
        if (!ok) {
            SecretBytes.wipe(pt)
            throw CorruptDataException("Authentication failed")
        }
        return pt
    }

    /** BLAKE2b-based subkey derivation (crypto_kdf). [context] must be exactly 8 ASCII bytes. */
    fun deriveSubkey(masterKey: ByteArray, subkeyId: Long, context: String, length: Int = KEY_BYTES): ByteArray {
        val ctx = context.toByteArray(Charsets.US_ASCII)
        require(ctx.size == KDF_CONTEXT_BYTES) { "KDF context must be $KDF_CONTEXT_BYTES bytes" }
        require(masterKey.size == KEY_BYTES) { "KDF key must be $KEY_BYTES bytes" }
        val out = ByteArray(length)
        check(sodium.cryptoKdfDeriveFromKey(out, length, subkeyId, ctx, masterKey) == 0) { "KDF failed" }
        return out
    }

    /** X25519 keypair for sealed boxes: (publicKey, secretKey). */
    fun boxKeypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(BOX_PUBLIC_KEY_BYTES)
        val sk = ByteArray(BOX_SECRET_KEY_BYTES)
        check(sodium.cryptoBoxKeypair(pk, sk)) { "Keypair generation failed" }
        return pk to sk
    }

    /** Anonymous sealed box: anyone with [publicKey] can seal, only the secret key can open. */
    fun boxSeal(publicKey: ByteArray, message: ByteArray): ByteArray {
        val out = ByteArray(message.size + BOX_SEAL_BYTES)
        check(sodium.cryptoBoxSeal(out, message, message.size.toLong(), publicKey)) { "Seal failed" }
        return out
    }

    fun boxSealOpen(publicKey: ByteArray, secretKey: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < BOX_SEAL_BYTES) throw CorruptDataException("Sealed box too short")
        val out = ByteArray(sealed.size - BOX_SEAL_BYTES)
        if (!sodium.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), publicKey, secretKey)) {
            SecretBytes.wipe(out)
            throw CorruptDataException("Sealed box authentication failed")
        }
        return out
    }

    // --- Streaming encryption (crypto_secretstream_xchacha20poly1305) ---

    class StreamState internal constructor(internal val state: SecretStream.State) {
        fun wipe() {
            state.k?.fill(0)
            state.nonce?.fill(0)
        }
    }

    fun streamInitPush(key: ByteArray): Pair<StreamState, ByteArray> {
        require(key.size == KEY_BYTES)
        val state = SecretStream.State()
        val header = ByteArray(STREAM_HEADER_BYTES)
        check(sodium.cryptoSecretStreamInitPush(state, header, key)) { "Stream init failed" }
        return StreamState(state) to header
    }

    fun streamPush(state: StreamState, message: ByteArray, length: Int, associatedData: ByteArray, final: Boolean): ByteArray {
        val chunk = if (length == message.size) message else message.copyOf(length)
        val out = ByteArray(length + STREAM_ABYTES)
        val outLen = LongArray(1)
        val tag = if (final) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
        check(
            sodium.cryptoSecretStreamPush(
                state.state, out, outLen, chunk, length.toLong(), associatedData, associatedData.size.toLong(), tag,
            ),
        ) { "Stream encryption failed" }
        if (chunk !== message) SecretBytes.wipe(chunk)
        return out
    }

    fun streamInitPull(key: ByteArray, header: ByteArray): StreamState {
        require(key.size == KEY_BYTES)
        if (header.size != STREAM_HEADER_BYTES) throw CorruptDataException("Bad stream header")
        val state = SecretStream.State()
        if (!sodium.cryptoSecretStreamInitPull(state, header, key)) throw CorruptDataException("Bad stream header")
        return StreamState(state)
    }

    /** Returns (plaintext, isFinal). Throws [CorruptDataException] on any authentication failure. */
    fun streamPull(state: StreamState, ciphertext: ByteArray, length: Int, associatedData: ByteArray): Pair<ByteArray, Boolean> {
        if (length < STREAM_ABYTES) throw CorruptDataException("Chunk too short")
        val ct = if (length == ciphertext.size) ciphertext else ciphertext.copyOf(length)
        val out = ByteArray(length - STREAM_ABYTES)
        val outLen = LongArray(1)
        val tag = ByteArray(1)
        val ok = sodium.cryptoSecretStreamPull(
            state.state, out, outLen, tag, ct, length.toLong(), associatedData, associatedData.size.toLong(),
        )
        if (!ok) {
            SecretBytes.wipe(out)
            throw CorruptDataException("Chunk authentication failed")
        }
        return when (tag[0]) {
            SecretStream.TAG_FINAL -> out to true
            SecretStream.TAG_MESSAGE -> out to false
            else -> {
                SecretBytes.wipe(out)
                throw CorruptDataException("Unexpected chunk tag")
            }
        }
    }

    companion object {
        const val KEY_BYTES = 32
        const val AEAD_NONCE_BYTES = 24
        const val AEAD_TAG_BYTES = 16
        const val KDF_CONTEXT_BYTES = 8
        const val STREAM_HEADER_BYTES = 24
        const val STREAM_ABYTES = 17
        const val BOX_PUBLIC_KEY_BYTES = 32
        const val BOX_SECRET_KEY_BYTES = 32
        const val BOX_SEAL_BYTES = 48
        private const val MAX_RANDOM = 1 shl 20
    }
}
