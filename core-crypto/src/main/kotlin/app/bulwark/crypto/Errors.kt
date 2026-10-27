package app.bulwark.crypto

/**
 * Every failure the crypto core reports. Callers never see a partial result: if one of these is
 * thrown, no plaintext from the failing operation has been released.
 */
sealed class BulwarkCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The password, PIN or recovery key did not open this keyslot. */
class WrongCredentialsException : BulwarkCryptoException("These credentials do not unlock this vault")

/** Data failed authentication or is structurally invalid: corruption or tampering. */
class CorruptDataException(reason: String, cause: Throwable? = null) : BulwarkCryptoException(reason, cause)

/** Data ended early: a file or backup was cut off. */
class TruncatedDataException(reason: String) : BulwarkCryptoException(reason)

/** A format version or cipher suite this build does not understand. */
class UnsupportedFormatException(reason: String) : BulwarkCryptoException(reason)

/** An invalid recovery phrase: unknown word, wrong length or failed checksum. */
class InvalidRecoveryPhraseException(reason: String) : BulwarkCryptoException(reason)
