package com.amlyric.flyme.core

import android.os.Handler
import android.os.Looper
import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.NativeLyricsParser
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 位置驱动歌词调度器 v8（v1.3.8 = v1.3.7 + B 方案「下一行探测 + 时间戳精准调度」+ LEAD_MS 由 1000ms 提升到 2000ms，歌词提前 2 秒上屏）。
 *
 * 【v1.3.6 变更】
 *  1. 回滚 v1.3.4 的「取消延迟上限」改动 —— 该改动导致后台歌词完全停更；
 *     恢复 MAX_DELAY_MS = 5000 作为后台保活心跳。
 *  2. 纠正 processEvents 返回值语义：它是【下一歌词事件的绝对位置(ms)】，
 *     不是延迟。换算 delay = nextEventPos - queryPos 后，既精准对齐官方
 *     进度（误差 < 100ms），又不会出现长时挂起。
 *  3. 新增 LEAD_MS 提前量：喂给官方引擎的位置 = 实际位置 + 2000ms（v1.3.8 起），
 *     使状态栏歌词早于实际进度约 2 秒出现。
 *  4. B 方案（v1.3.8）：每次主查询拿到 nextEventPos（下一行绝对位置）后，立刻对
 *     nextEventPos 再做一次只读探测调用，缓存「下一行文本 + 下一行开始时间」，
 *     调度直接锚定 nextLineStart - LEAD_MS，比单纯依赖 postDelayed 估算更稳，
 *     且为将来双行/过渡动画预留了下一行数据。
 *
 * 【关键突破（v1.3.1）】
 *  拆包发现 getLinesAtPosition 在整个宿主 Java 层零调用——它是 JavaCPP
 *  生成的包装方法，语义未经验证，很可能是 v1.3.0 后台停更的直接原因。
 *
 *  替代方案：SongInfoTimeProcessor.processEvents —— 这是播放界面 Fragment
 *  驱动逐行歌词的**官方引擎入口**（字节码逐条验证）：
 *
 *    processEvents(SongInfoPtr ptr, long positionMs,
 *      jf/q lineCallback,       // 行事件 → (Long pos, LyricsLineVector line, Long ts)
 *      jf/q wordCallback,       // 字级高亮
 *      jf/q bgWordCallback,     // 背景字级
 *      jf/q prWordCallback,     // 发音字级
 *      jf/q prBgWordCallback    // 背景发音字级
 *    ): long  // 返回距下一事件的毫秒延迟
 *
 *  SongInfoTimeProcessor 有公开无参构造器（<init> → super 即 Native 基类），
 *  jf/q 是纯 Kotlin 单方法接口 i(Object,Object,Object):Object，
 *  两者都可用 java.lang.reflect.Proxy 实现。
 *
 *  线程模型：轮询跑在宿主主线程（与播放界面 Fragment 的 Handler 同线程，
 *  processEvents 内部调原生 processEvents_，需同线程防并发）。
 */
object BackgroundLyrics {

    /** 默认轮询间隔（无歌词或 processEvents 失败时兜底） */
    private const val POLL_INTERVAL_MS = 500L

    /** 非播放态轮询间隔（低频保活，恢复播放立即提速） */
    private const val IDLE_INTERVAL_MS = 2000L

    /** processEvents 返回延迟的下限护栏（防 0/负 busy-loop） */
    private const val MIN_DELAY_MS = 100L

    /**
     * 延迟上限 = 后台保活心跳（v1.3.3 行为，v1.3.6 恢复）。
     *
     * 【为什么必须保留上限（v1.3.4 教训）】
     *  v1.3.4 曾把上限放到 60s，直接把 processEvents 返回值当延迟用（15~45s）。
     *  结果后台歌词【完全停更】：
     *   1. 主线程一次挂十几秒无消息，进程在后台被降优/延时消息被系统推后执行；
     *   2. 这十几秒内我们既不读 position 也不感知切歌/seek，一旦发生就卡死。
     *  所以上限的真正作用是"心跳"——保证后台每 5s 至少醒一次、重新读一次
     *  position 并重新驱动一次官方引擎。
     *
     * 【上限不会造成滞后（前提：延迟换算正确）】
     *  行间隔 > 5s 时会被截成提前唤醒，但下一跳会用新的 position 重新算出
     *  真实剩余（nextEventPos - queryPos），最后一跳必然 < 5s 且为精确值，
     *  因此行事件触发误差 < 100ms。真正的滞后源是"把绝对位置当延迟"。
     */
    private const val MAX_DELAY_MS = 5000L

