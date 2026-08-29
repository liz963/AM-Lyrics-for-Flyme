package com.amlyric.flyme.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amlyric.flyme.XLog
import com.amlyric.flyme.hook.NativeLyricsParser
import java.lang.reflect.Proxy

/**
 * 无 UI 歌词加载器 v3（v1.3.1 loadLyrics 路径回归）。
 *
 * 【策略演变】
 *  - v1.2.1：反射 PlayerLyricsViewModel + Proxy PlaybackItem → loadLyrics → 实测无歌词
 *  - v1.3.0：绕过 ViewModel，直接调 ttml/g.e 挂起函数 → 失败（g.e 的 Continuation
 *    参数类型被 R8 收窄为 bf/c 抽象类，getDeclaredMethod 用 kotlin.coroutines.Continuation
 *    接口查找 → NoSuchMethodException → 取词从未执行）
 *  - v1.3.1：回到 loadLyrics 路径（普通 void 方法，无 Continuation 参数），
 *    补全 PlaybackItem Proxy 的全部已知方法 + 详细日志
 *
 * loadLyrics 内部流程（字节码逐条验证）：
 *  1. hasLyrics()==true → 主路径
 *  2. getId() → Long.parseLong → adamId
 *  3. getQueueId() → queueId
 *  4. 语言参数 = currentSystemLyricsLanguage（构造器从 LocaleUtil 初始化）
 *  5. 创建 PlayerLyricsViewModel$f 协程并 launch
 *  6. $f.invokeSuspend: isDownloaded()==false → g.e(adamId, langs, queueId, scripts, $f自身)
 *     → g.e 内部用 $f 自己的 Continuation（extends bf/c，类型匹配）→ 网络取词 → 解析
 *     → buildTimeRangeToLyricsMap(ptr) ← 我们的 Hook 在这里捕获 ptr
 *
 * Proxy PlaybackItem 需要实现的方法（$f 字节码确认）：
 *  - hasLyrics() → true（走主路径）
 *  - isDownloaded() → false（走在线分支）
 *  - getId() → storeId（数字字符串，Long.parseLong 必须成功）
 *  - getQueueId() → queueId
 *  - getTitle() → 歌曲名（仅用于日志）
 *  - offlineLyricsFilePath() → null
 *  - hasCustomLyrics() → false
 *  - 其余方法 → 安全默认值
 */
object LyricsLoader {

    private const val CLS_VM =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
    private const val CLS_PLAYBACK_ITEM =
        "com.apple.android.music.model.PlaybackItem"

    /** 会话级 ptr 缓存上限 */
    private const val CACHE_LIMIT = 6

    /** 每首歌取词失败重试上限 */
    private const val MAX_ATTEMPTS = 3

    private val DIGITS = Regex("^\\d+$")

    private var appContext: Context? = null
    private var classLoader: ClassLoader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 已请求（含成功）的歌曲 id */
    private val requested = HashSet<String>()

    /** 失败计数（达上限后放弃） */
    private val failures = HashMap<String, Int>()

    /** 已放弃的歌曲（无歌词/取词失败）：仅用于把"gave up"日志压成一次，避免每 tick 刷屏 */
    private val gaveUpLogged = HashSet<String>()

    /** storeId → SongInfoPtr 强引用缓存（LRU，保证原生 shared_ptr 不释放） */
    private val ptrCache = LinkedHashMap<String, Any>(CACHE_LIMIT, 0.75f, true)

    /** 取词超时检查（15 秒内 buildTimeRangeToLyricsMap 未被触发则视为失败） */
    private val pendingTimeouts = HashMap<String, Runnable>()

    fun init(context: Context, cl: ClassLoader) {
        appContext = context
        classLoader = cl
    }

    fun resetSession() {
        synchronized(this) {
            requested.clear()
            failures.clear()
            gaveUpLogged.clear()
            ptrCache.clear()
            pendingTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
            pendingTimeouts.clear()
        }
    }

    /** 官方流水线已自己加载过（UI 路径 Hook 触发）：记录防重复请求 */
    fun markRequested(id: String?) {
        if (id == null || !DIGITS.matches(id)) return
        synchronized(this) {
            requested.add(id)
            // 取消超时检查
            pendingTimeouts.remove(id)?.let { mainHandler.removeCallbacks(it) }
        }
    }

    /** Hook 捕获到 ptr（buildTimeRangeToLyricsMap Hook 触发）：缓存 + 通知 */
    fun onPtrCaptured(ptr: Any?) {
        if (ptr == null) return
        val adamId = NativeLyricsParser.adamId(ptr)
        if (adamId != null && DIGITS.matches(adamId)) {
            synchronized(this) {
                failures.remove(adamId)
                gaveUpLogged.remove(adamId)
                pendingTimeouts.remove(adamId)?.let { mainHandler.removeCallbacks(it) }
                while (ptrCache.size >= CACHE_LIMIT && ptrCache.keys.firstOrNull() != adamId) {
                    ptrCache.keys.firstOrNull()?.let { ptrCache.remove(it) }
                }
                ptrCache[adamId] = ptr
            }
            XLog.i("ptr captured & cached: $adamId")
        }
        BackgroundLyrics.onSongInfo(ptr)
    }

    /** 命中缓存的歌词句柄 */
    fun cachedPtr(storeId: String?): Any? {
        if (storeId == null) return null
        synchronized(this) { return ptrCache[storeId] }
    }

