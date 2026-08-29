package com.amlyric.flyme.hook

import com.amlyric.flyme.XLog
import com.amlyric.flyme.util.Reflect

/**
 * Apple Music 5.2.0 原生歌词对象的反射读取器（TTML / Kotlin-Native 模型）。
 *
 * 与 4.x 的根本区别：5.2.0 的逐行时间戳不再以 getter 暴露（LyricsLineNative
 * 没有 getBegin/getEnd），时间由 SongInfoTimeProcessor 在播放时实时计算。
 * 我们要的“当前行文本”由引擎通过逐行回调直接给出（见 AppleMusicHooks），
 * 这里只负责把回调里拿到的 LyricsLineVector 读成字符串。
 *
 * 容器结构（5.2.0，已用 dexdump 从 Apple Music 5.2.0 确认）：
 *   LyricsLineVector
 *     ├─ size(): Long
 *     ├─ get(i: Long): LyricsLine$LyricsLinePtr
 *     └─ LyricsLinePtr.get(): LyricsLine$LyricsLineNative
 *          ├─ getHtmlLineText(): String           本行歌词（含 HTML 标签）
 *          └─ getHtmlTranslationLineText(): String 翻译（含 HTML 标签）
 *
 *   SongInfo$SongInfoPtr
 *     └─ get(): SongInfo$SongInfoNative
 *          ├─ getAdamId(): Long                    歌曲标识
 *          └─ getSections(): LyricsSectionVector   段落；size()==0 即无歌词
 */
object NativeLyricsParser {

    /**
     * 从 LyricsLineVector 提取当前行文本。通常只有 1~2 行（主歌 + 可选的翻译/伴唱），
     * 用 “ · ” 连接。所有原生访问逐步判空，任何异常安全返回 null。
     */
    fun extractLineText(lineVector: Any?): String? {
        if (lineVector == null) return null
        val size = (Reflect.call(lineVector, "size") as? Long) ?: return null
        if (size <= 0 || size > 200) return null // 防御异常数据
        val sb = StringBuilder()
        for (i in 0 until size) {
            val ptr = Reflect.call(lineVector, "get", i.toLong()) ?: continue
            val native = Reflect.call(ptr, "get") ?: continue
            val text = stripHtml(Reflect.call(native, "getHtmlLineText") as? String)
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(text)
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /** 判断 SongInfoPtr 对应歌曲是否含有逐行歌词（段落数 > 0） */
    fun hasLyrics(songInfoPtr: Any?): Boolean {
        if (songInfoPtr == null) return false
        val native = Reflect.call(songInfoPtr, "get") ?: return false
        val sections = Reflect.call(native, "getSections") ?: return false
        val size = (Reflect.call(sections, "size") as? Long) ?: 0L
        return size > 0
    }

    /** 取歌曲标识（adamId，Long 转 String），用于切歌去重 */
    fun adamId(songInfoPtr: Any?): String? {
        if (songInfoPtr == null) return null
        val native = Reflect.call(songInfoPtr, "get") ?: return null
        return when (val id = Reflect.call(native, "getAdamId")) {
            is Long -> id.toString()
            is String -> id
            else -> null
        }
    }

    // ─────────────────────────── HTML 清洗 ───────────────────────────

    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " "
    )

    fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        ENTITY.forEach { (k, v) -> s = s.replace(k, v) }
        s = s.replace(TAG, "")
        ENTITY.forEach { (k, v) -> s = s.replace(k, v) }
        return s.trim()
    }
}
