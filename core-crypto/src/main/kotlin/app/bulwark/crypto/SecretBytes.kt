package app.bulwark.crypto

import java.io.Closeable

/**
 * A key or other secret held in memory. Call [close] (or use [use]) as soon as it is no longer
 * needed: the bytes are overwritten with zeros.
 *
 * The JVM can copy arrays during garbage collection, so zeroing is best effort; it still shortens
 * the time secrets sit in memory, which is the goal.
 */
class SecretBytes private constructor(private var bytes: ByteArray?) : Closeable {

    val size: Int get() = checkOpen().size

    /** Runs [block] with the raw bytes. Do not keep a reference to the array after [block] returns. */
    fun <T> withBytes(block: (ByteArray) -> T): T = block(checkOpen())

    fun copy(): SecretBytes = SecretBytes(checkOpen().copyOf())

    val isClosed: Boolean get() = bytes == null

    override fun close() {
        bytes?.let { wipe(it) }
        bytes = null
    }

    private fun checkOpen(): ByteArray = bytes ?: throw IllegalStateException("Secret already wiped")

    override fun toString(): String = "SecretBytes(${bytes?.size ?: "wiped"})"

    companion object {
        /** Takes ownership of [bytes]; the caller must not use or wipe the array afterwards. */
        fun adopt(bytes: ByteArray): SecretBytes = SecretBytes(bytes)

        fun wipe(bytes: ByteArray) = bytes.fill(0)

        fun wipe(chars: CharArray) = chars.fill('\u0000')
    }
}
