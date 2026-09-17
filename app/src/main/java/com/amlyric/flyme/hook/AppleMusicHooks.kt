package com.amlyric.flyme.hook

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.amlyric.flyme.XLog
import com.amlyric.flyme.flyme.FlymeStatusBarLyric
import com.amlyric.flyme.core.BackgroundLyrics
import com.amlyric.flyme.core.LyricController
import com.amlyric.flyme.core.LyricsEngineDriver
import com.amlyric.flyme.core.LyricsLoader
import com.amlyric.flyme.lyric.LyricFetcher
import com.amlyric.flyme.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Apple Music Hook 层（libxposed API 102 拦截器链模型）。
 *
 * Hook 清单（v1.4.0）：
 *  1. Application.attach                      初始化入口
 *  2. PlayerLyricsViewModel.loadLyrics        UI 路径歌词加载（歌曲同步）
 *  3. PlayerLyricsViewModel.buildTimeRangeToLyricsMap  歌词句柄捕获（缓存 + 后台驱动数据源）
 *  4. SongInfoTimeProcessor.processEvents     反射驱动官方歌词引擎（无 UI/后台均逐行推送）
 *  5. LocalMediaPlayerController.onPlaybackStateChanged 播放状态 + 控制器捕获
 *  6. NotificationManager.notify/cancel       ★ 载波模式：歌词 Ticker 注入宿主媒体通知
 *  7. SettingsFragment.onViewCreated          ★ 设置页注入（见 [SettingsInjector]，内含逆向后的事实）
 *  8. PlayerLyricsViewFragment.I2             ★ 在线歌词注入（见 [LyricsInjector]）
 *
 * ⚠️ **原生歌词一律不碰**：宿主解析出来的歌词原样使用，不做任何改写。
 * 制作名单行、`歌曲名 - 歌手` 尾注只针对**我们自己补来的第三方歌词**过滤
 * （见 [com.amlyric.flyme.lyric.CreditLine]），入口在 [LyricController.onLyricLine]。
 */
object AppleMusicHooks {

    const val TARGET_PACKAGE = "com.apple.android.music"

    /** mediaStyle 通知在 extras 里携带 MediaSession token 的 key（framework 常量） */
    private const val EXTRA_MEDIA_SESSION = "android.mediaSession"

    private lateinit var xposed: XposedInterface
    private lateinit var classLoader: ClassLoader
    private var initialized = false
    private var lastSongId: String? = null

    fun install(xp: XposedInterface, cl: ClassLoader) {
        xposed = xp
        classLoader = cl
        hookApplication()
    }

    // ─────────────────────────── 1. Application ───────────────────────────

    private fun hookApplication() {
        runHook("Application.attach") {
            val attach = Application::class.java
                .getDeclaredMethod("attach", Context::class.java)

            hookAfter(attach, "Application.attach") { chain ->
                val ctx = chain.args[0] as? Context ?: return@hookAfter
                if (initialized) return@hookAfter
                initialized = true
                LyricController.init(ctx)
                LyricsLoader.init(ctx, classLoader)
                BackgroundLyrics.init(classLoader)
                installPlaybackHooks()
                installNotificationHooks()
                SettingsInjector.install(xposed, classLoader)
                TtmlBridge.init(classLoader)
                TtmlBridge.selfTest { ptr, pos -> BackgroundLyrics.probeLineAt(ptr, pos) }
                // 开发期链路自检（网络 + 解析 + TTML 落地），发版前把 SELF_CHECK 关掉
                LyricFetcher.selfCheck()
                XLog.i("hooks installed (module 1.4.0)")
            }
        }
    }

    private fun installPlaybackHooks() {
        hookLyricsLoad()       // 歌词加载（UI 路径歌曲同步）
        hookLyricsBuild()      // 歌词句柄捕获 + 切歌 + 无歌词判定
        hookLineCallback()     // 引擎推当前行（前台）
        hookPlaybackState()    // 播放状态 + 控制器捕获（位置/当前曲目来源）
        LyricsInjector.install(xposed, classLoader)  // 在线歌词注入（I2）
    }

    // ────────── 2a. UI 路径：歌词加载入口 ──────────

