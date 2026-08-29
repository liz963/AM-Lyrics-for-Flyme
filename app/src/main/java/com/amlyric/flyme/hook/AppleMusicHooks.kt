package com.amlyric.flyme.hook

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.flyme.FlymeStatusBarLyric
import com.amlyric.flyme.core.BackgroundLyrics
import com.amlyric.flyme.core.LyricController
import com.amlyric.flyme.core.LyricsLoader
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

/**
 * Apple Music 5.2.0 Hook 层（libxposed API 102 拦截器链模型）。
 *
 * v1.3.6 Hook 清单：
 *  1. Application.attach                      初始化入口
 *  2. PlayerLyricsViewModel.loadLyrics        UI 路径歌词加载（歌曲同步）
 *  3. PlayerLyricsViewModel.buildTimeRangeToLyricsMap  歌词句柄捕获（缓存 + 后台驱动数据源）
 *  4. SongInfoTimeProcessor.processEvents     反射驱动官方歌词引擎（无 UI/后台均逐行推送）
 *  5. LocalMediaPlayerController.onPlaybackStateChanged 播放状态 + 控制器捕获
 *  6. NotificationManager.notify/cancel       ★ 载波模式：歌词 Ticker 注入宿主媒体通知
 *  7. PreferenceFragmentCompat.setPreferenceScreen     设置页开关注入
 */
object AppleMusicHooks {

    const val TARGET_PACKAGE = "com.apple.android.music"

    private const val PREF_KEY = Settings.KEY_LYRIC_ENABLED
    private const val CLS_PREF_FRAG = "androidx.preference.PreferenceFragmentCompat"
    private const val CLS_PREF_SCREEN = "androidx.preference.PreferenceScreen"

    /** 「状态栏歌词」开关注入的宿主页面（主设置页 + 歌词设置页） */
    private val SWITCH_HOST_ACTIVITIES = setOf(
        "com.apple.android.music.settings.activity.SettingsActivity",
        "com.apple.android.music.settings.activity.LyricsSettingsActivity"
    )

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
                hookSettingsUI()
                XLog.i("hooks installed (module v1.3.6)")
            }
        }
    }

    private fun installPlaybackHooks() {
        hookLyricsLoad()       // 歌词加载（UI 路径歌曲同步）
        hookLyricsBuild()      // 歌词句柄捕获 + 切歌 + 无歌词判定
        hookLineCallback()     // 引擎推当前行（前台）
        hookPlaybackState()    // 播放状态 + 控制器捕获（位置/当前曲目来源）
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
                    ReflectCompat.string(it, "getId")
                        ?: ReflectCompat.string(it, "getAdamId")
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
                if (!com.amlyric.flyme.hook.NativeLyricsParser.hasLyrics(ptr)) {
                    LyricController.onNoLyrics()
                }
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
                // 引擎逐行回调（播放界面打开时的精确时机驱动）。
                // v1.3.0 起位置轮询与它走同一数据源，update() 按文本去重，互不冲突。
                val lineVector = chain.args.getOrNull(1) ?: return@hookAfter
                val text = NativeLyricsParser.extractLineText(lineVector)
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

    // ───────────── 6. 在 Apple Music 设置页注入「状态栏歌词」开关 ─────────────

    private fun hookSettingsUI() {
        runHook("SettingsUI inject") {
            val fragClass = classLoader.loadClass(CLS_PREF_FRAG)
            val screenClass = classLoader.loadClass(CLS_PREF_SCREEN)
            val setScreen = fragClass.getDeclaredMethod("setPreferenceScreen", screenClass)
            XLog.i("hook OK: settingsUI.setPreferenceScreen")

            hookAfter(setScreen, "settingsUI.setPreferenceScreen") { chain ->
                val fragment = chain.thisObject ?: return@hookAfter
                val screen = chain.args.firstOrNull() ?: return@hookAfter
                injectSwitch(fragment, screen)
            }
        }
    }

    private fun injectSwitch(fragment: Any, screen: Any) {
        val fragClass = runCatching { classLoader.loadClass(CLS_PREF_FRAG) }.getOrNull() ?: return
        if (!fragClass.isInstance(fragment)) return

        val act = runCatching {
            fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
        }.getOrNull() ?: return
        val actName = act.javaClass.name
        if (actName !in SWITCH_HOST_ACTIVITIES) return

        val screenClass = screen.javaClass

        val existing = runCatching {
            screenClass.getMethod("findPreference", CharSequence::class.java)
                .invoke(screen, PREF_KEY)
        }.getOrNull()
        if (existing != null) return

        val ctx = runCatching {
            fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context
        }.getOrNull() ?: act

        runCatching {
            val switchClass = classLoader.loadClass("androidx.preference.SwitchPreference")
            val prefClass = classLoader.loadClass("androidx.preference.Preference")
            val listenerClass =
                classLoader.loadClass("androidx.preference.Preference\$OnPreferenceChangeListener")
            val sw = switchClass.getConstructor(Context::class.java).newInstance(ctx)

            switchClass.getMethod("setKey", String::class.java).invoke(sw, PREF_KEY)
            switchClass.getMethod("setTitle", CharSequence::class.java).invoke(sw, "状态栏歌词")
            switchClass.getMethod("setSummary", CharSequence::class.java)
                .invoke(sw, "在 Flyme 状态栏显示当前播放歌词")
            switchClass.getMethod("setChecked", Boolean::class.javaPrimitiveType)
                .invoke(sw, Settings.enabled)
            switchClass.getMethod("setOrder", Int::class.javaPrimitiveType).invoke(sw, 0)
            switchClass.getMethod("setPersistent", Boolean::class.javaPrimitiveType)
                .invoke(sw, false)

            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
                if (method.name == "onPreferenceChange") {
                    val nv = args?.getOrNull(1)
                    val v = when (nv) {
                        is Boolean -> nv
                        is java.lang.Boolean -> nv.booleanValue()
                        else -> true
                    }
                    Settings.setEnabled(v)
                    if (!v) FlymeStatusBarLyric.clear()
                    true
                } else null
            }
            switchClass.getMethod("setOnPreferenceChangeListener", listenerClass).invoke(sw, proxy)

            screenClass.getMethod("addPreference", prefClass).invoke(screen, sw)
            XLog.i("settings switch injected into $actName")
        }.onFailure {
            XLog.w("inject settings switch failed: ${it.message}")
        }
    }

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

/** 轻量反射（宿主对象取值，失败一律返回 null） */
private object ReflectCompat {
    fun string(target: Any?, method: String): String? {
        if (target == null) return null
        return runCatching {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                val m = cls.declaredMethods.firstOrNull { it.name == method && it.parameterCount == 0 }
                if (m != null) {
                    m.isAccessible = true
                    return@runCatching m.invoke(target) as? String
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()
    }
}
