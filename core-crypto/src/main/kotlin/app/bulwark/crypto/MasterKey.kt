package app.bulwark.crypto

import java.io.Closeable

/**
 * The vault's single 256-bit master key. It is generated once, never written to disk unwrapped,
 * and every other key is either derived from it or wrapped by it.
 */
class MasterKey private constructor(private val secret: SecretBytes, private val primitives: Primitives) : Closeable {

    /** Purpose-bound subkeys; each context string is fixed forever (changing one breaks vaults). */
    enum class Purpose(internal val context: String, internal val id: Long) {
        INDEX("BWindex1", 1),
        THUMBNAILS("BWthumb1", 2),
        FILE_KEY_WRAP("BWfkwrp1", 3),
        SEAL_KEY_WRAP("BWseal01", 4),
    }

    fun deriveSubkey(purpose: Purpose): SecretBytes =
        secret.withBytes { SecretBytes.adopt(primitives.deriveSubkey(it, purpose.id, purpose.context)) }

    internal fun <T> withBytes(block: (ByteArray) -> T): T = secret.withBytes(block)

    val isClosed get() = secret.isClosed

    override fun close() = secret.close()

    companion object {
        fun generate(primitives: Primitives): MasterKey =
            MasterKey(SecretBytes.adopt(primitives.randomBytes(Primitives.KEY_BYTES)), primitives)

        internal fun fromBytes(bytes: ByteArray, primitives: Primitives): MasterKey {
            if (bytes.size != Primitives.KEY_BYTES) throw CorruptDataException("Master key has wrong length")
            return MasterKey(SecretBytes.adopt(bytes), primitives)
        }
    }
}

/**
 * Wraps per-file keys with a subkey of the master key. The file ID is bound into the wrapping,
 * so a wrapped key copied onto another file's record will not open.
 */
class FileKeyWrapper(masterKey: MasterKey, private val primitives: Primitives) : Closeable {
    private val wrapKey = masterKey.deriveSubkey(MasterKey.Purpose.FILE_KEY_WRAP)

    fun newFileKey(): SecretBytes = SecretBytes.adopt(primitives.randomBytes(Primitives.KEY_BYTES))

    fun wrap(fileKey: SecretBytes, fileId: FileId): ByteArray =
        wrapKey.withBytes { k -> fileKey.withBytes { fk -> primitives.aeadSeal(k, fk, ad(fileId)) } }

    fun unwrap(wrapped: ByteArray, fileId: FileId): SecretBytes =
        wrapKey.withBytes { k -> SecretBytes.adopt(primitives.aeadOpen(k, wrapped, ad(fileId))) }

    private fun ad(fileId: FileId) = "bulwark/file-key/v1".toByteArray() + fileId.bytes

    override fun close() = wrapKey.close()
}

/** A file's random 128-bit identity; also its name on disk (as hex), so names reveal nothing. */
class FileId(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "File ID must be $SIZE bytes" }
    }

    val hex: String get() = bytes.toHex()

    override fun equals(other: Any?) = other is FileId && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "FileId($hex)"

    companion object {
        const val SIZE = 16
        fun random(primitives: Primitives) = FileId(primitives.randomBytes(SIZE))
        fun fromHex(hex: String) = FileId(hexToBytes(hex))
    }
}
