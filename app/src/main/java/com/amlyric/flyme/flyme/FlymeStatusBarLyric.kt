package com.amlyric.flyme.flyme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog

/**
 * Flyme 状态栏歌词发送器（魅族官方适配方案，v1.2.1 载波模式）。
 *
 * 依据魅族开放平台文档《Flyme 状态栏歌词适配》：
 *  - 本质是带 Ticker 的常驻通知：FLAG_ALWAYS_SHOW_TICKER（一直显示歌词）
 *    + FLAG_ONLY_UPDATE_TICKER（只更新歌词，不刷新通知其它内容）。
 *
 * v1.2.1 关键变更 —— 「载波（carrier）模式」：
 *  - 不再由模块自己发一条独立通知（用户反馈：不想要多出来的通知），
 *    而是 Hook NotificationManager.notify，把歌词 Ticker 注入到
 *    Apple Music 自己的媒体通知（MediaStyle，extras 带 mediaSession）上。
 *    媒体通知本来就在，挂上歌词后状态栏零新增通知。
 *  - 歌词更新时只改 tickerText 重发同一条通知：when/内容均不动，
 *    Flyme 走「只更新歌词」动画 → 左侧图标不再跟着歌词一起滚动。
 *  - 旧版本（≤1.2.0）自建的 "am_flyme_status_bar_lyric" 渠道与常驻通知
 *    在 init 时清理，避免残留。
 *  - 万一媒体通知识别失败（极端情况），自动退回「自带通知」模式保证可用。
 */
object FlymeStatusBarLyric {

    /** 旧版（≤1.2.0）自带通知的渠道 ID / 通知 ID，仅用于清理残留 */
    private const val LEGACY_CHANNEL_ID = "am_flyme_status_bar_lyric"
    private const val LEGACY_NOTIFICATION_ID = 0x414D4C59 // "AMLY"

    /** 退回模式（识别不到媒体通知时）使用的通知 ID */
    private const val FALLBACK_NOTIFICATION_ID = 0x414D4C59

    /** 退回模式固定 when：任何字段都不随歌词变化，避免整条通知重建动画 */
    private const val FIXED_WHEN = 0L

    private var appContext: Context? = null
    private var notificationManager: NotificationManager? = null

    private var flagAlwaysShowTicker = 0
    private var flagOnlyUpdateTicker = 0
    private var supported = false
    private var iconRes = 0

    /** 当前歌词文本（null = 无歌词/未播放，不注入） */
    @Volatile
    private var currentText: String? = null

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

        // 清理 ≤1.2.0 的残留：旧渠道 + 旧自带通知
        runCatching {
            notificationManager?.cancel(LEGACY_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            }
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
     * NotificationManager.notify(String,int,Notification) 拦截到疑似媒体通知时调用。
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
                if (currentText != null) {
                    // 从退回模式切换到载波模式：撤掉自带通知
                    runCatching { notificationManager?.cancel(FALLBACK_NOTIFICATION_ID) }
                    XLog.i("carrier attached: id=$id tag=$tag")
                }
            } else {
                // 宿主重发了新的 Notification 对象，刷新引用
                carrier = n
            }
            if (Settings.enabled && currentText != null) {
                inject(n)
                return true
            }
        }
        return false
    }

    /** 把歌词写进通知：只动 tickerText / flags，其余内容一律不碰；不显示左侧图标 */
    private fun inject(n: Notification) {
        n.tickerText = currentText
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
     * 载波已死：置空引用，避免 clear() 时把已被宿主取消的媒体通知“复活”。
     */
    fun noteCarrierCancelled(tag: String?, id: Int) {
        synchronized(lock) {
            if (carrier != null && carrierId == id && carrierTag == tag) {
                carrier = null
                currentText = null
            }
        }
    }

    fun noteAllCancelled() {
        synchronized(lock) {
            carrier = null
            currentText = null
        }
    }

    // ─────────────────────── 歌词更新入口 ───────────────────────

    /**
     * 更新状态栏歌词文本。文本变化才重发通知（同文本直接跳过，防刷屏）。
     */
    fun update(text: String?) {
        if (!Settings.enabled) {
            clear()
            return
        }
        if (text.isNullOrBlank()) return // 空行/间奏：保持上一句，不清不换
        if (!supported) return
        val nm = notificationManager ?: return

        synchronized(lock) {
            if (text == currentText) return
            currentText = text
            XLog.d("ticker: $text")

            runCatching {
                val c = carrier
                if (c != null) {
                    // 载波模式：注入后重发宿主媒体通知（内容不变，只有 Ticker 变）
                    inject(c)
                    nm.notify(carrierTag, carrierId, c)
                } else {
                    // 退回模式：自带通知。when 固定、内容固定，只有 Ticker 变化，
                    // 保证 Flyme 走「只更新歌词」动画、图标不重新滚动。
                    postFallback(nm, text)
                }
            }.onFailure {
                XLog.e("post status bar lyric failed: ${it.message}", it)
            }
        }
    }

    /** 清除状态栏歌词（停止播放 / 关闭开关时调用，避免残留） */
    fun clear() {
        val nm = notificationManager ?: return
        synchronized(lock) {
            currentText = null
            runCatching {
                val c = carrier
                if (c != null) {
                    // 重发一次无歌词 flags 的载波，让状态栏 Ticker 消失。
                    // （若宿主已 cancel 载波，carrier 已被置空，不会误复活）
                    strip(c)
                    nm.notify(carrierTag, carrierId, c)
                }
                nm.cancel(FALLBACK_NOTIFICATION_ID)
            }
        }
    }

    // ─────────────────────── 退回模式（识别不到媒体通知时）───────────────────────

    private fun postFallback(nm: NotificationManager, text: String) {
        val ctx = appContext ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureFallbackChannel(nm)
            Notification.Builder(ctx, LEGACY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        builder
            .setSmallIcon(runCatching { ctx.applicationInfo.icon }.getOrDefault(0))
            .setPriority(Notification.PRIORITY_MAX)
            .setTicker(text)
            .setWhen(FIXED_WHEN) // 固定不变！变了会触发整条通知刷新动画
            .setOnlyAlertOnce(true)
            .setContentTitle("Apple Music")
            .setContentText(text)

        val n = builder.build()
        n.flags = n.flags or
                Notification.FLAG_NO_CLEAR or
                flagAlwaysShowTicker or
                flagOnlyUpdateTicker
        // 退回模式同样只显示歌词文本，不显示左侧图标
        n.extras?.putBoolean("ticker_icon_switch", false)
        nm.notify(FALLBACK_NOTIFICATION_ID, n)
    }

    private fun ensureFallbackChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val existing = nm.getNotificationChannel(LEGACY_CHANNEL_ID)
            if (existing != null && existing.importance != NotificationManager.IMPORTANCE_HIGH) {
                nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            }
            if (nm.getNotificationChannel(LEGACY_CHANNEL_ID) != null) return@runCatching
            val channel = NotificationChannel(
                LEGACY_CHANNEL_ID, "状态栏歌词", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Apple Music 状态栏歌词"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
