package app.bulwark.vault

import app.bulwark.crypto.Argon2Params
import app.bulwark.crypto.BulwarkCryptoException
import app.bulwark.crypto.FileCipher
import app.bulwark.crypto.FileId
import app.bulwark.crypto.FileKeyWrapper
import app.bulwark.crypto.Keyring
import app.bulwark.crypto.Keyslot
import app.bulwark.crypto.MasterKey
import app.bulwark.crypto.Primitives
import app.bulwark.crypto.SealKeys
import app.bulwark.crypto.SecretBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

/** Thrown when an operation is attempted after the vault was locked (for example by auto-lock). */
class VaultLockedException : IllegalStateException("The vault is locked")

/**
 * A vault on disk. Layout under [root] (app-private storage, invisible to other apps and the gallery):
 *
 *     keyring.bin   keyslots, sealed by the device layer
 *     seal.bin      locked-capture public key and its wrapped secret key
 *     index.bin     the encrypted index (names, folders, sizes, dates, file keys)
 *     blobs/<id>    one encrypted file per item, named by its random ID
 *     staging/      imports in progress; anything left here after a crash is deleted
 *
 * keyring.bin is written last when a vault is created, so its presence means the vault is complete.
 */
class VaultStore(
    val root: File,
    private val primitives: Primitives,
    private val sealer: KeyringSealer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val keyringFile = File(root, "keyring.bin")
    private val sealFile = File(root, "seal.bin")
    internal val indexFile = File(root, "index.bin")
    internal val blobDir = File(root, "blobs")
    internal val stagingDir = File(root, "staging")

    fun exists(): Boolean = keyringFile.isFile

    /** Whether a recovery key slot exists; readable without unlocking (slots hold no secrets in the clear). */
    fun hasRecoveryKey(): Boolean = readKeyring().ofType(Keyslot.Type.RECOVERY_KEY).isNotEmpty()

    fun create(
        password: CharArray,
        params: Argon2Params,
        recoveryKey: SecretBytes?,
        allowBelowFloor: Boolean = false,
    ): UnlockedVault {
        check(!exists()) { "A vault already exists here" }
        root.deleteRecursively() // remove any half-created vault left by a crash
        blobDir.mkdirs(); stagingDir.mkdirs()
        val mk = MasterKey.generate(primitives)
        try {
            var ring = Keyring(listOf(Keyslot.createPassword(primitives, mk, password, params, allowBelowFloor = allowBelowFloor)))
            if (recoveryKey != null) ring = ring.with(Keyslot.createWithSecret(primitives, mk, Keyslot.Type.RECOVERY_KEY, recoveryKey))
            AtomicFiles.write(sealFile, SealKeys.generate(primitives, mk).serialize())
            val vault = UnlockedVault(this, primitives, mk, ring, clock, emptyList())
            vault.saveIndex()
            writeKeyring(ring)
            return vault
        } catch (t: Throwable) {
            mk.close()
            throw t
        }
    }

    /** Throws [app.bulwark.crypto.WrongCredentialsException] on a wrong password. */
    fun unlock(password: CharArray): UnlockedVault = open(readKeyring().let { it to it.unlockWithPassword(primitives, password) })

    fun unlockWithRecoveryKey(recoveryKey: SecretBytes): UnlockedVault =
        open(readKeyring().let { it to it.unlockWithSecret(primitives, Keyslot.Type.RECOVERY_KEY, recoveryKey) })

    /** Crypto-erase: destroys the device key, then deletes every file. */
    fun eraseEverything() {
        sealer.destroy()
        root.deleteRecursively()
    }

    private fun open(pair: Pair<Keyring, MasterKey>): UnlockedVault {
        val (ring, mk) = pair
        try {
            val entries = UnlockedVault.loadIndex(this, primitives, mk)
            return UnlockedVault(this, primitives, mk, ring, clock, entries).also { it.maintenance() }
        } catch (t: Throwable) {
            mk.close()
            throw t
        }
    }

    internal fun readKeyring(): Keyring = Keyring.parse(sealer.open(keyringFile.readBytes()))

    internal fun writeKeyring(ring: Keyring) = AtomicFiles.write(keyringFile, sealer.seal(ring.serialize()))

    internal fun blobFile(id: FileId) = File(blobDir, id.hex)
}

