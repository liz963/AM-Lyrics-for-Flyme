package com.amlyric.flyme.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.LyricsInjector
import com.amlyric.flyme.hook.NativeLyricsParser
import com.amlyric.flyme.util.Reflect

/**
 * 歌词调度器（编排层）。
 *
 * ## 职责边界
 * 本类只负责「什么时候读位置、什么时候驱动引擎、下一跳隔多久」的编排。
 * 具体策略与实现下沉到两个协作组件，便于各自独立推理与修改：
 *  · [LyricGate]          —— 前奏期闸门（纯状态机，只返回决策，不做外部动作）；
 *  · [LyricsEngineDriver] —— 官方引擎的反射驱动（processEvents 与回调代理）。
 *
 * ## 展示规则（v1.4.0 用户明确要求）
 *  · **前奏期不推送任何内容**：从歌曲开头播放时，状态栏一直空白；
 *  · 到「第一句歌词 − [LEAD_MS]」这一拍，放开闸门、第一句准时上屏；
 *  · 中途起播 / 拖进度条：不做任何等待，歌词立刻跟上；
 *  · 无歌词的歌曲：状态栏保持空白（不再推歌名、不再弹「暂无歌词」占位）。
 *
 * ## 驱动机制：用官方引擎 processEvents 逐行求值
 * `processEvents(ptr, positionMs, 5×callback)` 是播放界面 Fragment 驱动逐行歌词的官方入口：
 * 行事件 → 提取文本 → 上屏；**返回值是「下一歌词事件的绝对位置(ms)」**（v1.3.6 语义纠正）。
 * 所以调度不靠估算，而是锚定「下一事件位置 − [LEAD_MS]」计算下一跳；
 * 同时保留 [MAX_DELAY_MS] 作为后台保活心跳（原因见该常量注释）。
 *
 * ## 提前量 LEAD_MS
 * 喂给引擎的位置 = 实际播放位置 + [LEAD_MS]（v1.3.10 起 1000ms），
 * 使歌词早于实际人声约 1 秒出现，抵消状态栏 ticker 的渲染/合成延迟。
 * 同时它就是前奏期的放开基准（第一句前 1 秒）。
 *
 * ## ★ 两条上屏通路，必须共用同一把闸门（改上屏逻辑前必读）
 * 1. **本类的驱动**：display 回调收到行事件 —— 始终在位；
 * 2. **前台 Hook**：`AppleMusicHooks` 的 `lineEventCallback` —— 播放界面打开时引擎自己回调。
 *
 * v1.3.12/v1.3.13 只堵了 ①，② 无条件上屏，于是前奏期第一句把刚推的歌名顶掉，
 * 用户看到的就是"歌名一闪而过"。现两条通路都经 [LyricGate.isHolding] 判断
 * （② 走 [onForegroundLine]）。**任何新增的上屏/抑制逻辑都要两边同时考虑。**
 *
 * ## 线程模型
 * 轮询跑在**宿主主线程**（与播放界面 Fragment 的 Handler 同线程；processEvents 内部会调
 * 原生实现，需同线程防并发）。Hook 回调可能来自其它线程，故跨线程状态一律 `@Volatile`。
 */
object BackgroundLyrics {

    // ═══════════════════════════ 调度参数 ═══════════════════════════

    /** 兜底轮询间隔（引擎未就绪 / 无后续事件时） */
    private const val POLL_INTERVAL_MS = 500L

    /** 非播放态轮询间隔（低频保活，恢复播放立即提速） */
    private const val IDLE_INTERVAL_MS = 2000L

    /** 下一跳间隔下限（防 0/负值导致 busy-loop） */
    private const val MIN_DELAY_MS = 100L

    /**
     * 下一跳间隔上限 = 后台保活心跳。
     *
     * 【为什么必须有上限（v1.3.4 教训）】
     *  v1.3.4 曾把上限放到 60s，结果后台歌词**完全停更**：
     *  主线程一次挂十几秒无消息，进程被降优、延时消息被系统推后；
     *  这期间既不读 position 也不感知切歌/seek，一旦发生就卡死。
     *  上限的真正作用是"心跳"——保证后台每 5s 至少醒一次、重读 position 并重新驱动引擎。
     *
     * 【为什么不会造成滞后】行间隔 > 5s 时会被截成提前唤醒，但下一跳会用新的 position
     *  重新算出真实剩余，最后一跳必然 < 5s 且为精确值，触发误差 < 100ms。
     */
    private const val MAX_DELAY_MS = 5000L

