package com.amlyric.flyme.lyric

import com.amlyric.flyme.XLog
import android.util.Base64
import org.json.JSONObject

/**
 * QQ 音乐取词（搜索 → 会话 → GetPlayLyricInfo → QRC 解密 → 解析）。
 *
 * ══════════════ 接口选择：为什么用 musicu 而不用老的 lyric_download ══════════════
 *
 * 两条路都实测过，返回的密文**完全一样**（6912 个 hex 字符），但：
 *  · 老接口 `c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg` 是未公开的爬虫口，
 *    返回一段包在 XML 注释里的伪 XML，字段靠 `<content encode="1">` 认；
 *  · `u.y.qq.com/cgi-bin/musicu.fcg` 是客户端自己用的正式 RPC，
 *    字段清楚（`lyric` / `trans` / `roma` + 三个 `*_t` 时间戳），还好做错误处理。
 * 所以走 musicu。它需要**先拿一次会话**（`GetSession` → uid/sid/userip），
 * 拿到的会话复用（见 [session]），不必每次播放都握手。
 *
 * ══════════════ 返回的三个字段形态不同（实测）══════════════
 *
 * | 字段 | 解密后是什么 |
 * |---|---|
 * | `lyric` | `<?xml…><QrcInfos>…<Lyric_1 LyricContent="[ti:…]&#10;[0,400]字(0,14)…"/>` |
 * | `trans` | **纯 LRC**（`[ti:]…` + `[mm:ss.xx]译文`），没有 XML 外壳 |
 * | `roma` | 与 `lyric` 同构（QRC 逐字） |
 *
 * 只有非空且时间戳非 0 的字段才有内容——[decrypt] 前的判空不是多余的。
 */
internal object QQMusicClient {

    private const val API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"

    private val HEADERS = mapOf(
        // 服务端按 UA 给能力，写 OkHttp/3.14.9 是客户端自身的取值
        "User-Agent" to "okhttp/3.14.9",
        "Cookie" to "tmeLoginType=-1;",
        "Referer" to "https://y.qq.com/",
    )

    /** `comm` 块里那些"客户端指纹"字段，照客户端取值填即可 */
    private const val CLIENT_VER = "1003006"
    private const val TME_APP_ID = "qqmusiclight"

    /** 会话复用时长；过期就重新 GetSession */
    private const val SESSION_TTL_MS = 6 * 60 * 60 * 1000L

    @Volatile
    private var session: JSONObject? = null

    @Volatile
    private var sessionAt = 0L

    /**
     * 取一首歌的逐字歌词。
     *
     * @param title      曲名（Apple Music 给的，可能带副标题/后缀）
     * @param artist     歌手
     * @param album      专辑（可空，只用于提高匹配精度）
     * @param durationMs 时长（可空传 0）
     * @return 逐字歌词；搜不到/取不到/解密失败都返回 null
     */
    fun fetch(title: String, artist: String, album: String?, durationMs: Long): TimedLyrics? {
        val candidates = search("$title $artist")
        if (candidates.isEmpty()) {
            XLog.w("qq: no search result for '$title - $artist'")
            return null
        }
        val best = LyricMatch.pickBest(candidates, title, artist, durationMs)
        if (best == null) {
            XLog.w("qq: no candidate matched '$title'")
            return null
        }
        XLog.i("qq: matched '${best.title}' - '${best.artist}' (${best.mid})")

        val data = lyrics(best, album ?: best.album) ?: return null

        // 主轨：优先进逐字（lyric），没有就退回行级（某些歌只有 LRC）
        val wordLevel = decrypt(data.optString("lyric"), data.optLong("qrc_t"))?.let(::extractQrcBody)
        val lineLevel = decrypt(data.optString("lyric"), data.optLong("lrc_t"))?.let(::extractQrcBody)
        val lyrics = KaraokeText.parse(wordLevel ?: lineLevel.orEmpty())
            ?: LrcTrack.parse(lineLevel).takeIf { it.isNotEmpty() }?.let { TimedLyrics(it) }
        if (lyrics == null) {
            XLog.w("qq: no usable lyric body for ${best.mid}")
            return null
        }

        // 翻译轨是纯 LRC，按时间贴合到主轨上
        val translation = decrypt(data.optString("trans"), data.optLong("trans_t"))
        return TimedLyrics(LrcTrack.mergeTranslation(lyrics.lines, translation), lyrics.language)
    }

    // ─────────────────────────── 搜索 ───────────────────────────

    private fun search(keyword: String): List<LyricCandidate> {
        val url = "$SEARCH?p=1&n=30&t=0&format=json&w=" + enc(keyword)
        val raw = LyricHttp.get(url, HEADERS) ?: return emptyList()
        // 老接口偶尔外面裹一层 callback(...)
        val text = if (raw.trimStart().startsWith("{")) raw
        else raw.substringAfter('(', "").substringBeforeLast(')').ifBlank { return emptyList() }

        return runCatching {
            val list = JSONObject(text).getJSONObject("data")
                .getJSONObject("song").getJSONArray("list")
            (0 until list.length()).mapNotNull { i ->
                val song = list.optJSONObject(i) ?: return@mapNotNull null
                val singer = song.optJSONArray("singer")
                val artists = buildString {
                    if (singer != null) for (j in 0 until singer.length()) {
                        val name = singer.optJSONObject(j)?.optString("name").orEmpty()
                        if (name.isNotEmpty()) {
                            if (isNotEmpty()) append(' ')
                            append(name)
                        }
                    }
                }
                LyricCandidate(
                    id = song.optLong("songid"),
                    mid = song.optString("songmid").takeIf { it.isNotEmpty() },
                    title = song.optString("songname"),
                    artist = artists,
                    album = song.optString("albumname"),
                    // interval 单位是秒
                    durationMs = song.optLong("interval") * 1000L,
                )
            }
        }.onFailure { XLog.w("qq search parse: ${it.message}") }.getOrDefault(emptyList())
    }