    private fun hookLyricsLoad() {
        runHook("PlayerLyricsViewModel.loadLyrics") {
            val vm = classLoader.loadClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
            )
            val method = vm.declaredMethods.firstOrNull {
                it.name == "loadLyrics" && it.parameterCount == 1
            } ?: error("loadLyrics not found")
            XLog.i("hook OK: loadLyrics")

            hookAfter(method, "loadLyrics") { chain ->
                val item = chain.args.firstOrNull()
                val id = item?.let {
                    Reflect.string(it, "getId")
                        ?: Reflect.string(it, "getAdamId")
                }
                // UI 路径已发起加载：同步歌曲标识，并防止无 UI 加载器重复请求同一首
                LyricController.onLyricsLoaded(id)
                if (id != null) LyricsLoader.markRequested(id)
            }
        }
    }

    // ─────────── 2b. 歌词句柄捕获（官方流水线产出 SongInfoPtr） ───────────

    private fun hookLyricsBuild() {
        runHook("PlayerLyricsViewModel.buildTimeRangeToLyricsMap") {
            val vm = classLoader.loadClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
            )
            val method = vm.declaredMethods.firstOrNull { it.name == "buildTimeRangeToLyricsMap" }
                ?: error("buildTimeRangeToLyricsMap not found")
            XLog.i("hook OK: buildTimeRangeToLyricsMap")

            hookAfter(method, "buildTimeRangeToLyricsMap") { chain ->
                val ptr = chain.args.firstOrNull() ?: return@hookAfter
                // 缓存 ptr + 通知后台调度器（onPtrCaptured 内部会调 BackgroundLyrics.onSongInfo）
                LyricsLoader.onPtrCaptured(ptr)

                val adamId = com.amlyric.flyme.hook.NativeLyricsParser.adamId(ptr)
                if (adamId != null && adamId != lastSongId) {
                    lastSongId = adamId
                    LyricController.onSongChanged(adamId)
                }
                // v1.4.0：无歌词时不再弹「♪ 暂无歌词」占位——前奏期闸门会让状态栏保持空白，
                // 无歌词的歌曲自然一直空白（用户要求"不推送任何内容"）。
            }
        }
    }

    // ───────────── 3. 逐行回调（当前行，前台） ─────────────

    private fun hookLineCallback() {
        runHook("SongInfoTimeProcessor.lineEventCallback.call") {
            val cb = classLoader.loadClass(
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents\$lineEventCallback\$1"
            )
            val method = cb.declaredMethods.firstOrNull { m ->
                m.name == "call" && m.parameterCount == 3
            } ?: error("line callback call not found")
            XLog.i("hook OK: lineEventCallback.call")

            hookAfter(method, "lineEventCallback.call") { chain ->
                // ★ 先分辨"这次回调是谁引起的"。
                // 宿主把行回调包了一层自己的 lambda（就是这个类），**按类**挂 Hook 就意味着
                // **我们自己在反射调用 processEvents 时它也会触发**。不分辨的后果（真机实测）：
                //  ① 开机自检的样例歌词 `自检歌词`/`第二句啊` 被当歌词推到状态栏
                //     （旧版还会因此发一条写着歌词的通知）；
                //  ② 为了算"下一跳时间"做的只读探测，会把**下一句提前推**出去。
                // 我们驱动时行事件由 LyricsEngineDriver 的 display 回调负责，这里整条跳过。
                if (LyricsEngineDriver.isInvoking) return@hookAfter

                // 引擎逐行回调（播放界面打开时的精确时机驱动）。
                // v1.3.0 起位置轮询与它走同一数据源，update() 按文本去重，互不冲突。
                val lineVector = chain.args.getOrNull(1) ?: return@hookAfter
                val text = NativeLyricsParser.extractLineText(lineVector)
                // v1.3.14 关键修复：歌名锁定期间必须一并抑制这条前台路径。
                // 引擎在播放界面打开时会自己回调当前行，前奏期它会把「第一句」
                // 当活动行回传——真机实测按住锁定时 ticker 仍被首句顶掉，
                // 这就是"歌名一闪而过"始终没修好的真正原因（此前只堵了后台驱动）。
                // 同时借这条最可靠的路径捕获首句文本，供释放锁定时补推。
                if (BackgroundLyrics.onForegroundLine(text)) return@hookAfter
                LyricController.onLyricLine(text)
            }
        }
    }

    // ─────────────────────────── 4. 播放状态 ───────────────────────────

    private fun hookPlaybackState() {
        runHook("LocalMediaPlayerController.onPlaybackStateChanged") {
            val controller = classLoader.loadClass(
                "com.apple.android.music.playback.controller.LocalMediaPlayerController"
            )
            val method = controller.declaredMethods.firstOrNull { m ->
                m.name == "onPlaybackStateChanged" && m.parameterCount == 3 &&
                        m.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: error("onPlaybackStateChanged not found")
            XLog.i("hook OK: onPlaybackStateChanged")

            hookAfter(method, "onPlaybackStateChanged") { chain ->
                val state = chain.args.getOrNull(2) as? Int ?: return@hookAfter
                // 捕获控制器实例 → 后台兜底的位置/当前曲目来源
                chain.thisObject?.let { BackgroundLyrics.setController(it) }
                LyricController.onPlaybackStateChanged(state)
            }
        }
    }

    // ─────────────── 5. 载波模式：拦宿主通知，注入歌词 Ticker ───────────────
    /**
     * 拦 NotificationManager.notify：识别宿主的 MediaStyle 媒体通知，
     * 在提交前把歌词 Ticker（+ Flyme 扩展 flags/图标）注入进去。
     * Apple Music 的媒体播放通知永远从主进程发出，播放期间必然出现 ——
     * 歌词挂上去后状态栏零新增通知。
     *
     * 同时拦 cancel/cancelAll：宿主取消媒体通知时标记载波失效，
     * 防止停止播放后把已被取消的通知“复活”。
     */
    private fun installNotificationHooks() {
        runHook("NotificationManager.notify") {
            val nm = NotificationManager::class.java
            val notify = nm.getMethod(
                "notify",
                String::class.java, Int::class.javaPrimitiveType, Notification::class.java
            )
            // notify(int,Notification) 内部调 notify(null,id,n)，拦一个入口即可全覆盖
            XLog.i("hook OK: NotificationManager.notify")

            hookBefore(notify, "NM.notify") { chain ->
                val tag = chain.args.getOrNull(0) as? String
                val id = chain.args.getOrNull(1) as? Int ?: return@hookBefore
                val n = chain.args.getOrNull(2) as? Notification ?: return@hookBefore
                if (!isMediaNotification(n)) return@hookBefore
                FlymeStatusBarLyric.offerCarrier(tag, id, n)
            }
        }

        runHook("NotificationManager.cancel") {
            val nm = NotificationManager::class.java
            hookBefore(
                nm.getMethod("cancel", Int::class.javaPrimitiveType), "NM.cancel(int)"
            ) { chain ->
                val id = chain.args.getOrNull(0) as? Int ?: return@hookBefore
                FlymeStatusBarLyric.noteCarrierCancelled(null, id)
            }
            hookBefore(
                nm.getMethod("cancel", String::class.java, Int::class.javaPrimitiveType),
                "NM.cancel(tag,id)"
            ) { chain ->
                val tag = chain.args.getOrNull(0) as? String
                val id = chain.args.getOrNull(1) as? Int ?: return@hookBefore
                FlymeStatusBarLyric.noteCarrierCancelled(tag, id)
            }
            hookBefore(nm.getMethod("cancelAll"), "NM.cancelAll") {
                FlymeStatusBarLyric.noteAllCancelled()
            }
            XLog.i("hook OK: NotificationManager.cancel")
        }
    }

    /** 判定宿主媒体通知：MediaStyle（extras 带 mediaSession）或「常驻+多 action」兜底启发 */
    private fun isMediaNotification(n: Notification): Boolean {
        val extras = runCatching { n.extras }.getOrNull() ?: return false
        if (runCatching { extras.get(EXTRA_MEDIA_SESSION) }.getOrNull() != null) return true
        val actions = runCatching { n.actions }.getOrNull()
        return actions != null && actions.size >= 2 &&
                (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    }

    // ─────────────────── 6. 设置页注入 → 见 SettingsInjector ───────────────────

    // ─────────────────────── hook 工具（拦截器链模型） ───────────────────────

    private fun hookAfter(exec: Executable, name: String, after: (XposedInterface.Chain) -> Unit) {
        xposed.hook(exec)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                runCatching { after(chain) }.onFailure { XLog.w("hook[$name]: ${it.message}") }
                result
            })
    }

    private fun hookBefore(exec: Executable, name: String, before: (XposedInterface.Chain) -> Unit) {
        xposed.hook(exec)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(XposedInterface.Hooker { chain ->
                runCatching { before(chain) }.onFailure { XLog.w("hook[$name]: ${it.message}") }
                chain.proceed()
            })
    }

    private inline fun runHook(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            XLog.e("hook [$name] failed: ${it.message}", it)
        }
    }
}