    /**
     * 歌词提前量(ms)：喂给引擎的位置 = 实际位置 + LEAD_MS。
     * 相当于把歌词时间轴整体前拨，观感上"歌词先到、人声后到"。
     * v1.3.10 起由 1200ms 回调到 1000ms（提前约 1 秒）。
     * 同时被 [LyricGate] 用作前奏期的放开基准（第一句前 1 秒放开）。
     */
    private const val LEAD_MS = 1000L

    /** 形如纯数字的歌曲标识（storeId / adamId）——用于陈旧句柄校验 */
    private val DIGITS_ONLY = Regex("^\\d+$")

    /**
     * 「原生歌词缺失」的判定宽限期(ms)。
     *
     * 刚切歌时宿主自己的取词还在路上，句柄必然还没到；不等一会儿就断言"缺失"，
     * 会给每一首歌都白跑一次在线请求——而这与用户要求相悖（只在确实缺时才请求）。
     * 宿主正常 1~2 秒内就能拿到句柄，3 秒足够且不会让用户觉得"歌词怎么不来了"。
     */
    private const val ONLINE_GRACE_MS = 3000L

    // ═══════════════════════ 会话 / 播放器状态 ═══════════════════════

    /** 播放器控制器（LocalMediaPlayerController，由 onPlaybackStateChanged 捕获） */
    @Volatile private var controller: Any? = null

    /** 仅用于控制「是否发起取词请求」与轮询频率；显示驱动不依赖它 */
    @Volatile private var playing = false

    /** 当前歌曲 key（来自 getCurrentItem，无 UI 也有效） */
    @Volatile private var currentSongKey: String? = null

    /** 当前歌开始的时间戳（elapsedRealtime），用于 [ONLINE_GRACE_MS] 宽限判定 */
    @Volatile private var songStartedAt: Long = 0L

    /**
     * 已经做过一次「该不该在线补全」判定的句柄（按对象身份去重）。
     * 这条判定要反射读 timing / language / translation，每 tick 都做没必要；
     * 同一首歌的句柄不变时只判一次即可。
     */
    @Volatile private var completionEvaluatedPtr: Any? = null

    /**
     * 已经做过判定的"无句柄"歌曲 key（见 [requestOnlineCompletion]）。
     *
     * 【为什么光有 [completionEvaluatedPtr] 不够】宿主确实没有歌词的歌（尤其伴奏轨）
     * **永远不会有句柄**，于是每 tick 都会重新走一遍判定与日志 —— 真机实测每秒一条
     * 「不补 —— 伴奏/纯音乐轨」。按歌曲 key 去重后，每首歌只判一次。
     */
    @Volatile private var completionEvaluatedSong: String? = null

    /** 当前歌曲的歌词句柄（强引用持有，保证原生 shared_ptr 不被释放） */
    @Volatile private var songPtr: Any? = null

    /**
     * 引擎报出的下一个事件位置(ms)，由主查询的返回值提供；-1 = 未知。
     *
     * ⚠️ 它**不是「下一行开始时间」**——实测该返回值可以是**行内字级事件**
     * （`nextEventPos - queryPos` 只有 2~900ms，且 `peek` 返回 null）。
     * 因此只允许用作**唤醒节奏的锚点**，绝不能当作语义时间使用。
     * （首句时间用的是同一个返回值，但只在"前奏期"这个特定窗口采信，见 [LyricGate.noteFirstLineTime]）
     */
    @Volatile private var nextEventAt: Long = -1L

    // ═══════════════════════════ 协作组件 ═══════════════════════════

    /** 前奏期闸门 */
    private val gate = LyricGate(LEAD_MS)

    /** 官方引擎反射驱动；由 [init] 按宿主 ClassLoader 创建 */
    @Volatile private var engine: LyricsEngineDriver? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var polling = false

