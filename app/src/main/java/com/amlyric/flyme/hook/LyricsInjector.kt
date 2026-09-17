package com.amlyric.flyme.hook

import android.os.Handler
import android.os.Looper
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.core.BackgroundLyrics
import com.amlyric.flyme.lyric.LyricFetcher
import com.amlyric.flyme.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 在线歌词注入器 —— 把 QQ / 网易云的逐字歌词塞进 Apple Music 自己的歌词通路。
 *
 * ══════════════════════ 注入点是怎么找到的（真机 6.5.2 / vc1586）══════════════════════
 *
 * 宿主里负责"把一份歌词装进歌词页"的方法是：
 * ```
 * PlayerLyricsViewFragment.I2(SongInfo$SongInfoPtr) -> void
 * ```
 * 它是**唯一**同时满足「实例方法 + void + 单参且参数类型是 SongInfoPtr」的方法；
 * 同形状的 `R2(SongInfoPtr)` 也被 R8 改成了这个名字之外的东西，两者不会混淆
 * （判定契约与 AM++ 一致：方法名必须是 `I2`，形状不对就整个功能降级，绝不猜）。
 *
 * 参数 `args[0]` 就是**宿主自己解析出来的原生歌词句柄**：有歌词时是 ptr，没歌词时是 null。
 * 所以这一个点就能同时回答两个问题：
 *  ① 这首歌现在有没有可用歌词？（看 args[0]）
 *  ② 要把歌词换成什么？（把 args[0] 换掉）
 *
 * 另外两个事实来源（都从 `I2` 的 fragment 实例上取，见 [itemOf]）：
 *  · 当前曲目 `BaseContentItem`（`getId/getTitle/getArtistName`）→ 拿去搜歌词；
 *  · 是否为 `PlaybackItem`（有 `hasCustomLyrics/customLyrics`）→ 判断"用户已手动给了歌词"。
 *
 * ══════════════════════ 什么时候才允许联网（用户明确要求）══════════════════════
 *
 * **「自动实时补全」关掉时，播放过程中一个请求都不许发。**
 * 打开时也只有满足下面任一条才取词（[eligible]）：
 *  ① `args[0] == null`                      —— 原生歌词缺失；
 *  ② 时间轴不是逐字（timing 名不含 "word"） —— 不是逐字时间轴。
 * 并且：**已有原生逐字歌词一律不补（不管有没有翻译）**、**伴奏/纯音乐轨一律不补**、
 * `PlaybackItem.hasCustomLyrics()` 为真时一律不动（用户自己配的歌词优先）。
 *
 * ⚠️ 判据全部来自 ptr 自身（`getAvailableTiming / getLanguage / getTranslation`），
 * **不需要**原始 TTML 文本 —— 宿主解析完就把文本丢了，我们拿不到。这也是为什么
 * 只有走到了 `I2`（拿到 ptr）才能做判定，比"提前猜"更可靠。
 * 读不出判据时按 AM++ 的语义**保守放行（不请求）**，宁可漏补也不乱补。
 *
 * ══════════════════════ 补出来的歌词怎么落地（两条通路）══════════════════════
 *
 * 取词 → 写 Apple 格式 TTML（[com.amlyric.flyme.lyric.TtmlWriter]）→ 让宿主自己的
 * 解析器把它变成 ptr（[TtmlBridge.parse]）→ 然后：
 *  · **App 内歌词页**：重新进入 `I2`（[ReactApply]），把 ptr 交给歌词视图；
 *  · **状态栏**：交给 [BackgroundLyrics.onSongInfo]，由模块自己的调度器驱动。
 *
 * 【为什么要"重新进入"】取词要联网，几百毫秒到几秒不等，而 `I2` 是同步调用、等不起。
 * 所以首帧只能放行（宿主显示"暂无歌词"），取完再自己调一次 `I2(fragment, ptr)` 把页面补上。
 * 这期间 fragment 可能已被销毁 / 已切歌，所以每次重入前都要复核
 * 「fragment 还可用 && 当前曲目还是这首歌」（[ReactApply]）。
 */
