package app.bulwark.android.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bulwark.android.R
import app.bulwark.android.security.BiometricUnlock
import app.bulwark.android.session.VaultSession
import app.bulwark.vault.UnlockedVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Security & privacy settings. Every default is the strictest choice. The rule throughout:
 * making Bulwark stricter is one tap; making it less strict asks for the password first, so
 * someone holding an unlocked phone can't quietly weaken it.
 */
@Composable
fun SecuritySettingsScreen(
    session: VaultSession,
    vault: UnlockedVault,
    onBack: () -> Unit,
    openChangePassword: () -> Unit,
    openRecoverySetup: () -> Unit,
) {
    val prefs = session.prefs
    val activity = LocalActivity.current!!
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // re-read settings after each change
    val bump = { version++ }

    var needPassword by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var confirmErasePolicy by remember { mutableStateOf<Int?>(null) }
    var confirmEraseNow by remember { mutableStateOf(false) }
    var confirmAllowScreens by remember { mutableStateOf(false) }
    var removeRecovery by remember { mutableStateOf(false) }
    var bioMessage by remember { mutableStateOf<String?>(null) }
    var bioAvailability by remember { mutableStateOf<BiometricUnlock.Availability?>(null) }
    LaunchedEffect(Unit) { bioAvailability = withContext(Dispatchers.Default) { session.biometrics.availability() } }

    val bioTitle = stringResource(R.string.bio_prompt_title)
    val bioSetup = stringResource(R.string.bio_prompt_setup)
    val cancelText = stringResource(R.string.cancel)
    val bioFailed = stringResource(R.string.bio_setup_failed)
    val weakenTitle = stringResource(R.string.confirm_password_title)

    /** Runs [action] directly, or after the password when [weakening] (the change makes Bulwark less strict). */
    fun guarded(weakening: Boolean, reason: String, action: () -> Unit) {
        if (weakening) needPassword = reason to action else action()
    }

    fun startBiometricSetup() {
        val cipher = runCatching { session.biometrics.cipherForSetup() }.getOrElse { bioMessage = bioFailed; return }
        session.beginExternalActivity()
        session.biometrics.prompt(activity, bioTitle, bioSetup, cancelText, cipher) { authorized, error ->
            session.endExternalActivity()
            if (authorized == null) { bioMessage = error; session.biometrics.disable(); return@prompt }
            scope.launch {
                val ok = withContext(Dispatchers.Default) { runCatching { session.completeBiometricSetup(authorized) }.isSuccess }
                if (!ok) bioMessage = bioFailed
                bump()
            }
        }
    }

    // Read current values (re-read whenever `version` changes).
    @Suppress("UNUSED_EXPRESSION") version
    val bioOn = prefs.biometricEnabled && session.biometrics.isSetUp()

    Page(title = stringResource(R.string.security_privacy), onBack = onBack) {
        Quiet(stringResource(R.string.security_intro))

        Section(stringResource(R.string.section_unlock))
        val bioSubtitle = when (bioAvailability) {
            BiometricUnlock.Availability.NO_HARDWARE -> stringResource(R.string.bio_no_hardware)
            BiometricUnlock.Availability.NOT_ENROLLED -> stringResource(R.string.bio_not_enrolled)
            else -> stringResource(if (bioOn) R.string.bio_on_sub else R.string.bio_off_sub)
        }
        SwitchRow(
            stringResource(R.string.bio_unlock), bioSubtitle, bioOn,
            enabled = bioOn || bioAvailability == BiometricUnlock.Availability.AVAILABLE,
        ) { on ->
            if (on) guarded(true, weakenTitle) { startBiometricSetup() } else { session.disableBiometric(); bump() }
        }
        bioMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (bioOn) {
            SettingRow(stringResource(R.string.password_every), hoursLabel(prefs.passwordEveryHours)) {
                choice = Choice(R.string.password_every, listOf(24, 72, 168), prefs.passwordEveryHours, { hoursText(it) }) { h ->
                    guarded(h > prefs.passwordEveryHours, weakenTitle) { prefs.passwordEveryHours = h; bump() }
                }
            }
        }
        SettingRow(stringResource(R.string.change_password), null, onClick = openChangePassword)
        if (vault.hasRecoveryKey) {
            SettingRow(stringResource(R.string.replace_recovery_key), stringResource(R.string.replace_recovery_key_sub), onClick = openRecoverySetup)
            SettingRow(stringResource(R.string.remove_recovery_key), null) { removeRecovery = true }
        } else {
            SettingRow(stringResource(R.string.set_up_recovery), stringResource(R.string.no_recovery_sub), onClick = openRecoverySetup)
        }

        Section(stringResource(R.string.section_auto_lock))
        SettingRow(stringResource(R.string.lock_leaving), graceLabel(prefs.leaveGraceSeconds)) {
            choice = Choice(R.string.lock_leaving, listOf(0, 30, 60, 300), prefs.leaveGraceSeconds, { graceText(it) }) { g ->
                guarded(g > prefs.leaveGraceSeconds, weakenTitle) { prefs.leaveGraceSeconds = g; bump() }
            }
        }
        SwitchRow(stringResource(R.string.lock_screen_off), stringResource(R.string.lock_screen_off_sub), prefs.lockOnScreenOff) { on ->
            guarded(!on, weakenTitle) { prefs.lockOnScreenOff = on; bump() }
        }
        SettingRow(stringResource(R.string.auto_lock), pluralStringResource(R.plurals.auto_lock_minutes, prefs.autoLockMinutes, prefs.autoLockMinutes)) {
            choice = Choice(R.string.auto_lock, listOf(1, 2, 5, 10, 30), prefs.autoLockMinutes, { idleText(it) }) { m ->
                guarded(m > prefs.autoLockMinutes, weakenTitle) { prefs.autoLockMinutes = m; bump() }
            }
        }

        Section(stringResource(R.string.section_break_in))
        SettingRow(stringResource(R.string.attempt_delays), stringResource(R.string.attempt_delays_sub)) {}
        SwitchRow(stringResource(R.string.notify_failed), stringResource(R.string.notify_failed_sub), prefs.notifyFailedAttempts) { on ->
            guarded(!on, weakenTitle) { prefs.notifyFailedAttempts = on; bump() }
        }
        SettingRow(
            stringResource(R.string.erase_after),
            if (prefs.eraseAfterFailures == 0) stringResource(R.string.off) else pluralStringResource(R.plurals.erase_after_count, prefs.eraseAfterFailures, prefs.eraseAfterFailures),
        ) {
            choice = Choice(R.string.erase_after, listOf(0, 10, 20, 30, 50), prefs.eraseAfterFailures, { eraseText(it) }) { n ->
                when {
                    n == 0 -> guarded(true, weakenTitle) { prefs.eraseAfterFailures = 0; bump() }
                    else -> confirmErasePolicy = n
                }
            }
        }

        Section(stringResource(R.string.section_screen))
        SwitchRow(stringResource(R.string.block_screens), stringResource(R.string.block_screens_sub), !prefs.allowScreenshots) { block ->
            if (block) { session.setAllowScreenshots(false); bump() } else confirmAllowScreens = true
        }

        Section(stringResource(R.string.section_files))
        SwitchRow(stringResource(R.string.confirm_export), stringResource(R.string.confirm_export_sub), prefs.confirmPasswordForExport) { on ->
            guarded(!on, weakenTitle) { prefs.confirmPasswordForExport = on; bump() }
        }
        SettingRow(stringResource(R.string.trash_keep), pluralStringResource(R.plurals.days, prefs.trashRetentionDays, prefs.trashRetentionDays)) {
            choice = Choice(R.string.trash_keep, listOf(7, 30, 90), prefs.trashRetentionDays, { daysText(it) }) { d ->
                prefs.trashRetentionDays = d; bump()
            }
        }

        Section(stringResource(R.string.section_danger))
        DangerButton(stringResource(R.string.erase_now), { confirmEraseNow = true })
        Quiet(stringResource(R.string.erase_now_sub), Modifier.padding(top = 6.dp, bottom = 24.dp))
    }

    choice?.let { c -> ChoiceDialog(c, onDismiss = { choice = null }) { v -> choice = null; c.onPick(v) } }

    needPassword?.let { (title, action) ->
        PasswordConfirmDialog(session, title, onDismiss = { needPassword = null }) { needPassword = null; action() }
    }

    confirmErasePolicy?.let { n ->
        ConfirmDialog(
            stringResource(R.string.erase_after), pluralStringResource(R.plurals.erase_after_body, n, n), stringResource(R.string.turn_on),
            danger = true, requireTyping = stringResource(R.string.erase_phrase), onDismiss = { confirmErasePolicy = null },
        ) {
            confirmErasePolicy = null
            needPassword = weakenTitle to { prefs.eraseAfterFailures = n; bump() }
        }
    }

    if (confirmAllowScreens) ConfirmDialog(
        stringResource(R.string.allow_screens_title), stringResource(R.string.allow_screens_body), stringResource(R.string.allow),
        danger = true, onDismiss = { confirmAllowScreens = false },
    ) {
        confirmAllowScreens = false
        needPassword = weakenTitle to { session.setAllowScreenshots(true); bump() }
    }

    if (removeRecovery) ConfirmDialog(
        stringResource(R.string.remove_recovery_key), stringResource(R.string.remove_recovery_body), stringResource(R.string.remove),
        danger = true, requireTyping = stringResource(R.string.skip_phrase), onDismiss = { removeRecovery = false },
    ) {
        removeRecovery = false
        needPassword = weakenTitle to { vault.setRecoveryKey(null); prefs.noRecoveryAcknowledged = false; bump() }
    }

    if (confirmEraseNow) ConfirmDialog(
        stringResource(R.string.erase_now), stringResource(R.string.erase_now_body), stringResource(R.string.erase),
        danger = true, requireTyping = stringResource(R.string.erase_phrase), onDismiss = { confirmEraseNow = false },
    ) {
        confirmEraseNow = false
        needPassword = weakenTitle to { session.eraseVault() }
    }
}

