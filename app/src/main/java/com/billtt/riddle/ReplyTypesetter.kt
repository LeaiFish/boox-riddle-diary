package com.billtt.riddle

import android.graphics.Paint

/** One animatable unit after layout: one CJK character, or one Western word. */
data class ReplyWord(val text: String, val x: Float, val y: Float, val accent: Boolean = false)

data class ReplyLayout(val words: List<ReplyWord>, val textSizePx: Float, val isCjk: Boolean)

/**
 * Lays the reply text out on the page: vertically starting around the top third,
 * each line horizontally centered, split per-character for CJK and per-word for
 * Western text, so each unit can be revealed / faded independently.
 */
object ReplyTypesetter {

    private fun containsCjk(text: String): Boolean =
        text.any { c ->
            val b = Character.UnicodeBlock.of(c)
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                b == Character.UnicodeBlock.HIRAGANA ||
                b == Character.UnicodeBlock.KATAKANA
        }

    /** Split into animation units: each CJK char is its own unit (trailing punctuation
     *  sticks to the previous char); Western text is split on spaces. */
    private fun tokenize(text: String, cjk: Boolean): List<String> {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        if (!cjk) return cleaned.split(' ').filter { it.isNotEmpty() }
        val tokens = mutableListOf<String>()
        for (c in cleaned) {
            when {
                c == ' ' -> tokens.add("")   // line-break marker, filtered out later
                tokens.isNotEmpty() && isTrailingPunct(c) ->
                    tokens[tokens.size - 1] = tokens.last() + c
                else -> tokens.add(c.toString())
            }
        }
        return tokens.filter { it.isNotEmpty() }
    }

    private fun isTrailingPunct(c: Char): Boolean =
        c in "，。！？；：、”’）》…—,.!?;:)\"'"

    /** Extract *marked* accent spans; returns (clean text, accent spans). Odd asterisk
     *  counts are treated as no markers so a stray star never leaks into the page. */
    private fun parseMarkers(raw: String): Pair<String, List<String>> {
        val spans = if (raw.count { it == '*' } >= 2)
            Regex("\\*([^*]{1,24})\\*").findAll(raw).map { it.groupValues[1] }.toList()
        else emptyList()
        return raw.replace("*", "") to spans
    }

    fun layout(text: String, pageWidth: Int, pageHeight: Int, paint: Paint): ReplyLayout {
        val (clean, spans) = parseMarkers(text)
        val cjk = containsCjk(clean)
        val accentTokens = spans.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
        val accentChars = spans.flatMap { it.toList() }.toSet()
        fun isAccent(token: String): Boolean =
            if (cjk) token.firstOrNull() in accentChars
            else token.trimEnd { isTrailingPunct(it) } in accentTokens
        // Tangerine's x-height is small; western text needs a larger base size
        var textSize = if (cjk) pageWidth / 24f else pageWidth / 18f
        val maxWidth = pageWidth * 0.78f
        val topStart = pageHeight * 0.30f
        val maxBottom = pageHeight * 0.85f

        var words: List<ReplyWord>
        while (true) {
            paint.textSize = textSize
            val toks = tokenize(clean, cjk).map { it to isAccent(it) }
            words = flow(toks, cjk, maxWidth, pageWidth, topStart, paint)
            val bottom = words.lastOrNull()?.y ?: topStart
            if (bottom <= maxBottom || textSize <= (if (cjk) pageWidth / 40f else pageWidth / 30f)) break
            textSize *= 0.88f
        }
        return ReplyLayout(words, textSize, cjk)
    }

    /** Fill line by line and horizontally center each line. */
    private data class Tok(val text: String, val width: Float, val accent: Boolean)

    private fun flow(
        tokens: List<Pair<String, Boolean>>, cjk: Boolean, maxWidth: Float,
        pageWidth: Int, topStart: Float, paint: Paint,
    ): List<ReplyWord> {
        val spaceW = if (cjk) 0f else paint.measureText(" ")
        val lineHeight = paint.textSize * 1.7f
        val lines = mutableListOf<MutableList<Tok>>()
        var line = mutableListOf<Tok>()
        var lineW = 0f
        for ((t, acc) in tokens) {
            val w = paint.measureText(t)
            val extra = if (line.isEmpty()) w else w + spaceW
            if (lineW + extra > maxWidth && line.isNotEmpty()) {
                lines.add(line); line = mutableListOf(); lineW = 0f
            }
            lineW += if (line.isEmpty()) w else w + spaceW
            line.add(Tok(t, w, acc))
        }
        if (line.isNotEmpty()) lines.add(line)

        val words = mutableListOf<ReplyWord>()
        var y = topStart
        for (l in lines) {
            val totalW = l.sumOf { it.width.toDouble() }.toFloat() + spaceW * (l.size - 1)
            var x = (pageWidth - totalW) / 2f
            for (tok in l) {
                words.add(ReplyWord(tok.text, x, y, tok.accent))
                x += tok.width + spaceW
            }
            y += lineHeight
        }
        return words
    }
}
