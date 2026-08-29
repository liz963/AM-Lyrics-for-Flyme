package com.amlyric.flyme

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块开关（SharedPreferences 持久化）。
 *
 * 默认开启。用户在 Apple Music 设置页里拨动「状态栏歌词」开关时，
 * 会调用 [setEnabled] 写入本进程（Apple Music 进程）私有的
 * "am_flyme_prefs.xml"；[FlymeStatusBarLyric] 与设置注入代码都读同一份，
 * 因此实时生效，无需重启应用或去 LSPosed 取消激活模块。
 */
object Settings {

    const val PREF_FILE = "am_flyme_prefs"
    const val KEY_LYRIC_ENABLED = "lyric_enabled"

    @Volatile
    var enabled = true
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (prefs == null) {
            prefs = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
        enabled = prefs?.getBoolean(KEY_LYRIC_ENABLED, true) ?: true
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        runCatching { prefs?.edit()?.putBoolean(KEY_LYRIC_ENABLED, value)?.apply() }
    }
}
