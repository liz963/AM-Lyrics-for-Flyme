package com.amlyric.flyme.core

import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.NativeLyricsParser
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 官方歌词引擎的反射驱动 —— 模块内唯一需要知道混淆名/SAM 接口/Proxy 的地方。
 *
 * 把「怎么把 `SongInfoTimeProcessor.processEvents` 反射调起来」从调度逻辑里隔离出来：
 * 调用方只面对 [drive] / [peek]，不必关心宿主类名与方法签名。
 *
 * ## processEvents 语义
 * ```
 * processEvents(ptr, positionMs, lineCb, wordCb, bgWordCb, prWordCb, prBgWordCb): long
 * ```
 *  · 行事件回调签名 `(Long position, LyricsLineVector line, Long timestamp)`；
 *  · 返回值 = **下一歌词事件的绝对位置(ms)**（v1.3.6 语义纠正。早期误当延迟用，
 *    把返回值 15~45s 当 delay，导致后台歌词完全停更）。
 *
 * ## v1.3.3 关键结论：回调类型以 method 参数为准（勿回退此实现）
 * processEvents 的第 3 个参数类型是 **SAM 接口本身**（混淆名 `g.q`），
 * **不是**它的 Kotlin lambda 包装类 `lineEventCallback$1`。
 * v1.3.2 曾把代理再包进 `lineEventCallback$1` 才传入 → 类型不匹配 → 整条反射链路失败，
 * 表现为后台/无 UI 都没歌词（ticker 一直为空）。
 *
 * 正确做法（完全不依赖混淆名）：
 *  1. 定位 processEvents（7 参数，第 1 个参数类型名以 `SongInfoPtr` 结尾、第 2 个是 `long`）；
 *  2. 从 `parameterTypes[2]` 读出真实 SAM 接口，并取其唯一业务方法名；
 *  3. 用该接口直接 `newProxyInstance` 出 5 个代理，第 0 个（line）抽文本，其余 no-op。
 *
 * ## 两套回调，用途完全不同
 *  · `displayCallbacks` —— line 事件交给 [onDisplayLine] 决策；
 *  · `peekCallbacks`    —— 只把文本缓存下来供 [peek] 返回，**绝不上屏**、也不影响播放器 UI
 *    （用的是本模块自己 new 出来的处理器实例，与播放界面那个互不干扰）。
 */
