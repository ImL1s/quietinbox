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
     * Query tokens: the subset of index tokens a query must match. Latin runs of 3+ characters use
     * only their 3-grams (so "hell" finds "hello"); shorter runs use the whole word. CJK runs use
     * bigrams, or the single character when the run is one character long.
     */
    fun queryTokens(normalized: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (run in runs(normalized)) {
            when (run.kind) {
                RunKind.CJK -> if (run.chars.size == 1) out += run.chars[0] else for (k in 0 until run.chars.size - 1) out += run.chars[k] + run.chars[k + 1]
                RunKind.LATIN -> {
                    val w = run.text
                    if (w.length >= 3) for (k in 0..w.length - 3) out += w.substring(k, k + 3) else out += w
                }
            }
        }
        return out
    }

    private enum class RunKind { CJK, LATIN }
    private class Run(val kind: RunKind, val text: String, val chars: List<String>)

    private fun runs(normalized: String): List<Run> {
        val out = ArrayList<Run>()
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
                    out += Run(RunKind.CJK, run.joinToString(""), run)
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
                    out += Run(RunKind.LATIN, sb.toString(), emptyList())
                    i = j
                }
                else -> i += len
            }
        }
        return out
    }

    /**
     * Index tokens: whole Latin/digit words plus their 3-grams; every CJK character plus CJK
     * bigrams. Deterministic and side-effect free; [queryTokens] produces a subset of these.
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
                    for (c in run) out += c
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
