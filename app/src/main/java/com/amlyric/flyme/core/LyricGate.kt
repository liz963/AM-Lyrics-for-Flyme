package com.amlyric.flyme.core

/**
 * 前奏期闸门（纯状态机）。
 *
 * 【它现在负责什么（v1.4.0 起）】
 *  歌曲从**最开头**播放时，状态栏在**第一句歌词前 [leadMs]** 之前**什么都不显示**；
 *  到了「第一句 − leadMs」这一拍，放开闸门并把第一句补推上屏。
 *
 * 【为什么必须存在：引擎会提前把第一句送出来】
 *  官方引擎（前台回调与后台 `processEvents` 两条通路）在前奏期就会把**第一句**当活动行回传。
 *  真机日志（v1.3.16 采集）：
 *  ```
 *  19:52:36.732  song meta pushed at pos=0
 *  19:52:37.236  first line text captured (fg): [蝴蝶眨几次眼睛]   ← 0.5s 就到了
 *  19:52:42.473  ... firstLineMs=6374                              ← 但它的真实时间是 6.37s
 *  ```
 *  没有闸门的话，前奏刚开始状态栏就会冒出第一句歌词，与"前奏期间不推送"相悖。
 *
 * 【为什么不再有"歌名/无歌词"逻辑】
 *  v1.3.x 这个类叫 `SongTitleGate`，管的是"推歌名 → 锁定 → 到第一句放开"，还带一套
 *  「8 秒拿不到首句就判定无歌词并清空」的兜底。v1.4.0 需求变更——**前奏期不推歌名**——
 *  于是：
 *   · 无歌名的歌曲本来就不会有任何上屏动作，**无歌词歌曲自然保持空白**，兜底逻辑整块删除；
 *   · 「歌名一闪而过」这个纠缠三个版本的 bug 从根上消失（没有歌名可闪）。
 *
 * 【安全网：bail-out】
 *  唯一残留的风险是"引擎一直送行文本、却始终探测不到首句时间"（例如歌词句柄迟到且
 *  `processEvents` 不再回报未来事件）——那样闸门会永久关着，用户再也看不到歌词。
 *  对策：抑制期间若已见到 [BAIL_OUT_TEXTS] 个**互不相同**的非空文本，说明早就越过了
 *  第一句，立即放开并推最新那句。取 3 而不是 1，是为了不被引擎重复回报同一句干扰。
 *
 * 【线程】所有状态 @Volatile —— 引擎回调（宿主 UI 线程）、轮询 tick（主线程 Handler）、
 *  Hook 回调（可能任意线程）都会访问。
 */
internal class LyricGate(private val leadMs: Long) {

    private companion object {
        /** 「从最开头播放」的位置阈值(ms)。取 1500 兼顾 leadMs 与播放启动延迟 */
        const val START_MAX_POS_MS = 1500L

        /** 「回到开头」判定：位置相对上一次 tick 回退超过该值，且落在 [START_MAX_POS_MS] 内 */
        const val RESTART_BACK_MS = 2000L

        /** bail-out：抑制期间见到多少个不同文本后强制放开（见类注释） */
        const val BAIL_OUT_TEXTS = 3
    }

    /** 本首（或本次重播）是否已做过"开头播放"判定，保证只判定一次 */
    @Volatile private var armed = false

    /** 是否正在抑制前奏期上屏 */
    @Volatile private var holding = false

    /** 首句绝对时间(ms)；-1 = 尚未探测到 */
    @Volatile private var firstLineAtMs = -1L

    /** 抑制期间捕获到的首个非空文本（即第一句），放开时补推 */
    @Volatile private var firstLineText: String? = null

    /** 抑制期间最后见到的非空文本（bail-out 时推它） */
    @Volatile private var lastText: String? = null

    /** 抑制期间见过的不同文本数量（安全网计数） */
    @Volatile private var textCount = 0

    /** 安全网已触发 */
    @Volatile private var bailOut = false

    /** 上一次 tick 的播放位置(ms)；-1 = 尚未取到 */
    @Volatile private var lastPosMs = -1L

    /** 是否正在抑制歌词上屏 */
    val isHolding: Boolean get() = holding

