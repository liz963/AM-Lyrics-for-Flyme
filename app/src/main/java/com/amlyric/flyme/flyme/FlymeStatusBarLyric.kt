package com.amlyric.flyme.flyme

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.core.HantConverter

/**
 * Flyme 状态栏歌词发送器（魅族官方适配方案，**载波模式**）。
 *
 * 依据魅族开放平台文档《Flyme 状态栏歌词适配》：
 *  - 本质是带 Ticker 的常驻通知：FLAG_ALWAYS_SHOW_TICKER（一直显示歌词）
 *    + FLAG_ONLY_UPDATE_TICKER（只更新歌词，不刷新通知其它内容）。
 *
 * ══════════════════ 载波（carrier）模式：本模块**不发任何通知** ══════════════════
 *
 * 不自己发通知，而是 Hook `NotificationManager.notify`，把歌词 Ticker 注入到
 * **Apple Music 自己的媒体通知**上：
 *  - 媒体通知本来就在（播放期间必然存在），挂上歌词后状态栏零新增通知；
 *  - 歌词更新时只改 tickerText 重发同一条通知：when/内容均不动，
 *    Flyme 走「只更新歌词」动画 → 左侧图标不再跟着歌词一起滚动。
 *
 * ⚠️ **v1.4.0：彻底删除「退回模式」（自带通知）** —— 用户明确要求
 * 「只需要状态栏歌词功能，不要推送通知」，而那条后路会：
 *  ① 在通知栏/控制中心多出一条写着歌词的通知；
 *  ② 在系统「通知设置」里给 Apple Music 多出一个「状态栏歌词」通知渠道
 *     （真机实测：渠道下累计 12 条通知，用户一眼就看出来了）。
 * 现在载波拿不到时**什么都不做**，只记一条日志 —— 宁可这一句不显示，
 * 也绝不往通知栏里塞东西。[init] 里还会把历史遗留的渠道与通知清掉。
 *
 * v1.4.0：成为**简繁转换的唯一落点**（[update] 里转换），并提供
 * [onConversionChanged] 供切换方向后原地重刷当前句。
 */
object FlymeStatusBarLyric {

    /**
     * 历史遗留（≤ v1.3.x 的退回模式）留下的通知渠道与通知 ID。
     * 只用于**清理**：新版本不再创建它们，但老用户机器上可能还残留，
     * 留着这个渠道会让 Apple Music 的通知设置里多出一个「状态栏歌词」。
     */
    private const val STRAY_CHANNEL_ID = "am_flyme_status_bar_lyric"
    private const val STRAY_NOTIFICATION_ID = 0x414D4C59 // "AMLY"

    private var appContext: Context? = null
    private var notificationManager: NotificationManager? = null

    private var flagAlwaysShowTicker = 0
    private var flagOnlyUpdateTicker = 0
    private var supported = false
    private var iconRes = 0

    /** 当前歌词文本（null = 无歌词/未播放，不注入） */
    @Volatile
    private var currentText: String? = null

    /**
     * 还没能挂上去的歌词（载波尚未出现时的暂存）。
     *
     * 【为什么需要它】播放刚开始时，歌词推送可能早于宿主重发媒体通知 ——
     * 这时载波还不存在。旧版会为此发一条自带通知（用户不接受），
     * 新版改为暂存，等 [offerCarrier] 拿到媒体通知时再补挂，一句都不会丢。
     */
    @Volatile
    private var pendingText: String? = null

    /** 最近收到的原始歌词（未做简繁转换），切方向后原地重刷用 */
    @Volatile
    private var lastRawText: String? = null

    // ─────────────────────── 载波（宿主媒体通知）───────────────────────

    /** 载波通知：宿主媒体通知的 (tag, id, Notification)。Notification 持强引用，每次宿主重发都会刷新 */
    private var carrierTag: String? = null
    private var carrierId: Int = 0
    private var carrier: Notification? = null