    /** 下一跳间隔；每 tick 由 [driveEngine] 重算 */
    @Volatile private var nextDelay: Long = POLL_INTERVAL_MS

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            runCatching { onTick() }.onFailure { XLog.d("tick error: ${it.message}") }
            val delay = if (playing) nextDelay else IDLE_INTERVAL_MS
            mainHandler.postDelayed(this, delay.coerceAtLeast(50L))
        }
    }

    // ═════════════════════════ 对外事件入口 ═════════════════════════

    /** 初始化（由 AppleMusicHooks 在 Application.attach 后调用） */
    fun init(cl: ClassLoader) {
        engine = LyricsEngineDriver(cl, ::onEngineLine)
    }

    /**
     * 诊断用：让官方引擎在 [posMs] 处求值并返回该处的行文本。
     *
     * 走的是 [LyricsEngineDriver.peek] 的**静默回调**——只承文本、不上屏，
     * 也不会碰到播放页那个引擎实例，所以可以随时调用做链路自检。
     */
    fun probeLineAt(ptr: Any, posMs: Long): String? {
        val drv = engine ?: return null
        drv.ensureReady()
        if (!drv.isReady) return null
        return runCatching { drv.peek(ptr, posMs) }.getOrNull()
    }

    /**
     * 歌词句柄就绪（`LyricsLoader.onPtrCaptured` 或 `buildTimeRangeToLyricsMap` Hook 触发）。
     * 带陈旧性校验：切歌瞬间可能拿到上一首的句柄（实测偶发），此时直接丢弃。
     */
    fun onSongInfo(ptr: Any?) {
        if (ptr == null) return
        val key = currentSongKey
        val adamId = NativeLyricsParser.adamId(ptr)
        if (key != null && adamId != null && key != adamId &&
            key.matches(DIGITS_ONLY) && adamId.matches(DIGITS_ONLY)
        ) {
            XLog.d("stale ptr ignored: ptr=$adamId current=$key")
            return
        }
        // ★ 这首歌若已有"在线补全"结果，就让它胜出。
        // 宿主会在我们补完后再次回传它自己那份（行级）歌词；不拦的话状态栏会退回原样。
        songPtr = LyricsInjector.preferredPtr(key, ptr)
        ensurePolling()
    }

    /** 捕获到播放器控制器实例（onPlaybackStateChanged Hook） */
    fun setController(c: Any?) {
        if (c != null) {
            controller = c
            ensurePolling()
        }
    }

    /** 播放状态变化（仅控制取词请求与轮询频率，不影响显示驱动） */
    fun setPlaying(value: Boolean) {
        // ★ 宽限期从"真的开始播"起算，而不是从"切歌"起算。
        // 真机实测（v1.4.0）：启动时宿主会先恢复上次队列、切歌事件在第 1 秒就到了，
        // 但真正开播要等十几秒；若按切歌起算，一开播宽限期就已满足，
        // 于是"宿主还没取到歌词"被误判成"原生歌词缺失"，白白发了一次请求。
        if (value && !playing) songStartedAt = SystemClock.elapsedRealtime()
        playing = value
        if (value) ensurePolling()
    }

    /** 停止播放：清空句柄（防 processEvents 在停止后继续推行）并复位会话状态 */
    fun stop() {
        playing = false
        songPtr = null
        nextEventAt = -1L
        completionEvaluatedPtr = null
        completionEvaluatedSong = null
        gate.reset()
        LyricsLoader.resetSession()
    }

    /**
     * 前台逐行回调的统一闸门，由 `AppleMusicHooks` 的
     * `SongInfoTimeProcessor.lineEventCallback.call` Hook 调用。
     *
     * 【为什么必须走这里】引擎在播放界面打开时会**自己**回调当前行，前奏期它会把
     * 「第一句」当活动行回传 —— 不经过闸门就会在前奏期提前把歌词顶上状态栏。
     * 详见类注释「两条上屏通路」。
     *
     * 【顺带捕获首句文本】这条路径拿到的文本最可靠（后台探测偶发给 null，真机见"泡沫"案例），
     * 记下来供放开闸门时补推，避免第一句被永久吞掉。
     *
     * @return true = 应抑制本次上屏；false = 正常放行
     */
    fun onForegroundLine(text: String?): Boolean {
        if (!gate.isHolding) return false
        // 占位/版权行不算"第一句"：宿主在歌词没就绪时会回传 `歌曲名 - 歌手`，
        // 把它记成首句会让前奏抑制提前放开（真机实测：日文歌在 1 秒处回传占位行，
        // 闸门立刻放开，状态栏空等 20 秒才等到真正的第一句）。
        if (LyricController.isNonLyricLine(text)) return true
        if (gate.noteLineText(text)) XLog.d("first line text captured (fg): [$text]")
        return true
    }

    // ═════════════════════════ 轮询主循环 ═════════════════════════

    /**
     * 一个 tick 的全部工作，顺序固定：
     *  ① 读位置（本 tick 只读一次，供后续各判断复用）
     *  ② 感知歌曲 / 重播 → 武装前奏抑制 → 取歌词句柄
     *  ③ 闸门裁决（放开前奏 / 补推第一句）
     *  ④ 驱动引擎并算出下一跳间隔
     */
    private fun onTick() {
        // 位置读不到（反射失败 / 播放器实例被回收）→ 本 tick 什么也不做。
        // ★ 不能用 0 兜底：0 与「真的回到开头」不可区分，会被 notePosition 误判为
        // 「重播 / 拖回开头」而反复复位闸门（v1.3.16 修复）。
        val posMs = readPositionMs() ?: return

        readCurrentMediaItem()?.let { onMediaItem(it, posMs) }

        applyGate(posMs)
        driveEngine(posMs)
    }

    /** 当前媒体项处理 */
    private fun onMediaItem(item: Any, posMs: Long) {
        val storeId = Reflect.string(item, "getPlaybackStoreId")
        val key = storeId ?: "p${Reflect.long(item, "getPersistentId")}"

        if (key != currentSongKey) {
            currentSongKey = key
            songStartedAt = SystemClock.elapsedRealtime()
            completionEvaluatedPtr = null
            completionEvaluatedSong = null
            songPtr = null
            nextEventAt = -1L
            gate.reset()
            LyricController.onSongChanged(key)
            XLog.d("song key -> $key (storeId=$storeId)")
        }

        // 重播同一首 / 拖回开头时歌曲 key 不变，须单独识别并重新武装前奏抑制
        if (gate.notePosition(posMs)) {
            XLog.d("restart to head detected (pos=$posMs): prelude gate re-armed")
        }
        if (gate.armIfAtStart(posMs)) {
            XLog.d("prelude gate armed at pos=$posMs: no output until first line - ${LEAD_MS}ms")
        }

        // 让控制器知道"当前歌是谁"：它要用歌名+歌手挡掉宿主回传的
        // `歌曲名 - 歌手` 占位行（真机实测：歌词没就绪时宿主就是这么回传的）。
        // 每个 tick 都同步一次，因为切歌那一刻 item 里的字段可能还没填好。
        LyricController.onSongMeta(
            Reflect.string(item, "getTitle"),
            Reflect.string(item, "getArtistName"),
        )

        acquireLyricsHandle(item, key, storeId)
    }

    /** 当前歌没有句柄：先查会话缓存，再主动取词 */
    private fun acquireLyricsHandle(item: Any, key: String, storeId: String?) {
        if (songPtr != null) {
            // ★ 有句柄也可能"不合格"：非逐字时间轴、或外语逐字却没有翻译轨。
            // 这两种正是用户要求补全的对象，且**不需要等待**——判据就在句柄里。
            // 补全任务由 [LyricsInjector] 去重与执行，取好后经 onSongInfo 顶掉当前句柄。
            requestOnlineCompletion(item, storeId, songPtr)
            return
        }

        val cached = LyricsLoader.cachedPtr(storeId)
        if (cached != null) {
            songPtr = cached
            XLog.d("ptr restored from cache: $key")
            return
        }
        if (playing && storeId != null && storeId.matches(DIGITS_ONLY)) {
            LyricsLoader.requestLyrics(storeId, readQueueId(), Reflect.string(item, "getTitle"))
        }

        // ★ 兜底：等了 [ONLINE_GRACE_MS] 宿主还没给句柄，就按"原生歌词缺失"处理，自己去找。
        // 之所以要等：宿主自己取词是异步的，刚切歌那一刻句柄必然还没到，
        // 不等就会给每首歌都白跑一次请求——而用户明确要求只在"确实缺"的时候才请求。
        if (playing && elapsedSinceSongStart() >= ONLINE_GRACE_MS) {
            requestOnlineCompletion(item, storeId, null)
        }
    }

    /**
     * 请求在线补全（是否需要 / 是否允许联网由 [LyricsInjector] 统一裁决）。
     *
     * 这里刻意**不接收返回值**：补全一般要几百毫秒，本 tick 用不上；
     * 结果由注入器回主线程调 [onSongInfo] 落地，与"宿主自己捕获到 ptr"走完全相同的后续流程。
     *
     * ⚠️ 两个维度都要去重，否则判定会被每 tick 重复执行（日志刷屏、白反射）：
     *  · 有句柄 → 按**句柄身份**（宿主重新解析会换句柄，那时该重判）；
     *  · 无句柄 → 按**歌曲 key**（这类歌永远等不到句柄，只判一次）。
     * 歌名还没读出来时直接返回，不记账 —— 否则会把"信息不全"错当成"已判定过"。
     */
    private fun requestOnlineCompletion(item: Any, storeId: String?, nativePtr: Any?) {
        val title = Reflect.string(item, "getTitle")
        if (title.isNullOrBlank()) return

        if (nativePtr != null) {
            // 同一句柄只判一次（判定要反射读 timing/language/translation，没必要每 tick 重复）
            if (nativePtr === completionEvaluatedPtr) return
            completionEvaluatedPtr = nativePtr
        } else {
            val key = currentSongKey
            if (key == null || key == completionEvaluatedSong) return
            completionEvaluatedSong = key
        }

        LyricsInjector.completionFor(
            storeId = storeId,
            title = title,
            artist = Reflect.string(item, "getArtistName"),
            album = Reflect.string(item, "getCollectionName"),
            durationMs = Reflect.long(item, "getPlaybackDuration"),
            nativePtr = nativePtr,
        )
    }

    /**
     * 闸门裁决：前奏期是否放开、放开时补推第一句。
     *
     * 必须放在 [onTick] 而不是 [driveEngine] 里 —— 无歌词的歌曲**连句柄都没有**，
     * [driveEngine] 会在拿到 `songPtr` 时立刻 return，永远走不到放开分支（v1.3.15 的教训）。
     */
    private fun applyGate(posMs: Long) {
        when (val decision = gate.evaluate(posMs)) {
            is LyricGate.Decision.Release -> {
                // 引擎对首句只回传一次且常在开头提前回传，抑制期间被吞掉后不会二次回传，
                // 故放开时手动补推一次，否则第一句会被永久吞掉（v1.3.13）
                val text = decision.text
                if (!text.isNullOrBlank()) {
                    LyricController.onLyricLine(text)
                    XLog.d("first line pushed on release: [$text]")
                }
                XLog.d(
                    "prelude gate released at pos=$posMs " +
                        "(firstLineMs=${decision.firstLineAtMs} bailOut=${decision.bailedOut})"
                )
            }

            LyricGate.Decision.None -> Unit
        }
    }

    /**
     * 驱动官方引擎求值（含只读探测），并按结果算出下一跳间隔。
     *
     * ⚠️ `processEvents` 的返回值是**下一事件位置**，**不等于「下一行开始时间」**
     * （行内字级事件也会被报出来，实测提前量可小到 2ms）。所以它在这里只有两个用途：
     * 算出唤醒节奏、给前奏期的首句时间提供**候选**（候选只在抑制窗口采信）。
     */
    private fun driveEngine(posMs: Long) {
        val ptr = songPtr ?: return

        val drv = engine ?: run {
            XLog.w("driveEngine: engine not initialized")
            nextDelay = POLL_INTERVAL_MS
            return
        }
        drv.ensureReady()
        if (!drv.isReady) {
            XLog.w("driveEngine: engine not ready")
            nextDelay = POLL_INTERVAL_MS
            return
        }

        val queryPos = posMs + LEAD_MS
        runCatching {
            // ① 主查询：行事件经 display 回调上屏（抑制期由 onEngineLine 拦下），
            //    返回值 = 下一事件绝对位置(ms)
            val nextEventPos = drv.drive(ptr, queryPos)
            val nextEventIn = nextEventPos?.takeIf { it > queryPos }

            // ② 只读探测：取该位置的文本（仅用于日志与首句候选，不做语义假设）
            val nextText = if (nextEventIn != null) drv.peek(ptr, nextEventIn) else null
            nextEventAt = nextEventIn ?: -1L

            if (nextEventIn != null) {
                // ③ 前奏期首次拿到下一事件 → 它就是第一句起点
                //    （前奏期还没进入第一句内部，不存在行内字级事件干扰）
                if (gate.noteFirstLineTime(nextEventIn, nextText)) {
                    XLog.d("first line time captured: $nextEventIn text=[$nextText]")
                }
            }

            nextDelay = nextDelayFor(nextEventPos, queryPos, posMs)
            XLog.d(
                "engine nextPos=$nextEventPos gap=${nextEventPos?.let { it - queryPos }} " +
                    "delay=$nextDelay nextText=[$nextText] (pos=$posMs q=$queryPos)"
            )
        }.onFailure {
            XLog.w("engine drive failed: ${it.message}")
            nextDelay = POLL_INTERVAL_MS
        }
    }

    /**
     * 下一跳间隔。
     *  · 抑制中且已知首句时间：密轮询（≤ [POLL_INTERVAL_MS]）贴近放开点，
     *    否则会被 5s 心跳拖到过迟才放开；
     *  · 无后续事件（歌词播完 / 无歌词）：退回心跳频率，避免空转；
     *  · 其余：锚定「下一事件位置 − [LEAD_MS]」。
     *
     * seek / 系统压制导致错过精确 tick 时，下一 tick 会用真实 position 重新校准。
     */
    private fun nextDelayFor(nextEventPos: Long?, queryPos: Long, posMs: Long): Long {
        val pollTarget = gate.pollTargetMs
        if (pollTarget > 0) {
            return (pollTarget - posMs).coerceIn(MIN_DELAY_MS, POLL_INTERVAL_MS)
        }
        return when {
            nextEventPos == null -> POLL_INTERVAL_MS
            nextEventPos <= queryPos -> MAX_DELAY_MS
            else -> {
                val switchDelay = nextEventAt - LEAD_MS - posMs
                if (switchDelay <= 0L) MIN_DELAY_MS
                else switchDelay.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            }
        }
    }

    // ═══════════════════ 引擎回调（后台驱动路径的行事件） ═══════════════════

    /** 按前奏闸门决定上屏 / 抑制 */
    private fun onEngineLine(text: String?) {
        if (text == null) return

        if (gate.isHolding) {
            // 抑制中不上屏；顺手把首个非空文本记为首句
            // （前台路径未触发时的兜底，例如后台播放 / 播放界面没打开）
            // 占位/版权行同样不算首句（见 [LyricController.isNonLyricLine]）
            if (!LyricController.isNonLyricLine(text) && gate.noteLineText(text)) {
                XLog.d("first line text captured (bg): [$text]")
            }
            return
        }
        LyricController.onLyricLine(text)
    }

    // ═════════════════════════ 内部工具 ═════════════════════════

    private fun ensurePolling() {
        if (polling) return
        polling = true
        mainHandler.post(tick)
    }

    /** `LocalMediaPlayerController.getCurrentItem()?.getItem()` → PlayerMediaItem */
    private fun readCurrentMediaItem(): Any? {
        val queueItem = Reflect.call(controller, "getCurrentItem") ?: return null
        return Reflect.call(queueItem, "getItem")
    }

    /** 当前播放位置(ms)；读取失败返回 null（与「真的取到 0」区分开） */
    private fun readPositionMs(): Long? =
        (Reflect.call(controller, "getCurrentPosition") as? Number)?.toLong()

    private fun readQueueId(): Long {
        val queueItem = Reflect.call(controller, "getCurrentItem") ?: return 0L
        return Reflect.long(queueItem, "getPlaybackQueueId")
    }

    /**
     * 当前歌已经播了多久（毫秒）。
     * 未感知到歌曲时返回 0 → 宽限期不满足 → **不发请求**（宁可漏补也不乱补）。
     */
    private fun elapsedSinceSongStart(): Long = songStartedAt.let {
        if (it == 0L) 0L else SystemClock.elapsedRealtime() - it
    }
}
