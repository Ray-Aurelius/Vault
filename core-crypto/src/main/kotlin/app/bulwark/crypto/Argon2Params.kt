package app.bulwark.crypto

import java.text.Normalizer

/**
 * Argon2id cost settings, stored next to every password or PIN keyslot so they can be raised
 * later without breaking older vaults.
 */
data class Argon2Params(val opsLimit: Long, val memLimitBytes: Long) {

    init {
        require(opsLimit in MIN_OPS..MAX_OPS) { "Argon2 passes out of range: $opsLimit" }
        require(memLimitBytes in MIN_MEM_BYTES..MAX_MEM_BYTES) { "Argon2 memory out of range: $memLimitBytes" }
        require(memLimitBytes % 1024 == 0L) { "Argon2 memory must be whole KiB" }
    }

    /** True if these settings meet Bulwark's floor for real vaults (spec: m >= 64 MiB, t >= 3). */
    val meetsProductionFloor: Boolean
        get() = opsLimit >= PRODUCTION_MIN_OPS && memLimitBytes >= PRODUCTION_MIN_MEM_BYTES

    companion object {
        const val SALT_BYTES = 16

        /** Absolute limits accepted when reading a keyslot (protects against absurd values). */
        const val MIN_OPS = 1L
        const val MAX_OPS = 32L
        const val MIN_MEM_BYTES = 8L * 1024
        const val MAX_MEM_BYTES = 2L * 1024 * 1024 * 1024

        const val PRODUCTION_MIN_OPS = 3L
        const val PRODUCTION_MIN_MEM_BYTES = 64L * 1024 * 1024

        /** Floor for backup files, which may face unlimited offline guessing (spec: 256 MiB). */
        const val BACKUP_MIN_MEM_BYTES = 256L * 1024 * 1024

        /** The production floor itself: m = 64 MiB, t = 3. */
        val FLOOR = Argon2Params(PRODUCTION_MIN_OPS, PRODUCTION_MIN_MEM_BYTES)
    }
}

/**
 * Picks Argon2id settings for this device: as much memory as the device can spare (up to
 * [maxMemBytes]), then enough passes to take roughly [targetMillis]. Never goes below the floor.
 */
object Argon2Calibrator {

    fun calibrate(
        primitives: Primitives,
        availableMemBytes: Long,
        targetMillis: Long = 1000,
        maxMemBytes: Long = 512L * 1024 * 1024,
        floor: Argon2Params = Argon2Params.FLOOR,
        clock: () -> Long = System::nanoTime,
    ): Argon2Params {
        // Use at most a quarter of what the device says it can spare, rounded down to whole MiB.
        val mib = 1024L * 1024
        val mem = ((availableMemBytes / 4).coerceAtMost(maxMemBytes) / mib * mib).coerceAtLeast(floor.memLimitBytes)
        val probe = Argon2Params(floor.opsLimit, mem)
        val salt = primitives.randomBytes(Argon2Params.SALT_BYTES)
        val password = "calibration".toByteArray()
        val start = clock()
        primitives.argon2id(password, salt, probe).also { SecretBytes.wipe(it) }
        val elapsedMs = ((clock() - start) / 1_000_000).coerceAtLeast(1)
        val ops = (probe.opsLimit * targetMillis / elapsedMs).coerceIn(floor.opsLimit, 10)
        return Argon2Params(ops, mem)
    }
}

/** Turns a typed password into bytes the same way on every device, then wipes the chars. */
object PasswordEncoding {
    /**
     * Unicode NFC normalization, so "é" typed as one character or as "e" + accent unlocks the same
     * vault. The returned array must be wiped by the caller.
     */
    fun encode(password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Empty password" }
        val normalized = Normalizer.normalize(java.nio.CharBuffer.wrap(password), Normalizer.Form.NFC)
        val buffer = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(normalized))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