    private val lock = Any()

    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (appContext != null && appContext == appCtx && notificationManager != null) return
        appContext = appCtx
        notificationManager =
            appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        runCatching {
            val clazz = Class.forName("android.app.Notification")
            flagAlwaysShowTicker = clazz.getField("FLAG_ALWAYS_SHOW_TICKER").getInt(null)
            flagOnlyUpdateTicker = clazz.getField("FLAG_ONLY_UPDATE_TICKER").getInt(null)
            supported = flagAlwaysShowTicker > 0 && flagOnlyUpdateTicker > 0
        }.onFailure {
            supported = false
            XLog.i("Flyme status bar lyric NOT supported: ${it.message}")
        }

        // 状态栏歌词左侧图标：宿主自带的小号白色音符（ic_widgets_music_note，
        // 12dp 宽，视觉高度与状态栏文字接近，白色单色随主题着色）；
        // 找不到时退回 appwidget_music_note，再退回宿主应用图标。
        iconRes = resolveIcon("ic_widgets_music_note")
            .let { if (it != 0) it else resolveIcon("appwidget_music_note") }
        if (iconRes == 0) {
            iconRes = runCatching { appCtx.applicationInfo.icon }.getOrDefault(0)
        }

        // 清理 ≤ v1.3.x 的遗留：退回模式的渠道 + 通知。
        // 渠道必须删掉 —— 否则它会一直挂在系统「通知设置 → Apple Music」里。
        runCatching {
            notificationManager?.cancel(STRAY_NOTIFICATION_ID)
            notificationManager?.deleteNotificationChannel(STRAY_CHANNEL_ID)
        }

