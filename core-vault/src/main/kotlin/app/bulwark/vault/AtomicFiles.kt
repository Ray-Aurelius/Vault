package app.bulwark.vault

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: write to a temporary file in the same folder, flush it to the
 * storage chip, then rename over the target in one step. After a crash at any moment the target
 * is either entirely old or entirely new, never half-written.
 */
internal object AtomicFiles {
    fun write(target: File, block: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                block(out)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    fun write(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    /** Moves a finished file into place in one step (same filesystem). */
    fun commit(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
