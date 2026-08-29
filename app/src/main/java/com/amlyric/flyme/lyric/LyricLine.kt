package com.amlyric.flyme.lyric

/**
 * 一行歌词。
 *
 * @param begin       行起始时间（毫秒）
 * @param end         行结束时间（毫秒），无逐行结束信息时为 -1
 * @param text        歌词文本（已去除 HTML 标签）
 * @param translation 翻译文本（已去除 HTML 标签），可为空
 */
data class LyricLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val translation: String? = null
) {
    val hasEnd: Boolean get() = end > begin
}

/**
 * 一首歌的完整歌词集合，按 begin 升序排列，供二分查找。
 */
class LyricData(lines: List<LyricLine>) {

    val lines: List<LyricLine> = lines.sortedBy { it.begin }

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 二分查找：返回最后一个 begin <= posMs 的行下标；
     * 若 posMs 早于第一行，返回 -1（前奏阶段）。
     */
    fun indexAt(posMs: Long): Int {
        if (isEmpty || posMs < lines.first().begin) return -1
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lines[mid].begin <= posMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineAt(posMs: Long): LyricLine? {
        val idx = indexAt(posMs)
        return if (idx >= 0) lines[idx] else null
    }
}