internal class LyricsEngineDriver(
    private val classLoader: ClassLoader,
    private val onDisplayLine: (String?) -> Unit,
) {

    internal companion object {
        const val CLS_TIME_PROCESSOR = "com.apple.android.music.ttml.SongInfoTimeProcessor"

        /** processEvents 参数个数：ptr + positionMs + 5 个回调 */
        const val PROCESS_EVENTS_ARGC = 7

        /** line 回调在参数列表中的下标（也是回调数组里唯一有行为的那个） */
        const val LINE_CALLBACK_INDEX = 0

        /** 每个回调承载的参数个数下限：至少要有 (position, line) */
        const val MIN_CALLBACK_ARGC = 2

        /** SAM 接口里不属于业务方法的 Object 方法 */
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")

        /**
         * 「本模块正在反射调用 `processEvents`」标记。
         *
         * ══════════════ 为什么必须有它（v1.4.0 真机实测，改前必读）══════════════
         *
         * 宿主 `SongInfoTimeProcessor.processEvents` **内部**会把传进来的行回调包一层
         * 自己的 lambda（混淆名 `SongInfoTimeProcessor$processEvents$lineEventCallback$1`），
         * 由 native 侧回调。而我们为"前台精确时机"挂的那个 Hook 正是**按类**挂在这层 lambda 上 ——
         * 于是**我们自己**调用 processEvents 时，它也会触发，被当成"宿主 UI 正在显示歌词"。
         *
         * 后果（都是实测过的）：
         *  ① 自检用的样例歌词 `自检歌词` / `第二句啊` 会跑到状态栏上
         *     （旧版还会因此发一条写着歌词的通知，用户一眼就看到了）；
         *  ② 为算"下一跳时间"而做的只读探测（[peek]）会把**下一句提前推**出去。
         *
         * 所以 [drive] / [peek] 期间置位，前台 Hook 看到它就整条跳过。
         * 同线程同步调用，用 `@Volatile` 足够。
         */
        @Volatile
        var isInvoking: Boolean = false
            private set
    }

    private var processor: Any? = null
    private var processMethod: Method? = null
    private var displayCallbacks: Array<Any>? = null
    private var peekCallbacks: Array<Any>? = null

    /** [peek] 期间由静默回调写入的临时承接变量（同线程调用后即读） */
    private var peekedText: String? = null

    val isReady: Boolean
        get() = processor != null && processMethod != null &&
            displayCallbacks != null && peekCallbacks != null

    /** 懒初始化。任何失败只记日志、不抛——宿主进程内绝不能让异常外溢 */
    fun ensureReady() {
        if (isReady) return
        runCatching { build() }.onFailure { XLog.e("engine init failed: ${it.message}", it) }
    }

    /**
     * 主查询：驱动引擎在 [queryPos] 处求值，行事件经 `onDisplayLine` 回调。
     * @return 下一歌词事件的绝对位置(ms)；引擎未就绪或调用失败时返回 null
     */
    fun drive(ptr: Any, queryPos: Long): Long? {
        val tp = processor ?: return null
        val m = processMethod ?: return null
        val cbs = displayCallbacks ?: return null
        isInvoking = true
        try {
            return m.invoke(tp, ptr, queryPos, cbs[0], cbs[1], cbs[2], cbs[3], cbs[4]) as? Long
        } finally {
            isInvoking = false
        }
    }

    /**
     * 只读探测：返回 [atPos] 处的行文本（无行 / 引擎未就绪时返回 null）。
     * 用独立的静默回调，只承文本、不上屏，因此不会污染播放器自身引擎。
     *
     * ⚠️ 但它仍会经过宿主内部那层回调 wrapper，从而触发我们挂的前台 Hook ——
     * 靠 [isInvoking] 把它挡掉（见该字段注释）。
     */
    fun peek(ptr: Any, atPos: Long): String? {
        val tp = processor ?: return null
        val m = processMethod ?: return null
        val cbs = peekCallbacks ?: return null
        peekedText = null
        isInvoking = true
        try {
            m.invoke(tp, ptr, atPos, cbs[0], cbs[1], cbs[2], cbs[3], cbs[4])
        } finally {
            isInvoking = false
        }
        return peekedText
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun build() {
        val tpClass = classLoader.loadClass(CLS_TIME_PROCESSOR)
        processor = tpClass.getDeclaredConstructor().newInstance()
        XLog.i("SongInfoTimeProcessor created")

        val m = tpClass.declaredMethods.firstOrNull { meth ->
            meth.name == "processEvents" &&
                meth.parameterCount == PROCESS_EVENTS_ARGC &&
                meth.parameterTypes[0].name.endsWith("SongInfoPtr") &&
                meth.parameterTypes[1] == Long::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("processEvents(...)")
        m.isAccessible = true
        processMethod = m

        val samInterface = m.parameterTypes[2]
        val samMethod = samInterface.declaredMethods
            .firstOrNull { it.name !in OBJECT_METHODS }?.name ?: "invoke"
        XLog.d("lyrics callback SAM: ${samInterface.name}#$samMethod")

        displayCallbacks = lineCallbacks(samInterface, samMethod) { text ->
            XLog.d("cbInvoke line txt=[$text]")
            onDisplayLine(text)
        }
        peekCallbacks = lineCallbacks(samInterface, samMethod) { text ->
            if (text != null) peekedText = text
        }
        XLog.i("lyrics callbacks ready (display=${displayCallbacks?.size}, peek=${peekCallbacks?.size})")
        XLog.i("processEvents method ready")
    }

    /**
     * 造 5 个 SAM 代理，只有第 [LINE_CALLBACK_INDEX] 个（line）有行为：抽出该行文本交给 [onLine]。
     * 其余（字级/背景字级/发音字级）保持 no-op —— 我们只做整行歌词。
     */
    private fun lineCallbacks(
        samInterface: Class<*>,
        samMethod: String,
        onLine: (String?) -> Unit,
    ): Array<Any> = Array(5) { index ->
        Proxy.newProxyInstance(samInterface.classLoader, arrayOf(samInterface)) { _, method, args ->
            if (index == LINE_CALLBACK_INDEX && method.name == samMethod &&
                args != null && args.size >= MIN_CALLBACK_ARGC
            ) {
                onLine(NativeLyricsParser.extractLineText(args[1]))
            }
            null
        }
    }
}
