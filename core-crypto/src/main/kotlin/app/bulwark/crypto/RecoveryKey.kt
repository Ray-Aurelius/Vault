package app.bulwark.crypto

import java.security.MessageDigest

/**
 * The 24-word recovery key: 256 random bits written as words from the standard BIP-39 English
 * list (2,048 words, each uniquely identified by its first four letters). The last word carries an
 * 8-bit checksum, so a typo is caught before any decryption is attempted.
 *
 * The phrase is shown once at setup and never stored by the app; only a keyslot derived from it is.
 */
object RecoveryKey {
    const val WORDS = 24
    const val ENTROPY_BYTES = 32

    /** SHA-256 of the bundled list; the build fails tests if the list is ever altered. */
    const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    val wordlist: List<String> by lazy {
        val stream = RecoveryKey::class.java.getResourceAsStream("bip39-english.txt")
            ?: error("Word list missing from the build")
        val words = stream.bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        check(words.size == 2048) { "Word list must have 2048 words" }
        words
    }

    private val index: Map<String, Int> by lazy { wordlist.withIndex().associate { (i, w) -> w to i } }
    private val prefixIndex: Map<String, Int> by lazy { wordlist.withIndex().associate { (i, w) -> w.take(4) to i } }

    fun generate(primitives: Primitives): SecretBytes = SecretBytes.adopt(primitives.randomBytes(ENTROPY_BYTES))

    /** The words for [entropy]; the returned list is the only copy of the phrase, show it then drop it. */
    fun toWords(entropy: SecretBytes): List<String> = entropy.withBytes { e ->
        require(e.size == ENTROPY_BYTES) { "Recovery key must be $ENTROPY_BYTES bytes" }
        val checksum = sha256(e)[0]
        val bits = BitBuffer(e + checksum)
        List(WORDS) { wordlist[bits.read11(it)] }
    }

    /**
     * Parses what the user typed. Accepts any case, extra spaces, and each word's first four
     * letters. Throws [InvalidRecoveryPhraseException] naming what is wrong.
     */
    fun fromWords(input: String): SecretBytes {
        val tokens = input.lowercase().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map { it.trimStart { c -> c.isDigit() || c == '.' || c == ')' } }.filter { it.isNotEmpty() }
        if (tokens.size != WORDS) throw InvalidRecoveryPhraseException("A recovery key has $WORDS words; found ${tokens.size}")
        val indices = tokens.mapIndexed { i, t ->
            index[t] ?: (if (t.length >= 4) prefixIndex[t.take(4)]?.takeIf { wordlist[it].startsWith(t) } else null)
                ?: throw InvalidRecoveryPhraseException("Word ${i + 1} (\"$t\") is not in the recovery word list")
        }
        val bytes = ByteArray(ENTROPY_BYTES + 1)
        val bits = BitBuffer(bytes)
        indices.forEachIndexed { i, v -> bits.write11(i, v) }
        val entropy = bytes.copyOfRange(0, ENTROPY_BYTES)
        val ok = sha256(entropy)[0] == bytes[ENTROPY_BYTES]
        SecretBytes.wipe(bytes)
        if (!ok) {
            SecretBytes.wipe(entropy)
            throw InvalidRecoveryPhraseException("These words don't form a valid recovery key; check for a typo or swapped words")
        }
        return SecretBytes.adopt(entropy)
    }

    internal fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private class BitBuffer(val bytes: ByteArray) {
        fun read11(word: Int): Int {
            var v = 0
            for (i in 0 until 11) {
                val bit = word * 11 + i
                v = (v shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
            }
            return v
        }

        fun write11(word: Int, value: Int) {
            for (i in 0 until 11) {
                val bit = word * 11 + i
                if ((value ushr (10 - i)) and 1 == 1) {
                    bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
            }
        }
    }
}

/**
 * Locked capture: the camera, Share to Bulwark and background imports seal new files to the
 * vault's public key, so they can be saved while the vault is locked. Only the master key can
 * open the matching secret key.
 */
class SealKeys private constructor(val publicKey: ByteArray, private val wrappedSecretKey: ByteArray) {

    /** Seals a small secret (in practice, a new file's key) to the vault. No master key needed. */
    fun seal(primitives: Primitives, secret: SecretBytes): ByteArray =
        secret.withBytes { primitives.boxSeal(publicKey, it) }

    /** Opens a sealed secret once the vault is unlocked. */
    fun open(primitives: Primitives, masterKey: MasterKey, sealed: ByteArray): SecretBytes =
        masterKey.deriveSubkey(MasterKey.Purpose.SEAL_KEY_WRAP).use { wk ->
            val sk = wk.withBytes { primitives.aeadOpen(it, wrappedSecretKey, ad(publicKey)) }
            try { SecretBytes.adopt(primitives.boxSealOpen(publicKey, sk, sealed)) } finally { SecretBytes.wipe(sk) }
        }

    fun serialize(): ByteArray = BinaryWriter().bytes(MAGIC).u8(1).bytes(publicKey)
        .u16(wrappedSecretKey.size).bytes(wrappedSecretKey).toByteArray()

    companion object {
        private val MAGIC = "BWSK".toByteArray(Charsets.US_ASCII)
        private fun ad(pk: ByteArray) = "bulwark/seal-key/v1".toByteArray() + pk

        fun generate(primitives: Primitives, masterKey: MasterKey): SealKeys {
            val (pk, sk) = primitives.boxKeypair()
            try {
                val wrapped = masterKey.deriveSubkey(MasterKey.Purpose.SEAL_KEY_WRAP).use { wk ->
                    wk.withBytes { primitives.aeadSeal(it, sk, ad(pk)) }
                }
                return SealKeys(pk, wrapped)
            } finally {
                SecretBytes.wipe(sk)
            }
        }

        fun parse(bytes: ByteArray): SealKeys {
            val r = BinaryReader(bytes)
            if (!r.bytes(4).contentEquals(MAGIC)) throw CorruptDataException("Not seal keys")
            if (r.u8() != 1) throw UnsupportedFormatException("Seal key version")
            val pk = r.bytes(Primitives.BOX_PUBLIC_KEY_BYTES)
            val wrapped = r.bytes(r.u16())
            r.requireEnd()
            return SealKeys(pk, wrapped)
        }
    }
}
