package com.amlyric.flyme.core

/**
 * 「歌曲名 - 歌手名」展示闸门（纯状态机）。
 *
 * 【它解决的两个问题】
 *  1. **歌名一闪而过**：官方引擎在前奏期会把「第一句」当活动行回传，若直接上屏，
 *     刚推的歌名会被瞬间顶掉。因此推完歌名后进入**锁定(holding)**——期间一切歌词行
 *     都不上屏，直到「第一句前 [leadMs]」才释放，第一句按原提前量准时接上。
 *  2. **无歌词歌曲歌名卡死**：有些歌曲宿主根本不产出歌词（连句柄都没有），永远等不到
 *     第一句。因此锁定超过 [HOLD_MAX_MS] 仍拿不到首句时间时判定**无歌词**，
 *     转为**静默(silent)**：清空状态栏、本首不再推送任何内容。
 *
 * 【设计约束：只做决策，不碰外部】
 *  本类不做任何上屏/反射动作，只返回 [Decision]，由调用方（BackgroundLyrics）执行。
 *  这样"展示策略"与"驱动实现"解耦，逻辑可以脱离宿主单独推理。
 *
 * 【★ v1.3.16 最短展示护栏 —— 修掉「一闪而过」的根因】
 *  释放点原本是纯 `第一句时间 − leadMs`。但实测证明官方引擎 `processEvents` 的返回值
 *  **不是「下一行」，而是「下一事件」**（可以是行内字级事件，提前量只有 2~900ms）。于是：
 *   · 第一句来得很早（如 1.2s，推歌名时就已经进了第一句内部）；
 *   · 或歌词句柄迟到（拿到 ptr 时已越过第一句）
 *  这两种情况下算出的释放点**落在过去** → 下一 tick 立刻释放 → 歌名只活一两百毫秒。
 *  现在给释放点加上下限 `推出位置 + [MIN_TITLE_MS]`：无论首句时间多离谱，歌名至少显示
 *  [MIN_TITLE_MS]，绝不可能"闪"。前奏正常的歌曲（首句远在 [MIN_TITLE_MS] 之后）释放点
 *  不受任何影响，行为与之前完全一致。
 *
 * 【线程】所有状态 @Volatile —— 引擎回调（宿主 UI 线程）、轮询 tick（主线程 Handler）、
 *  LyricController（可能任意 Hook 线程）都会访问。
 */
internal class SongTitleGate(private val leadMs: Long) {

    private companion object {
        /** 推歌名的位置阈值(ms)：位置 ≤ 该值才算「从最开头播放」。取 1500 兼顾 leadMs 与启动延迟 */
        const val PUSH_MAX_POS_MS = 1500L

        /** 「回到开头」判定：位置相对上一次 tick 回退超过该值，且落在 [PUSH_MAX_POS_MS] 内 */
        const val RESTART_BACK_MS = 2000L

        /**
         * 歌名最短展示时长(ms)。释放点 = `max(第一句 − leadMs, 推出位置 + 本值)`。
         * 取 3s：短到不会盖住正常歌曲的第一句（前奏 >4s 的歌曲完全不受影响），
         * 又长到足以杜绝"一闪而过"的观感。
         */
        const val MIN_TITLE_MS = 3000L

        /**
         * 无歌词兜底：锁定最长保持时长(ms)，**仅在播放中计时**。
         * 取 8s 的依据——有歌词的歌曲首句时间实测在 0~1.5s 内就被探测捕获，
         * 8s 留足歌词加载余量。宁可多留几秒（无歌词时只是歌名多停一会儿），
         * 也不要误清有歌词的歌曲（那样又变回"一闪而过"）。
         */
        const val HOLD_MAX_MS = 8000L
    }

    /** 本首是否已推过歌名（重播 / 拖回开头会重新武装） */
    @Volatile private var titlePushed = false

    /** 锁定中：抑制歌词上屏，保持歌名 */
    @Volatile private var holding = false

    /** 首句绝对时间(ms)；-1 = 尚未取到 */
    @Volatile private var firstLineAtMs = -1L

    /** 首句文本；释放锁定时补推用。引擎对首句只回传一次，不补推就会永久吞掉 */
    @Volatile private var firstLineText: String? = null

    /** 上一次 tick 的播放位置(ms)；-1 = 尚未取到（切歌后复位） */
    @Volatile private var lastPosMs = -1L

    /** 推出歌名时的播放位置(ms)，最短展示护栏的基准 */
    @Volatile private var holdStartPosMs = -1L

    /** 本次锁定的开始时刻（wall clock, ms），供无歌词兜底计时 */
    @Volatile private var holdStartedAtMs = 0L

    /** 静默中：本首已判定无歌词，不再推送任何内容 */
    @Volatile private var silent = false

    val isHolding: Boolean get() = holding

    val isSilent: Boolean get() = silent

    /** 锁定或静默中 → 歌词行一律不上屏 */
    val blocksLyricLines: Boolean get() = holding || silent

