package com.amlyric.flyme.lyric

import com.amlyric.flyme.LyricSource
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.TtmlBridge

/**
 * 在线歌词的**统一入口**：按用户选的源取一份歌词，并转成 Apple 能吃的 TTML。
 *
 * 调用链：
 * ```
 * LyricFetcher.fetch()  →  QQMusicClient / NeteaseClient
 *                       →  TimedLyrics（逐字 + 翻译）
 * LyricFetcher.ttml()   →  TtmlWriter.write() → Apple 格式 TTML
 *                       →  TtmlBridge.parse()  → SongInfo$SongInfoPtr
 * ```
 *
 * ══════════════ 调用时机（用户明确要求，改前必读）══════════════
 *
 * **只有「自动实时补全」打开时才可以走到这里。**
 * 关掉时播放过程中不搜索、不取词、不发任何请求。
 * 所以这里的每个入口都先自查开关，不做"反正调用方会判断"的假设——
 * 未来多一个调用方（快捷方式、诊断按钮）也不会把请求漏出去。
 * 唯一例外是 [selfCheck]，它是显式的自检入口，不参与播放逻辑。
 */
object LyricFetcher {

    /**
     * 自检开关：**仅供开发期验证整条链路**，发版前必须改回 false。
     *
     * 打开时会在模块加载后主动跑一次"搜索 → 取词 → 解密 → 解析 → 写 TTML"，
     * 把结果打进 logcat，用来确认两家源的接口都还活着。
     * 它不做任何与播放相关的注入，但**会发网络请求**，所以不能留在发布版里。
     */
    private const val SELF_CHECK = false

    /**
     * 过滤前的**原文转储**开关（默认关）。
     *
     * [CreditLine] 的规则是照着 QQ / 网易云的真实返回形态调出来的，
     * 出现漏网行时唯一靠谱的排查手段就是看原文：
     * 打开后会逐条打出过滤前的头 6 行，以及本次被删掉的行原文。
     * 只在开发期临时打开，**发版保持 false**（否则日志会多出一堆歌词正文）。
     */
    private const val DUMP_LINES = false

    /**
     * 自检用的探针歌。刻意选两首，覆盖两种形态：
     *  ① 只有逐字、没有翻译（《年轮》在两家都是这种）；
     *  ② 逐字 + 翻译轨都有（日文歌，两家的翻译轨都齐）。
     * 只测一首会漏掉「翻译合并」那条分支。
     */
    private val PROBES = listOf("年轮" to "张碧晨", "残酷な天使のテーゼ" to "高橋洋子")

    /** 缓存条数；用户来回切歌时省掉重复请求 */
    private const val CACHE_MAX = 4

