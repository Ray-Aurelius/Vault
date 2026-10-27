package app.bulwark.vault

import app.bulwark.crypto.Primitives
import app.bulwark.crypto.RecoveryKey
import kotlin.math.log2

/**
 * A deliberately conservative, offline estimate of how hard a password is to guess, in bits.
 * It is a guide for the strength meter, not a guarantee; the spec requires about 60 bits.
 */
object PasswordStrength {
    const val REQUIRED_BITS = 60.0

    enum class Level { TOO_WEAK, WEAK, GOOD, STRONG }

    data class Estimate(val bits: Double, val level: Level, val hint: Hint?)

    enum class Hint { TOO_SHORT, COMMON, REPEATS, SEQUENCE, ADD_VARIETY }

    fun estimate(password: CharSequence): Estimate {
        val pw = password.toString()
        if (pw.isEmpty()) return Estimate(0.0, Level.TOO_WEAK, Hint.TOO_SHORT)
        if (pw.lowercase() in COMMON) return Estimate(0.0, Level.TOO_WEAK, Hint.COMMON)

        // Passphrases: count list words (11 bits each, as if chosen at random) plus anything else.
        val tokens = pw.lowercase().split(Regex("[\\s\\-_.,]+")).filter { it.isNotEmpty() }
        val listWords = tokens.count { it in wordSet }
        val phraseBits = if (tokens.size >= 3 && listWords >= 3) {
            listWords * 11.0 + tokens.filter { it !in wordSet }.sumOf { charBits(it) * 0.5 }
        } else 0.0

        var hint: Hint? = null
        var effective = collapseRuns(pw)
        if (effective.length < pw.length - 2) hint = Hint.REPEATS
        if (hasSequence(pw)) { hint = hint ?: Hint.SEQUENCE; effective = effective.dropLast(minOf(3, effective.length / 2)) }
        val charBits = charBits(effective)
        val bits = maxOf(charBits, phraseBits)

        if (pw.length < 10 && hint == null) hint = Hint.TOO_SHORT
        if (hint == null && bits < REQUIRED_BITS) hint = Hint.ADD_VARIETY
        val level = when {
            bits < 40 -> Level.TOO_WEAK
            bits < REQUIRED_BITS -> Level.WEAK
            bits < 80 -> Level.GOOD
            else -> Level.STRONG
        }
        return Estimate(bits, level, if (level >= Level.GOOD) null else hint)
    }

    fun meetsRequirement(password: CharSequence) = estimate(password).bits >= REQUIRED_BITS

    private fun charBits(s: String): Double {
        if (s.isEmpty()) return 0.0
        var pool = 0
        if (s.any { it in 'a'..'z' }) pool += 26
        if (s.any { it in 'A'..'Z' }) pool += 26
        if (s.any { it in '0'..'9' }) pool += 10
        if (s.any { it.code in 32..126 && !it.isLetterOrDigit() }) pool += 33
        if (s.any { it.code > 126 }) pool += 100
        return s.length * log2(pool.toDouble())
    }

    private fun collapseRuns(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (sb.length < 2 || sb[sb.length - 1] != c || sb[sb.length - 2] != c) sb.append(c)
        return sb.toString()
    }

    private fun hasSequence(s: String): Boolean {
        val l = s.lowercase()
        return SEQUENCES.any { seq -> (0..seq.length - 4).any { l.contains(seq.substring(it, it + 4)) } }
    }

    private val wordSet by lazy { RecoveryKey.wordlist.toHashSet() }

    private val SEQUENCES = listOf("abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiop", "asdfghjkl", "zxcvbnm", "9876543210")

    private val COMMON = setOf(
        "password", "password1", "password123", "123456", "12345678", "123456789", "1234567890", "qwerty", "qwerty123",
        "111111", "000000", "iloveyou", "admin", "welcome", "letmein", "monkey", "dragon", "football", "baseball",
        "sunshine", "princess", "master", "shadow", "superman", "trustno1", "abc123", "passw0rd", "starwars",
        "whatever", "freedom", "correct horse battery staple",
    )
}

/** Random passphrases from the recovery word list: 6 words is about 66 bits. */
object Passphrase {
    fun generate(primitives: Primitives, words: Int = 6): String {
        require(words in 4..12)
        val list = RecoveryKey.wordlist
        return List(words) {
            // Rejection sampling over 16-bit values keeps every word equally likely.
            var v: Int
            do {
                val b = primitives.randomBytes(2)
                v = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
            } while (v >= 65536 - 65536 % list.size)
            list[v % list.size]
        }.joinToString("-")
    }
}
