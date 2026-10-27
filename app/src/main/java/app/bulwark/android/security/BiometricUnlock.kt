package app.bulwark.android.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional fingerprint or face unlock (spec section 5). Off by default.
 *
 * A random 32-byte secret opens the vault's BIOMETRIC keyslot. That secret is stored only
 * encrypted, under a Keystore key that:
 *  - can be used once per successful Class 3 (strong) biometric match, never without one;
 *  - is destroyed by Android as soon as a new fingerprint or face is enrolled;
 *  - lives in StrongBox when the phone has it, and only works while the phone is unlocked.
 * Bulwark itself never sees any biometric data.
 */
class BiometricUnlock(private val context: Context, private val vaultDir: File) {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val file get() = File(vaultDir, FILE)

    enum class Availability { AVAILABLE, NOT_ENROLLED, NO_HARDWARE }

    /** Only strong (Class 3) biometrics count; weak face unlock on some phones is not accepted. */
    fun availability(): Availability {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return Availability.NO_HARDWARE
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION") bm.canAuthenticate()
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> Availability.NO_HARDWARE
        }
    }

    /** True when biometric unlock is set up on this phone (secret file and its key both present). */
    fun isSetUp(): Boolean = file.isFile && runCatching { keyStore.containsAlias(ALIAS) }.getOrDefault(false)

    /** A fresh key and cipher for setting up; the prompt must authorize it before [seal] works. */
    fun cipherForSetup(): Cipher {
        runCatching { keyStore.deleteEntry(ALIAS) }
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, generate()) }
    }

    /**
     * The cipher to unlock with, or null if Android invalidated the key (a new fingerprint or
     * face was added, or the screen lock was removed). In that case biometric unlock is switched
     * off here and the password is needed.
     */
    fun cipherForUnlock(): Cipher? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val key = runCatching { keyStore.getKey(ALIAS, null) as? SecretKey }.getOrNull() ?: run { disable(); return null }
        if (bytes.size < 2 + 12 + 16 || bytes[0].toInt() != 1) { disable(); return null }
        val ivLen = bytes[1].toInt() and 0xFF
        return try {
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 2, ivLen)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(); null
        }
    }

    /** After the prompt succeeds during setup: store [secret] encrypted by the authorized cipher. */
    fun seal(authorized: Cipher, secret: ByteArray) {
        val ct = authorized.doFinal(secret)
        val iv = authorized.iv
        val out = ByteBuffer.allocate(2 + iv.size + ct.size).put(1).put(iv.size.toByte()).put(iv).put(ct).array()
        val tmp = File(vaultDir, ".$FILE.tmp")
        tmp.outputStream().use { it.write(out); it.fd.sync() }
        if (!tmp.renameTo(file)) { tmp.delete(); error("Could not save biometric setup") }
    }

    /** After the prompt succeeds at unlock: recover the secret. The caller wipes it. */
    fun open(authorized: Cipher): ByteArray {
        val bytes = file.readBytes()
        val ivLen = bytes[1].toInt() and 0xFF
        return authorized.doFinal(bytes, 2 + ivLen, bytes.size - 2 - ivLen)
    }

    /** Removes the encrypted secret and its key. The vault's slot is removed by the session when unlocked. */
    fun disable() {
        file.delete()
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    /**
     * Shows the system biometric prompt for [cipher]. [onResult] gets the authorized cipher, or null
     * with a message when the user cancels or the sensor fails (message is null for a plain cancel).
     */
    fun prompt(
        activity: Activity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        cipher: Cipher,
        onResult: (Cipher?, String?) -> Unit,
    ) {
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)
            .setNegativeButton(cancelLabel, executor) { _, _ -> onResult(null, null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(
            BiometricPrompt.CryptoObject(cipher), CancellationSignal(), executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(result.cryptoObject?.cipher, null)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    onResult(null, if (cancelled) null else errString.toString())
                }
            },
        )
    }

    private fun generate(): SecretKey {
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        var last: Exception? = null
        for (strongBox in listOfNotNull(true.takeIf { hasStrongBox }, false)) {
            try {
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .setUnlockedDeviceRequired(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // every use needs a fresh match
                        } else {
                            @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
                        }
                        if (strongBox) setIsStrongBoxBacked(true)
                    }
                    .build()
                return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Could not create the biometric key", last)
    }

    companion object {
        private const val ALIAS = "bulwark.biometric.v1"
        private const val FILE = "biometric.bin"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
