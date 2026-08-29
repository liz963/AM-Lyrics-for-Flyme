package com.amlyric.flyme.lyric

/**
 * 通用 LRC 歌词解析器。
 *
 * 支持：
 *  - 标准行级时间戳：[mm:ss.xx] / [mm:ss.xxx] / [mm:ss]
 *  - 一行多时间戳：  [00:12.00][01:30.00]重叠的副歌
 *  - 增强逐字时间轴：剥离 <mm:ss.xx> 逐字标记，保留纯文本
 *  - 常见元数据标签：[ti:][ar:][al:][offset:]（offset 会参与时间修正）
 *
 * Apple Music 的原生歌词走 [com.amlyric.flyme.hook.NativeLyricsParser] 反射提取，
 * 本解析器用于：本地缓存回读、外部 LRC 兜底与调试。
 */
object LrcParser {

    private val LINE_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * @param lrcText 原始 LRC 文本
     * @return 按 begin 升序的歌词行；解析失败返回空集合（调用方按“无歌词”处理）
     */
    fun parse(lrcText: String?): LyricData {
        if (lrcText.isNullOrBlank()) return LyricData(emptyList())

        val offsetMs = lrcText.lineSequence()
            .mapNotNull { META_OFFSET.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            .firstOrNull() ?: 0L

        val result = mutableListOf<LyricLine>()
        for (raw in lrcText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val times = LINE_TIME.findAll(line).map { it.groupValues.toTimeMs() }.toList()
            if (times.isEmpty()) continue // 元数据行或无时间戳行，忽略

            // 去掉行首全部时间戳、再剥离逐字标记
            val text = line
                .replace(LINE_TIME, "")
                .replace(WORD_TIME, "")
                .trim()

            for (t in times) {
                val begin = (t + offsetMs).coerceAtLeast(0)
                result.add(LyricLine(begin = begin, end = -1L, text = text))
            }
        }

        // 按时间排序并补齐 end（下一行 begin），便于滚动切换判断
        val sorted = result.sortedBy { it.begin }
        val filled = sorted.mapIndexed { i, l ->
            val next = sorted.getOrNull(i + 1)
            val end = when {
                l.hasEnd -> l.end
                next != null && next.begin > l.begin -> next.begin
                else -> -1L
            }
            l.copy(end = end)
        }
        return LyricData(filled)
    }

    private fun List<String>.toTimeMs(): Long {
        val min = this[0].toLongOrNull() ?: 0L
        val sec = this[1].toLongOrNull() ?: 0L
        val fracRaw = getOrNull(2) ?: "0"
        // "5" -> 500ms；"50" -> 500ms；"500" -> 500ms（即按位数解释为分秒毫秒）
        val frac = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + frac
    }
}
