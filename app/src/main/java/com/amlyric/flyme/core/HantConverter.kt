package com.amlyric.flyme.core

import android.icu.text.Transliterator
import android.os.Build
import com.amlyric.flyme.HantMode
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog

/**
 * 歌词简繁转换（v1.4.0）。
 *
 * 【为什么不用词典】用户问过"能不能调用系统的字库"——**能，而且实测可用**：
 * Android 10（API 29）起系统自带 ICU，`android.icu.text.Transliterator` 提供
 * `Simplified-Traditional` / `Traditional-Simplified` 两个转换器。真机（Android 16）实测：
 *
 * ```
 * 简→繁  头发干了以后，里面有条龙，我在后台看着你和面
 *       → 頭髮乾了以後，裡面有條龍，我在後台看著你和面
 * 繁→简  頭髮乾了以後，裡面有條龍，我在後臺看著你和麵
 *       → 头发干了以后，里面有条龙，我在后台看着你和面
 * ```
 *
 * 因此**不需要** OpenCC 词表（几百 KB）、不需要 Rust、不需要额外依赖。
 *
 * 【已知局限（务必知情）】
 *  1. **一简对多繁无法靠上下文判定**：ICU 的简→繁是"按字映射"，`和面`不会变成 `和麵`、
 *     `后台`变成 `後台`而不是 `臺`。繁→简方向是"多对一"，**几乎不会出错**。
 *     要 100% 准确只能上词组表（体积换准确率），当前取舍是"零体积、够用"。
 *  2. `Transliterator` **非线程安全**（内部带扫描位置状态），两个方向的调用都用锁串行化。
 *  3. API < 29 无此类 → 直接不转换（[apply] 原样返回），不会崩。
 */
internal object HantConverter {

    /** ICU 转换器 ID（官方 Unicode CLDR 名称，不是自定义规则） */
    private const val ID_S2T = "Simplified-Traditional"
    private const val ID_T2S = "Traditional-Simplified"

    private val lock = Any()

    /**
     * 真机转换器持有者。**单独放一个类**是为了让 `Transliterator` 的类引用
     * 只在 API ≥ 29 时被加载（minSdk 26，低版本设备上引用它会导致类校验失败）。
     * （用 `by lazy` + SDK 判断包住，低版本永不触发这个类的加载）
     */
    private class Icu {
        val s2t: Transliterator? = runCatching { Transliterator.getInstance(ID_S2T) }
            .onFailure { XLog.e("ICU transliterator '$ID_S2T' unavailable: ${it.message}", it) }
            .getOrNull()

        val t2s: Transliterator? = runCatching { Transliterator.getInstance(ID_T2S) }
            .onFailure { XLog.e("ICU transliterator '$ID_T2S' unavailable: ${it.message}", it) }
            .getOrNull()

        fun run(tr: Transliterator?, text: String): String =
            synchronized(lock) { runCatching { tr?.transliterate(text) }.getOrNull() ?: text }
    }

    private val icu: Icu? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Icu() else null
    }

    /** 系统 ICU 是否可用（仅用于日志/诊断） */
    val available: Boolean
        get() = icu?.let { it.s2t != null && it.t2s != null } == true

    /**
     * 按当前设置转换歌词文本。
     * 关闭 / 无可用转换器 / 转换失败 时**原样返回**，绝不因为转换出错而丢歌词。
     */
    fun apply(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val impl = icu ?: return text
        return when (Settings.hantMode) {
            HantMode.OFF -> text
            HantMode.S2T -> impl.run(impl.s2t, text)
            HantMode.T2S -> impl.run(impl.t2s, text)
        }
    }
}
