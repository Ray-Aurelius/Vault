package app.bulwark.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoveryKeyTest {
    private fun words(hexEntropy: String) = RecoveryKey.toWords(SecretBytes.adopt(hexToBytes(hexEntropy))).joinToString(" ")

    @Test
    fun `word list is the unmodified BIP-39 English list`() {
        val joined = RecoveryKey.wordlist.joinToString("\n", postfix = "\n").toByteArray()
        assertEquals(RecoveryKey.WORDLIST_SHA256, RecoveryKey.sha256(joined).toHex())
        assertEquals(2048, RecoveryKey.wordlist.map { it.take(4) }.toSet().size, "first four letters must be unique")
    }

    // Official BIP-39 test vectors (256-bit entropy).
    @Test
    fun `matches the published BIP-39 vectors`() {
        assertEquals(List(23) { "abandon" }.joinToString(" ") + " art", words("00".repeat(32)))
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful " +
                "legal winner thank year wave sausage worth title",
            words("7f".repeat(32)),
        )
        assertEquals(
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid " +
                "letter advice cage absurd amount doctor acoustic bless",
            words("80".repeat(32)),
        )
        assertEquals(List(23) { "zoo" }.joinToString(" ") + " vote", words("ff".repeat(32)))
    }

    @Test
    fun `typed phrases are forgiving about case, numbering and four-letter prefixes`() {
        val phrase = words("7f".repeat(32)).split(" ")
        val messy = phrase.mapIndexed { i, w -> "${i + 1}. " + (if (i % 2 == 0) w.take(4) else w.uppercase()) }.joinToString("\n")
        RecoveryKey.fromWords(messy).use { assertEquals("7f".repeat(32), it.withBytes { b -> b.toHex() }) }
    }

    @Test
    fun `typos, swapped words and wrong lengths are caught`() {
        val phrase = words("80".repeat(32)).split(" ")
        val swapped = phrase.toMutableList().also { val t = it[0]; it[0] = it[1]; it[1] = t }
        assertFailsWith<InvalidRecoveryPhraseException> { RecoveryKey.fromWords(swapped.joinToString(" ")) }
        assertFailsWith<InvalidRecoveryPhraseException> { RecoveryKey.fromWords(phrase.drop(1).joinToString(" ")) }
        val typo = phrase.toMutableList().also { it[3] = "absurdd" }
        assertFailsWith<InvalidRecoveryPhraseException> { RecoveryKey.fromWords(typo.joinToString(" ")) }
    }
}

class PadmeTest {
    @Test
    fun `padding never shrinks, is stable, and stays within 12 percent`() {
        var last = 0L
        for (l in (0L..5000L) + listOf(65_536L, 1_000_003L, 123_456_789L, 10_000_000_000L)) {
            val p = Padme.paddedLength(l)
            assertTrue(p >= l)
            assertEquals(p, Padme.paddedLength(p), "idempotent at $l")
            if (l <= 5000) { assertTrue(p >= last, "monotonic at $l"); last = p }
            if (l >= 8) assertTrue((p - l).toDouble() / l <= 0.12 + 1e-9, "overhead at $l")
        }
        assertEquals(8, Padme.paddedLength(8))
        assertEquals(10, Padme.paddedLength(9))
    }
}
