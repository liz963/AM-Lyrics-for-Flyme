package com.amlyric.flyme.lyric

/**
 * 两个歌词源共用的**候选匹配**逻辑。
 *
 * 为什么要专门做匹配——Apple Music 给的歌名/歌手和两家曲库的对不上号：
 *
 * | Apple Music | QQ 音乐 | 网易云 |
 * |---|---|---|
 * | `年轮` | `年轮` | `年轮` |
 * | `残酷な天使のテーゼ` | `残酷な天使のテーゼ (残酷天使的行动纲领)` | `残酷な天使のテーゼ` |
 * | `オリオン (PSYQUI Remix)` | `オリオン (PSYQUI Remix)` | `オリオン` |
 *
 * 所以搜索完**必须自己挑**：拿标题归一化后比对，并给"多出来的后缀"
 * （Live / Cover / Remix / 伴奏 / DJ 版…）扣分——这些版本时间轴完全不同，
 * 配错会让歌词整场错位。
 */
internal data class LyricCandidate(
    val id: Long,
    /** QQ 的 songMID；网易云用不到 */
    val mid: String?,
    val title: String,
    val artist: String,
    val album: String,
    /** 时长（毫秒），0 表示未知 */
    val durationMs: Long,
)

internal object LyricMatch {

    /**
     * 版本标记：查询里没有、候选里却有 → 扣分。
     *
     * 这些词决定了时间轴，必须区分开：同一首歌的 Live 版和录音室版
     * 歌词文本一样但每句时间都不同，配错就是"永远慢半拍"。
     */
    private val VERSION_MARKERS = listOf(
        "live", "cover", "remix", "instrumental", "伴奏", "纯音乐", "dj", "版",
        "演唱会", "现场", "翻唱", "remaster", "acoustic", "demo", "edit",
    )

    private val BRACKETS = Regex("""[\(（\[【].*?[\)）\]】]""")

    /** 标题归一化：小写、去括号内容、去标点与空白 */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(BRACKETS, " ")
            .filter { it.isLetterOrDigit() || it.code > 0x2E80 }
            .replace(" ", "")

    /**
     * 从候选里挑最像的一个。
     *
     * @return 最佳候选；[candidates] 为空时返回 null
     */
    fun pickBest(
        candidates: List<LyricCandidate>,
        title: String,
        artist: String,
        durationMs: Long = 0L,
    ): LyricCandidate? {
        if (candidates.isEmpty()) return null
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        val wantMarkers = markersOf(title)

        var best: LyricCandidate? = null
        var bestScore = Int.MIN_VALUE

        for (candidate in candidates) {
            val haveTitle = normalize(candidate.title)
            var score = 0

            score += when {
                haveTitle == wantTitle -> 100
                wantTitle.isNotEmpty() && haveTitle.startsWith(wantTitle) -> 70
                wantTitle.isNotEmpty() && haveTitle.contains(wantTitle) -> 50
                wantTitle.isNotEmpty() && wantTitle.contains(haveTitle) -> 30
                else -> -50
            }

            if (wantArtist.isNotEmpty()) {
                val haveArtist = normalize(candidate.artist)
                if (haveArtist.contains(wantArtist) || wantArtist.contains(haveArtist)) score += 40
                else {
                    // 歌手完全对不上：可能是同曲翻唱，扣但不排除（原唱常有多人合唱版本）
                    val overlap = wantArtist.take(6).let { haveArtist.contains(it) }
                    score += if (overlap) 15 else -25
                }
            }

            // 版本标记只惩罚"查询里没有、候选里多出来"的那种
            val extra = markersOf(candidate.title) - wantMarkers
            score -= extra.size * 45

            if (durationMs > 0 && candidate.durationMs > 0) {
                val delta = kotlin.math.abs(candidate.durationMs - durationMs)
                score += when {
                    delta <= 3_000 -> 30
                    delta <= 8_000 -> 10
                    delta <= 20_000 -> -10
                    else -> -60
                }
            }

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun markersOf(title: String): Set<String> {
        val lower = title.lowercase()
        return VERSION_MARKERS.filterTo(mutableSetOf()) { lower.contains(it) }
    }
}