    /**
     * 主动取词：反射创建 PlayerLyricsViewModel + Proxy PlaybackItem，
     * 调用 loadLyrics(proxy) 触发官方取词协程。
     * 必须在主线程执行（协程作用域用 Dispatchers.Main.immediate）。
     */
    fun requestLyrics(storeId: String, queueId: Long, title: String?) {
        if (!DIGITS.matches(storeId)) {
            XLog.w("requestLyrics: invalid storeId '$storeId'")
            return
        }
        val cl = classLoader ?: run {
            XLog.w("requestLyrics: classLoader null")
            return
        }

        synchronized(this) {
            if (storeId in requested) return
            val fails = failures[storeId] ?: 0
            if (fails >= MAX_ATTEMPTS) {
                // 只在首次放弃时记一条日志（tick 每 500ms 都会走到这里）
                if (gaveUpLogged.add(storeId)) {
                    XLog.w("requestLyrics: gave up on $storeId ($fails failures)")
                }
                return
            }
            requested.add(storeId)
        }

        XLog.i("requestLyrics: $storeId (queueId=$queueId, title=$title)")

        mainHandler.post {
            runCatching {
                val ctx = appContext ?: throw IllegalStateException("appContext null")
                val vmClass = cl.loadClass(CLS_VM)
                val vm = vmClass.getConstructor(android.app.Application::class.java)
                    .newInstance(ctx.applicationContext as android.app.Application)
                XLog.d("ViewModel created")

                val itemClass = cl.loadClass(CLS_PLAYBACK_ITEM)
                val proxy = Proxy.newProxyInstance(cl, arrayOf(itemClass),
                    PlaybackItemHandler(storeId, queueId, title))
                XLog.d("PlaybackItem proxy created")

                val method = vmClass.declaredMethods.firstOrNull {
                    it.name == "loadLyrics" && it.parameterCount == 1
                } ?: throw NoSuchMethodException("loadLyrics(PlaybackItem)")
                method.isAccessible = true

                XLog.d("calling loadLyrics($storeId)...")
                method.invoke(vm, proxy)
                XLog.d("loadLyrics returned (coroutine launched)")

                // 超时检查：15 秒内无 ptr 则视为失败
                val timeout = Runnable {
                    synchronized(this) {
                        if (storeId !in ptrCache.keys) {
                            XLog.w("loadLyrics timeout for $storeId (no ptr after 15s)")
                            requested.remove(storeId)
                            val n = (failures[storeId] ?: 0) + 1
                            failures[storeId] = n
                        }
                        pendingTimeouts.remove(storeId)
                    }
                }
                pendingTimeouts[storeId] = timeout
                mainHandler.postDelayed(timeout, 15000L)

            }.onFailure {
                XLog.e("requestLyrics($storeId) failed: ${it.message}", it)
                synchronized(this) {
                    requested.remove(storeId)
                    val n = (failures[storeId] ?: 0) + 1
                    failures[storeId] = n
                    pendingTimeouts.remove(storeId)
                }
            }
        }
    }

    // ─────────────────────────── PlaybackItem Proxy ───────────────────────────

    /**
     * PlaybackItem 接口代理。$f 协程只调以下方法（字节码确认）：
     *  - hasLyrics() → true（走主路径）
     *  - isDownloaded() → false（走在线分支）
     *  - getId() → storeId
     *  - getQueueId() → queueId
     *  - getTitle() → title
     *  - offlineLyricsFilePath() → null
     *  - hasCustomLyrics() → false
     * 其余方法返回类型安全默认值。
     */
    private class PlaybackItemHandler(
        private val storeId: String,
        private val queueId: Long,
        private val title: String?
    ) : java.lang.reflect.InvocationHandler {

        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val name = method.name
            val result = when (name) {
                "hasLyrics" -> true
                "hasCustomLyrics" -> false
                "isDownloaded" -> false
                "hasOfflineLyrics" -> false
                "isOfflineLyricsExpired" -> false
                "getId" -> storeId
                "getQueueId" -> queueId
                "getTitle" -> title ?: ""
                "getPersistentId" -> storeId.toLongOrNull() ?: 0L
                "getPlaybackStoreId" -> storeId
                "getPlaybackDuration" -> 0L
                "getCloudId" -> 0L
                "getAlbumDiscNumber" -> 0
                "getPlaybackEndpointType" -> 0
                "customLyrics" -> null
                "offlineLyricsFilePath" -> null
                "getOfflineLyricsFilePath" -> null
                "getArtistName" -> null
                "getCollectionName" -> null
                "getAssetUrl" -> null
                "getAssetRootDir" -> null
                "getCloudLibraryUniversalId" -> null
                "getContainerId" -> null
                "getDownloadLocation" -> null
                "offlineLyricsExpirationDateMillis" -> 0L
                // Observable interface
                "addOnPropertyChangedCallback" -> null
                "removeOnPropertyChangedCallback" -> null
                // Object methods
                "toString" -> "AMLyricPlaybackItem($storeId)"
                "hashCode" -> storeId.hashCode()
                "equals" -> (args?.getOrNull(0) === proxy)
                else -> {
                    XLog.d("PlaybackItem proxy: unhandled method $name")
                    defaultReturnValue(method)
                }
            }
            return result
        }

        private fun defaultReturnValue(method: java.lang.reflect.Method): Any? {
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                Short::class.javaPrimitiveType -> 0.toShort()
                Byte::class.javaPrimitiveType -> 0.toByte()
                Char::class.javaPrimitiveType -> ' '
                Void::class.javaPrimitiveType, Unit::class.java -> null
                else -> null
            }
        }
    }
}