    /**
     * 实际释放锁定的位置(ms)；≤0 表示仍在等待首句时间。
     * = `max(第一句 − leadMs, 推出位置 + [MIN_TITLE_MS])`，即 [MIN_TITLE_MS] 护栏生效处。
     */
    val releaseAtMs: Long
        get() = if (holding && firstLineAtMs > 0) {
            maxOf(firstLineAtMs - leadMs, holdStartPosMs + MIN_TITLE_MS)
        } else -1L

    /**
     * 密轮询目标位置(ms)：锁定期希望贴近这个位置唤醒；≤0 表示无需密轮询。
     *  · 已知首句 → 释放点；
     *  · 未知首句 → 最短展示的终点，相当于 500ms 级密轮询（保证无歌词兜底能被及时评估）。
     */
    val pollTargetMs: Long
        get() = when {
            !holding -> -1L
            firstLineAtMs > 0 -> firstLineAtMs - leadMs
            else -> holdStartPosMs + MIN_TITLE_MS
        }

    // ─────────────────────────── 状态迁移 ───────────────────────────

    /** 切歌 / 停止播放：全量复位 */
    fun reset() {
        rearmTitle()
        lastPosMs = -1L
    }

    /** 是否满足「推歌名」条件：位置在开头、本首还没推过、且不处于静默 */
    fun canPushTitle(posMs: Long): Boolean =
        !titlePushed && !silent && posMs in 0..PUSH_MAX_POS_MS

    /** 调用方已推歌名 → 进入锁定 */
    fun onTitlePushed(posMs: Long, nowMs: Long) {
        titlePushed = true
        holding = true
        firstLineAtMs = -1L
        holdStartPosMs = posMs
        holdStartedAtMs = nowMs
    }

    /**
     * 记录本 tick 的播放位置。
     * @return true = 检测到「回到开头」（重播同一首 / 拖回进度 0），此时已自动重新武装歌名推送
     */
    fun notePosition(posMs: Long): Boolean {
        if (posMs < 0L) return false
        val prev = lastPosMs
        lastPosMs = posMs
        if (prev < 0L) return false
        // 正常播放位置只会单调前进：大幅回退且落在开头阈值内 ⇒ 重播 / 拖回开头
        if (prev <= posMs + RESTART_BACK_MS || posMs > PUSH_MAX_POS_MS) return false
        rearmTitle()
        return true
    }

    /**
     * 锁定/静默期间收到一行文本 → 记为首句（只记首个非空文本）。
     * @return true = 本次是新记录
     */
    fun noteFirstLineText(text: String?): Boolean {
        if (firstLineText != null || text.isNullOrBlank()) return false
        firstLineText = text
        return true
    }

    /**
     * 用本 tick 的探测结果补上首句时间（prelude 期探测到的 nextEventPos 即第一句起点）。
     * 仅在锁定中且尚未记录时生效。
     * @return true = 本次是新记录
     */
    fun noteFirstLineTime(atMs: Long, text: String?): Boolean {
        if (!holding || firstLineAtMs > 0) return false
        firstLineAtMs = atMs
        noteFirstLineText(text)
        return true
    }

    /** 静默期间引擎重新报出歌词事件 → 解除静默（说明此前只是歌词加载慢） */
    fun clearSilence() {
        silent = false
    }

    // ─────────────────────────── 裁决 ───────────────────────────

    /** 一个 tick 的闸门裁决结果 */
    sealed interface Decision {
        /** 无需动作 */
        object None : Decision

        /**
         * 释放锁定。调用方需在 [firstLineText] 非空时补推首句；
         * [firstLineAtMs] / [releaseAtMs] 仅用于日志。
         */
        data class Release(
            val firstLineText: String?,
            val firstLineAtMs: Long,
            val releaseAtMs: Long,
        ) : Decision

        /** 判定本首无歌词：调用方需清空状态栏并停止推送 */
        object NoLyrics : Decision
    }

    /**
     * 每 tick 评估一次。两个分支互斥（[firstLineAtMs] 是否已知）。
     *
     * @param posMs   当前播放位置(ms)
     * @param nowMs   wall clock（`SystemClock.elapsedRealtime()`），供无歌词兜底计时
     * @param playing 仅播放中才对兜底计时——否则「刚进歌就暂停」会因首句时间还没取到而被误判
     */
    fun evaluate(posMs: Long, nowMs: Long, playing: Boolean): Decision {
        if (!holding) return Decision.None

        // 首句时间未知：只能等，或判定无歌词
        if (firstLineAtMs <= 0) {
            if (playing && holdStartedAtMs > 0 && nowMs - holdStartedAtMs >= HOLD_MAX_MS) {
                holding = false
                silent = true
                return Decision.NoLyrics
            }
            return Decision.None
        }

        // 位置不可用（读取失败）：本 tick 不释放
        if (posMs < 0L) return Decision.None

        val releaseAt = releaseAtMs
        if (posMs >= releaseAt) {
            holding = false
            return Decision.Release(firstLineText, firstLineAtMs, releaseAt)
        }
        return Decision.None
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /** 复位歌名相关状态（不动 [lastPosMs]） */
    private fun rearmTitle() {
        titlePushed = false
        holding = false
        firstLineAtMs = -1L
        firstLineText = null
        holdStartPosMs = -1L
        holdStartedAtMs = 0L
        silent = false
    }
}