/**
 * An open vault. Holds the master key in memory until [close], which wipes it; auto-lock calls
 * [close]. All methods throw [VaultLockedException] once closed.
 */
class UnlockedVault internal constructor(
    private val store: VaultStore,
    private val primitives: Primitives,
    private val masterKey: MasterKey,
    private var keyring: Keyring,
    private val clock: () -> Long,
    initial: List<VaultEntry>,
) : AutoCloseable {
    private val lock = Any()
    private val entries = LinkedHashMap<FileId, VaultEntry>().apply { initial.forEach { put(it.id, it) } }
    private val wrapper = FileKeyWrapper(masterKey, primitives)
    private val indexKey = masterKey.deriveSubkey(MasterKey.Purpose.INDEX)
    private val cipher = FileCipher(primitives)

    @Volatile
    var isClosed = false
        private set

    // --- Reading ---

    /** Items in [folder] (null = top level), folders first, then by name. Excludes the trash. */
    fun children(folder: FileId?): List<VaultEntry> = synchronized(lock) {
        ensureOpen()
        entries.values.filter { !it.isTrashed && it.parent == folder }.sortedWith(ORDER)
    }

    /** What the Trash screen shows: each trashed item whose parent is not itself in the trash. */
    fun trash(): List<VaultEntry> = synchronized(lock) {
        ensureOpen()
        entries.values.filter { e -> e.isTrashed && (e.parent == null || entries[e.parent]?.isTrashed != true) }
            .sortedByDescending { it.trashedAt }
    }

    fun get(id: FileId): VaultEntry? = synchronized(lock) { ensureOpen(); entries[id] }

    /** Folder path from the top level down to [folder], for breadcrumbs. */
    fun pathTo(folder: FileId?): List<VaultEntry> = synchronized(lock) {
        ensureOpen()
        generateSequence(folder?.let { entries[it] }) { e -> e.parent?.let { entries[it] } }.toList().reversed()
    }

    fun fileCount(): Int = synchronized(lock) { entries.values.count { !it.isFolder } }

    /** Bytes the vault occupies on the phone, including encryption overhead and padding. */
    fun storageUsed(): Long = store.blobDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Decrypts a file into [sink]; throws on any tampering. */
    fun readFile(id: FileId, sink: OutputStream): Long {
        val entry = get(id) ?: throw NoSuchElementException("No such file")
        require(!entry.isFolder) { "Folders have no contents" }
        val fk = synchronized(lock) { ensureOpen(); wrapper.unwrap(entry.wrappedKey, id) }
        fk.use { key -> FileInputStream(store.blobFile(id)).buffered().use { return cipher.decrypt(key, id, it, sink) } }
    }

    fun readBytes(id: FileId, maxBytes: Long): ByteArray {
        val entry = get(id) ?: throw NoSuchElementException("No such file")
        require(entry.size <= maxBytes) { "File too large to open in memory" }
        return ByteArrayOutputStream(entry.size.toInt()).also { readFile(id, it) }.toByteArray()
    }

    // --- Adding ---

    fun createFolder(name: String, parent: FileId?): VaultEntry = mutate {
        requireFolder(parent)
        val now = clock()
        VaultEntry(FileId.random(primitives), true, parent, cleanName(name), FOLDER_MIME, 0, now, now, null, ByteArray(0))
            .also { entries[it.id] = it }
    }

    /**
     * Encrypts exactly [length] bytes from [source] into the vault. Crash-safe: the file is
     * encrypted into staging, read back and verified, moved into place, and only then added to the
     * index. [onProgress] receives bytes read so far.
     */
    fun importFile(
        name: String,
        mimeType: String,
        length: Long,
        source: InputStream,
        parent: FileId?,
        onProgress: (Long) -> Unit = {},
    ): VaultEntry {
        synchronized(lock) { ensureOpen(); requireFolder(parent) }
        val id = FileId.random(primitives)
        val staged = File(store.stagingDir, "${id.hex}.part").also { store.stagingDir.mkdirs() }
        val fk = wrapper.newFileKey()
        try {
            FileOutputStream(staged).use { out ->
                val buffered = out.buffered(256 * 1024)
                cipher.encrypt(fk, id, ProgressStream(source, onProgress), length, buffered)
                buffered.flush()
                out.fd.sync()
            }
            // Verify before commit: decrypt the staged copy completely.
            FileInputStream(staged).buffered().use { cipher.decrypt(fk, id, it, DiscardStream) }
            val wrapped = synchronized(lock) { ensureOpen(); wrapper.wrap(fk, id) }
            val target = store.blobFile(id)
            AtomicFiles.commit(staged, target)
            val now = clock()
            val entry = VaultEntry(id, false, parent, cleanName(name), mimeType.ifBlank { DEFAULT_MIME }, length, now, now, null, wrapped)
            try {
                mutate { entries[id] = entry; entry }
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
            return entry
        } finally {
            fk.close()
            staged.delete()
        }
    }

    // --- Changing ---

    fun rename(id: FileId, newName: String): VaultEntry = mutate {
        val e = entries[id] ?: throw NoSuchElementException("No such item")
        e.copy(name = cleanName(newName), modifiedAt = clock()).also { entries[id] = it }
    }

    fun move(id: FileId, newParent: FileId?): VaultEntry = mutate {
        val e = entries[id] ?: throw NoSuchElementException("No such item")
        requireFolder(newParent)
        var cursor = newParent
        while (cursor != null) {
            require(cursor != id) { "A folder can't be moved inside itself" }
            cursor = entries[cursor]?.parent
        }
        e.copy(parent = newParent, modifiedAt = clock()).also { entries[id] = it }
    }

    /** Moves an item (and everything inside a folder) to the trash. */
    fun moveToTrash(id: FileId) = mutate {
        val now = clock()
        subtree(id).filter { !it.isTrashed }.forEach { entries[it.id] = it.copy(trashedAt = now) }
    }

    /** Restores an item from the trash, with whatever was trashed along with it. */
    fun restore(id: FileId) = mutate {
        val e = entries[id] ?: throw NoSuchElementException("No such item")
        val stamp = e.trashedAt ?: return@mutate
        subtree(id).filter { it.trashedAt == stamp }.forEach { entries[it.id] = it.copy(trashedAt = null) }
        val parent = e.parent?.let { entries[it] }
        if (e.parent != null && (parent == null || parent.isTrashed)) entries[id] = entries[id]!!.copy(parent = null)
    }

    /** Permanently deletes an item and its contents: keys leave the index first, then files are removed. */
    fun deleteForever(id: FileId) {
        val removed = mutate { subtree(id).onEach { entries.remove(it.id) } }
        removed.filter { !it.isFolder }.forEach { store.blobFile(it.id).delete() }
    }

    fun emptyTrash(): Int {
        val roots = trash()
        roots.forEach { deleteForever(it.id) }
        return roots.size
    }

    fun purgeExpiredTrash(retentionMillis: Long): Int {
        val cutoff = clock() - retentionMillis
        val expired = trash().filter { (it.trashedAt ?: Long.MAX_VALUE) < cutoff }
        expired.forEach { deleteForever(it.id) }
        return expired.size
    }

    fun changePassword(newPassword: CharArray, params: Argon2Params, allowBelowFloor: Boolean = false) = synchronized(lock) {
        ensureOpen()
        val slot = Keyslot.createPassword(primitives, masterKey, newPassword, params, allowBelowFloor = allowBelowFloor)
        val ring = Keyring(keyring.slots.filterNot { it.type == Keyslot.Type.PASSWORD } + slot)
        store.writeKeyring(ring)
        keyring = ring
    }

    /** Replaces the recovery key (or removes it when [recoveryKey] is null). The old one stops working at once. */
    fun setRecoveryKey(recoveryKey: SecretBytes?) = synchronized(lock) {
        ensureOpen()
        var ring = Keyring(keyring.slots.filterNot { it.type == Keyslot.Type.RECOVERY_KEY })
        if (recoveryKey != null) ring = ring.with(Keyslot.createWithSecret(primitives, masterKey, Keyslot.Type.RECOVERY_KEY, recoveryKey))
        store.writeKeyring(ring)
        keyring = ring
    }

    val hasRecoveryKey: Boolean get() = synchronized(lock) { keyring.ofType(Keyslot.Type.RECOVERY_KEY).isNotEmpty() }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            wrapper.close(); indexKey.close(); masterKey.close()
            entries.clear()
        }
    }

    // --- Internals ---

    /** Cleans up after crashes and applies trash retention. Runs on every unlock. */
    internal fun maintenance(trashRetentionMillis: Long = DEFAULT_TRASH_RETENTION) {
        store.stagingDir.listFiles()?.forEach { it.delete() }
        store.root.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val known = synchronized(lock) { entries.keys.map { it.hex }.toSet() }
        store.blobDir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
        purgeExpiredTrash(trashRetentionMillis)
    }

    private fun <T> mutate(block: () -> T): T = synchronized(lock) {
        ensureOpen()
        val before = LinkedHashMap(entries)
        try {
            block().also { saveIndex() }
        } catch (t: Throwable) {
            entries.clear(); entries.putAll(before) // keep memory and disk in step
            throw t
        }
    }

    internal fun saveIndex() = synchronized(lock) {
        val plain = IndexCodec.encode(entries.values)
        try {
            AtomicFiles.write(store.indexFile) { out ->
                cipher.encrypt(indexKey, INDEX_ID, ByteArrayInputStream(plain), plain.size.toLong(), out)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun subtree(id: FileId): List<VaultEntry> {
        val root = entries[id] ?: throw NoSuchElementException("No such item")
        val out = mutableListOf(root)
        var frontier = listOf(root.id)
        while (frontier.isNotEmpty()) {
            val next = entries.values.filter { it.parent in frontier }
            out += next; frontier = next.filter { it.isFolder }.map { it.id }
        }
        return out
    }

    private fun requireFolder(id: FileId?) {
        if (id == null) return
        val f = entries[id] ?: throw NoSuchElementException("No such folder")
        require(f.isFolder && !f.isTrashed) { "Not an available folder" }
    }

    private fun ensureOpen() { if (isClosed) throw VaultLockedException() }

    private class ProgressStream(source: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(source) {
        private var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) report(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) report(it.toLong()) }
        private fun report(n: Long) { total += n; onProgress(total) }
    }

    private object DiscardStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    companion object {
        const val FOLDER_MIME = "inode/directory"
        const val DEFAULT_MIME = "application/octet-stream"
        const val DEFAULT_TRASH_RETENTION = 30L * 24 * 60 * 60 * 1000
        internal val INDEX_ID = FileId(ByteArray(FileId.SIZE) { 0x49 })

        private val ORDER = compareBy<VaultEntry>({ !it.isFolder }, { it.name.lowercase() }, { it.createdAt })

        internal fun cleanName(name: String): String {
            val n = name.replace(Regex("[\\u0000-\\u001f/\\\\]"), " ").trim().take(255)
            return n.ifEmpty { "Untitled" }
        }

        internal fun loadIndex(store: VaultStore, primitives: Primitives, mk: MasterKey): List<VaultEntry> {
            if (!store.indexFile.isFile) throw app.bulwark.crypto.CorruptDataException("Vault index is missing")
            return mk.deriveSubkey(MasterKey.Purpose.INDEX).use { key ->
                val out = ByteArrayOutputStream()
                FileInputStream(store.indexFile).buffered().use { FileCipher(primitives).decrypt(key, INDEX_ID, it, out) }
                val bytes = out.toByteArray()
                try { IndexCodec.decode(bytes) } finally { bytes.fill(0) }
            }
        }
    }
}

/** Errors the UI shows as "this vault or file is damaged". */
fun Throwable.isVaultDamage(): Boolean = this is BulwarkCryptoException && this !is app.bulwark.crypto.WrongCredentialsException
