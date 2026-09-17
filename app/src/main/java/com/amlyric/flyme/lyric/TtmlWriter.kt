package com.amlyric.flyme.lyric

/**
 * 把外部逐字歌词写成 **Apple Music 认的 TTML**。
 *
 * 这份格式不是抄来的文档，是**真机实测出来的**：`TtmlBridge.selfTest` 用本文件生成的
 * 结构去调宿主自己的 `songInfoFromTTML(String)`，拿到有效 ptr（sections=1），
 * 再用官方引擎在 1.0s / 4.0s 求值分别得到正确行文本。
 *
 * ══════════════════ 为什么长这样（每一条都有原因，改前必读）══════════════════
 *
 * | 写法 | 原因 |
 * |---|---|
 * | 根节点 `xml:lang="ko"` | Android 版 Apple Music **只在歌词是韩语时**才渲染翻译轨。写别的语言翻译不显示 |
 * | 翻译放 `<head><iTunesMetadata>`，用 `<text for="L1">` 挂到行上 | Apple 的格式：正文只放歌词，翻译/音译在头部声明一次，靠 `itunes:key` 关联 |
 * | **每一行都要有 `<text for="Ln">` 条目，顺序一致** | Android 版是**按顺序走条目**而不是按 `for` 解析——少一条，后面全部错位；有空洞整条轨会被拒 |
 * | 无翻译的行写一个空格 `" "` | 同上，占位保住顺序 |
 * | `<div>` / `<metadata>` 上**不能有 `xmlns=""`** | 会把歌词子树踢出 TTML 命名空间，Apple 一行都读不到 |
 * | 逐字用 `<p><span begin end>字</span></p>`；`itunes:timing="Word"` | 逐字高亮靠 span 的 begin/end；根节点声明 Word 才按逐字解析 |
 * | 纯行级歌词写 `itunes:timing="Line"`，`<p>` 里直接放文本 | 没有 span 就别谎报 Word，否则官方按逐字解析会得到空行 |
 * | 背景和声 `ttm:role="x-bg"` 且**不带 begin/end** | Apple 从和声自己的音节推时间范围；带了反而错 |
 * | 时间格式 `00:00:01.000` | TTML 标准 clock-time，实测被宿主解析器接受 |
 *
 * 时间轴一律**从 0 开始、单调递增**，并且每行的 `end` 都强制大于 `begin`
 * （Apple 解析器对空/逆序区间不容错）。
 */
object TtmlWriter {

    private const val ITUNES_NS = "http://music.apple.com/lyric-ttml-internal"
    private const val ROLE_BACKGROUND = "x-bg"

    /** 固定韩语：这是 Apple 在 Android 上渲染翻译轨的开关（见类注释） */
    private const val ROOT_LANGUAGE = "ko"
    /** 翻译轨声明的语言 */
    private const val TRANSLATION_LANGUAGE = "zh-Hans"
    /** Apple 用来标记"这是要显示在歌词下方的翻译" */
    private const val TRANSLATION_TYPE = "subtitle"

    /** 无翻译行的占位（保住条目顺序） */
    private const val ABSENT_TEXT = " "

    /** 行区间兜底时长：源没给 end 时用 begin + 这个值 */
    private const val FALLBACK_LINE_MS = 4000L

