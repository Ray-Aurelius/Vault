package app.bulwark.vault

import app.bulwark.crypto.CorruptDataException
import app.bulwark.crypto.FileId
import app.bulwark.crypto.UnsupportedFormatException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** One item in the vault: a file or a folder. Everything here is stored only inside the encrypted index. */
data class VaultEntry(
    val id: FileId,
    val isFolder: Boolean,
    val parent: FileId?,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val trashedAt: Long?,
    /** The file's key, wrapped by the master key and bound to [id]. Empty for folders. */
    val wrappedKey: ByteArray,
) {
    val isTrashed get() = trashedAt != null

    override fun equals(other: Any?) = other is VaultEntry && other.id == id && other.isFolder == isFolder &&
        other.parent == parent && other.name == name && other.mimeType == mimeType && other.size == size &&
        other.createdAt == createdAt && other.modifiedAt == modifiedAt && other.trashedAt == trashedAt &&
        other.wrappedKey.contentEquals(wrappedKey)

    override fun hashCode() = id.hashCode()
}

/**
 * The vault's table of contents. Serialized, then encrypted as a whole with the index subkey and
 * replaced atomically on every change.
 *
 *     "BWIX" | version u8 = 1 | count u32 | entry * count
 *     entry: id[16] | isFolder u8 | hasParent u8 [parent[16]] | name UTF | mime UTF | size u64
 *            createdAt u64 | modifiedAt u64 | isTrashed u8 [trashedAt u64] | keyLength u16 | key
 */
internal object IndexCodec {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte(), 'X'.code.toByte())
    private const val VERSION = 1
    private const val MAX_ENTRIES = 5_000_000

    fun encode(entries: Collection<VaultEntry>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.write(MAGIC); o.writeByte(VERSION); o.writeInt(entries.size)
            for (e in entries) {
                o.write(e.id.bytes)
                o.writeBoolean(e.isFolder)
                o.writeBoolean(e.parent != null); e.parent?.let { o.write(it.bytes) }
                o.writeUTF(e.name); o.writeUTF(e.mimeType)
                o.writeLong(e.size); o.writeLong(e.createdAt); o.writeLong(e.modifiedAt)
                o.writeBoolean(e.trashedAt != null); e.trashedAt?.let { o.writeLong(it) }
                o.writeShort(e.wrappedKey.size); o.write(e.wrappedKey)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<VaultEntry> = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            val magic = ByteArray(4).also { i.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw CorruptDataException("Not a vault index")
            val version = i.readUnsignedByte()
            if (version != VERSION) throw UnsupportedFormatException("Index version $version")
            val count = i.readInt()
            if (count !in 0..MAX_ENTRIES) throw CorruptDataException("Bad index size")
            val list = ArrayList<VaultEntry>(minOf(count, 10_000))
            repeat(count) {
                val id = FileId(ByteArray(FileId.SIZE).also { i.readFully(it) })
                val folder = i.readBoolean()
                val parent = if (i.readBoolean()) FileId(ByteArray(FileId.SIZE).also { i.readFully(it) }) else null
                val name = i.readUTF(); val mime = i.readUTF()
                val size = i.readLong(); val created = i.readLong(); val modified = i.readLong()
                val trashed = if (i.readBoolean()) i.readLong() else null
                val key = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
                list += VaultEntry(id, folder, parent, name, mime, size, created, modified, trashed, key)
            }
            if (i.read() != -1) throw CorruptDataException("Trailing data in index")
            list
        }
    } catch (e: EOFException) {
        throw CorruptDataException("Index ends early", e)
    } catch (e: IOException) {
        throw CorruptDataException("Unreadable index", e)
    }
}
