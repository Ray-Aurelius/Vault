package app.bulwark.vault

/**
 * The device layer: an extra lock on the keyring file that only this phone's secure hardware can
 * open (Android Keystore, StrongBox when present). A copied disk image is useless elsewhere.
 * The Android implementation lives in the app; tests use [NoDeviceLayer].
 */
interface KeyringSealer {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    /** Destroys the hardware key, which makes the vault on this phone unrecoverable at once. */
    fun destroy() {}
}

/** For tests and for backup files, which must open on any phone. */
object NoDeviceLayer : KeyringSealer {
    override fun seal(plaintext: ByteArray) = plaintext.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}
