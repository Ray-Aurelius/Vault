package app.bulwark.android.session

import android.content.Context
import androidx.core.content.edit

/**
 * Non-secret app settings, kept in app-private storage (excluded from all backups). Nothing about
 * the vault's contents or credentials is ever stored here. Every default is the more private choice.
 */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("bulwark_settings", Context.MODE_PRIVATE)

    var acceptedTermsVersion: String?
        get() = p.getString("terms_version", null)
        set(v) = p.edit { putString("terms_version", v).putLong("terms_accepted_at", System.currentTimeMillis()) }

    // --- Auto-lock ---
    /** Lock after this long without interaction while open. */
    var autoLockMinutes: Int
        get() = p.getInt("auto_lock_minutes", 2)
        set(v) = p.edit { putInt("auto_lock_minutes", v) }

    /** Grace period after leaving the app; 0 = lock immediately (default). */
    var leaveGraceSeconds: Int
        get() = p.getInt("leave_grace_seconds", 0)
        set(v) = p.edit { putInt("leave_grace_seconds", v) }

    var lockOnScreenOff: Boolean
        get() = p.getBoolean("lock_on_screen_off", true)
        set(v) = p.edit { putBoolean("lock_on_screen_off", v) }

    // --- Biometric unlock (off by default) ---
    var biometricEnabled: Boolean
        get() = p.getBoolean("biometric_enabled", false)
        set(v) = p.edit { putBoolean("biometric_enabled", v) }

    /** Biometric unlock stops working this long after the last password unlock (and after any restart). */
    var passwordEveryHours: Int
        get() = p.getInt("password_every_hours", 72)
        set(v) = p.edit { putInt("password_every_hours", v) }

    var lastPasswordUnlockAt: Long
        get() = p.getLong("last_password_unlock_at", 0)
        set(v) = p.edit { putLong("last_password_unlock_at", v) }

    /** Estimated boot time when the password was last used, to detect restarts. */
    var lastPasswordBootTime: Long
        get() = p.getLong("last_password_boot_time", 0)
        set(v) = p.edit { putLong("last_password_boot_time", v) }

    // --- Break-in protection ---
    var failedAttempts: Int
        get() = p.getInt("failed_attempts", 0)
        set(v) = p.edit { putInt("failed_attempts", v) }

    var lockoutUntil: Long
        get() = p.getLong("lockout_until", 0)
        set(v) = p.edit { putLong("lockout_until", v) }

    /** Erase the vault after this many wrong passwords in a row; 0 = off (default). */
    var eraseAfterFailures: Int
        get() = p.getInt("erase_after_failures", 0)
        set(v) = p.edit { putInt("erase_after_failures", v) }

    var notifyFailedAttempts: Boolean
        get() = p.getBoolean("notify_failed_attempts", true)
        set(v) = p.edit { putBoolean("notify_failed_attempts", v) }

    // --- Screen and files ---
    /** Off by default: screenshots, recording, casting and the recent-apps preview are blocked. */
    var allowScreenshots: Boolean
        get() = p.getBoolean("allow_screenshots", false)
        set(v) = p.edit { putBoolean("allow_screenshots", v) }

    var confirmPasswordForExport: Boolean
        get() = p.getBoolean("confirm_password_export", false)
        set(v) = p.edit { putBoolean("confirm_password_export", v) }

    var trashRetentionDays: Int
        get() = p.getInt("trash_retention_days", 30)
        set(v) = p.edit { putInt("trash_retention_days", v) }

    var noRecoveryAcknowledged: Boolean
        get() = p.getBoolean("no_recovery_ack", false)
        set(v) = p.edit { putBoolean("no_recovery_ack", v) }

    /** After an erase, everything returns to defaults except the accepted Terms. */
    fun resetAfterErase() {
        val terms = acceptedTermsVersion
        p.edit { clear() }
        if (terms != null) acceptedTermsVersion = terms
    }
}
