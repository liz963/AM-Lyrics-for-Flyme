package com.amlyric.flyme.lyric

import com.amlyric.flyme.XLog
import org.json.JSONObject

/**
 * 网易云取词（搜索 → `/api/song/lyric/v1` → YRC/LRC 解析）。
 *
 * ══════════════ 接口选择 ══════════════
 *
 * 用 `music.163.com/api/song/lyric/v1` 而不是老的单参版本，
 * 因为**逐字（YRC）只有 v1 的 `yv=1` 才给**：
 *
 * ```
 * /api/song/lyric/v1?id={id}&cp=false&tv=1&lv=1&rv=0&kv=0&yv=1&ytv=1&yrv=0
 *                     │            │      │      │      │      │
 *                     │            │      │      │      │      └ ytv=1 取逐字翻译
 *                     │            │      │      │      └ yv=1 取逐字主轨
 *                     │            │      │      └ kv=0 不要 karaoke 轨
 *                     │            │      └ rv=0 不要罗马音
 *                     │            └ lv=1 取行级主轨（YRC 缺失时兜底）
 *                     └ tv=1 取行级翻译
 * ```
 *
 * 必须带 `Referer: https://music.163.com/` 与那个 `os=pc; appver=2.9.7` 的 Cookie，
 * 否则接口会返回"需要登录"或空数据。
 *
 * ══════════════ 一个容易踩的坑：`yrc` 经常不存在 ══════════════
 *
 * **不是所有歌都有逐字歌词**。实测热歌榜前四首里只有一首带 `yrc` 字段，
 * 其余响应里连这个 key 都没有（不是空串，是不存在）。所以：
 *  · 取 `yrc` 一律用 `optJSONObject`，不要用 `getJSONObject`；
 *  · 没有 `yrc` 就退回 `lrc`（行级）——**纯行级歌词对"原生歌词缺失"这类场景依然有用**，
 *    只是没有逐字高亮而已（[TimedLyrics.hasWordTiming] 为 false，
 *    [TtmlWriter] 会自动写 `itunes:timing="Line"`，不会谎报 Word）。
 */
internal object NeteaseClient {

    private const val SEARCH = "https://music.163.com/api/search/get"
    private const val LYRIC = "https://music.163.com/api/song/lyric/v1"

    private val HEADERS = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "os=pc; appver=2.9.7; channel=netease;",
    )

    /** 搜索一次取多少条候选 */
    private const val SEARCH_LIMIT = 15

    fun fetch(title: String, artist: String, album: String?, durationMs: Long): TimedLyrics? {
        val candidates = search("$title $artist")
        if (candidates.isEmpty()) {
            XLog.w("netease: no search result for '$title - $artist'")
            return null
        }
        val best = LyricMatch.pickBest(candidates, title, artist, durationMs)
        if (best == null) {
            XLog.w("netease: no candidate matched '$title'")
            return null
        }
        XLog.i("netease: matched '${best.title}' - '${best.artist}' (${best.id})")

        val json = call(best.id) ?: return null

        val yrc = json.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = json.optJSONObject("tlyric")?.optString("lyric").orEmpty()

        // 优先逐字；没有就退回行级。两者都没有 = 这首歌没歌词
        val lyrics = KaraokeText.parse(yrc)
            ?: LrcTrack.parse(lrc).takeIf { it.isNotEmpty() }?.let { TimedLyrics(it) }
        if (lyrics == null) {
            XLog.w("netease: no lyric body for ${best.id} (yrc=${yrc.length} lrc=${lrc.length})")
            return null
        }

        val merged = LrcTrack.mergeTranslation(lyrics.lines, tlyric)
        return TimedLyrics(merged, lyrics.language)
    }

    // ─────────────────────────── 搜索 ───────────────────────────

    private fun search(keyword: String): List<LyricCandidate> {
        val url = "$SEARCH?type=1&limit=$SEARCH_LIMIT&s=" + enc(keyword)
        // 这个接口认 POST 表单
        val raw = LyricHttp.postForm(url, "s=" + enc(keyword) + "&type=1&limit=$SEARCH_LIMIT", HEADERS)
            ?: return emptyList()

        return runCatching {
            val songs = JSONObject(raw).optJSONObject("result")
                ?.optJSONArray("songs") ?: return emptyList()
            (0 until songs.length()).mapNotNull { i ->
                val song = songs.optJSONObject(i) ?: return@mapNotNull null
                val artists = song.optJSONArray("artists")
                val artist = buildString {
                    if (artists != null) for (j in 0 until artists.length()) {
                        val name = artists.optJSONObject(j)?.optString("name").orEmpty()
                        if (name.isNotEmpty()) {
                            if (isNotEmpty()) append(' ')
                            append(name)
                        }
                    }
                }
                LyricCandidate(
                    id = song.optLong("id"),
                    mid = null,
                    title = song.optString("name"),
                    artist = artist,
                    album = song.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = song.optLong("duration"),
                )
            }
        }.onFailure { XLog.w("netease search parse: ${it.message}") }.getOrDefault(emptyList())
    }

    // ─────────────────────────── 取词 ───────────────────────────

    private fun call(songId: Long): JSONObject? {
        val url = "$LYRIC?id=$songId&cp=false&tv=1&lv=1&rv=0&kv=0&yv=1&ytv=1&yrv=0"
        val raw = LyricHttp.get(url, HEADERS) ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure { XLog.w("netease lyric parse: ${it.message}") }
            .getOrNull()
    }

    private fun enc(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")
}
