package com.amlyric.flyme.lyric

/**
 * 逐字歌词的数据模型（外部歌词源用，与原生 ptr 无关）。
 *
 * 为什么要单独一套、而不是复用 [LyricLine]：
 * 状态栏只需要"当前整行文本"，但**外部歌词源给的是逐字时间轴**（网易云 YRC / QQ QRC），
 * 而我们把这份数据交给 Apple 官方引擎时要原样保留字级时间——
 * 官方引擎的逐字高亮（App 内歌词页）就靠它。所以这里必须把每个字的时间留下来。
 */

/** 一个字（或一个词，取决于源的粒度）及其时间 */
data class LyricWord(
    val begin: Long,
    val end: Long,
    val text: String,
) {
    val durationMs: Long get() = (end - begin).coerceAtLeast(0L)
}

/**
 * 一行歌词。
 *
 * @param begin       行起始时间（毫秒）
 * @param end         行结束时间（毫秒）；源没给就由最后一个字的结束时间兜底
 * @param words       逐字时间轴；**可以为空**（纯行级 LRC），此时用 [text] 当整行文本
 * @param translation 翻译文本（可选）
 * @param background  是否为背景和声（Apple 用 `ttm:role="x-bg"` 表示）
 */
data class TimedLine(
    val begin: Long,
    val end: Long,
    val words: List<LyricWord>,
    val translation: String? = null,
    val background: Boolean = false,
) {
    /** 整行文本（逐字拼接；没有逐字数据时由 [plainText] 提供） */
    val wordsText: String get() = words.joinToString("") { it.text }

    companion object {
        /** 造一条**只有行级时间**的歌词（无逐字） */
        fun plain(begin: Long, end: Long, text: String, translation: String? = null): TimedLine =
            TimedLine(
                begin = begin,
                end = end,
                words = listOf(LyricWord(begin, if (end > begin) end else begin, text)),
                translation = translation,
            )
    }
}

/** 一首歌的逐字歌词 */
class TimedLyrics(
    lines: List<TimedLine>,
    /** 原文档语言（写 TTML 根节点用）；未知时由 [TtmlWriter] 兜底 */
    val language: String? = null,
) {
    val lines: List<TimedLine> = lines.sortedBy { it.begin }

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 有没有真正的逐字时间轴（只要有一行是逐字就认为有） */
    val hasWordTiming: Boolean
        get() = lines.any { it.words.size > 1 }

    /** 有没有翻译 */
    val hasTranslation: Boolean
        get() = lines.any { !it.translation.isNullOrBlank() }
}