        if (supported) {
            XLog.i("Flyme status bar lyric ready (carrier mode, icon=0x${Integer.toHexString(iconRes)})")
        } else {
            XLog.e("Flyme status bar lyric UNSUPPORTED on this device (flags not found)")
        }
    }

    private fun resolveIcon(name: String): Int = runCatching {
        appContext?.resources?.getIdentifier(name, "drawable", appContext?.packageName)
    }.getOrDefault(0) ?: 0

    // ─────────────────────── 载波注入（由 notify Hook 调用）───────────────────────

    /**
     * `NotificationManager.notify(String,int,Notification)` 拦截到疑似媒体通知时调用。
     * 在通知真正提交前把歌词 Ticker 注进去 —— 宿主每次重发媒体通知都会
     * 自动带上最新歌词，天然保持常驻。
     */
    fun offerCarrier(tag: String?, id: Int, n: Notification): Boolean {
        if (!supported || n == null) return false
        synchronized(lock) {
            val firstAttach = carrier == null || carrierId != id || carrierTag != tag
            if (firstAttach) {
                carrierTag = tag
                carrierId = id
                carrier = n
                XLog.i("carrier attached: id=$id tag=$tag")
            } else {
                // 宿主重发了新的 Notification 对象，刷新引用
                carrier = n
            }
            if (!Settings.enabled) return false

            // 歌词可能比媒体通知先到（刚开播那一两秒）：把暂存的那句补挂上。
            // 这就是"删掉退回模式也不丢第一句"的保证。
            val text = currentText ?: pendingText
            if (text == null) return false
            currentText = text
            pendingText = null
            inject(n, text)
            return true
        }
    }

    /** 把歌词写进通知：只动 tickerText / flags，其余内容一律不碰；不显示左侧图标 */
    private fun inject(n: Notification, text: String) {
        n.tickerText = text
        n.flags = n.flags or flagAlwaysShowTicker or flagOnlyUpdateTicker or Notification.FLAG_NO_CLEAR
        // 仅歌词文本，不显示左侧图标（用户要求：状态栏只保留歌词文字）
        n.extras?.putBoolean("ticker_icon_switch", false)
    }

    /** 清除通知上的歌词 flags（停止播放时把载波还原，避免 Ticker 残留） */
    private fun strip(n: Notification) {
        runCatching {
            n.tickerText = null
            n.flags = n.flags and (flagAlwaysShowTicker or flagOnlyUpdateTicker).inv()
            n.extras?.remove("ticker_icon")
            n.extras?.remove("ticker_icon_switch")
        }
    }

    /**
     * 宿主取消了通知（cancel/cancelAll Hook 通知到这里）。
     * 载波已死：置空引用，避免 [clear] 时把已被宿主取消的媒体通知"复活"。
     *
     * 故意**不动 [currentText]**：宿主随后会重发媒体通知（暂停/继续、切歌都会），
     * 那时 [offerCarrier] 会把这句重新挂上去，不需要用户再等下一句歌词。
     */
    fun noteCarrierCancelled(tag: String?, id: Int) {
        synchronized(lock) {
            if (carrier != null && carrierId == id && carrierTag == tag) {
                carrier = null
            }
        }
    }

    fun noteAllCancelled() {
        synchronized(lock) { carrier = null }
    }

    // ─────────────────────── 歌词更新入口 ───────────────────────

    /**
     * 更新状态栏歌词文本。文本变化才重发通知（同文本直接跳过，防刷屏）。
     *
     * 这里是**简繁转换的唯一落点**（v1.4.0）：两条上屏通路（后台驱动 + 前台 Hook）最终
     * 都会汇到 [com.amlyric.flyme.core.LyricController.onLyricLine] → 本方法，
     * 所以在这里转换就不会有漏网之鱼。
     */
    fun update(text: String?) {
        if (!Settings.enabled) {
            clear()
            return
        }
        if (text.isNullOrBlank()) return // 空行/间奏：保持上一句，不清不换
        if (!supported) return

        lastRawText = text
        push(HantConverter.apply(text))
    }

    /**
     * 简繁方向变了：用新方向把**当前这句**原地重刷一次，用户能立刻看到效果
     * （否则要等到下一句歌词才看得出区别，还以为是没生效）。
     */
    fun onConversionChanged() {
        if (!Settings.enabled) return
        val raw = lastRawText ?: return
        push(HantConverter.apply(raw))
    }

    /**
     * 真正写入通知。
     *
     * **载波不存在时只暂存，不发任何通知**（v1.4.0 用户明确要求，见类注释）。
     */
    private fun push(converted: String?) {
        if (converted.isNullOrBlank()) return

        synchronized(lock) {
            if (converted == currentText) return
            val c = carrier
            if (c == null) {
                // 宿主媒体通知还没出现（刚开播 / 通知刚被系统回收）：
                // 暂存，等 offerCarrier 挂载时补上，绝不在这里自己发通知。
                if (pendingText != converted) {
                    pendingText = converted
                    XLog.d("carrier not ready, pending: $converted")
                }
                return
            }
            currentText = converted
            pendingText = null
            XLog.d("ticker: $converted")

            runCatching {
                inject(c, converted)
                notificationManager?.notify(carrierTag, carrierId, c)
            }.onFailure {
                XLog.e("post status bar lyric failed: ${it.message}", it)
            }
        }
    }

    /** 清除状态栏歌词（停止播放 / 切歌 / 关闭开关时调用，避免残留） */
    fun clear() {
        val nm = notificationManager ?: return
        synchronized(lock) {
            lastRawText = null
            pendingText = null
            runCatching { nm.cancel(STRAY_NOTIFICATION_ID) }
            if (currentText == null) return // 本来就没有歌词挂着，不必重发通知
            currentText = null
            // 重发一次无歌词 flags 的载波，让状态栏 Ticker 消失。
            // （若宿主已 cancel 载波，carrier 已被置空，不会误复活）
            val c = carrier
            if (c != null) {
                runCatching {
                    strip(c)
                    nm.notify(carrierTag, carrierId, c)
                }
            }
        }
    }
}
