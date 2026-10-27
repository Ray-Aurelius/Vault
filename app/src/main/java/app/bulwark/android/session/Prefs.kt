package app.bulwark.android.session

import android.content.Context

/**
 * Non-secret app settings, kept in app-private storage (excluded from all backups). Nothing about
 * the vault's contents or credentials is ever stored here.
 */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("bulwark_settings", Context.MODE_PRIVATE)

    var acceptedTermsVersion: String?
        get() = p.getString("terms_version", null)
        set(v) = p.edit().putString("terms_version", v).putLong("terms_accepted_at", System.currentTimeMillis()).apply()

    val termsAcceptedAt: Long get() = p.getLong("terms_accepted_at", 0)

    var autoLockMinutes: Int
        get() = p.getInt("auto_lock_minutes", 2)
        set(v) = p.edit().putInt("auto_lock_minutes", v).apply()

    var failedAttempts: Int
        get() = p.getInt("failed_attempts", 0)
        set(v) = p.edit().putInt("failed_attempts", v).apply()

    var lockoutUntil: Long
        get() = p.getLong("lockout_until", 0)
        set(v) = p.edit().putLong("lockout_until", v).apply()

    var noRecoveryAcknowledged: Boolean
        get() = p.getBoolean("no_recovery_ack", false)
        set(v) = p.edit().putBoolean("no_recovery_ack", v).apply()

    var tipsEnabled: Boolean
        get() = p.getBoolean("tips", true)
        set(v) = p.edit().putBoolean("tips", v).apply()
}
