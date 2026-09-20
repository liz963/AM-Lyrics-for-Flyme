package com.amlyric.flyme.hook

import android.os.Handler
import android.os.Looper
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.core.BackgroundLyrics
import com.amlyric.flyme.lyric.LyricFetcher
import com.amlyric.flyme.lyric.TtmlWriter
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
 * 打开时只要**宿主拿不出能滚动的歌词**就取词（[eligible]）：
 *  · `args[0] == null`   —— 根本没交句柄（宿主确实没有这首歌的歌词）；
 *  · 句柄的 `getAvailableTiming()` 既不是 `Word` 也不是 `Line` —— 见下。
 *
 * **判定信号（v1.4.6 用户口径，唯一判据，见 [eligible]）**：
 *  ① `timing = Word`（逐字）/ `Line`（逐行）→ **不替换**（播放页与状态栏都会滚动）；
 *  ② `timing = None`（有歌词数据但静态不滚）→ **替换**；
 *  ③ `timing` 读不到 → **替换**（保守取向：证明不了"能滚"就当不能滚）。
 *
 * ⚠️ **创作者名单（songwriters）自 v1.4.6 起不参与决策**。v1.4.2~v1.4.5 曾用它当判据，
 * 2026-09-20 真机 16 首实测把它证伪：逐行的 `Line` 原生歌词**全都带名单**，
 * 而名单只证明"宿主有歌词数据"，证明不了"这份歌词能滚"。读取代码保留，仅供日志排查
 * （见 [hasNativeCredits]）。
 *
 * 另外三条边界：
 *  · **伴奏 / 纯音乐轨一律不补**（按标题关键词判定，这类轨本来就没有词可唱）；
 *  · `PlaybackItem.hasCustomLyrics()` 为真时一律不动（用户自己配的歌词优先）；
 *  · 结果落地前若宿主交出了原生歌词 → 整份丢弃（见 [hostHasLyrics]，反应用户口径的赛跑）。
 *
 * ⚠️ 判据全部来自 ptr 自身（`getAvailableTiming / getLanguage / getTranslation`），
 * **不需要**原始 TTML 文本 —— 宿主解析完就把文本丢了，我们拿不到。这也是为什么
 * 只有走到了 `I2`（拿到 ptr）才能做判定，比"提前猜"更可靠。
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

    /** 创作者名单最多读几条（只用于判定"有没有"，不需要全文） */
    private const val MAX_CREDITS = 16

    // ─────────────────────────── 解析出来的句柄 ───────────────────────────

    @Volatile private var installMethod: Method? = null
    @Volatile private var ptrClass: Class<*>? = null
    @Volatile private var playbackItemClass: Class<*>? = null

    /** fragment 继承链上所有声明类型为 `BaseContentItem` 的字段（最近优先） */
    @Volatile private var itemFields: List<Field> = emptyList()

    /** 实测命中的那个字段（首次读到非 null 值时定下来，避免每次都遍历） */
    @Volatile private var liveItemField: Field? = null

    /** 读创作者名单用的宿主 JNI 方法（`getSongwriters`），只解一次 */
    @Volatile private var creditsMethod: Method? = null
    @Volatile private var creditsMethodResolved = false

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

    /**
     * 宿主**自己**交出过原生歌词的 adamId。
     *
     * 【为什么需要它】"这首歌到底有没有原生歌词"只能等宿主亲口说 —— 而宿主取词是异步的，
     * 反应用户口径（**有原生歌词就不替换**）的时机因此变成一场赛跑：
     * ```
     * 切歌 → 宿主还没取到歌词（I2(null)）→ 宽限期到点 → 我们开始联网取词
     *      → 几百毫秒后宿主把它自己那份原生歌词交出来了 ← 我们却已经把第三方的注入进去了
     * ```
     * 所以在"结果就要落地"的最后关口再复核一次：只要宿主期间给出过原生歌词，就整份丢弃。
     *
     * 只增不减，靠上限整体清空（真正的淘汰由"每首歌只判一次"的用法决定）。
     */
    private val hostHasLyrics = HashSet<Long>()

    /**
     * 已确认**带创作者名单**（songwriters）的歌曲 —— 判定为"宿主确有原生歌词"，永久不替换。
     *
     * ⚠️ 自 v1.4.6 起**不参与决策**，仅作日志排查（名单判据已被真机数据证伪，
     * 见 [eligible] 的口径演进）。
     */
    private val nativeCredits = HashSet<Long>()

    /**
     * **宿主原生 TTML 里有没有 `<songwriters>`**（adamId → 有/无）。
     *
     * 由 [onNativeTtml] 在宿主解析原生歌词时观察得到。
     * ⚠️ 自 v1.4.6 起**不参与决策**（见 [hasNativeCredits]）。
     */
    private val nativeTtmlCredits = HashMap<Long, Boolean>()

    /**
     * 上次探测创作者名单的时刻（adamId → 毫秒）。
     *
     * 【为什么要节流】[eligible] 有两个高频入口（`I2` 每次装歌词 + 状态栏轮询每 tick），
     * 而读名单要跨 JNI 拿一个新对象出来。没有名单的歌（正是要被替换的那些）
     * 结果不会被缓存，不节流就是每秒两次 JNI 分配。
     */
    private val creditsProbedAt = HashMap<Long, Long>()

    /** 名单命中时只打一次日志（否则每 3 秒一条） */
    private val creditsLogged = HashSet<Long>()

    /**
     * 最近一份**原生** TTML 有没有创作者名单（认不出它是哪首歌时的兜底，见 [onNativeTtml]）。
     *
     * ⚠️ 自 v1.4.6 起**不参与决策**（这条兜底会跨歌误报：A 歌的名单可能记到 B 歌头上，
     * 见 [eligible] 的口径演进）。
     *
     * 【为什么当初需要它】真机实测（v1.4.2）：宿主调 `songInfoFromTTML` 时，**返回的 ptr 上还没有歌曲标识**
     * （实测是未初始化的垃圾负值 —— 标识是调用方随后写进去的，我们自己注入时也是自己 `bindAdamId`），
     * 于是那份 TTML 的观察结果没法记账。
     */
    @Volatile private var recentTtmlHasWriters = false
    @Volatile private var recentTtmlAt = 0L

    /** 兜底窗口：解析与判定的间隔通常在同一次切歌流程内（秒级） */
    private const val RECENT_TTML_WINDOW_MS = 15_000L

    /** 探测节流窗口 */
    private const val CREDITS_PROBE_INTERVAL_MS = 3_000L

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

        // 宿主这一帧交出的是**能用的原生歌词**（带创作者名单 / 逐字时间轴）→ 记账，
        // 供结果落地前的最后关口复核（见 [hostHasLyrics]）。判据与 [eligible] 同一口径，
        // 见 [hasNativeLyrics]。
        if (hasNativeLyrics(original, adamId)) markHostLyrics(adamId)

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
     * **判定顺序（v1.4.6，改前必读）—— 唯一判据是时间轴类型：**
     *  ① 伴奏 / 纯音乐轨 → 不补（标题关键词，这类轨本来就没词可唱）；
     *  ② `original == null` → **补**（宿主根本没交句柄）；
     *  ③ `timing = Word / Line` → **不补**（逐字与逐行，在播放页/状态栏都会滚动）；
     *  ④ `timing = None` → **补**（有歌词数据但静态不滚）；
     *  ⑤ `timing` 读不到 → **补**（保守取向：证明不了"能滚"就当不能滚）。
     *
     * ⚠️ 口径演进（别再来回改）：
     *  · 最早"外语逐字缺翻译也补" → 会把宿主带翻译的歌词换成外部的，翻译反而丢；
     *  · 中间试过"原生歌词带创作者名单就不补"（当时指定的信号：歌词页那行「创作者：xxx」）。
     *    **真机数据（2026-09-20，16 首实测）推翻了它**：逐行的 `Line` 原生歌词**全都带名单**，
     *    而名单只能证明"宿主有歌词数据"，证明不了"这份歌词能滚"。用户最终口径是
     *    「能滚就别动，不能滚才补」—— 于是名单判据**整体退出决策**（读取代码保留，仅作诊断）。
     *
     * @param adamId 用于日志去重（这个入口会被高频调用）
     */
    private fun eligible(original: Any?, title: String, adamId: Long): String? {
        if (isInstrumental(title)) return skip(adamId, "伴奏/纯音乐轨('$title')")
        if (original == null) return "原生歌词缺失(宿主没交句柄)"
        val timing = originalTiming(original) ?: return "时间轴读不到"
        if (isRollingTiming(timing)) return skip(adamId, "原生歌词能滚动(timing=$timing)")
        return "原生歌词不滚动(timing=$timing)"
    }

    /**
     * 宿主的原生歌词是不是**能滚**的 —— v1.4.6 起唯一的决策判据。
     *
     * · `Word`（字级时间轴）：官方引擎逐字高亮，最完整的一档；
     * · `Line`（行级时间轴）：播放页与状态栏同样**逐行滚动**，算"能滚"
     *   （真机实测 2026-09-20：一批 Line 原生歌词滚动正常，用户确认不替换）。
     *
     * 剩下的 `None`（静态）与"读不到"一律走"补"（见 [eligible]）。
     */
    private fun isRollingTiming(timing: String): Boolean =
        timing.contains("word", ignoreCase = true) || timing.contains("line", ignoreCase = true)

    /**
     * 宿主交给我们的这份句柄，是不是**一份能滚的原生歌词**（口径同 [eligible]）。
     *
     * 供 `hostHasLyrics` 的记账使用：这是"结果落地前再复核一次"的判据，
     * **必须和 [eligible] 保持同一口径** —— 否则要么把刚补好的歌词在落地前丢掉
     * （复核比判定更宽松），要么判定说不补、复核又说宿主没词（更严格）。
     *
     * `None` / 读不到 → false：这类歌词正是我们要替换掉的，不能算"能用的原生歌词"。
     *
     * @param adamId 目前不参与判定，仅为调用点签名统一保留
     */
    private fun hasNativeLyrics(original: Any?, adamId: Long): Boolean {
        if (original == null) return false
        val timing = originalTiming(original) ?: return false
        return isRollingTiming(timing)
    }

    /**
     * 原生句柄里有没有**创作者名单**（songwriters）。
     *
     * 名单来自宿主自己解析 TTML 的结果（`<iTunesMetadata><songwriters>`），
     * 在播放页会渲染成一行「创作者：xxx」。
     *
     * ⚠️ **自 v1.4.6 起不参与任何决策**（[eligible] 只看时间轴类型）。
     * 保留整条读取链只为排查用：真机日志里那句
     * `native credits: 命中创作者名单 …` 是判断"宿主这份歌词到底带不带名单"的唯一手段。
     * 读法见 [nativeSongwriters]，任何异常一律当作"没有"。
     */
    @Suppress("unused")
    private fun hasNativeCredits(original: Any?, adamId: Long): Boolean {
        if (original == null) return false
        synchronized(nativeCredits) { if (adamId in nativeCredits) return true }

        // ① 文本侧：宿主解析原生 TTML 时已经记下这首歌有没有 `<songwriters>`（见 [onNativeTtml]）
        val fromTtml = synchronized(nativeTtmlCredits) { nativeTtmlCredits[adamId] }
        if (fromTtml == true) {
            markNativeCredits(adamId, "原生 TTML 的 <songwriters>")
            return true
        }

        // ② 兜底：认不出歌的那份"最近一份原生 TTML"（见 [recentTtmlHasWriters]）
        val now = System.currentTimeMillis()
        if (recentTtmlHasWriters && now - recentTtmlAt < RECENT_TTML_WINDOW_MS) {
            markNativeCredits(adamId, "最近一份原生 TTML 的 <songwriters>")
            return true
        }

        // ③ 句柄侧：直接问 JNI（作为补充，参数语义不确定，见 [nativeSongwriters]）
        synchronized(creditsProbedAt) {
            val last = creditsProbedAt[adamId]
            if (last != null && now - last < CREDITS_PROBE_INTERVAL_MS) return false
            if (creditsProbedAt.size > 64) creditsProbedAt.clear()
            creditsProbedAt[adamId] = now
        }

        val list = nativeSongwriters(original)
        if (list.isEmpty()) return false
        markNativeCredits(adamId, "句柄 getSongwriters ${list.take(4)}")
        return true
    }

    /** 记下"这首歌有创作者名单"，并打一次日志（[source] 说明是哪条路读到的） */
    private fun markNativeCredits(adamId: Long, source: String) {
        synchronized(nativeCredits) {
            if (nativeCredits.size > 64) nativeCredits.clear()
            nativeCredits.add(adamId)
        }
        synchronized(creditsLogged) {
            if (creditsLogged.add(adamId)) {
                XLog.i("native credits: 命中创作者名单 adamId=$adamId ← $source")
            }
        }
    }

    /**
     * 调宿主 JNI 读创作者名单：`SongInfo$SongInfoNative.getSongwriters(...)` → `StringVectorNative`。
     *
     * 【为什么可以写死方法名】这一族是 JavaCPP 的 native 绑定（`com.apple.*.javanative.*`），
     * 名字必须和 C++ 侧对上，R8 改不了 —— 与 `getAvailableTiming`/`getSections` 同理
     * （真机 6.5.2 classes3.dex 核对：`getSongwriters(byte)`、`getSongwriter(byte, byte)`，
     * 返回 `StringVector$StringVectorNative`，该向量有 `size()` 与 `get(long)`）。
     *
     * 【参数为什么按类型现填】native 方法的参数类型在 dex 里是 `byte` 这类窄类型，
     * 直接传 Int 会 `IllegalArgumentException`。所以统一按 [defaultArg] 的类型适配填值，
     * 不假定任何签名 —— 换成 0 参重载也能用。
     *
     * @return 名单（去空行、最多 [MAX_CREDITS] 条）；任何一步失败都返回空列表
     */
    private fun nativeSongwriters(original: Any?): List<String> {
        val native = runCatching { Reflect.call(original, "get") }.getOrNull() ?: return emptyList()
        val method = songwritersMethod(native.javaClass) ?: return emptyList()
        val vector = runCatching { invokeDefaults(native, method) }.getOrNull() ?: return emptyList()
        val size = (runCatching { Reflect.call(vector, "size") }.getOrNull() as? Number)?.toInt() ?: 0
        if (size <= 0) return emptyList()
        val getter = findMethod(vector.javaClass, "get", 1) ?: return emptyList()

        val out = ArrayList<String>(minOf(size, MAX_CREDITS))
        for (i in 0 until minOf(size, MAX_CREDITS)) {
            val item = runCatching { invokeIndexed(vector, getter, i.toLong()) }.getOrNull()
            val text = when (item) {
                is String -> item
                null -> null
                else -> runCatching { item.toString() }.getOrNull()
            }
            // 我们自己写进去的来源标注不算"原生名单"（否则会拿自己的标注骗自己）
            text?.trim()?.takeIf { it.isNotEmpty() && it != TtmlWriter.SOURCE_LABEL }?.let { out += it }
        }
        return out
    }

    /**
     * 宿主自己解析了一份 TTML 之后的通报（由 [AppleMusicHooks] 的解析 Hook 调用）。
     *
     * 【为什么还要盯文本】播放页那行「创作者：xxx」就是宿主从原生 TTML 的
     * `<iTunesMetadata><songwriters>` 渲染出来的，**信息本来就在文本里**；
     * 而从句柄读要依赖 `getSongwriters(String)` 这个 JNI 接口的参数语义
     * （真机日志只告诉我们参数是 String，传什么值才返回内容并不确定）。
     * 所以文本侧是本版判据的主来源，句柄侧只作补充。
     *
     * @param ttml 宿主解析的原始文本；**我们自己的注入会被排除**（见 [TtmlWriter.SOURCE_LABEL]，
     *   我们写标注时用的是一个固定串，靠它区分来源，否则会拿自己的标注骗自己）
     * @param ptr  解析产物，用它的 adamId 认歌
     */
    fun onNativeTtml(ttml: String?, ptr: Any?) {
        if (ttml.isNullOrBlank()) return
        // 来源三分：自注入（我们灌进去的）与自检样例都不算"原生歌词"，不能拿来判定
        val source = when {
            ttml.contains(TtmlWriter.SOURCE_LABEL) -> "own"
            ttml.contains(TtmlBridge.SELF_TEST_MARK) -> "selftest"
            else -> "native"
        }
        val has = ttml.contains("<songwriter", ignoreCase = true)
        val ptrId = TtmlBridge.adamIdOf(ptr)
        // ⚠️ 宿主解析时 **ptr 上还没有歌曲标识**：实测 `getAdamId()` 返回的是未初始化的垃圾
        // （形如 -5476376653955703808）。所以只有**正数**才可信，其余一律当"认不出"。
        // 【绝不能退而用 currentAdamId 记账】它更新滞后于解析（实测落后一首），
        // 会把 B 歌的名单状态写到 A 头上 → 要么少补一首，要么白白替换掉有歌词的歌。
        val adamId = ptrId?.takeIf { it > 0L } ?: 0L
        // 现场证据：这条日志是判断"文本侧判据到底有没有在工作"的唯一入口，
        // 缺了它就只能靠猜（v1.4.2 就吃过这个亏：所有调用都因认不出歌而被静默丢弃）
        XLog.d(
            "native ttml: $source len=${ttml.length} songwriters=$has ptrAdamId=$ptrId 采用=$adamId"
        )
        if (source != "native") return

        if (adamId <= 0L) {
            // 认不出歌也要留个案底：解析紧跟着这首歌的加载，判定时用"最近一份"兜底（见 [recentTtmlHasWriters]）
            recentTtmlHasWriters = has
            recentTtmlAt = System.currentTimeMillis()
            return
        }
        synchronized(nativeTtmlCredits) {
            if (nativeTtmlCredits.size > 128) nativeTtmlCredits.clear()
            nativeTtmlCredits[adamId] = has
        }
        if (has) markNativeCredits(adamId, "原生 TTML 的 <songwriters>")
    }

    /** 定位 `getSongwriters`（0/1 参）或 `getSongwriter`（2 参），只解一次 */
    private fun songwritersMethod(nativeClass: Class<*>): Method? {
        if (creditsMethodResolved) return creditsMethod
        synchronized(this) {
            if (creditsMethodResolved) return creditsMethod
            creditsMethodResolved = true
            creditsMethod = findMethod(nativeClass, "getSongwriters", 1)
                ?: findMethod(nativeClass, "getSongwriters", 0)
                ?: findMethod(nativeClass, "getSongwriter", 2)
            XLog.i(
                "native credits probe: " + (
                    creditsMethod?.let {
                        "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})"
                    } ?: "getSongwriters 不存在（本版只能按时间轴判定）"
                    )
            )
        }
        return creditsMethod
    }

    /** 沿继承链找方法（native 方法声明在 JavaCPP 子类上，父类可能也有同名重载） */
    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == argc }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return cls.methods.firstOrNull { it.name == name && it.parameterCount == argc }
            ?.also { it.isAccessible = true }
    }

    /**
     * 按参数类型填默认值调用。
     *
     * `String` 参数填**宿主自己的语言**（`getLanguage()`，拿不到才用空串）：
     * `getSongwriters(String)` 的参数多半就是"要哪个语种的创作者名"，
     * 传空串很可能拿到空结果，传 null 更糟（见 [defaultArg]）。
     */
    private fun invokeDefaults(target: Any, m: Method): Any? {
        val lang = (runCatching { Reflect.call(target, "getLanguage") }.getOrNull() as? String).orEmpty()
        val args = Array<Any?>(m.parameterCount) { i ->
            val t = m.parameterTypes[i]
            if (t == String::class.java || t == CharSequence::class.java) lang
            else defaultArg(t, null)
        }
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private fun invokeIndexed(target: Any, m: Method, index: Long): Any? {
        val args = Array<Any?>(m.parameterCount) { i ->
            defaultArg(m.parameterTypes[i], if (i == 0) index else null)
        }
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    /** 按参数类型造默认实参；`index != null` 时用作第 0 个参数（见 [nativeSongwriters]） */
    private fun defaultArg(type: Class<*>, index: Long?): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> index != null && index != 0L
        Byte::class.javaPrimitiveType -> (index ?: 0L).toByte()
        Short::class.javaPrimitiveType -> (index ?: 0L).toShort()
        Int::class.javaPrimitiveType -> (index ?: 0L).toInt()
        Long::class.javaPrimitiveType -> index ?: 0L
        Char::class.javaPrimitiveType -> 0.toChar()
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        // String 参数由 [invokeDefaults] 用宿主自己的语言填，这里只兜底给空串 —— **绝不传 null**：
        // `getSongwriters` 实测是 `(String)`（真机日志：`native credits probe: getSongwriters(String)`），
        // 传 null 会被 JNI 侧当"没有语言"处理。
        String::class.java, CharSequence::class.java -> ""
        else -> null
    }

    /** 读原生句柄的时间轴类型（枚举常量名，如 Word / Line）；读不到返回 null（仅为日志服务） */
    private fun originalTiming(original: Any?): String? = runCatching {
        val native = Reflect.call(original, "get") ?: return null
        (Reflect.call(native, "getAvailableTiming") as? Enum<*>)?.name
    }.getOrNull()

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
            // 排队这段时间里宿主可能已经把原生歌词取回来了：那就根本不该再取（用户口径：
            // 有原生歌词就不替换）。放在联网之前，能省掉一次白跑的网络请求。
            if (hostHadLyrics(adamId)) {
                XLog.i("lyrics inject: 放弃取词 —— 期间宿主已给出原生歌词 | '$title'")
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
            // ★ 最后一道关口：取词这几百毫秒里，宿主很可能已经把它自己那份原生歌词取回来了。
            // 一旦如此就整份丢弃 —— 用户口径是"有原生歌词就不替换"，哪怕我们这份是逐字的。
            if (hostHadLyrics(adamId)) {
                XLog.i("lyrics inject: 放弃注入 —— 期间宿主已给出原生歌词 | '$title'")
                forget(adamId)
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

    /** 记下"宿主确实有这首歌的原生歌词"（见 [hostHasLyrics]） */
    private fun markHostLyrics(adamId: Long) {
        if (adamId <= 0L) return
        synchronized(hostHasLyrics) {
            if (hostHasLyrics.size > 128) hostHasLyrics.clear()
            hostHasLyrics.add(adamId)
        }
    }

    /** 宿主在本次补全期间是否已经交出原生歌词 */
    private fun hostHadLyrics(adamId: Long): Boolean =
        synchronized(hostHasLyrics) { adamId in hostHasLyrics }

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
        // 宿主自己送来一份（不是我们回灌给它的那份）→ 它确实有原生歌词，记账。
        // 这是"宽限期误判之后才发现宿主其实有词"的主要发现途径：宿主常常不重调 I2，
        // 只走 LyricsLoader.onPtrCaptured 这条路把句柄交过来。
        if (native !== cached && hasNativeLyrics(native, adamId)) markHostLyrics(adamId)
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