    /**
     * 歌词提前量（毫秒）。喂给官方引擎的位置 = 实际播放位置 + LEAD_MS，
     * 等于把歌词时间轴整体往前拨，使状态栏歌词早于实际进度出现，
     * 抵消状态栏 ticker 的渲染/合成延迟，观感上"歌词先到、人声后到"。
     * v1.3.8 起从 1000ms 提升到 2000ms，实现"提前 2 秒推下一行"的需求（B 方案）。
     */
    private const val LEAD_MS = 2000L

    private const val CLS_TIME_PROCESSOR =
        "com.apple.android.music.ttml.SongInfoTimeProcessor"
    private const val CLS_SONG_PTR =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"

    /** 当前歌曲的歌词句柄（强引用持有，保证原生 shared_ptr 不被释放） */
    @Volatile
    private var songPtr: Any? = null

    /** 歌词句柄对应的歌曲 key，防止拿到上一首的旧 ptr */
    @Volatile
    private var songPtrKey: String? = null

    /** 当前歌曲 key（来自 getCurrentItem，无 UI 也有效） */
    @Volatile
    private var currentSongKey: String? = null

    /** 播放器控制器实例（LocalMediaPlayerController，onPlaybackStateChanged 捕获） */
    @Volatile
    private var controller: Any? = null

    /** 仅用于控制「是否发起取词请求」；显示驱动不再依赖它 */
    @Volatile
    private var playing = false

    private var classLoader: ClassLoader? = null

    // ─── processEvents 反射缓存 ───
    private var timeProcessor: Any? = null
    private var processMethod: Method? = null
    /** 5 个歌词事件回调（显示用：line 触发即上屏当前行） */
    private var eventCallbacks: Array<Any>? = null
    /** 5 个歌词事件回调（静默探测用：line 触发只缓存文本，不上屏） */
    private var peekCallbacks: Array<Any>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var polling = false

    /** processEvents 返回的自适应延迟（下次 tick 间隔） */
    @Volatile
    private var nextDelay: Long = POLL_INTERVAL_MS

