package app.bulwark.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bulwark.android.R
import app.bulwark.android.session.VaultSession
import app.bulwark.android.session.VaultSession.UnlockResult
import app.bulwark.crypto.InvalidRecoveryPhraseException
import app.bulwark.crypto.RecoveryKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UnlockScreen(session: VaultSession) {
    var recoveryMode by remember { mutableStateOf(false) }
    if (recoveryMode) RecoveryUnlockScreen(session) { recoveryMode = false } else PasswordUnlockScreen(session) { recoveryMode = true }
}

@Composable
private fun PasswordUnlockScreen(session: VaultSession, onUseRecovery: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var waitUntil by remember { mutableLongStateOf(session.prefs.lockoutUntil) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(waitUntil) { while (now < waitUntil) { delay(1000); now = System.currentTimeMillis() } }
    val waiting = waitUntil > now
    val scope = rememberCoroutineScope()
    var hasRecovery by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasRecovery = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { session.store.hasRecoveryKey() }.getOrDefault(false)
        }
    }
    val wrongText = stringResource(R.string.unlock_wrong)
    val damagedText = stringResource(R.string.unlock_damaged)
    val missingText = stringResource(R.string.unlock_device_key_missing)

    fun submit() {
        if (pw.isEmpty() || busy || waiting) return
        busy = true; message = null
        scope.launch {
            when (val r = session.unlock(pw.toCharArray())) {
                UnlockResult.Success -> Unit
                is UnlockResult.Wrong -> { message = wrongText; waitUntil = System.currentTimeMillis() + r.waitMillis; now = System.currentTimeMillis() }
                is UnlockResult.Wait -> { waitUntil = System.currentTimeMillis() + r.millis; now = System.currentTimeMillis() }
                UnlockResult.Damaged -> message = damagedText
                UnlockResult.DeviceKeyMissing -> message = missingText
            }
            pw = ""; busy = false
        }
    }

    Page {
        Spacer(Modifier.height(48.dp))
        BrandHeader(stringResource(R.string.unlock_subtitle))
        Spacer(Modifier.height(32.dp))
        SecretField(pw, { pw = it }, stringResource(R.string.password_label), onImeAction = ::submit, isError = message != null)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        if (waiting) {
            val secs = ((waitUntil - now) / 1000).toInt() + 1
            Quiet(pluralStringResource(R.plurals.unlock_wait, secs, secs), Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        else PrimaryButton(stringResource(R.string.unlock), ::submit, enabled = pw.isNotEmpty() && !waiting)
        if (hasRecovery) LinkButton(stringResource(R.string.use_recovery_key), onUseRecovery)
        Spacer(Modifier.height(24.dp))
        Quiet(stringResource(R.string.unlock_footer))
    }
}

@Composable
private fun RecoveryUnlockScreen(session: VaultSession, onBack: () -> Unit) {
    var words by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val wrongText = stringResource(R.string.recovery_wrong)
    val damagedText = stringResource(R.string.unlock_damaged)

    fun submit() {
        if (busy) return
        val key = try { RecoveryKey.fromWords(words) } catch (e: InvalidRecoveryPhraseException) { message = e.message; return }
        busy = true; message = null
        scope.launch {
            when (val r = session.unlockWithRecovery(key)) {
                UnlockResult.Success -> Unit
                is UnlockResult.Wrong, is UnlockResult.Wait -> message = wrongText
                else -> message = damagedText
            }
            busy = false
        }
    }

    Page(title = stringResource(R.string.use_recovery_key), onBack = onBack, bottomBar = {
        if (busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        else PrimaryButton(stringResource(R.string.unlock), ::submit, enabled = words.isNotBlank())
    }) {
        Body(stringResource(R.string.recovery_unlock_body))
        Spacer(Modifier.height(12.dp))
        SecretField(words, { words = it }, stringResource(R.string.recovery_words_label), singleLine = false, isError = message != null)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))
        Quiet(stringResource(R.string.recovery_unlock_after))
    }
}

/** After a recovery-key unlock the user must choose a new password before seeing their files. */
@Composable
fun ForcedNewPasswordScreen(session: VaultSession) {
    var saving by remember { mutableStateOf<String?>(null) }
    val pending = saving
    if (pending != null) {
        SecuringScreen(onRun = { session.changePassword(pending.toCharArray()) }, onFailed = { saving = null })
    } else {
        CreatePasswordScreen(
            session, title = stringResource(R.string.new_password_title),
            intro = stringResource(R.string.new_password_after_recovery),
        ) { saving = it }
    }
}
