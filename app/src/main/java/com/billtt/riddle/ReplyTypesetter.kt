package com.billtt.riddle

import android.graphics.Paint

/** One animatable unit after layout: one CJK character, or one Western word. */
data class ReplyWord(val text: String, val x: Float, val y: Float)

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

    fun layout(text: String, pageWidth: Int, pageHeight: Int, paint: Paint): ReplyLayout {
        val cjk = containsCjk(text)
        var textSize = pageWidth / 24f
        val maxWidth = pageWidth * 0.78f
        val topStart = pageHeight * 0.30f
        val maxBottom = pageHeight * 0.85f

        var words: List<ReplyWord>
        while (true) {
            paint.textSize = textSize
            words = flow(tokenize(text, cjk), cjk, maxWidth, pageWidth, topStart, paint)
            val bottom = words.lastOrNull()?.y ?: topStart
            if (bottom <= maxBottom || textSize <= pageWidth / 40f) break
            textSize *= 0.88f
        }
        return ReplyLayout(words, textSize, cjk)
    }

    /** Fill line by line and horizontally center each line. */
    private fun flow(
        tokens: List<String>, cjk: Boolean, maxWidth: Float,
        pageWidth: Int, topStart: Float, paint: Paint,
    ): List<ReplyWord> {
        val spaceW = if (cjk) 0f else paint.measureText(" ")
        val lineHeight = paint.textSize * 1.7f
        val lines = mutableListOf<MutableList<Pair<String, Float>>>() // (token, width)
        var line = mutableListOf<Pair<String, Float>>()
        var lineW = 0f
        for (t in tokens) {
            val w = paint.measureText(t)
            val extra = if (line.isEmpty()) w else w + spaceW
            if (lineW + extra > maxWidth && line.isNotEmpty()) {
                lines.add(line); line = mutableListOf(); lineW = 0f
            }
            lineW += if (line.isEmpty()) w else w + spaceW
            line.add(t to w)
        }
        if (line.isNotEmpty()) lines.add(line)

        val words = mutableListOf<ReplyWord>()
        var y = topStart
        for (l in lines) {
            val totalW = l.sumOf { it.second.toDouble() }.toFloat() + spaceW * (l.size - 1)
            var x = (pageWidth - totalW) / 2f
            for ((t, w) in l) {
                words.add(ReplyWord(t, x, y))
                x += w + spaceW
            }
            y += lineHeight
        }
        return words
    }
}