    /** 抑制期的密轮询目标位置(ms)；≤0 = 无需密轮询 */
    val pollTargetMs: Long
        get() = if (holding && firstLineAtMs > 0) firstLineAtMs - leadMs else -1L

    // ─────────────────────────── 状态迁移 ───────────────────────────

    /** 切歌 / 停止播放：全量复位 */
    fun reset() {
        resetHold()
        armed = false
        lastPosMs = -1L
    }

    /**
     * 记录本 tick 的播放位置。
     * @return true = 检测到「回到开头」（重播同一首 / 拖回进度 0），已重新武装
     */
    fun notePosition(posMs: Long): Boolean {
        if (posMs < 0L) return false
        val prev = lastPosMs
        lastPosMs = posMs
        if (prev < 0L) return false
        // 正常播放位置只会单调前进：大幅回退且落在开头阈值内 ⇒ 重播 / 拖回开头
        if (prev <= posMs + RESTART_BACK_MS || posMs > START_MAX_POS_MS) return false
        resetHold()
        armed = false
        return true
    }

    /**
     * 若本次是「从最开头播放」则武装前奏抑制。
     *
     * 判定用**位置**而非事件，天然覆盖「拖动进度条 / 跳播 / 中段续播」——这些情况位置明显
     * 大于阈值，直接标记为"已判定、不抑制"，歌词照常立刻上屏（不会干等一个前奏）。
     *
     * @return true = 本次武装（开始抑制前奏期上屏）
     */
    fun armIfAtStart(posMs: Long): Boolean {
        if (armed || posMs < 0L) return false
        armed = true
        if (posMs > START_MAX_POS_MS) return false
        holding = true
        return true
    }

    /**
     * 用一个探测结果补上首句时间（前奏期探测到的「下一事件」就是第一句起点）。
     * @return true = 本次是新记录
     */
    fun noteFirstLineTime(atMs: Long, text: String?): Boolean {
        if (!holding || firstLineAtMs > 0) return false
        firstLineAtMs = atMs
        if (firstLineText == null && !text.isNullOrBlank()) firstLineText = text
        return true
    }

    /**
     * 抑制期间收到一行文本 → 记为首句；若已明显越过第一句则触发安全网。
     * @return true = 本次记录下了首句文本（调用方可据此打日志）
     */
    fun noteLineText(text: String?): Boolean {
        if (text.isNullOrBlank() || text == lastText) return false
        lastText = text
        textCount++
        if (firstLineText == null) {
            firstLineText = text
            return true
        }
        if (firstLineAtMs <= 0 && textCount >= BAIL_OUT_TEXTS) bailOut = true
        return false
    }

    // ─────────────────────────── 裁决 ───────────────────────────

    /** 一个 tick 的闸门裁决结果 */
    sealed interface Decision {
        /** 继续抑制 / 无需动作 */
        object None : Decision

        /** 放开闸门，歌词恢复正常上屏。调用方需在 [text] 非空时补推一句 */
        data class Release(val text: String?, val firstLineAtMs: Long, val bailedOut: Boolean) : Decision
    }

    /**
     * 每 tick 评估一次。
     * @param posMs 当前播放位置(ms)，< 0 表示本 tick 读不到位置
     */
    fun evaluate(posMs: Long): Decision {
        if (!holding) return Decision.None

        // 安全网：引擎一直在送行文本却探测不到首句时间 → 立刻放开，推最新那句
        if (bailOut) {
            holding = false
            return Decision.Release(lastText, firstLineAtMs, true)
        }

        // 首句时间未知：继续等（无歌名的歌曲不推任何内容，这里等多久都不会有观感问题）
        if (firstLineAtMs <= 0) return Decision.None

        if (posMs < 0L) return Decision.None

        if (posMs >= firstLineAtMs - leadMs) {
            holding = false
            return Decision.Release(firstLineText, firstLineAtMs, false)
        }
        return Decision.None
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /** 复位抑制相关状态（不动 [armed] 与 [lastPosMs]） */
    private fun resetHold() {
        holding = false
        firstLineAtMs = -1L
        firstLineText = null
        lastText = null
        textCount = 0
        bailOut = false
    }
}