    // ─── B 方案：下一行缓存（时间戳精准调度） ───
    /** 下一行文本缓存（只读探测得到，不用于显示，仅供将来双行/过渡） */
    @Volatile
    private var nextLineText: String? = null
    /** 下一行绝对开始时间戳(ms)，由主查询的 nextEventPos 提供 */
    @Volatile
    private var nextLineStart: Long = -1L
    /** 当前已上屏文本，用于去重，避免同一行被重复推送 */
    @Volatile
    private var displayedText: String? = null
    /** 探测调用临时承接变量（单线程 handler，调用后即读即清） */
    private var peekedText: String? = null

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!polling) return
            runCatching { onTick() }.onFailure { XLog.d("tick error: ${it.message}") }
            val delay = if (playing) nextDelay else IDLE_INTERVAL_MS
            mainHandler.postDelayed(this, delay.coerceAtLeast(50L))
        }
    }

    private fun onTick() {
        // 1. 感知当前歌曲
        val item = readCurrentMediaItem()
        if (item != null) {
            val storeId = ReflectCompat.string(item, "getPlaybackStoreId")
            val key = storeId ?: "p${ReflectCompat.long(item, "getPersistentId")}"
            if (key != currentSongKey) {
                currentSongKey = key
                songPtr = null
                songPtrKey = null
                // B 方案：切歌重置下一行缓存与去重标记
                nextLineText = null
                nextLineStart = -1L
                displayedText = null
                LyricController.onSongChanged(key)
                XLog.d("song key -> $key (storeId=$storeId)")
            }

            // 2. 当前歌没有 ptr：先查会话缓存，再主动取词
            if (songPtr == null) {
                val cached = LyricsLoader.cachedPtr(storeId)
                if (cached != null) {
                    songPtr = cached
                    songPtrKey = key
                    XLog.d("ptr restored from cache: $key")
                } else if (playing && storeId != null && storeId.matches(Regex("^\\d+$"))) {
                    val title = ReflectCompat.string(item, "getTitle")
                    LyricsLoader.requestLyrics(storeId, readQueueId(), title)
                }
            }
        }

        // 3. 位置驱动：processEvents(ptr, pos, 5 callbacks)
        //    第一个 callback (lineCallback) 收到行事件 → 提取文本 → 上屏
        queryCurrentLine()
    }

    // ─────────────────────────── 事件入口（由 Hook 层喂） ───────────────────────────

    /**
     * 歌词句柄就绪（LyricsLoader.onPtrCaptured 或 buildTimeRangeToLyricsMap
     * Hook 触发）。带陈旧性校验。
     */
    fun onSongInfo(ptr: Any?) {
        if (ptr == null) return
        val key = currentSongKey
        val adamId = NativeLyricsParser.adamId(ptr)
        if (key != null && adamId != null && key != adamId &&
            key.matches(Regex("^\\d+$")) && adamId.matches(Regex("^\\d+$"))
        ) {
            XLog.d("stale ptr ignored: ptr=$adamId current=$key")
            return
        }
        songPtr = ptr
        songPtrKey = key
        ensurePolling()
    }

    /** onPlaybackStateChanged 捕获到播放器控制器实例 */
    fun setController(c: Any?) {
        if (c != null) {
            controller = c
            ensurePolling()
        }
    }

    /** 播放状态变化（仅控制取词请求和轮询频率，不影响显示驱动） */
    fun setPlaying(value: Boolean) {
        playing = value
        if (value) ensurePolling()
    }

    /** 停止播放：清空 ptr（防止 processEvents 在停止后继续推行）+ 重置会话缓存 */
    fun stop() {
        playing = false
        songPtr = null
        songPtrKey = null
        nextLineText = null
        nextLineStart = -1L
        displayedText = null
        LyricsLoader.resetSession()
    }

    /** 初始化（由 AppleMusicHooks 在 Application.attach 后调用） */
    fun init(cl: ClassLoader) {
        classLoader = cl
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private fun ensurePolling() {
        if (polling) return
        polling = true
        mainHandler.post(tick)
    }

    /** LocalMediaPlayerController.getCurrentItem()?.getItem() → PlayerMediaItem */
    private fun readCurrentMediaItem(): Any? {
        val c = controller ?: return null
        val queueItem = ReflectCompat.call(c, "getCurrentItem") ?: return null
        return ReflectCompat.call(queueItem, "getItem")
    }

    private fun readQueueId(): Long {
        val c = controller ?: return 0L
        val queueItem = ReflectCompat.call(c, "getCurrentItem") ?: return 0L
        return ReflectCompat.long(queueItem, "getPlaybackQueueId")
    }

    /**
     * 核心：用 SongInfoTimeProcessor.processEvents 驱动官方歌词引擎。
     *
     * processEvents 是播放界面 Fragment 驱动逐行歌词的官方入口：
     *   processEvents(ptr, positionMs, lineCallback, wordCallback,
     *                 bgWordCallback, prWordCallback, prBgWordCallback): long
     *
     * lineCallback 收到 (Long position, LyricsLineVector line, Long timestamp)，
     * 我们从中提取文本并推到状态栏。返回值 = 距下一事件的毫秒延迟，
     * 用于自适应调度（和播放界面完全相同的调度策略）。
     */
    private fun queryCurrentLine() {
        val ptr = songPtr ?: return
        val c = controller ?: return
        val pos = ReflectCompat.long(c, "getCurrentPosition")
        if (pos < 0L) return
        // 提前量：把喂给引擎的位置往前拨 LEAD_MS，歌词早于实际进度上屏
        val queryPos = pos + LEAD_MS

        runCatching {
            ensureProcessor()
            val tp = timeProcessor ?: run {
                XLog.w("processEvents: timeProcessor null")
                nextDelay = POLL_INTERVAL_MS
                return
            }
            val m = processMethod ?: run {
                XLog.w("processEvents: method null")
                nextDelay = POLL_INTERVAL_MS
                return
            }
            val dcbs = eventCallbacks ?: run {
                XLog.w("processEvents: display callbacks null")
                nextDelay = POLL_INTERVAL_MS
                return
            }
            val pcbs = peekCallbacks ?: run {
                XLog.w("processEvents: peek callbacks null")
                nextDelay = POLL_INTERVAL_MS
                return
            }

            // 1. 主查询：驱动显示当前行（display 回调上屏），并取回下一行绝对位置。
            //    返回值 nextEventPos = 下一歌词事件的绝对位置(ms)（v1.3.6 语义纠正）。
            val nextEventPos = m.invoke(tp, ptr, queryPos, dcbs[0], dcbs[1], dcbs[2], dcbs[3], dcbs[4]) as? Long

            // 2. B 方案：对 nextEventPos 再做一次只读探测，缓存下一行文本 + 时间戳。
            //    探测用独立的静默回调（pcbs）——只承接文本、不上屏，不影响播放器 UI。
            //    我们的 timeProcessor 是独立 new 出来的实例，探测不污染播放器自身引擎。
            if (nextEventPos != null && nextEventPos > queryPos) {
                peekedText = null
                m.invoke(tp, ptr, nextEventPos, pcbs[0], pcbs[1], pcbs[2], pcbs[3], pcbs[4])
                nextLineText = peekedText
                nextLineStart = nextEventPos
            } else {
                nextLineText = null
                nextLineStart = -1L
            }

            // 3. 调度：直接锚定「下一行开始时间 - 提前量」计算下次 tick 间隔。
            //    B 方案已显式持有 nextLineStart（= 下一行真实时间戳），比 (nextEventPos - queryPos)
            //    估算更直白，且在 seek/系统压制导致错过精确 tick 时，下一 tick 会用真实 pos 重新校准。
            nextDelay = when {
                nextEventPos == null -> POLL_INTERVAL_MS
                // 已无后续事件（歌词播完/无歌词）：退回心跳频率，避免 100ms 空转
                nextEventPos <= queryPos -> MAX_DELAY_MS
                else -> {
                    val switchDelay = nextLineStart - LEAD_MS - pos
                    if (switchDelay <= 0L) MIN_DELAY_MS
                    else switchDelay.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
                }
            }
            XLog.d("processEvents nextPos=$nextEventPos delay=$nextDelay nextText=[$nextLineText] nextStart=$nextLineStart (pos=$pos q=$queryPos)")
        }.onFailure {
            XLog.w("processEvents failed: ${it.message}")
            nextDelay = POLL_INTERVAL_MS
        }
    }

    /**
     * 懒初始化 SongInfoTimeProcessor 实例 + 5 个歌词事件回调。
     *
     * 【v1.3.3 关键修正 —— 回调类型以 method 参数为准】
     *  真机实测抛错：processEvents 的第 3 个参数类型是 g.q（SAM 接口本身），
     *  而不是 g.q 的 Kotlin lambda 包装类 lineEventCallback$1（FunctionPointer 子类）。
     *  v1.3.2 曾错误地把 g.q 代理再包进 lineEventCallback$1 才传入，导致类型不匹配、
     *  processEvents 整条反射链路失败 → 后台/无 UI 都没歌词（ticker 一直为 0）。
     *
     *  修正做法（完全不依赖混淆名，且从 method 参数读出真实 SAM）：
     *   1. 直接定位 processEvents（7 参数：ptr, long, 5×callback）；
     *   2. 从它的第 3 个参数类型（parameterTypes[2]）读出真实 SAM 接口 g.q；
     *   3. 用该接口直接 newProxyInstance 出 5 个 g.q 代理，第 0 个（line）抽文本，
     *      其余 no-op；直接作为 5 个回调传给 processEvents —— 与播放界面同一条原生路径。
     */
    private fun ensureProcessor() {
        if (timeProcessor != null && processMethod != null && eventCallbacks != null && peekCallbacks != null) return
        val cl = classLoader ?: run {
            XLog.w("ensureProcessor: classLoader null")
            return
        }

        runCatching {
            val tpClass = cl.loadClass(CLS_TIME_PROCESSOR)
            timeProcessor = tpClass.getDeclaredConstructor().newInstance()
            XLog.i("SongInfoTimeProcessor created")

            // 1. 定位 processEvents（7 参数：ptr, long, 5×callback）
            val m = tpClass.declaredMethods.firstOrNull { meth ->
                meth.name == "processEvents" && meth.parameterCount == 7 &&
                meth.parameterTypes[0].name.endsWith("SongInfoPtr") &&
                meth.parameterTypes[1] == Long::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("processEvents(...)")
            m.isAccessible = true
            processMethod = m

            // 2. 第 3 个参数（index 2）就是真实 SAM 接口 g.q
            val samInterface = m.parameterTypes[2]
            // SAM 业务方法名（忽略 Object 继承方法）
            val samMethod = samInterface.declaredMethods
                .firstOrNull { it.name !in setOf("equals", "hashCode", "toString") }?.name ?: "invoke"
            XLog.d("lyrics callback SAM: ${samInterface.name}#$samMethod")

            // 3. 直接构造两套各 5 个 g.q 代理：
            //    · displayCbs —— 第 0 个（line 回调）抽文本并上屏，同时记 displayedText 去重；
            //    · peekCbs    —— 第 0 个（line 回调）只把文本缓存到 peekedText，不上屏。
            //    processEvents 参数顺序：line, word, bgWord, prWord, prBgWord
            val displayCbs = Array(5) { idx ->
                Proxy.newProxyInstance(samInterface.classLoader, arrayOf(samInterface)) { _, method, args ->
                    if (idx == 0 && method.name == samMethod && args != null && args.size >= 2) {
                        val text = NativeLyricsParser.extractLineText(args[1])
                        XLog.d("cbInvoke ${method.name} n=${args.size} a1=${args[1]?.javaClass?.name} txt=[$text]")
                        if (text != null) {
                            LyricController.onLyricLine(text)
                            displayedText = text
                        }
                    }
                    null
                }
            }
            val peekCbs = Array(5) { idx ->
                Proxy.newProxyInstance(samInterface.classLoader, arrayOf(samInterface)) { _, method, args ->
                    if (idx == 0 && method.name == samMethod && args != null && args.size >= 2) {
                        val text = NativeLyricsParser.extractLineText(args[1])
                        if (text != null) peekedText = text
                    }
                    null
                }
            }
            eventCallbacks = displayCbs
            peekCallbacks = peekCbs
            XLog.i("lyrics callbacks ready (display=${eventCallbacks?.size}, peek=${peekCallbacks?.size})")
            XLog.i("processEvents method ready")

        }.onFailure {
            XLog.e("ensureProcessor failed: ${it.message}", it)
        }
    }
}

/** 反射小工具（与 util.Reflect 相同语义，隔离避免交叉依赖） */
private object ReflectCompat {
    fun call(target: Any?, method: String, vararg args: Any?): Any? {
        if (target == null) return null
        return runCatching {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                val m = cls.declaredMethods.firstOrNull { it.name == method && it.parameterCount == args.size }
                if (m != null) {
                    m.isAccessible = true
                    return@runCatching m.invoke(target, *args)
                }
                cls = cls.superclass
            }
            target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == args.size }
                ?.invoke(target, *args)
        }.getOrNull()
    }

    fun string(target: Any?, method: String): String? =
        call(target, method) as? String

    fun long(target: Any?, method: String): Long =
        when (val v = call(target, method)) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> 0L
        }
}
