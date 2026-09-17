package com.amlyric.flyme.lyric

/**
 * **行级 LRC 轨**的处理：解析 + 把翻译轨贴合到主轨上。
 *
 * 为什么需要它——两家的翻译轨**都不是逐字的**：
 *
 * | 来源 | 翻译字段 | 实际格式 |
 * |---|---|---|
 * | QQ 音乐 | `GetPlayLyricInfo` 的 `trans`（QRC 解密后） | **纯 LRC**：`[ti:]…` 元信息 + `[mm:ss.xx]译文` |
 * | 网易云 | `tlyric.lyric` | 纯 LRC：`[by:…]` + `[mm:ss.xxx]译文` |
 *
 * 而主轨是逐字的，两侧行边界**对不上**（实测 QQ《残酷な天使のテーゼ》：
 * 主轨 QRC 只有 16 行正文，翻译轨 73 行）。所以不能按行号硬配，
 * 必须**按时间归属**：每条译文落到"开始时间不晚于它的最后一行主轨"上，
 * 同一行落进多条就用空格连起来。
 *
 * 这样得到的行数**恰好等于主轨行数**，正好满足 Apple 的要求
 * （`<text>` 条目必须与歌词行一一对应、顺序一致，见 [TtmlWriter] 类注释）。
 */
internal object LrcTrack {

    /** `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]` */
    private val LINE_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** `[ti:…]` `[by:…]` `[offset:0]` `[kana:…]` 这类元信息行 */
    private val META_LINE = Regex("""^\[[A-Za-z]+:""")

    /**
     * 解析行级 LRC 成 [TimedLine]。
     *
     * 每行只造一个"整行字"，所以 [TimedLyrics.hasWordTiming] 为 false，
     * [TtmlWriter] 会据此写 `itunes:timing="Line"`——这正是纯行级歌词该有的形态
     * （谎报 Word 会让官方解析器按逐字解析，得到空行）。
     *
     * @return 按时间升序的行；没有有效行时返回空表
     */
    fun parse(text: String?): List<TimedLine> {
        if (text.isNullOrBlank()) return emptyList()
        val raw = ArrayList<Pair<Long, String>>(64)

        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (META_LINE.containsMatchIn(trimmed)) continue

            val times = LINE_TIME.findAll(trimmed).mapNotNull { it.toMillis() }.toList()
            if (times.isEmpty()) continue

            val content = LINE_TIME.replace(trimmed, "").trim()
            for (t in times) raw += t to content
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortBy { it.first }

        // 行尾用下一行的起点兜底；最后一行没有下一行，就交给 TtmlWriter 的兜底时长
        return raw.mapIndexed { index, (begin, content) ->
            val next = raw.getOrNull(index + 1)?.first
            val end = if (next != null && next > begin) next else begin
            TimedLine.plain(begin = begin, end = end, text = content)
        }
    }

    /**
     * 把翻译轨贴到主轨上。
     *
     * @param lines       主轨（必须已按 begin 升序）
     * @param translation 翻译轨原文（LRC）；空/解析不出内容时原样返回 [lines]
     * @return 行数与 [lines] 相同的新列表，只有 [TimedLine.translation] 不同
     */
    fun mergeTranslation(lines: List<TimedLine>, translation: String?): List<TimedLine> {
        if (lines.isEmpty() || translation.isNullOrBlank()) return lines
        val entries = parseEntries(translation)
        if (entries.isEmpty()) return lines

        val buckets = arrayOfNulls<StringBuilder>(lines.size)
        for ((time, text) in entries) {
            val index = lineIndexOf(lines, time)
            if (index < 0) continue
            val sb = buckets[index] ?: StringBuilder().also { buckets[index] = it }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(text)
        }

        var changed = false
        val merged = lines.mapIndexed { index, line ->
            val translationText = buckets[index]?.toString()?.takeIf { it.isNotBlank() }
            if (translationText == null) line else {
                changed = true
                line.copy(translation = translationText)
            }
        }
        return if (changed) merged else lines
    }

    /** 解析出 (时间, 文本) 条目；占位符（`//`、`-`、空串）丢掉 */
    private fun parseEntries(text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>(64)
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || META_LINE.containsMatchIn(trimmed)) continue
            val time = LINE_TIME.find(trimmed)?.toMillis() ?: continue
            val content = LINE_TIME.replace(trimmed, "").trim()
            if (!isRealText(content)) continue
            out += time to content
        }
        out.sortBy { it.first }
        return out
    }

    /** `//` 之类的占位（QQ 的翻译轨里大量存在，当成"没有翻译"） */
    private fun isRealText(text: String): Boolean {
        if (text.isEmpty()) return false
        val stripped = text.filterNot { it == '/' || it == '-' || it == '　' }
        return stripped.isNotBlank()
    }

    /** 最后一个 `begin <= time` 的行下标；早于所有行时返回 -1 */
    private fun lineIndexOf(lines: List<TimedLine>, time: Long): Int {
        if (time < lines.first().begin) return -1
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lines[mid].begin <= time) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun MatchResult.toMillis(): Long? {
        val min = groupValues[1].toLongOrNull() ?: return null
        val sec = groupValues[2].toLongOrNull() ?: return null
        val frac = when (val raw = groupValues.getOrNull(3) ?: "") {
            "" -> 0L
            else -> when (raw.length) {
                1 -> raw.toLong() * 100
                2 -> raw.toLong() * 10
                else -> raw.take(3).toLong()
            }
        }
        return min * 60_000 + sec * 1000 + frac
    }
}