private class Choice(val title: Int, val options: List<Int>, val current: Int, val label: @Composable (Int) -> String, val onPick: (Int) -> Unit)

@Composable
private fun ChoiceDialog(c: Choice, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(c.title)) },
        text = {
            Column {
                for (o in c.options) Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = c.current == o, role = Role.RadioButton) { onPick(o) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = c.current == o, onClick = null)
                    Text(c.label(o), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Asks for the vault password and checks it (about a second) before running [onVerified]. */
@Composable
fun PasswordConfirmDialog(session: VaultSession, title: String, onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun check() {
        if (pw.isEmpty() || checking) return
        checking = true; wrong = false
        scope.launch {
            val ok = session.verifyPassword(pw.toCharArray())
            checking = false; pw = ""
            if (ok) onVerified() else wrong = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.confirm_password_body))
                SecretField(pw, { pw = it }, stringResource(R.string.password_label), onImeAction = ::check, isError = wrong,
                    supporting = if (wrong) stringResource(R.string.unlock_wrong) else null)
                if (checking) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
        },
        confirmButton = { TextButton(onClick = ::check, enabled = pw.isNotEmpty() && !checking) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Quiet(subtitle)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable private fun hoursText(h: Int) = if (h % 24 == 0) pluralStringResource(R.plurals.days, h / 24, h / 24) else pluralStringResource(R.plurals.hours, h, h)
@Composable private fun hoursLabel(h: Int) = stringResource(R.string.password_every_sub, hoursText(h))
@Composable private fun graceText(s: Int) = when {
    s == 0 -> stringResource(R.string.immediately)
    s < 60 -> pluralStringResource(R.plurals.after_seconds, s, s)
    else -> pluralStringResource(R.plurals.after_minutes, s / 60, s / 60)
}
@Composable private fun graceLabel(s: Int) = graceText(s)
@Composable private fun idleText(m: Int) = pluralStringResource(R.plurals.auto_lock_minutes, m, m)
@Composable private fun eraseText(n: Int) = if (n == 0) stringResource(R.string.off) else pluralStringResource(R.plurals.erase_after_count, n, n)
@Composable private fun daysText(d: Int) = pluralStringResource(R.plurals.days, d, d)
