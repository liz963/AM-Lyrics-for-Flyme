package com.amlyric.flyme

import android.content.Context
import android.content.SharedPreferences

/** 简繁转换方向。 */
enum class HantMode {
    /** 关闭：歌词原样显示 */
    OFF,

    /** 简体 → 繁体 */
    S2T,

    /** 繁体 → 简体 */
    T2S,
}

/**
 * 在线歌词源。
 *
 * 只保留 QQ 音乐与网易云两家：这两家的**逐字**时间轴（QRC / YRC）最完整，
 * 而且都带翻译轨，正好补 Apple Music 缺的那部分。其他源（酷狗等）不接。
 */
enum class LyricSource(val label: String) {
    QQ("QQ 音乐"),
    NETEASE("网易云音乐"),
}

/**
 * 模块开关（SharedPreferences 持久化）。
 *
 * 【作用域】写入的是 **Apple Music 进程私有**的 `am_flyme_prefs.xml`。
 * 「状态栏歌词」总开关与简繁转换的两个方向都存这里；[FlymeStatusBarLyric]、
 * [core.HantConverter] 与设置页注入代码读的是同一份，因此改完立即生效，
 * 不需要重启应用、也不需要去 LSPosed 里重新激活模块。
 *
 * 【互斥在哪保证】简转繁 / 繁转简**只能有一个为真**，这条不变式由本类的
 * setter 强制维护（打开一个会自动关掉另一个），而不是靠界面代码自觉。
 * 这样即使将来多出别的调用方（快捷方式、自动化脚本），也不可能出现两个方向同时生效。
 */
object Settings {

    const val PREF_FILE = "am_flyme_prefs"
    const val KEY_LYRIC_ENABLED = "lyric_enabled"
    const val KEY_HANT_S2T = "hant_s2t"
    const val KEY_HANT_T2S = "hant_t2s"
    const val KEY_AUTO_COMPLETE = "auto_complete"
    const val KEY_LYRIC_SOURCE = "lyric_source"

    /** 状态栏歌词总开关，默认开 */
    @Volatile
    var enabled = true
        private set

    /**
     * 「自动实时补全」总开关，**默认关**。
     *
     * 关掉时播放过程中**完全不请求任何歌词服务**（不联网、不搜索、不取词），
     * 这是用户明确要求的语义：只有主动打开才允许在播放中发请求。
     */
    @Volatile
    var autoComplete = false
        private set

    /** 在线歌词源（QQ / 网易云，单选，总有值） */
    @Volatile
    var lyricSource = LyricSource.QQ
        private set

    /** 简转繁（与 [hantT2S] 互斥） */
    @Volatile
    var hantS2T = false
        private set

    /** 繁转简（与 [hantS2T] 互斥） */
    @Volatile
    var hantT2S = false
        private set

    /** 当前转换方向（由两个开关推出，两者都关 = [HantMode.OFF]） */
    val hantMode: HantMode
        get() = when {
            hantS2T -> HantMode.S2T
            hantT2S -> HantMode.T2S
            else -> HantMode.OFF
        }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
        enabled = prefs?.getBoolean(KEY_LYRIC_ENABLED, true) ?: true
        hantS2T = prefs?.getBoolean(KEY_HANT_S2T, false) ?: false
        hantT2S = prefs?.getBoolean(KEY_HANT_T2S, false) ?: false
        // 老数据里可能两个都为真（历史版本没有互斥保证），纠正为「简转繁优先」
        if (hantS2T && hantT2S) hantT2S = false
        autoComplete = prefs?.getBoolean(KEY_AUTO_COMPLETE, false) ?: false
        lyricSource = runCatching {
            LyricSource.valueOf(
                prefs?.getString(KEY_LYRIC_SOURCE, null) ?: LyricSource.QQ.name
            )
        }.getOrDefault(LyricSource.QQ)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        persist { putBoolean(KEY_LYRIC_ENABLED, value) }
    }

    fun setAutoComplete(value: Boolean) {
        autoComplete = value
        persist { putBoolean(KEY_AUTO_COMPLETE, value) }
    }

    /** 切换歌词源（单选：界面上的两个开关由调用方保证只有一个亮） */
    fun setLyricSource(value: LyricSource) {
        lyricSource = value
        persist { putString(KEY_LYRIC_SOURCE, value.name) }
    }

    /** 打开简转繁（自动关掉繁转简） */
    fun setHantS2T(value: Boolean) {
        hantS2T = value
        if (value) hantT2S = false
        persist {
            putBoolean(KEY_HANT_S2T, hantS2T)
            putBoolean(KEY_HANT_T2S, hantT2S)
        }
    }

    /** 打开繁转简（自动关掉简转繁） */
    fun setHantT2S(value: Boolean) {
        hantT2S = value
        if (value) hantS2T = false
        persist {
            putBoolean(KEY_HANT_T2S, hantT2S)
            putBoolean(KEY_HANT_S2T, hantS2T)
        }
    }

    private inline fun persist(block: SharedPreferences.Editor.() -> Unit) {
        runCatching { prefs?.edit()?.apply(block)?.apply() }
    }
}