    /**
     * @param lyrics 逐字歌词（按时间升序不必预先排好，这里会排）
     * @return 可直接交给 `TtmlBridge.parse` 的 TTML；[lyrics] 为空时返回空串
     */
    fun write(lyrics: TimedLyrics): String {
        val lines = lyrics.lines.filter { it.wordsText.isNotBlank() }
        if (lines.isEmpty()) return ""
        val wordTimed = lyrics.hasWordTiming || lines.any { it.words.size > 1 }

        return buildString(estimateSize(lines)) {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\"")
            append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
            append(" xmlns:itunes=\"$ITUNES_NS\"")
            append(" xml:lang=\"$ROOT_LANGUAGE\"")
            append(" itunes:timing=\"${if (wordTimed) "Word" else "Line"}\">")
            appendHead(lines)
            appendBody(lines, wordTimed)
            append("</tt>")
        }
    }

    // ─────────────────────────── 头部：翻译轨 ───────────────────────────

    private fun StringBuilder.appendHead(lines: List<TimedLine>) {
        if (lines.none { !it.translation.isNullOrBlank() }) return
        append("<head><iTunesMetadata xmlns=\"$ITUNES_NS\">")
        append("<translations><translation type=\"$TRANSLATION_TYPE\"")
        append(" xml:lang=\"$TRANSLATION_LANGUAGE\">")
        lines.forEachIndexed { index, line ->
            val text = line.translation?.takeIf { it.isNotBlank() } ?: ABSENT_TEXT
            append("<text for=\"${key(index)}\">").append(escape(text)).append("</text>")
        }
        append("</translation></translations></iTunesMetadata></head>")
    }

    // ─────────────────────────── 正文 ───────────────────────────

    private fun StringBuilder.appendBody(lines: List<TimedLine>, wordTimed: Boolean) {
        append("<body><div>")
        lines.forEachIndexed { index, line ->
            val begin = line.begin.coerceAtLeast(0L)
            val end = lineEnd(lines, index)
            append("<p itunes:key=\"").append(key(index)).append('"')
            append(" begin=\"").append(time(begin)).append('"')
            append(" end=\"").append(time(end)).append('"')
            append(" ttm:agent=\"v1\">")
            if (wordTimed) {
                appendWords(line, begin, end)
            } else {
                append(escape(line.wordsText))
            }
            append("</p>")
        }
        append("</div></body>")
    }

    /**
     * 逐字 span。要点：
     *  · 背景和声不带 begin/end（Apple 自己推）；
     *  · 每个 span 的区间都必须非空、单调不减，且夹在本行的 [begin, end] 之内。
     */
    private fun StringBuilder.appendWords(line: TimedLine, lineBegin: Long, lineEnd: Long) {
        if (line.background) {
            append("<span ttm:role=\"$ROLE_BACKGROUND\">")
            if (line.words.isEmpty()) append(escape(line.wordsText))
            else line.words.forEach { append(escape(it.text)) }
            append("</span>")
            return
        }
        val words = normalizeWords(line, lineBegin, lineEnd)
        if (words.isEmpty()) {
            append(escape(line.wordsText))
            return
        }
        for (word in words) {
            append("<span begin=\"").append(time(word.begin)).append("\" end=\"")
            append(time(word.end)).append("\">")
            append(escape(word.text)).append("</span>")
        }
    }

    /**
     * 让逐字区间满足「非空、单调不减、落在行内」。
     *
     * 为什么要**按比例缩放**而不是简单地夹紧——QQ 的 QRC 里有这么一类行：
     * `[0,400]残(0,14)酷(14,15)…`，声明时长 400ms 却塞了 60 多个字，逐字时间一路涨到 900ms+。
     * 直接夹到最后会变成几十个 1ms 的 span 堆在行尾（高亮动画会"卡住"），
     * 而放任不管又会越过下一行、和 Apple 的行区间叠加。
     * 按比例压缩进本行区间最稳：动画还在，也不越界。
     * 若连"每字 1ms"都放不下，则把行尾顺延（[lineEnd] 已经保证够用），宁可这行长一点也不丢字。
     */
    private fun normalizeWords(line: TimedLine, lineBegin: Long, lineEnd: Long): List<LyricWord> {
        val raw = line.words.filter { it.text.isNotEmpty() }
        if (raw.isEmpty()) return emptyList()

        val end = maxOf(lineEnd, lineBegin + raw.size)
        val span = end - lineBegin
        val first = raw.first().begin
        val last = raw.last().end
        // 源时间轴本身没有跨度（所有字同一时刻）时退化成"平均分配"
        val scale = if (last > first) span.toDouble() / (last - first) else 0.0

        var cursor = lineBegin
        val out = ArrayList<LyricWord>(raw.size)
        for ((index, word) in raw.withIndex()) {
            val begin = if (scale > 0) {
                lineBegin + ((word.begin - first) * scale).toLong()
            } else {
                lineBegin + span * index / raw.size
            }
            val endGuess = if (scale > 0) {
                lineBegin + ((word.end - first) * scale).toLong()
            } else {
                lineBegin + span * (index + 1) / raw.size
            }
            // 注意顺序：先抬到 cursor、再压到 end-1，这样 cursor 已经到 end 时也不会抛
            // （coerceIn(min,max) 在 min>max 时会抛 IllegalArgumentException）
            val safeBegin = begin.coerceAtLeast(cursor).coerceAtMost(end - 1)
            val safeEnd = endGuess.coerceAtLeast(safeBegin + 1).coerceAtMost(end)
            out += LyricWord(safeBegin, safeEnd, word.text)
            cursor = safeEnd
        }
        return out
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    /**
     * 行结束时间。
     *
     * 优先级（借自 getl 的 TTML 输出，比"begin + 兜底时长"稳）：
     *  ① 源声明的行时长；
     *  ② 下一行的开始时间（**关键**：用它才能保证行与行不重叠，
     *     Apple 的解析器对叠加区间不友好）；
     *  ③ 末字结束时间；
     *  ④ `begin + FALLBACK_LINE_MS`。
     */
    private fun lineEnd(lines: List<TimedLine>, index: Int): Long {
        val line = lines[index]
        val begin = line.begin.coerceAtLeast(0L)
        val lastWordEnd = line.words.lastOrNull()?.end ?: 0L
        val nextBegin = lines.getOrNull(index + 1)?.begin?.takeIf { it > begin }
        val candidate = listOfNotNull(
            line.end.takeIf { it > begin },
            nextBegin,
            lastWordEnd.takeIf { it > begin },
            begin + FALLBACK_LINE_MS,
        ).min()
        return candidate.coerceAtLeast(begin + 1)
    }

    private fun key(index: Int) = "L${index + 1}"

    /** 毫秒 → TTML clock-time `hh:mm:ss.mmm` */
    private fun time(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return buildString(12) {
            append(hours.toString().padStart(2, '0')).append(':')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0')).append('.')
            append(millis.toString().padStart(3, '0'))
        }
    }

    private fun escape(raw: String): String {
        if (raw.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return raw
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun estimateSize(lines: List<TimedLine>): Int =
        lines.sumOf { it.wordsText.length * 3 + it.words.size * 60 + 200 }
}