object LyricsInjector {

    // ─────────────────────── 宿主符号（6.5.2 真机核对） ───────────────────────

    /** 歌词页 Fragment。**未被 R8 改名**（真机 6.5.2 classes2.dex 确认） */
    private const val CLS_FRAGMENT =
        "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"

    /** 歌词安装方法名。R8 改的是别的，`I2` 这个名字在这一版仍然保留 */
    private const val METHOD_INSTALL = "I2"

    /** 宿主自己的歌词句柄类（native 绑定需要，R8 不能改名） */
    private const val CLS_PTR =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
    private const val CLS_BASE_ITEM = "com.apple.android.music.model.BaseContentItem"
    private const val CLS_PLAYBACK_ITEM = "com.apple.android.music.model.PlaybackItem"

    /** 等待重入的 fragment 上限（同时开着的歌词页不会有多个，纯防御） */
    private const val MAX_WAITING = 8

    /** 同时在跑的取词任务上限；队列满即丢（丢掉的下一帧 `I2` 会再触发一次） */
    private const val WORKER_QUEUE = 2

    // ─────────────────────────── 解析出来的句柄 ───────────────────────────

    @Volatile private var installMethod: Method? = null
    @Volatile private var ptrClass: Class<*>? = null
    @Volatile private var playbackItemClass: Class<*>? = null

    /** fragment 继承链上所有声明类型为 `BaseContentItem` 的字段（最近优先） */
    @Volatile private var itemFields: List<Field> = emptyList()

    /** 实测命中的那个字段（首次读到非 null 值时定下来，避免每次都遍历） */
    @Volatile private var liveItemField: Field? = null

