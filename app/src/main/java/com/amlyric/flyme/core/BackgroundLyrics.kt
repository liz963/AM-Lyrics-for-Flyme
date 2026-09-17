package com.amlyric.flyme.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.NativeLyricsParser
import com.amlyric.flyme.util.Reflect

/**
 * 歌词调度器（编排层）。
 *
 * ## 职责边界
 * 本类只负责「什么时候读位置、什么时候驱动引擎、下一跳隔多久」的编排。
 * 具体策略与实现下沉到两个协作组件，便于各自独立推理与修改：
 *  · [SongTitleGate]      —— 歌名展示闸门（歌名锁定 / 无歌词静默），纯状态机，不做外部动作；
 *  · [LyricsEngineDriver] —— 官方引擎的反射驱动（processEvents 与回调代理）。
 *
 * ## 驱动机制：用官方引擎 processEvents 逐行求值
 * `processEvents(ptr, positionMs, 5×callback)` 是播放界面 Fragment 驱动逐行歌词的官方入口：
 * 行事件 → 提取文本 → 上屏；**返回值是「下一歌词事件的绝对位置(ms)」**（v1.3.6 语义纠正）。
 * 所以调度不靠估算，而是锚定「下一行开始时间 − [LEAD_MS]」计算下一跳；
 * 同时保留 [MAX_DELAY_MS] 作为后台保活心跳（原因见该常量注释）。
 *
 * ## 提前量 LEAD_MS
 * 喂给引擎的位置 = 实际播放位置 + [LEAD_MS]（v1.3.10 起 1000ms），
 * 使歌词早于实际人声约 1 秒出现，抵消状态栏 ticker 的渲染/合成延迟。
 *
 * ## ★ 两条上屏通路，必须共用同一把闸门（改上屏逻辑前必读）
 * 1. **本类的驱动**：display 回调收到行事件 —— 始终在位；
 * 2. **前台 Hook**：`AppleMusicHooks` 的 `lineEventCallback` —— 播放界面打开时引擎自己回调。
 *
 * v1.3.12/v1.3.13 只堵了 ①，② 无条件上屏，于是前奏期第一句把刚推的歌名顶掉，
 * 用户看到的就是"歌名一闪而过"。现两条通路都经 [SongTitleGate.blocksLyricLines] 判断
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
     * 同时被 [SongTitleGate] 用作歌名锁定的释放基准（第一句前 1 秒释放）。
     */
    private const val LEAD_MS = 1000L

    /** 形如纯数字的歌曲标识（storeId / adamId）——用于陈旧句柄校验 */
    private val DIGITS_ONLY = Regex("^\\d+$")

    // ═══════════════════════ 会话 / 播放器状态 ═══════════════════════

    /** 播放器控制器（LocalMediaPlayerController，由 onPlaybackStateChanged 捕获） */
    @Volatile private var controller: Any? = null

    /** 仅用于控制「是否发起取词请求」与「是否推歌名」；显示驱动不依赖它 */
    @Volatile private var playing = false

    /** 当前歌曲 key（来自 getCurrentItem，无 UI 也有效） */
    @Volatile private var currentSongKey: String? = null

    /** 当前歌曲的歌词句柄（强引用持有，保证原生 shared_ptr 不被释放） */
    @Volatile private var songPtr: Any? = null

    // ═══════════════════ B 方案：下一行缓存（时间戳精准调度） ═══════════════════

    /**
     * 引擎报出的下一个事件位置(ms)，由主查询的返回值提供；-1 = 未知。
     *
     * ⚠️ 它**不是「下一行开始时间」**——实测该返回值可以是**行内字级事件**
     * （`nextEventPos - queryPos` 只有 2~900ms，且 `peek` 返回 null）。
     * 因此只允许用作**唤醒节奏的锚点**，绝不能当作语义时间使用；
     * 首句时间必须另加护栏（见 [SongTitleGate.MIN_TITLE_MS] 与 [noteFirstLineTime]）。
     */
    @Volatile private var nextEventAt: Long = -1L

    // ═══════════════════════════ 协作组件 ═══════════════════════════

    /** 歌名展示闸门（歌名锁定 / 无歌词静默） */
    private val titleGate = SongTitleGate(LEAD_MS)

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
        songPtr = ptr
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
        playing = value
        if (value) ensurePolling()
    }

    /** 停止播放：清空句柄（防 processEvents 在停止后继续推行）并复位会话状态 */
    fun stop() {
        playing = false
        songPtr = null
        nextEventAt = -1L
        titleGate.reset()
        LyricsLoader.resetSession()
    }

    /** 歌名是否正处于锁定展示中（供 LyricController 抑制「暂无歌词」占位，保持歌名） */
    fun isTitleHolding(): Boolean = titleGate.isHolding

    /**
     * 前台逐行回调的统一闸门，由 `AppleMusicHooks` 的
     * `SongInfoTimeProcessor.lineEventCallback.call` Hook 调用。
     *
     * 【为什么必须走这里】引擎在播放界面打开时会**自己**回调当前行，前奏期它会把
     * 「第一句」当活动行回传 —— 不经过闸门就会顶掉刚推的歌名（"一闪而过"）。
     * 详见类注释「两条上屏通路」。
     *
     * 【顺带捕获首句文本】这条路径拿到的文本最可靠（后台探测偶发给 null，真机见"泡沫"案例）。
     * 锁定期间把首个非空文本记为首句，供释放锁定时补推，避免第一句被永久吞掉。
     *
     * @return true = 应抑制本次上屏；false = 正常放行
     */
    fun onForegroundLine(text: String?): Boolean {
        if (!titleGate.blocksLyricLines) return false
        if (titleGate.noteFirstLineText(text)) XLog.d("first line text captured (fg): [$text]")
        return true
    }

    // ═════════════════════════ 轮询主循环 ═════════════════════════

    /**
     * 一个 tick 的全部工作，顺序固定：
     *  ① 读位置（本 tick 只读一次，供后续各判断复用）
     *  ② 感知歌曲 → 检测回到开头 → 推歌名 → 取歌词句柄
     *  ③ 歌名闸门裁决（释放锁定 / 无歌词兜底）
     *  ④ 驱动引擎并算出下一跳间隔
     */
    private fun onTick() {
        // 位置读不到（反射失败 / 播放器实例被回收）→ 本 tick 什么也不做。
        // ★ 不能用 0 兜底：0 与「真的回到开头」不可区分，会被 notePosition 误判为
        // 「重播 / 拖回开头」而清掉歌名锁定，表现为歌名闪一下又没了（v1.3.16 修复）。
        val posMs = readPositionMs() ?: return

        readCurrentMediaItem()?.let { onMediaItem(it, posMs) }

        applyTitleGate(posMs)
        keepHoldResponsive()
        driveEngine(posMs)
    }

    /** 当前媒体项处理 */
    private fun onMediaItem(item: Any, posMs: Long) {
        val storeId = Reflect.string(item, "getPlaybackStoreId")
        val key = storeId ?: "p${Reflect.long(item, "getPersistentId")}"

        if (key != currentSongKey) {
            currentSongKey = key
            songPtr = null
            nextEventAt = -1L
            titleGate.reset()
            LyricController.onSongChanged(key)
            XLog.d("song key -> $key (storeId=$storeId)")
        }

        // 重播同一首 / 拖回开头时歌曲 key 不变，须单独识别并重新武装歌名推送
        if (titleGate.notePosition(posMs)) {
            XLog.d("restart to head detected (pos=$posMs): title re-armed")
        }

        pushSongTitleIfDue(item, posMs)
        acquireLyricsHandle(item, key, storeId)
    }

    /**
     * 播放最开头时推一次「歌曲名 - 歌手名」。
     *
     * 判定用**位置**而非事件，天然覆盖「拖动进度条 / 跳播 / 中段续播」等非开头场景
     * （这些情形 pos 明显大于阈值，直接走歌词、不推歌名）。
     * 不依赖歌词句柄，故可先于取词执行，避免歌词未加载时错过开头窗口。
     */
    private fun pushSongTitleIfDue(item: Any, posMs: Long) {
        if (!playing) return
        if (!titleGate.canPushTitle(posMs)) return

        val title = Reflect.string(item, "getTitle")
        if (title.isNullOrBlank()) return
        // 歌手名：真实 PlayerMediaItem 优先 getArtistName，拿不到再依次退而求其次
        val artist = Reflect.string(item, "getArtistName")
            ?: Reflect.string(item, "getArtist")
            ?: Reflect.string(item, "getAlbumArtist")
        val meta = if (artist.isNullOrBlank()) title else "$title - $artist"

        LyricController.onSongMeta(meta)
        titleGate.onTitlePushed(posMs, SystemClock.elapsedRealtime())
        XLog.d("song meta pushed at pos=$posMs: $meta (titleHold on)")
    }

    /** 当前歌没有句柄：先查会话缓存，再主动取词 */
    private fun acquireLyricsHandle(item: Any, key: String, storeId: String?) {
        if (songPtr != null) return

        val cached = LyricsLoader.cachedPtr(storeId)
        if (cached != null) {
            songPtr = cached
            XLog.d("ptr restored from cache: $key")
            return
        }
        if (playing && storeId != null && storeId.matches(DIGITS_ONLY)) {
            LyricsLoader.requestLyrics(storeId, readQueueId(), Reflect.string(item, "getTitle"))
        }
    }

    /**
     * 歌名闸门裁决（释放锁定 / 无歌词兜底）。
     *
     * 必须放在 [onTick] 而不是 [driveEngine] 里 —— 无歌词的歌曲**连句柄都没有**，
     * [driveEngine] 会在拿到 `songPtr` 时立刻 return，永远走不到释放分支，
     * 结果歌名会卡住整曲（v1.3.15 修复的正是这个）。
     */
    private fun applyTitleGate(posMs: Long) {
        when (val decision = titleGate.evaluate(posMs, SystemClock.elapsedRealtime(), playing)) {
            is SongTitleGate.Decision.Release -> {
                val text = decision.firstLineText
                // 引擎对首句只回传一次且常在开头提前回传，锁定期间被抑制后不会二次回传，
                // 故释放时手动补推一次，否则第一句会被永久吞掉（v1.3.13）
                if (!text.isNullOrBlank()) {
                    LyricController.onLyricLine(text)
                    XLog.d("first line re-pushed on release: [$text]")
                }
                val guard = decision.releaseAtMs - (decision.firstLineAtMs - LEAD_MS)
                XLog.d(
                    "title hold released at pos=$posMs (firstLineMs=${decision.firstLineAtMs} " +
                        "releaseAt=${decision.releaseAtMs} guard=${if (guard > 0) "+$guard" else "off"})"
                )
            }

            SongTitleGate.Decision.NoLyrics -> {
                XLog.d("no lyrics: hold timed out -> clear ticker & stop pushing")
                LyricController.onSilence()
            }

            SongTitleGate.Decision.None -> Unit
        }
    }

    /**
     * 无歌词的歌曲**连句柄都没有**，[driveEngine] 在第一行就 return，永远不会设置 nextDelay，
     * 于是「无歌词兜底」要等上一次遗留的 5s 心跳才被评估。这里显式压低。
     * （有句柄时由 [nextDelayFor] 按 [SongTitleGate.pollTargetMs] 自行压低，无需在此处理。）
     */
    private fun keepHoldResponsive() {
        if (songPtr == null && titleGate.isHolding) nextDelay = POLL_INTERVAL_MS
    }

    /**
     * 驱动官方引擎求值（含只读探测），并按结果算出下一跳间隔。
     *
     * ⚠️ `processEvents` 的返回值是**下一事件位置**，**不等于「下一行开始时间」**
     * （行内字级事件也会被报出来，实测提前量可小到 2ms）。所以它在这里只有两个用途：
     * 算出唤醒节奏、给首句时间提供**候选**（候选本身另有护栏，见 [SongTitleGate]）。
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
            // ① 主查询：行事件经 display 回调上屏，返回值 = 下一事件绝对位置(ms)
            val nextEventPos = drv.drive(ptr, queryPos)
            val nextEventIn = nextEventPos?.takeIf { it > queryPos }

            // ② 只读探测：取该位置的文本（仅用于日志与首句候选，不做语义假设）
            val nextText = if (nextEventIn != null) drv.peek(ptr, nextEventIn) else null
            nextEventAt = nextEventIn ?: -1L

            if (nextEventIn != null) {
                // ③ 锁定中首次拿到下一事件 → 记为首句时间候选。
                //    prelude 期引擎会把第一句当活动行报出，那个候选就是第一句起点；
                //    若句柄迟到（已进第一句内部）候选会退化成字级事件，此时由
                //    SongTitleGate 的最短展示护栏兜底，不会闪。
                if (titleGate.noteFirstLineTime(nextEventIn, nextText)) {
                    XLog.d("first line time captured: $nextEventIn text=[$nextText]")
                }

                // ④ 静默期间引擎又报出事件 → 歌词其实存在（只是加载慢），解除静默
                if (titleGate.isSilent) {
                    titleGate.clearSilence()
                    XLog.d("no-lyrics silence lifted: engine reports event at $nextEventIn")
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
     *  · 歌名锁定中：密轮询（≤ [POLL_INTERVAL_MS]）贴近释放点，
     *    否则会被 5s 心跳拖到过迟才放开歌名；
     *  · 无后续事件（歌词播完 / 无歌词）：退回心跳频率，避免空转；
     *  · 其余：锚定「下一事件位置 − [LEAD_MS]」。
     *
     * seek / 系统压制导致错过精确 tick 时，下一 tick 会用真实 position 重新校准。
     */
    private fun nextDelayFor(nextEventPos: Long?, queryPos: Long, posMs: Long): Long {
        val pollTarget = titleGate.pollTargetMs
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

    /** 按歌名闸门决定上屏 / 抑制 */
    private fun onEngineLine(text: String?) {
        if (text == null) return

        if (titleGate.blocksLyricLines) {
            // 锁定/静默中不上屏；顺手把首个非空文本记为首句
            // （前台路径未触发时的兜底，例如后台播放 / 播放界面没打开）
            if (titleGate.noteFirstLineText(text)) {
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
}