    /** LRU：accessOrder=true 时 get 会把条目移到队尾 */
    private val cache = object : LinkedHashMap<String, TimedLyrics>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedLyrics>) =
            size > CACHE_MAX
    }

    /**
     * 取一首歌的逐字歌词。
     *
     * @return 取不到返回 null；**不会抛异常**（网络/解析问题一律降级为 null）
     */
    fun fetch(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
    ): TimedLyrics? {
        if (!Settings.autoComplete) return null
        if (title.isBlank()) return null

        val key = "${Settings.lyricSource.name}|$title|$artist|${durationMs / 1000}"
        synchronized(cache) { cache[key] }?.let { return it }

        val source = Settings.lyricSource
        val started = System.currentTimeMillis()
        val raw = runCatching {
            when (source) {
                LyricSource.QQ -> QQMusicClient.fetch(title, artist, album, durationMs)
                LyricSource.NETEASE -> NeteaseClient.fetch(title, artist, album, durationMs)
            }
        }.onFailure { XLog.w("lyric fetch failed (${source.name}): ${it.message}") }.getOrNull()

        val cost = System.currentTimeMillis() - started
        if (raw == null) {
            XLog.w("lyric fetch: ${source.name} miss for '$title - $artist' (${cost}ms)")
            return null
        }
        val lyrics = cleanup(raw, title, artist)
        XLog.i(
            "lyric fetch: ${source.name} ok for '$title - $artist' " +
                "(${lyrics.lines.size} lines, word=${lyrics.hasWordTiming}, " +
                "trans=${lyrics.hasTranslation}, ${cost}ms)"
        )
        // 首尾各取一行：一眼看出"这份词的头尾是不是正文"，
        // 版权行/尾注行没滤干净时不用再去翻整份歌词
        XLog.d(
            "lyric head/tail: [${lyrics.lines.first().wordsText.take(24)}] … " +
                "[${lyrics.lines.last().wordsText.take(24)}]"
        )
        synchronized(cache) { cache[key] = lyrics }
        return lyrics
    }

    /**
     * 去掉版权/制作人员行 + 「歌曲名 - 歌手」尾注行。
     *
     * QQ 的 QRC 会把「配唱制作人Vocal Producer：…」这类名单当正文行返回且带时间戳，
     * 于是状态栏前奏期会被这串名单顶住好几秒；正文之后还会补一行
     * `歌名 - 歌手`（真机实测：状态栏最后一句变成了歌名，播放页底部也多一行）。
     * 过滤规则与保守性见 [CreditLine]。逐字时间轴、翻译、语言等其余字段原样保留。
     */
    private fun cleanup(lyrics: TimedLyrics, title: String, artist: String): TimedLyrics {
        // 【临时诊断】过滤前的头几行原文。过滤规则只能照着**真实数据**调，
        // 靠猜会反复来回改；规则稳定后把这个常量改回 false。
        if (DUMP_LINES) {
            lyrics.lines.take(6).forEachIndexed { i, l ->
                XLog.d("[raw#$i ${l.begin}ms] ${l.wordsText.take(60)}")
            }
        }
        val kept = CreditLine.filter(lyrics.lines, title, artist)
        if (kept.size == lyrics.lines.size) return lyrics
        XLog.d("lyric: dropped ${lyrics.lines.size - kept.size} credit/credit-like line(s)")
        // 把被删掉的原文打出来：既是"删对了"的证据，也是漏网时的定位线索
        val dropped = lyrics.lines.filter { CreditLine.reason(it.wordsText, title, artist) != null }
        XLog.d("lyric: dropped as = " + dropped.take(10).joinToString(" ¦ ") { it.wordsText.take(26) })
        return TimedLyrics(kept, lyrics.language)
    }

    /**
     * 取一份歌词并写成 Apple 格式 TTML；取不到返回 null。
     *
     * 只要 TTML 的场景用这个；需要知道"有没有翻译轨"的用 [prepare]。
     */
    fun ttml(title: String, artist: String, album: String? = null, durationMs: Long = 0L): String? =
        prepare(title, artist, album, durationMs)?.ttml

    /**
     * 取词结果。
     *
     * @param ttml            Apple 格式 TTML
     * @param hasTranslation  这份歌词有没有翻译轨
     */
    class Prepared(val ttml: String, val hasTranslation: Boolean)

    /**
     * 取一份歌词 + 它的翻译情况。
     *
     * 【为什么要把 [Prepared.hasTranslation] 带出来】注入前要防"降级替换"：
     * 宿主给的行级歌词可能**带翻译轨**，而我们补出来的逐字歌词不一定有；
     * 换了就丢掉翻译，观感反而变差。调用方拿这个标志去否决这种替换。
     */
    fun prepare(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
    ): Prepared? {
        val lyrics = fetch(title, artist, album, durationMs) ?: return null
        val ttml = TtmlWriter.write(lyrics)
        if (ttml.isEmpty()) {
            XLog.w("lyric ttml: writer produced empty output for '$title'")
            return null
        }
        return Prepared(ttml, lyrics.hasTranslation)
    }

    /**
     * 自检：走完整条链路并把关键结果打日志。
     *
     * 调用方（模块加载流程）不应把它当作功能的一部分，它只是**证据**：
     * 证明"搜索 → 取词 → 解密 → 解析 → TTML → 官方解析器接受"这条链在当前版本仍然通。
     */
    fun selfCheck() {
        if (!SELF_CHECK) return
        Thread({
            XLog.i("lyric selfCheck: begin (sources=${LyricSource.entries.joinToString()})")
            for ((title, artist) in PROBES) {
                for (source in LyricSource.entries) {
                    checkOne(source, title, artist)
                }
            }
            XLog.i("lyric selfCheck: done")
        }, "amlyric-selfcheck").apply { isDaemon = true }.start()
    }

    private fun checkOne(source: LyricSource, title: String, artist: String) {
        val tag = "${source.name} '$title'"
        val started = System.currentTimeMillis()
        val lyrics = runCatching {
            when (source) {
                LyricSource.QQ -> QQMusicClient.fetch(title, artist, null, 0L)
                LyricSource.NETEASE -> NeteaseClient.fetch(title, artist, null, 0L)
            }
        }.onFailure { XLog.w("lyric selfCheck $tag failed: ${it.message}") }.getOrNull()
        val cost = System.currentTimeMillis() - started

        if (lyrics == null) {
            XLog.w("lyric selfCheck [$tag] MISS (${cost}ms)")
            return
        }
        val sample = lyrics.lines.firstOrNull { !it.translation.isNullOrBlank() }
        XLog.i(
            "lyric selfCheck [$tag] OK ${cost}ms: ${lyrics.lines.size} lines, " +
                "word=${lyrics.hasWordTiming}, trans=${lyrics.hasTranslation}, " +
                "first='${lyrics.lines.first().wordsText.take(30)}'"
        )
        if (sample != null) {
            XLog.i(
                "lyric selfCheck [$tag] trans sample ${sample.begin}ms: " +
                    "'${sample.wordsText.take(24)}' -> '${sample.translation?.take(24)}'"
            )
        }

        val ttml = TtmlWriter.write(lyrics)
        val ptr = runCatching { TtmlBridge.parse(ttml) }
            .onFailure { XLog.w("lyric selfCheck [$tag] ttml parse: ${it.message}") }
            .getOrNull()
        if (ptr == null) {
            XLog.w("lyric selfCheck [$tag] ttml REJECTED (${ttml.length} chars): ${ttml.take(300)}")
        } else {
            XLog.i("lyric selfCheck [$tag] ttml accepted (${ttml.length} chars): ${TtmlBridge.describe(ptr)}")
        }
    }
}
