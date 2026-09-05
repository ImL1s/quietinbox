package dev.quietinbox.core.model

import java.text.Normalizer

/**
 * Text normalisation used *only* for search. Equality/dedup never uses this: it deliberately
 * collapses whitespace, width and case differences that dedup must preserve.
 */
object SearchNormalizer {
    fun normalize(input: String): String {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var lastSpace = false
        for (ch in nfkc) {
            if (ch.isWhitespace()) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * Tokenises normalised text into index tokens: whole Latin/digit words plus their 3-grams,
     * and CJK bigrams (plus single characters for one-character runs). Deterministic and
     * side-effect free, so the same function builds the index and the query.
     */
    fun tokens(normalized: String): Set<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        val n = normalized.length
        while (i < n) {
            val cp = normalized.codePointAt(i)
            val len = Character.charCount(cp)
            when {
                isCjk(cp) -> {
                    var j = i
                    val run = ArrayList<String>()
                    while (j < n) {
                        val c = normalized.codePointAt(j)
                        if (!isCjk(c)) break
                        run += String(Character.toChars(c))
                        j += Character.charCount(c)
                    }
                    if (run.size == 1) out += run[0]
                    for (k in 0 until run.size - 1) out += run[k] + run[k + 1]
                    i = j
                }
                Character.isLetterOrDigit(cp) -> {
                    var j = i
                    val sb = StringBuilder()
                    while (j < n) {
                        val c = normalized.codePointAt(j)
                        if (!Character.isLetterOrDigit(c) || isCjk(c)) break
                        sb.appendCodePoint(c)
                        j += Character.charCount(c)
                    }
                    val word = sb.toString()
                    out += word
                    if (word.length >= 3) for (k in 0..word.length - 3) out += word.substring(k, k + 3)
                    i = j
                }
                else -> i += len
            }
        }
        return out
    }

    fun isCjk(cp: Int): Boolean {
        val block = Character.UnicodeBlock.of(cp) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.BOPOMOFO
    }
}
