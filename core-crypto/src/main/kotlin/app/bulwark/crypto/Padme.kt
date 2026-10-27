package app.bulwark.crypto

/**
 * Padmé padding (Nikitin et al., "Reducing Metadata Leakage from Encrypted Files and
 * Communication with PURBs", 2019). Rounds a length up so that stored sizes reveal only about
 * log log of the true size, with at most ~12% overhead (much less for large files).
 */
object Padme {
    fun paddedLength(length: Long): Long {
        require(length >= 0) { "Negative length" }
        if (length < 2) return length
        val e = 63 - java.lang.Long.numberOfLeadingZeros(length) // floor(log2 L)
        val s = 64 - java.lang.Long.numberOfLeadingZeros(e.toLong()) // floor(log2 E) + 1
        val lastBits = e - s
        val mask = (1L shl lastBits) - 1
        return (length + mask) and mask.inv()
    }
}
