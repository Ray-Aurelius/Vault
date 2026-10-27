package app.bulwark.android.session

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.bulwark.android.security.BiometricUnlock
import app.bulwark.android.security.DeviceKeyMissingException
import app.bulwark.android.security.DeviceLayer
import app.bulwark.crypto.Argon2Calibrator
import app.bulwark.crypto.Argon2Params
import app.bulwark.crypto.Keyslot
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
import kotlin.math.abs

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
        data class Wrong(val attempts: Int, val waitMillis: Long, val attemptsBeforeErase: Int?) : UnlockResult
        data class Wait(val millis: Long) : UnlockResult
        data object Damaged : UnlockResult
        data object DeviceKeyMissing : UnlockResult
        data object Erased : UnlockResult
    }

    val prefs = Prefs(context)
    val primitives: Primitives by lazy(primitivesProvider)
    val deviceLayer = DeviceLayer(context)
    private val vaultDir = File(context.noBackupFilesDir, "vault")
    val store: VaultStore by lazy {
        VaultStore(vaultDir, primitives, deviceLayer, trashRetentionMillis = { prefs.trashRetentionDays * DAY })
    }
    val biometrics = BiometricUnlock(context, vaultDir)

    /** Long-running work (imports) lives here so it survives screen rotation; locking cancels it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableSetOf<Job>()

    val importer: Importer by lazy { Importer(context, this) }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether windows must block screenshots, recording and the recents preview. */
    private val _secureScreens = MutableStateFlow(!prefs.allowScreenshots)
    val secureScreens: StateFlow<Boolean> = _secureScreens.asStateFlow()

    /** Wrong passwords since the last unlock, shown once after unlocking (when that notice is on). */
    private val _failedNotice = MutableStateFlow(0)
    val failedNotice: StateFlow<Int> = _failedNotice.asStateFlow()
    fun dismissFailedNotice() { _failedNotice.value = 0 }

    private fun initialState(): State = when {
        prefs.acceptedTermsVersion != TERMS_VERSION -> State.NeedsTerms
        !File(vaultDir, "keyring.bin").isFile -> State.NeedsSetup
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
            recordPasswordUse()
            touch()
            _state.value = State.Unlocked(vault)
        } finally {
            SecretBytes.wipe(password)
            recoveryKey?.close()
        }
    }

    suspend fun unlock(password: CharArray): UnlockResult =
        attempt(countsAsPassword = true) { store.unlock(password) }.also { SecretBytes.wipe(password) }

    suspend fun unlockWithRecovery(recovery: SecretBytes): UnlockResult =
        attempt(mustChangePassword = true) { store.unlockWithRecoveryKey(recovery) }.also { recovery.close() }

    suspend fun unlockWithBiometric(secret: SecretBytes): UnlockResult =
        attempt { store.unlockWithSecret(Keyslot.Type.BIOMETRIC, secret) }.also { secret.close() }

    private suspend fun attempt(
        mustChangePassword: Boolean = false,
        countsAsPassword: Boolean = false,
        open: () -> UnlockedVault,
    ): UnlockResult = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        if (prefs.lockoutUntil > now) return@withContext UnlockResult.Wait(prefs.lockoutUntil - now)
        try {
            val vault = open()
            val failedBefore = prefs.failedAttempts
            prefs.failedAttempts = 0
            prefs.lockoutUntil = 0
            if (countsAsPassword) recordPasswordUse()
            if (prefs.notifyFailedAttempts && failedBefore > 0) _failedNotice.value = failedBefore
            // Biometric unlock was switched off (or invalidated by Android) while locked: drop its slot.
            if (!biometrics.isSetUp() && vault.hasSlot(Keyslot.Type.BIOMETRIC)) {
                runCatching { vault.setSecretSlot(Keyslot.Type.BIOMETRIC, null) }
                prefs.biometricEnabled = false
            }
            touch()
            _state.value = State.Unlocked(vault, mustChangePassword)
            UnlockResult.Success
        } catch (e: WrongCredentialsException) {
            val n = prefs.failedAttempts + 1
            prefs.failedAttempts = n
            val eraseAt = prefs.eraseAfterFailures
            if (eraseAt > 0 && n >= eraseAt) {
                eraseVault()
                return@withContext UnlockResult.Erased
            }
            val wait = delayAfter(n)
            prefs.lockoutUntil = if (wait > 0) System.currentTimeMillis() + wait else 0
            UnlockResult.Wrong(n, wait, if (eraseAt > 0) eraseAt - n else null)
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
            recordPasswordUse()
            _state.value = State.Unlocked(s.vault, mustChangePassword = false)
        } finally {
            SecretBytes.wipe(newPassword)
        }
    }

    /** Checks the password (about a second, by design). Used before sensitive settings changes. */
    suspend fun verifyPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val s = _state.value as? State.Unlocked ?: return@withContext false
        try { s.vault.verifyPassword(password) } finally { SecretBytes.wipe(password) }
    }

    fun newRecoveryKey(): SecretBytes = RecoveryKey.generate(primitives)

    private fun calibrate(): Argon2Params {
        val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(ActivityManager::class.java)).getMemoryInfo(it) }
        return Argon2Calibrator.calibrate(primitives, availableMemBytes = mem.availMem)
    }

    // --- Biometric unlock ---

    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun recordPasswordUse() {
        prefs.lastPasswordUnlockAt = System.currentTimeMillis()
        prefs.lastPasswordBootTime = bootTime()
    }

    /** Spec: biometrics only after a password unlock in this boot, and within the chosen window. */
    fun biometricAllowedNow(): Boolean {
        if (!prefs.biometricEnabled || !biometrics.isSetUp()) return false
        if (biometrics.availability() != BiometricUnlock.Availability.AVAILABLE) return false
        val restarted = abs(bootTime() - prefs.lastPasswordBootTime) > 60_000
        val expired = System.currentTimeMillis() - prefs.lastPasswordUnlockAt > prefs.passwordEveryHours * HOUR
        return !restarted && !expired
    }

    /** Finishes enabling biometric unlock after the prompt authorized [cipher]. */
    fun completeBiometricSetup(cipher: javax.crypto.Cipher) {
        val s = _state.value as? State.Unlocked ?: return
        val secret = primitives.randomBytes(32)
        try {
            biometrics.seal(cipher, secret)
            s.vault.setSecretSlot(Keyslot.Type.BIOMETRIC, SecretBytes.adopt(secret.copyOf()))
            prefs.biometricEnabled = true
        } catch (e: Exception) {
            biometrics.disable()
            throw e
        } finally {
            SecretBytes.wipe(secret)
        }
    }

    fun disableBiometric() {
        (_state.value as? State.Unlocked)?.vault?.let { runCatching { it.setSecretSlot(Keyslot.Type.BIOMETRIC, null) } }
        biometrics.disable()
        prefs.biometricEnabled = false
    }

    // --- Settings that take effect immediately ---

    fun setAllowScreenshots(allow: Boolean) {
        prefs.allowScreenshots = allow
        _secureScreens.value = !allow
    }

    /** Crypto-erase: destroys the device and biometric keys, deletes every vault file, resets settings. */
    fun eraseVault() {
        lock()
        biometrics.disable()
        store.eraseEverything()
        prefs.resetAfterErase()
        _secureScreens.value = true
        _state.value = State.NeedsSetup
    }

    // --- Locking ---

    @Volatile private var lastInteraction = System.currentTimeMillis()
    @Volatile private var externalUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val graceLock = Runnable { lock() }

    fun touch() { lastInteraction = System.currentTimeMillis() }

    /** A system screen (file picker, biometric prompt) is about to open: don't lock just because Bulwark leaves the screen. */
    fun beginExternalActivity() { externalUntil = System.currentTimeMillis() + 10 * 60 * 1000 }
    fun endExternalActivity() { externalUntil = 0; touch() }

    fun onAppBackgrounded() {
        if (System.currentTimeMillis() <= externalUntil) return
        val grace = prefs.leaveGraceSeconds
        if (grace <= 0) lock() else main.postDelayed(graceLock, grace * 1000L)
    }

    fun onAppForegrounded() {
        main.removeCallbacks(graceLock)
        touch()
    }

    fun onScreenOff() {
        if (prefs.lockOnScreenOff) lock()
    }

    fun checkIdle() {
        val timeout = prefs.autoLockMinutes * 60_000L
        if (_state.value is State.Unlocked && System.currentTimeMillis() > externalUntil &&
            System.currentTimeMillis() - lastInteraction > timeout
        ) lock()
    }

    fun lock() {
        main.removeCallbacks(graceLock)
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
        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR

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