    // ─────────────────────────── 取词 ───────────────────────────

    private fun lyrics(candidate: LyricCandidate, album: String): JSONObject? {
        val mid = candidate.mid ?: return null
        val comm = session() ?: return null
        val param = JSONObject().apply {
            put("songMID", mid)
            put("songID", candidate.id)
            put("songName", b64(candidate.title))
            put("singerName", b64(candidate.artist))
            put("albumName", b64(album))
            put("interval", (candidate.durationMs / 1000).toInt())
            put("lrc_t", 0)
            put("qrc_t", 0)
            put("trans_t", 0)
            put("roma_t", 0)
            put("crypt", 1)
            put("ct", 19)
            put("cv", 2111)
            put("qrc", 1)
            put("roma", 1)
            put("trans", 1)
            put("type", 0)
        }
        val response = call(comm, "GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param)
            ?: return null
        return response
    }

    /** 会话（uid/sid/userip）；缓存失效或调用方拿到数据但报错时会被重新拉取 */
    private fun session(force: Boolean = false): JSONObject? {
        val cached = session
        if (!force && cached != null && System.currentTimeMillis() - sessionAt < SESSION_TTL_MS) {
            return cached
        }
        val comm = baseComm()
        val param = JSONObject().apply {
            put("caller", 0)
            put("uid", "0")
            put("vkey", 0)
        }
        val data = call(comm, "GetSession", "music.getSession.session", param, needSession = false)
            ?: return cached
        val info = data.optJSONObject("session") ?: return cached
        val merged = baseComm().apply {
            put("uid", info.optString("uid", "0"))
            put("sid", info.optString("sid"))
            put("userip", info.optString("userip"))
        }
        session = merged
        sessionAt = System.currentTimeMillis()
        return merged
    }

    /** 发一次 musicu RPC，返回 `request.data` */
    private fun call(
        comm: JSONObject,
        method: String,
        module: String,
        param: JSONObject,
        needSession: Boolean = true,
    ): JSONObject? {
        if (needSession && comm.optString("sid").isEmpty()) return null
        val body = JSONObject().apply {
            put("comm", comm)
            put("request", JSONObject().apply {
                put("method", method)
                put("module", module)
                put("param", param)
            })
        }.toString()

        val raw = LyricHttp.postJson(API, body, HEADERS) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val code = root.optInt("code", -1)
            if (code != 0) {
                XLog.w("qq $method: code=$code")
                return@runCatching null
            }
            root.getJSONObject("request").optJSONObject("data")
        }.onFailure { XLog.w("qq $method parse: ${it.message}") }.getOrNull()
    }

    private fun baseComm(): JSONObject = JSONObject().apply {
        put("ct", 11)
        put("cv", CLIENT_VER)
        put("v", CLIENT_VER)
        put("os_ver", "15")
        put("phonetype", "24122RKC7C")
        put("rom", "Android")
        put("tmeAppID", TME_APP_ID)
        put("nettype", "NETWORK_WIFI")
        put("udid", "0")
    }

    // ─────────────────────────── 解密与拆壳 ───────────────────────────

    /**
     * 按 [timestamp] 判断该字段有没有内容再解。
     * `*_t == 0` 表示"这一轨没有"，此时字段通常是空串——
     * 直接丢给解密只会白跑一遍 DES。
     */
    private fun decrypt(field: String?, timestamp: Long): String? {
        if (field.isNullOrBlank() || timestamp == 0L) return null
        return QrcCrypto.decrypt(field)
    }

    /** 从解密后的 `QrcInfos` XML 里掏出 `Lyric_1` 的 `LyricContent` 属性 */
    private fun extractQrcBody(decrypted: String): String? {
        val match = LYRICS_CONTENT.find(decrypted)
        val body = if (match != null) match.groupValues[1] else decrypted
        return xmlUnescape(body).replace("\r\n", "\n").replace('\r', '\n')
    }

    private val LYRICS_CONTENT = Regex("""LyricContent\s*=\s*"(.*?)"\s*/>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * XML 属性里的实体还原。
     *
     * QRC 把换行编码成 `&#10;`、引号编码成 `&quot;`——不还原的话整份歌词会连成一行。
     * 从左到右**单趟**扫描即可，不需要"`&amp;` 最后处理"那种技巧：
     * 消费掉 `&amp;` 后指针跳过了 `;`，后面残留的 `lt;` 前面已经没有 `&`，不会再被当成实体。
     */
    private fun xmlUnescape(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, end)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded == null) {
                out.append(c)
                i++
                continue
            }
            out.append(decoded)
            i = end + 1
        }
        return out.toString()
    }

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun enc(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")
}