    @Volatile private var ready = false
    @Volatile private var unavailableReason: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 取词工作线程（单条，队列容量见 [WORKER_QUEUE]）—— 别占宿主线程池 */
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORKER_QUEUE),
        { r -> Thread(r, "amlyric-online-lyric").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** adamId → 已解析好的 SongInfoPtr（强引用：保证原生 shared_ptr 不被释放） */
    private val readyPtrs = LinkedHashMap<Long, Any>()

    /** 正在取词的 adamId；同时兼作"失败过就不再重试"的集合（见 [giveUp]） */
    private val inFlight = HashSet<Long>()

    /** 取过但确实没有的 adamId —— 避免每次 `I2` 都重打一次网络 */
    private val gaveUp = HashSet<Long>()

    /**
     * 已经打过的「不补」日志，元素是 `adamId to 理由`。
     *
     * 【为什么必须去重】判定是高频入口：`I2` 每次装歌词都会走到 [eligible]，
     * `BackgroundLyrics` 的轮询也会每 tick 走一次。而**伴奏轨这类歌永远不会有句柄**，
     * 于是「不补 —— 伴奏/纯音乐轨」会以 500ms 一条的速度刷屏（v1.4.0 真机实测），
     * 把真正有用的取词日志淹掉。
     */
    private val skipLogged = HashSet<Pair<Long, String>>()

    /** 等结果回来要重入 `I2` 的 fragment（弱引用，fragment 销毁后自动清） */
    private val waiting = ArrayList<Waiting>()

    private class Waiting(val fragment: WeakReference<Any>, val adamId: Long)

    // ═══════════════════════════ 安装 ═══════════════════════════

    fun install(xp: XposedInterface, cl: ClassLoader) {
        val method = resolve(cl) ?: run {
            XLog.w("lyrics injector disabled: $unavailableReason")
            return
        }
        val ptr = ptrClass
        runCatching {
            xp.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    val decision = runCatching { decide(chain) }
                        .onFailure { XLog.w("lyrics injector decide: ${it.message}") }
                        .getOrNull()
                    when {
                        // 不动：原样放行（绝大多数情况，零开销）
                        decision == null -> chain.proceed()
                        // 换成补出来的歌词：Chain 没有可写 args，只能走 proceed(新参数)
                        else -> {
                            XLog.i(
                                "lyrics injected: adamId=${decision.adamId} " +
                                    "'${decision.title} - ${decision.artist}' (${decision.reason})"
                            )
                            chain.proceed(arrayOf<Any?>(decision.ptr))
                        }
                    }
                })
            XLog.i(
                "hook OK: lyrics inject ${CLS_FRAGMENT.substringAfterLast('.')}" +
                    ".${method.name}(${ptr?.simpleName})"
            )
        }.onFailure { XLog.e("hook [lyrics inject] failed: ${it.message}", it) }
    }

    /** 解出安装方法 / 原生指针类 / 当前曲目字段；任何一项缺失都整体降级（不猜） */
    private fun resolve(cl: ClassLoader): Method? {
        if (ready) return installMethod
        synchronized(this) {
            if (ready) return installMethod
            ready = true
            runCatching {
                val fragment = cl.loadClass(CLS_FRAGMENT)

                // 指针类用的是宿主自己的全限定名（native 绑定需要，R8 不能改），直接 load
                val ptr = cl.loadClass(CLS_PTR)

                // ★ 严格契约：实例方法 + void + 单参 SongInfoPtr + 名字就是 I2
                val install = fragment.declaredMethods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.name == METHOD_INSTALL &&
                        m.returnType == Void::class.javaPrimitiveType &&
                        m.parameterCount == 1 &&
                        m.parameterTypes[0].isAssignableFrom(ptr)
                } ?: throw NoSuchMethodException("$METHOD_INSTALL(SongInfoPtr)")
                install.isAccessible = true

                installMethod = install
                ptrClass = ptr
                playbackItemClass = runCatching { cl.loadClass(CLS_PLAYBACK_ITEM) }.getOrNull()
                itemFields = collectItemFields(fragment)

                XLog.i(
                    "lyrics injector ready: ${install.name}(${ptr.simpleName}), " +
                        "current-item fields=${itemFields.map { "${it.declaringClass.simpleName}.${it.name}" }}"
                )
            }.onFailure {
                unavailableReason = "${it.message}（在线歌词注入不可用，状态栏不受影响）"
                installMethod = null
            }
        }
        return installMethod
    }

    /**
     * 收集 fragment 继承链上所有 `BaseContentItem` 字段。
     *
     * 【为什么不写死字段名】AM++ 在 6.5.0 钉的是 `fragment.m#c`，到 6.5.2 已经变成
     * `fragment.e#W` —— 字段名是最不稳定的一层。所以这里只钉**类型**（`BaseContentItem`
     * 是宿主自己的公开模型类，不参与混淆），字段名交给运行时挑（见 [itemOf]）。
     */
    private fun collectItemFields(fragment: Class<*>): List<Field> {
        val out = ArrayList<Field>(2)
        var c: Class<*>? = fragment
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type.name != CLS_BASE_ITEM && !f.type.name.endsWith(".model.BaseContentItem")) {
                    continue
                }
                f.isAccessible = true
                out += f
            }
            c = c.superclass
        }
        return out
    }

    // ═══════════════════════════ 裁决（I2 热路径） ═══════════════════════════

    private class Injection(
        val ptr: Any,
        val adamId: Long,
        val title: String,
        val artist: String,
        val reason: String,
    )

    /**
     * `I2(args[0])` 的裁决。返回 null = 原样放行；非 null = 用它的 ptr 替换 `args[0]`。
     *
     * 顺序刻意固定：**先判开关 → 再读缓存（快、无网）→ 再判条件 → 最后才排任务**。
     * 开关检查必须在最前面（缓存里可能还留着上一次的 ptr），关掉就该彻底不注入。
     *
     * ⚠️ 关键区别：`args[0] != null` 当场就能判、也当场就请求；
     * `args[0] == null` **只是"这一帧没有"，不等于"这首歌没有"**，所以只记账不请求
     * （"确实缺"的定性交给 [BackgroundLyrics] 的宽限期，见方法 ③ 段注释）。
     */
    private fun decide(chain: XposedInterface.Chain): Injection? {
        val original = runCatching { chain.getArg(0) }.getOrNull()
        // 观测点：宿主每次"装歌词"都会经过这里。这是判断播放页注入是否真的发生的唯一入口，
        // 所以即便开关关着、即便不改任何东西，也留一条 debug 级记录。
        XLog.d("I2 called: original=${if (original == null) "null(无原生歌词)" else original.javaClass.simpleName}")

        if (!Settings.autoComplete) return null

        val ptr = ptrClass ?: return null
        // 形状不符说明这版宿主的 I2 签名变了（或挂到了别的方法上）—— 一律不动
        if (original != null && !ptr.isInstance(original)) return null

        val fragment = chain.thisObject
        val item = itemOf(fragment)

        // 用户手动配了歌词 → 尊重它，绝不覆盖
        if (hasCustomLyrics(item)) {
            XLog.d("lyrics inject skipped: custom lyrics present")
            return null
        }

        val adamId = itemId(item)
        if (adamId == null) {
            // 这个 fragment 实例还没拿到当前曲目（新建的页面会先经过这一步）
            XLog.d("I2: skip — fragment 还没有当前曲目(item=${item?.javaClass?.simpleName})")
            return null
        }
        val title = Reflect.string(item, "getTitle").orEmpty()
        val artist = Reflect.string(item, "getArtistName").orEmpty()

        // ★ 先把歌词页记下来：不管这一帧补不补，取完词都可能要回来重入它。
        // 放在最前面（而不是等判定通过再记）是因为判定可能因为"这一帧信息不全"而放弃，
        // 但那不代表这个页面以后不需要补 —— 早记下才稳。
        remember(fragment, adamId)

        // ① 已经补好了（可能是上一次 I2 排的任务刚落地，或状态栏那条路先补上了）→ 直接换
        val cached = cachedPtr(adamId)
        XLog.d(
            "I2: adamId=$adamId cached=${cached != null} " +
                "raw=${readyPtrs[adamId] != null} alive=${readyPtrs[adamId]?.let { TtmlBridge.isAlive(it) }}"
        )
        cached?.let { replacement ->
            dismiss(fragment)
            return Injection(replacement, adamId, title, artist, "命中已补全结果")
        }

        if (adamId in gaveUp || title.isBlank()) return null

        // ② `args[0] != null`：判据确凿（时间轴类型就在句柄里），可以当场决定
        if (original != null) {
            val reason = eligible(original, title, adamId) ?: return null
            startCompletion(
                adamId, title, artist,
                Reflect.string(item, "getCollectionName"),
                Reflect.long(item, "getPlaybackDuration"),
                reason = reason,
            )
            // 排上任务就本帧放行；没排上但刚好已就绪则直接换
            return cachedPtr(adamId)?.let { Injection(it, adamId, title, artist, reason) }
        }

        // ③ `args[0] == null`：**这一帧不能断定"宿主没有歌词"**。
        // 真机实测（v1.4.0）：宿主自己的取词是异步的，I2(null) 之后 200ms 就拿到了 ptr；
        // 在这里武断地请求，会让"其实有歌词"的歌白跑一趟——而用户要求只在确实缺时才请求。
        // 所以只把歌词页记下来，让 [BackgroundLyrics] 的宽限期去定性"确实缺"；
        // 它补好后会回来重新进入 I2，歌词页一样能被补上。
        return null
    }

    /**
     * 条件判定。返回"补全理由"，不满足返回 null。
     *
     * **只有两种情况才补（v1.4.0 用户口径，改前必读）：**
     *  ① `original == null`   → 原生歌词缺失；
     *  ② 时间轴不是逐字        → 宿主只给了行级歌词，没有逐字/滚动效果。
     *
     * ⚠️ **原生已经是逐字歌词时，一律不补 —— 不管它有没有翻译。**
     * 早先还有第三条"外语逐字缺翻译也补"，已按用户要求删除：
     * 那样会把宿主带翻译的歌词换成外部的，翻译反而可能丢。
     *
     * 另外**伴奏 / 纯音乐轨直接跳过**（用户明确要求）：这类轨本来就没有词可唱，
     * 而歌词站上往往只有"人声版"的词，补进来会让纯音乐也滚出一串歌词 —— 明显是错的。
     *
     * @param adamId 只用于 [skip] 的日志去重（这个入口会被高频调用）
     */
    private fun eligible(original: Any?, title: String, adamId: Long): String? {
        if (isInstrumental(title)) return skip(adamId, "伴奏/纯音乐轨('$title')")
        if (original == null) return "原生歌词缺失"

        val native = Reflect.call(original, "get") ?: return skip(adamId, "从句柄读不出判据（保守不动）")
        val timing = (Reflect.call(native, "getAvailableTiming") as? Enum<*>)?.name
            ?: return skip(adamId, "读不到时间轴类型（保守不动）")
        if (!timing.contains("word", ignoreCase = true)) return "非逐字时间轴(timing=$timing)"
        return skip(adamId, "原生已经是逐字歌词(timing=$timing)")
    }

    /**
     * 记一条"不补"日志并返回 `null`（让调用点写成 `return skip(...)`，读起来是一条直线）。
     *
     * 【为什么必须按 `adamId + 理由` 去重】这个判定有两个高频入口：`I2` 每次装歌词都走、
     * [BackgroundLyrics] 的轮询每 tick 也走。而**伴奏轨这类歌永远不会有句柄**，于是
     * 「不补 —— 伴奏/纯音乐轨」会以 500ms 一条的速度刷屏（v1.4.0 真机实测），
     * 把真正有用的取词日志全淹掉。
     */
    private fun skip(adamId: Long, reason: String): String? {
        val fresh = synchronized(skipLogged) {
            if (skipLogged.size > 128) skipLogged.clear()
            skipLogged.add(adamId to reason)
        }
        if (fresh) XLog.d("lyrics inject: 不补 —— $reason")
        return null
    }

    /**
     * 是不是伴奏 / 纯音乐轨。
     *
     * 判据只能是**标题关键词**：宿主不暴露"这是伴奏版"的字段，
     * 而且这类轨的特征就是 Apple 也没有歌词（[eligible] 的第一条会命中）。
     * 关键词刻意只收明确的标记词，避免误伤正常歌名。
     */
    internal fun isInstrumental(title: String): Boolean {
        val t = title.lowercase()
        return INSTRUMENTAL_MARKERS.any { t.contains(it) }
    }

    private val INSTRUMENTAL_MARKERS = listOf(
        "伴奏", "纯音乐", "无人声", "伴唱版", "演奏版", "钢琴版",
        "instrumental", "off vocal", "offvocal", "karaoke", "backing track",
        "カラオケ", "インスト", "(inst)", "(inst.)", "[inst]",
    )

    // ═══════════════════════════ 取词 ═══════════════════════════

    /**
     * 排一个取词任务（两条判定路径共用：`I2` 注入 与 状态栏兜底）。
     *
     * @return true = 本次真的新起了一个任务；false = 歌曲无效 / 已有人取 / 队列满
     */
    private fun startCompletion(
        adamId: Long,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long,
        reason: String,
    ): Boolean {
        if (adamId <= 0L || title.isBlank()) return false
        synchronized(readyPtrs) {
            if (readyPtrs[adamId] != null && TtmlBridge.isAlive(readyPtrs[adamId])) return false
            if (!inFlight.add(adamId)) return false
        }

        // ★ 这一行是"该请求时才请求"的唯一现场证据：把判定原因和歌名一起打出来
        XLog.i("lyrics inject: 开始取词 —— $reason | '$title - ${artist.orEmpty()}'")

        val task = Runnable { complete(adamId, title, artist.orEmpty(), album, durationMs) }
        return try {
            worker.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            // 队列满：清掉在途标记，让下一帧 I2 / 下一个 tick 还能再试
            synchronized(readyPtrs) { inFlight.remove(adamId) }
            XLog.d("lyrics inject: worker queue full, dropped '$title'")
            false
        }
    }

    /** 工作线程：取词 → 写 TTML → 让宿主解析成 ptr → 回主线程落地 */
    private fun complete(
        adamId: Long,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ) {
        val started = System.currentTimeMillis()
        try {
            // 开关可能在任务排队期间被关掉 —— 再确认一次，绝不把请求发出去
            if (!Settings.autoComplete) {
                XLog.d("lyrics inject: cancelled (switch turned off) for '$title'")
                forget(adamId)
                return
            }
            val prepared = LyricFetcher.prepare(title, artist, album, durationMs)
            if (prepared == null) {
                XLog.w("lyrics inject: no lyrics for '$title - $artist'")
                giveUp(adamId)
                return
            }
            val ptr = TtmlBridge.parse(prepared.ttml)
            if (ptr == null) {
                XLog.w(
                    "lyrics inject: TTML rejected by host parser " +
                        "($title, ${prepared.ttml.length} chars)"
                )
                giveUp(adamId)
                return
            }
            // 写歌曲标识：宿主多处会拿它跟当前播放项比对，不写会被当"身份不符"忽略
            val bound = TtmlBridge.bindAdamId(ptr, adamId)
            val sections = TtmlBridge.sectionCount(ptr)
            if (sections <= 0) {
                XLog.w("lyrics inject: parsed but empty (sections=0) for '$title'")
                giveUp(adamId)
                return
            }
            synchronized(readyPtrs) {
                inFlight.remove(adamId)
                readyPtrs[adamId] = ptr
                // 上限纯防御：真正的淘汰由"只在当前歌保留"的用法决定
                while (readyPtrs.size > 6) readyPtrs.remove(readyPtrs.keys.first())
            }
            XLog.i(
                "lyrics inject: ready adamId=$adamId '$title - $artist' " +
                    "timing=${TtmlBridge.availableTiming(ptr)} sections=$sections " +
                    "bindAdamId=$bound trans=${prepared.hasTranslation} " +
                    "${System.currentTimeMillis() - started}ms"
            )
            mainHandler.post { ReactApply.run(adamId) }
        } catch (t: Throwable) {
            XLog.w("lyrics inject: failed for '$title': ${t.message}")
            giveUp(adamId)
        }
    }

    private fun giveUp(adamId: Long) {
        synchronized(readyPtrs) {
            inFlight.remove(adamId)
            gaveUp.add(adamId)
            if (gaveUp.size > 64) gaveUp.clear()
        }
        // 那些等这首的 fragment 别再等了
        synchronized(waiting) { waiting.removeAll { it.adamId == adamId } }
    }

    private fun forget(adamId: Long) {
        synchronized(readyPtrs) { inFlight.remove(adamId) }
    }

    // ═══════════════════════════ 落地（主线程） ═══════════════════════════

    /**
     * 把已就绪的 ptr 应用到两条通路。
     *
     * 必须回主线程：`I2` 是 UI 方法（会碰 RecyclerView 的 adapter），
     * 而 [BackgroundLyrics] 的轮询本来也跑在主线程（`processEvents` 的线程要求）。
     */
    private object ReactApply {
        fun run(adamId: Long) {
            val ptr = cachedPtr(adamId) ?: return

            // ① 状态栏：交给模块自己的调度器（这也是"Apple Music 没有滚动歌词"的正解）
            runCatching { BackgroundLyrics.onSongInfo(ptr) }
                .onFailure { XLog.w("lyrics inject: status bar handoff failed: ${it.message}") }

            // ② App 内歌词页：重新进入 I2
            val install = installMethod ?: return
            val targets = synchronized(waiting) {
                val matches = waiting.filter { it.adamId == adamId }.mapNotNull { it.fragment.get() }
                waiting.removeAll { it.adamId == adamId || it.fragment.get() == null }
                matches
            }
            XLog.d(
                "lyrics inject: reapply adamId=$adamId " +
                    "waiting=${targets.size} 剩余等待=${synchronized(waiting) { waiting.size }}"
            )
            for (fragment in targets) {
                if (!isUsable(fragment)) {
                    XLog.d("lyrics inject: re-entry skipped ($adamId: fragment 不可用)")
                    continue
                }
                // 页面可能已经切到别的歌了：身份对不上就放弃，绝不把 A 的歌词塞给 B
                val current = itemId(itemOf(fragment))
                if (current != null && current != adamId) {
                    XLog.d("lyrics inject: re-entry skipped (fragment on ${current}, want $adamId)")
                    continue
                }
                runCatching { install.invoke(fragment, ptr) }
                    .onSuccess { XLog.i("lyrics inject: re-entered ${install.name} for adamId=$adamId") }
                    .onFailure { XLog.w("lyrics inject: re-entry failed: ${it.message}") }
            }
        }
    }

    /** fragment 还活着吗（`isAdded()`）；读不到就放行，宁可多试一次 */
    private fun isUsable(fragment: Any?): Boolean {
        if (fragment == null) return false
        return runCatching {
            (fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean) ?: true
        }.getOrDefault(true)
    }

    private fun remember(fragment: Any?, adamId: Long) {
        if (fragment == null) return
        synchronized(waiting) {
            waiting.removeAll { it.fragment.get() == null }
            waiting.firstOrNull { it.fragment.get() === fragment }?.let {
                // 同一个 fragment 换了歌：更新目标，避免旧的等待项把旧歌歌词塞进来
                if (it.adamId != adamId) {
                    waiting.remove(it)
                } else {
                    return
                }
            }
            if (waiting.size >= MAX_WAITING) waiting.removeAt(0)
            waiting += Waiting(WeakReference(fragment), adamId)
        }
    }

    private fun dismiss(fragment: Any?) {
        if (fragment == null) return
        synchronized(waiting) { waiting.removeAll { it.fragment.get() === fragment } }
    }

    // ═══════════════════════════ 给外部用的读取器 ═══════════════════════════

    /**
     * 供 [BackgroundLyrics] 调用：宿主这条路拿不到歌词句柄时，试试我们自己的源。
     *
     * 与 `I2` 路径共用同一份缓存与任务去重，所以两条通路不会重复请求同一首歌。
     *
     * @param nativePtr 宿主现有的句柄（null = 没有）。判定条件同 [eligible]。
     * @return 已就绪的 ptr（调用方直接拿去驱动引擎），未就绪返回 null
     */
    fun completionFor(
        storeId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        nativePtr: Any?,
    ): Any? {
        if (!Settings.autoComplete) return null
        val adamId = storeId?.toLongOrNull() ?: return null
        if (adamId <= 0L) return null
        currentAdamId = adamId

        cachedPtr(adamId)?.let { return it }
        if (adamId in gaveUp) return null
        if (title.isNullOrBlank()) return null
        val reason = eligible(nativePtr, title, adamId) ?: return null

        // 复用 I2 路径的任务队列与去重；取好后由 complete() → ReactApply 落到状态栏
        startCompletion(adamId, title, artist, album, durationMs, reason = reason)
        return cachedPtr(adamId)
    }

    /**
     * 宿主送来新的歌词句柄时，若这首歌**已经有补全结果**，就让补全结果胜出。
     *
     * 【为什么必须有这一步】真机实测（v1.4.0）：宿主会在我们补完之后**再次**
     * 解析并回传它自己那份（行级）歌词，把 [BackgroundLyrics] 里刚装好的句柄顶掉，
     * 于是状态栏又退回没有逐字的时间轴。既然我们已经判定"这份不合格"，
     * 就该一直用补出来的那份，直到切歌或关掉开关。
     *
     * @return 该用的句柄（可能是补全结果，也可能是宿主原来的）
     */
    fun preferredPtr(storeId: String?, native: Any?): Any? {
        if (!Settings.autoComplete) return native
        val adamId = storeId?.toLongOrNull() ?: return native
        currentAdamId = adamId
        val cached = cachedPtr(adamId)
        XLog.d(
            "preferredPtr: adamId=$adamId 采用=${if (cached != null) "补全结果" else "宿主原生"} " +
                "(raw=${readyPtrs[adamId] != null} alive=${readyPtrs[adamId]?.let { TtmlBridge.isAlive(it) }})"
        )
        return cached ?: native
    }

    /**
     * 当前这首歌正在用的歌词，是不是**我们补来的第三方歌词**。
     *
     * 【为什么要问这个】用户口径（v1.4.0）：
     * **原生歌词一律不做任何处理** —— 宿主给什么就显示什么，连它自带的制作名单行也算数。
     * 只有第三方源补来的歌词才需要清洗：QQ 的 QRC 会把 `作词：…`、`歌曲名 - 歌手`
     * 这类行当成**正文行**返回且带真实时间戳，不清就会顶在状态栏/播放页上。
     *
     * 判定条件 = 「已经知道当前是哪首歌」且「这首歌有存活着的补全结果」。
     * 这与 [preferredPtr] 末尾那句 `return cached ?: native` 是**同一个条件**，
     * 所以两者永远一致：只有我们真的在用补全结果时，这里才为 true。
     */
    fun isThirdPartyActive(): Boolean {
        if (!Settings.autoComplete) return false
        val adamId = currentAdamId
        return adamId > 0L && cachedPtr(adamId) != null
    }

    private fun cachedPtr(adamId: Long): Any? = synchronized(readyPtrs) {
        readyPtrs[adamId]?.takeIf { TtmlBridge.isAlive(it) }
    }

    /**
     * 当前**正在播放**歌曲的 adamId。
     *
     * 由 [BackgroundLyrics] 每个 tick 通过 [completionFor]、以及 [preferredPtr] 告知。
     * 它的唯一用途是 [itemOf]：歌词页 fragment 上有**不止一个** `BaseContentItem` 字段，
     * 必须挑出"和当前播放歌曲是同一首"的那个。
     */
    @Volatile private var currentAdamId: Long = 0L

    private fun itemOf(fragment: Any?): Any? {
        if (fragment == null) return null
        val expected = currentAdamId

        // 快路径：上次选中的字段如果依然对得上，就不必再扫一遍
        liveItemField?.let { f ->
            val v = runCatching { f.get(fragment) }.getOrNull()
            if (v != null && (expected <= 0L || itemId(v) == expected)) return v
        }

        val values = itemFields.mapNotNull { f ->
            runCatching { f.get(fragment) }.getOrNull()?.let { f to it }
        }
        if (values.isEmpty()) return null

        // 认 id：★ 真机实测（6.5.2）fragment 上 `e.W` 拿到的是 **6805436921**，
        // 而当前播放的是 **6805436926** —— 同一张专辑里的不同条目。
        // 选错这一个字段的后果是"整条播放页注入静默失效"（判定对象、歌名、歌曲标识全错），
        // 所以不能靠"第一个非空的字段"，必须按 id 认人。
        if (expected > 0L) {
            values.firstOrNull { itemId(it.second) == expected }?.let { (f, v) ->
                liveItemField = f
                return v
            }
            XLog.d("itemOf: 没有字段匹配当前播放歌曲($expected)，本次不动")
            return null
        }

        // 兜底（还没感知到播放歌曲时）：认类型 —— 当前曲目一定是 PlaybackItem，
        // 只有它有 hasCustomLyrics/customLyrics，BaseContentItem 本身没有。
        val playback = playbackItemClass
        val pick = values.firstOrNull { playback?.isInstance(it.second) == true } ?: values.first()
        liveItemField = pick.first
        return pick.second
    }

    private fun itemId(item: Any?): Long? = when (val id = Reflect.call(item, "getId")) {
        is String -> id.toLongOrNull()
        is Number -> id.toLong()
        else -> null
    }?.takeIf { it > 0L }

    private fun hasCustomLyrics(item: Any?): Boolean {
        if (item == null) return false
        val cls = playbackItemClass ?: return false
        if (!cls.isInstance(item)) return false
        return Reflect.call(item, "hasCustomLyrics") as? Boolean ?: false
    }
}
