package app.bulwark.android.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.OpenableColumns
import app.bulwark.crypto.FileCipher
import app.bulwark.crypto.FileId
import app.bulwark.vault.UnlockedVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Imports files chosen in the system picker. Plaintext is never written anywhere: each file is
 * streamed straight from the picker into the vault's encryptor. Before a large import the plan is
 * checked against free space and battery (spec: data-loss protection).
 */
class Importer(private val context: Context, private val session: VaultSession) {

    data class Item(val uri: Uri, val name: String, val mimeType: String, val size: Long)

    data class Plan(
        val items: List<Item>,
        val totalBytes: Long,
        val neededBytes: Long,
        val freeBytes: Long,
        val batteryPercent: Int,
        val charging: Boolean,
        val estimatedSeconds: Long,
    ) {
        val enoughSpace get() = freeBytes >= neededBytes
        val isLarge get() = totalBytes > LARGE_BYTES || items.size > LARGE_COUNT
        /** Spec: below 15% and not charging, a large operation will not start. */
        val batteryTooLow get() = isLarge && !charging && batteryPercent in 0 until 15
        val suggestPlugIn get() = isLarge && !charging && batteryPercent < 50
    }

    sealed interface State {
        data class Running(val fileIndex: Int, val fileCount: Int, val bytesDone: Long, val bytesTotal: Long, val currentName: String) : State
        data class Finished(val imported: Int, val failed: Int, val cancelled: Boolean) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun dismiss() { if (_state.value is State.Finished) _state.value = null }

    /** Reads names and sizes. Files whose size the provider doesn't report are measured by reading them once (never stored). */
    fun plan(uris: List<Uri>): Plan {
        val resolver = context.contentResolver
        val items = uris.mapNotNull { uri ->
            runCatching {
                var name = "Untitled"; var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
                if (size < 0) size = resolver.openInputStream(uri)?.use { countBytes(it) } ?: return@runCatching null
                Item(uri, name, resolver.getType(uri) ?: "application/octet-stream", size)
            }.getOrNull()
        }
        val cipher = FileCipher(session.primitives)
        val total = items.sumOf { it.size }
        val encrypted = items.sumOf { cipher.encryptedSize(it.size) }
        val needed = encrypted + encrypted / 10 + HEADROOM_BYTES
        val free = File(context.noBackupFilesDir.path).usableSpace
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        // Encrypt + verify reads everything twice; a conservative 25 MB/s overall.
        val seconds = (total * 2 / (25L * 1024 * 1024)).coerceAtLeast(1)
        return Plan(items, total, needed, free, level, charging, seconds)
    }

    fun start(vault: UnlockedVault, plan: Plan, folder: FileId?) {
        val job = session.scope.launch {
            var imported = 0; var failed = 0; var doneBytes = 0L
            try {
                plan.items.forEachIndexed { i, item ->
                    coroutineContext.ensureActive()
                    _state.value = State.Running(i + 1, plan.items.size, doneBytes, plan.totalBytes, item.name)
                    try {
                        context.contentResolver.openInputStream(item.uri)!!.use { input ->
                            vault.importFile(item.name, item.mimeType, item.size, CancellableStream(input), folder) { n ->
                                _state.value = State.Running(i + 1, plan.items.size, doneBytes + n, plan.totalBytes, item.name)
                            }
                        }
                        imported++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (vault.isClosed) throw CancellationException("Vault locked")
                        failed++
                    }
                    doneBytes += item.size
                }
                _state.value = State.Finished(imported, failed, cancelled = false)
            } catch (e: CancellationException) {
                _state.value = State.Finished(imported, plan.items.size - imported, cancelled = true)
            }
        }
        session.track(job)
        currentJob = job
    }

    @Volatile private var currentJob: kotlinx.coroutines.Job? = null

    fun cancel() { currentJob?.cancel() }

    /** Stops reading as soon as the import is cancelled; the half-written staging file is then discarded. */
    private inner class CancellableStream(private val inner: InputStream) : InputStream() {
        private fun check() { if (currentJob?.isCancelled == true) throw java.io.InterruptedIOException("Import cancelled") }
        override fun read(): Int { check(); return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return inner.read(b, off, len) }
        override fun close() = inner.close()
    }

    private fun countBytes(input: InputStream): Long {
        val buf = ByteArray(256 * 1024); var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; total += n }
        buf.fill(0)
        return total
    }

    companion object {
        const val LARGE_BYTES = 1L shl 30 // 1 GB
        const val LARGE_COUNT = 500
        const val HEADROOM_BYTES = 500L * 1024 * 1024
    }
}
