package app.bulwark.android.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.bulwark.crypto.CorruptDataException
import app.bulwark.vault.KeyringSealer
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The device layer: the keyring file is sealed with an AES-256-GCM key that lives inside this
 * phone's secure hardware (StrongBox chip when the phone has one, otherwise the TEE) and can only
 * be used while the phone is unlocked. Copying the vault's files to another device gets nothing.
 *
 * Sealed format: "BWDL" | version u8 = 1 | ivLength u8 | iv | ciphertext+tag
 */
class DeviceLayer(private val context: Context) : KeyringSealer {

    enum class Protection { STRONGBOX, SECURE_HARDWARE, SOFTWARE, UNKNOWN }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun seal(plaintext: ByteArray): ByteArray {
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        val cipher = if (existing != null) {
            // Never replace an existing key: the current keyring is sealed with it, and deleting it
            // would make the vault unrecoverable. If it can't be used right now, fail and let the
            // caller retry (the keyring on disk is untouched).
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, existing) }
        } else {
            val fresh = generate(allowUnlockedRequirement = true)
            try {
                Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, fresh) }
            } catch (e: Exception) {
                // A brand-new key nothing depends on yet. Some phones refuse "unlocked device
                // required" keys without a screen lock: replace it with one that doesn't need it.
                keyStore.deleteEntry(ALIAS)
                Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, generate(allowUnlockedRequirement = false)) }
            }
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(6 + iv.size + ct.size).put(MAGIC).put(1).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 6 + 12 + 16 || !sealed.copyOfRange(0, 4).contentEquals(MAGIC) || sealed[4].toInt() != 1) {
            throw CorruptDataException("Keyring seal is damaged")
        }
        val ivLen = sealed[5].toInt() and 0xFF
        if (ivLen !in 12..16 || sealed.size < 6 + ivLen + 16) throw CorruptDataException("Keyring seal is damaged")
        val key = keyStore.getKey(ALIAS, null) as? SecretKey
            ?: throw DeviceKeyMissingException()
        return try {
            Cipher.getInstance(TRANSFORM).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 6, ivLen))
                doFinal(sealed, 6 + ivLen, sealed.size - 6 - ivLen)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CorruptDataException("Keyring seal failed authentication", e)
        }
    }

    override fun destroy() {
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    /** Where the device key actually lives, shown in Settings. */
    fun protection(): Protection = runCatching {
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: return Protection.UNKNOWN
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Protection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Protection.SECURE_HARDWARE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Protection.SOFTWARE
                else -> Protection.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Protection.SECURE_HARDWARE else Protection.SOFTWARE
        }
    }.getOrDefault(Protection.UNKNOWN)

    private fun generate(allowUnlockedRequirement: Boolean): SecretKey {
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val attempts = buildList {
            if (hasStrongBox && allowUnlockedRequirement) add(true to true)
            if (allowUnlockedRequirement) add(false to true)
            if (hasStrongBox) add(true to false)
            add(false to false)
        }
        var last: Exception? = null
        for ((strongBox, unlockedRequired) in attempts) {
            try {
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .apply {
                        if (strongBox) setIsStrongBoxBacked(true)
                        if (unlockedRequired) setUnlockedDeviceRequired(true)
                    }
                    .build()
                return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run { init(spec); generateKey() }
            } catch (e: StrongBoxUnavailableException) {
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Could not create a device key", last)
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "bulwark.device.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte())
    }
}

/** The phone's device key is gone (app data restored onto another phone, or the key was wiped). */
class DeviceKeyMissingException : IllegalStateException("This vault's device key is missing on this phone")
