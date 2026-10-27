package app.bulwark.android.session

import android.app.ActivityManager
import android.content.Context
import app.bulwark.android.security.DeviceKeyMissingException
import app.bulwark.android.security.DeviceLayer
import app.bulwark.crypto.Argon2Calibrator
import app.bulwark.crypto.Argon2Params
import app.bulwark.crypto.Primitives
import app.bulwark.crypto.RecoveryKey
import app.bulwark.crypto.SecretBytes
import app.bulwark.crypto.WrongCredentialsException
import app.bulwark.vault.UnlockedVault
import app.bulwark.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app-wide state of the vault: whether the Terms were accepted, whether a vault exists, and
 * the unlocked vault while it is open. Locking closes the vault, which wipes its keys from memory.
 */
class VaultSession(private val context: Context, primitivesProvider: () -> Primitives) {

    sealed interface State {
        data object NeedsTerms : State
        data object NeedsSetup : State
        data object Locked : State
        class Unlocked(val vault: UnlockedVault, val mustChangePassword: Boolean = false) : State
    }

    sealed interface UnlockResult {
        data object Success : UnlockResult
        data class Wrong(val attempts: Int, val waitMillis: Long) : UnlockResult
        data class Wait(val millis: Long) : UnlockResult
        data object Damaged : UnlockResult
        data object DeviceKeyMissing : UnlockResult
    }

    val prefs = Prefs(context)
    val primitives: Primitives by lazy(primitivesProvider)
    val deviceLayer = DeviceLayer(context)
    val store: VaultStore by lazy { VaultStore(File(context.noBackupFilesDir, "vault"), primitives, deviceLayer) }

    /** Long-running work (imports) lives here so it survives screen rotation; locking cancels it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableSetOf<Job>()

    val importer: Importer by lazy { Importer(context, this) }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun initialState(): State = when {
        prefs.acceptedTermsVersion != TERMS_VERSION -> State.NeedsTerms
        !File(context.noBackupFilesDir, "vault/keyring.bin").isFile -> State.NeedsSetup
        else -> State.Locked
    }

    fun acceptTerms() {
        prefs.acceptedTermsVersion = TERMS_VERSION
        _state.value = if (store.exists()) State.Locked else State.NeedsSetup
    }

    // --- Creating and unlocking ---

    /** Calibrates Argon2id for this phone and creates the vault. Takes a few seconds; run off the main thread. */
    suspend fun createVault(password: CharArray, recoveryKey: SecretBytes?) = withContext(Dispatchers.Default) {
        try {
            val params = calibrate()
            val vault = store.create(password, params, recoveryKey)
            prefs.failedAttempts = 0
            touch()
            _state.value = State.Unlocked(vault)
        } finally {
            SecretBytes.wipe(password)
            recoveryKey?.close()
        }
    }

    suspend fun unlock(password: CharArray): UnlockResult = attempt { store.unlock(password) }.also { SecretBytes.wipe(password) }

    suspend fun unlockWithRecovery(recovery: SecretBytes): UnlockResult =
        attempt(mustChangePassword = true) { store.unlockWithRecoveryKey(recovery) }.also { recovery.close() }

    private suspend fun attempt(mustChangePassword: Boolean = false, open: () -> UnlockedVault): UnlockResult =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            if (prefs.lockoutUntil > now) return@withContext UnlockResult.Wait(prefs.lockoutUntil - now)
            try {
                val vault = open()
                prefs.failedAttempts = 0
                prefs.lockoutUntil = 0
                touch()
                _state.value = State.Unlocked(vault, mustChangePassword)
                UnlockResult.Success
            } catch (e: WrongCredentialsException) {
                val n = prefs.failedAttempts + 1
                prefs.failedAttempts = n
                val wait = delayAfter(n)
                prefs.lockoutUntil = if (wait > 0) System.currentTimeMillis() + wait else 0
                UnlockResult.Wrong(n, wait)
            } catch (e: DeviceKeyMissingException) {
                UnlockResult.DeviceKeyMissing
            } catch (e: Exception) {
                UnlockResult.Damaged
            }
        }

    suspend fun changePassword(newPassword: CharArray) = withContext(Dispatchers.Default) {
        val s = _state.value as? State.Unlocked ?: return@withContext
        try {
            s.vault.changePassword(newPassword, calibrate())
            _state.value = State.Unlocked(s.vault, mustChangePassword = false)
        } finally {
            SecretBytes.wipe(newPassword)
        }
    }

    fun newRecoveryKey(): SecretBytes = RecoveryKey.generate(primitives)

    private fun calibrate(): Argon2Params {
        val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(ActivityManager::class.java)).getMemoryInfo(it) }
        return Argon2Calibrator.calibrate(primitives, availableMemBytes = mem.availMem)
    }

    // --- Locking ---

    @Volatile private var lastInteraction = System.currentTimeMillis()
    @Volatile private var externalUntil = 0L

    fun touch() { lastInteraction = System.currentTimeMillis() }

    /** The system file picker is about to open: don't lock just because Bulwark leaves the screen. */
    fun beginExternalActivity() { externalUntil = System.currentTimeMillis() + 10 * 60 * 1000 }
    fun endExternalActivity() { externalUntil = 0; touch() }

    fun onAppBackgrounded() {
        if (System.currentTimeMillis() > externalUntil) lock()
    }

    fun onScreenOff() = lock()

    fun checkIdle() {
        val timeout = prefs.autoLockMinutes * 60_000L
        if (_state.value is State.Unlocked && System.currentTimeMillis() > externalUntil &&
            System.currentTimeMillis() - lastInteraction > timeout
        ) lock()
    }

    fun lock() {
        val s = _state.value
        if (s is State.Unlocked) {
            synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear() }
            s.vault.close()
            _state.value = State.Locked
        }
    }

    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    companion object {
        const val TERMS_VERSION = "1.0-draft"

        /** Spec section 5: five free tries, then 30 s, 1 min, 5 min, 15 min, then 1 hour each. */
        fun delayAfter(failures: Int): Long = when {
            failures <= 5 -> 0
            failures == 6 -> 30_000
            failures == 7 -> 60_000
            failures == 8 -> 5 * 60_000
            failures == 9 -> 15 * 60_000
            else -> 60 * 60_000
        }
    }
}
