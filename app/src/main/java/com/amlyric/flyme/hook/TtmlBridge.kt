package com.amlyric.flyme.hook

import com.amlyric.flyme.XLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Apple Music 自己的 TTML 解析入口 —— 把「外部歌词文本」变成官方引擎认的 `SongInfoPtr`。
 *
 * ══════════════════════════ 为什么必须走这条路 ══════════════════════════
 *
 * 我们自己解析外部歌词（QQ / 网易云）能拿到文本和时间轴，但**只有官方引擎认的
 * `SongInfoPtr` 才能同时喂给两条上屏通路**：
 *  · 状态栏：`LyricsEngineDriver` 用 `SongInfoTimeProcessor.processEvents(ptr, ...)` 驱动；
 *  · App 内歌词页：官方把自己解析出来的 ptr 交给歌词视图，逐字高亮也走同一套引擎。
 * 所以最优解不是"另造一套播放器"，而是**借官方解析器把外部 TTML 变成 ptr**。
 *
 * ══════════════════════════ 宿主里的真实入口（6.5.2 / vc1586 实测存在）══════════════════════
 *
 * ```
 * TTMLParser$TTMLParserNative.songInfoFromTTML(String) -> SongInfo$SongInfoPtr
 *   SongInfo$SongInfoPtr.get()                          -> SongInfo$SongInfoNative
 *     getAdamId() / setAdamId(long)   ← 写歌曲标识，官方会拿它做身份校验
 *     getSections()                   ← 段落向量；size()==0 即"没有歌词"
 *     getAvailableTiming()            ← 时间轴类型（Word / Line）
 * ```
 *
 * 这条链路上的方法**全部是 JNI native 方法**（`acc & 0x100`），native 名字必须和 C++ 侧
 * 对上，所以 R8 **改不了它们的名字** —— 这是整个工程里唯一能安全写死名字的一批符号。
 * （对比：`androidx.preference` 那边全被压成单字母，见 [SettingsInjector]。）
 *
 * ══════════════════════════ native 指针的生命周期（重要）═════════════════════════
 *
 * 这些 Ptr 是 JavaCPP 的包装（基类 `org.bytedeco.javacpp.Pointer`），官方会在歌词页
 * `onDestroyView` 时 `deallocate()`，把 native 地址清零，而 Java 侧对象可能还活着。
 * 所以每次使用前都要查一次"还活着吗"（读基类的 `address` 字段，或调 `isNull`），
 * 死指针一律丢弃重取，绝不拿它去调 native —— 否则就是 use-after-free。
 */
object TtmlBridge {

    private const val CLS_PARSER =
        "com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative"
    private const val CLS_PTR =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr"
    private const val CLS_NATIVE =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative"
    private const val CLS_JAVACPP_POINTER = "org.bytedeco.javacpp.Pointer"

    private var classLoader: ClassLoader? = null

    @Volatile private var parser: Any? = null
    @Volatile private var parseMethod: Method? = null
    @Volatile private var ptrGet: Method? = null
    @Volatile private var sectionsMethod: Method? = null
    @Volatile private var sectionsSizeMethod: Method? = null
    @Volatile private var timingMethod: Method? = null
    @Volatile private var adamIdGet: Method? = null
    @Volatile private var adamIdSet: Method? = null

    /** JavaCPP `Pointer.address`：0 表示 native 侧已释放 */
    @Volatile private var addressField: Field? = null

    @Volatile private var resolved = false
    @Volatile private var unavailableReason: String? = null

    /** 上次日志里说过的原因（避免每 tick 刷屏） */
    @Volatile private var lastComplaint: String? = null

    val isReady: Boolean get() = resolved && unavailableReason == null

    fun init(cl: ClassLoader) {
        classLoader = cl
    }

    /**
     * 解析 TTML → `SongInfoPtr`。
     *
     * @return 解析成功且指针存活时返回 ptr；任何异常/不可用都返回 null（调用方按"取词失败"处理）
     */
    fun parse(ttml: String): Any? {
        if (ttml.isBlank()) return null
        resolve()
        val p = parser
        val m = parseMethod
        if (p == null || m == null) {
            complain(unavailableReason ?: "parser unavailable")
            return null
        }
        return runCatching { m.invoke(p, ttml) }
            .onFailure { complain("songInfoFromTTML threw: ${it.cause?.message ?: it.message}") }
            .getOrNull()
            ?.takeIf { isAlive(it) }
    }

    /** 把歌曲标识写进 ptr —— 官方多处会拿它跟当前播放项比对，不写会"身份不符"被忽略 */
    fun bindAdamId(ptr: Any, adamId: Long): Boolean {
        resolve()
        val native = nativeOf(ptr) ?: return false
        val set = adamIdSet ?: return false
        return runCatching {
            set.invoke(native, adamId)
            adamIdOf(ptr) == adamId
        }.getOrDefault(false)
    }

    fun adamIdOf(ptr: Any?): Long? {
        val native = nativeOf(ptr) ?: return null
        return runCatching { (adamIdGet?.invoke(native) as? Number)?.toLong() }.getOrNull()
    }

    /** 段落数；> 0 表示这份 TTML 真的被解析出了歌词 */
    fun sectionCount(ptr: Any?): Int {
        resolve()
        val native = nativeOf(ptr) ?: return 0
        val sections = runCatching { sectionsMethod?.invoke(native) }.getOrNull() ?: return 0
        return runCatching { (sectionsSizeMethod?.invoke(sections) as? Number)?.toInt() }.getOrNull() ?: 0
    }

    /**
     * 时间轴类型（枚举常量名，如 `Word` / `Line` / `None`）。
     *
     * 用来证明注入的 TTML **真的被认成逐字**：宿主原始歌词常常是 `Line` / `None`，
     * 补全后应当变成 `Word`。读不到返回 null。
     */
    fun availableTiming(ptr: Any?): String? {
        resolve()
        val native = nativeOf(ptr) ?: return null
        val timing = runCatching { timingMethod?.invoke(native) }.getOrNull() ?: return null
        return (timing as? Enum<*>)?.name ?: timing.javaClass.simpleName
    }

    /** JavaCPP 包装是否还持有 native 地址 */
    fun isAlive(ptr: Any?): Boolean {
        if (ptr == null) return false
        resolve()
        val f = addressField ?: return true // 认不出 liveness 表面时只能放行（best effort）
        return runCatching { f.getLong(ptr) != 0L }.getOrDefault(true)
    }

    /** 诊断用的一句话描述 */
    fun describe(ptr: Any?): String {
        if (ptr == null) return "ptr=null"
        return "ptr=${ptr.javaClass.name} alive=${isAlive(ptr)} " +
            "adamId=${adamIdOf(ptr)} sections=${sectionCount(ptr)}"
    }

    // ─────────────────────────── 解析句柄 ───────────────────────────

    private fun resolve() {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            val cl = classLoader
            if (cl == null) {
                unavailableReason = "not initialized"
                resolved = true
                return
            }
            runCatching {
                val parserClass = cl.loadClass(CLS_PARSER)
                val ptrClass = cl.loadClass(CLS_PTR)
                val nativeClass = cl.loadClass(CLS_NATIVE)

                val ctor = parserClass.getDeclaredConstructor()
                ctor.isAccessible = true
                val instance = ctor.newInstance()

                // 只按「参数 String、返回类型是 SongInfoPtr」定位，不写死方法名之外的东西
                val method = parserClass.declaredMethods.firstOrNull {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == ptrClass
                } ?: throw NoSuchMethodException("songInfoFromTTML(String)")

                val get = ptrClass.getDeclaredMethod("get").apply { isAccessible = true }
                val sections = nativeClass.getDeclaredMethod("getSections").apply { isAccessible = true }
                val size = sections.returnType.getMethod("size").apply { isAccessible = true }
                val timing = nativeClass.getDeclaredMethod("getAvailableTiming").apply { isAccessible = true }
                val idGet = nativeClass.getDeclaredMethod("getAdamId").apply { isAccessible = true }
                val idSet = nativeClass
                    .getDeclaredMethod("setAdamId", Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }

                parser = instance
                parseMethod = method
                ptrGet = get
                sectionsMethod = sections
                sectionsSizeMethod = size
                timingMethod = timing
                adamIdGet = idGet
                adamIdSet = idSet
                addressField = findAddressField(ptrClass)
                XLog.i(
                    "TtmlBridge ready: ${method.name}(String) -> ${ptrClass.simpleName}, " +
                        "liveness=${if (addressField != null) "Pointer.address" else "unchecked"}"
                )
            }.onFailure {
                unavailableReason = it.message ?: it.javaClass.simpleName
                XLog.w("TtmlBridge unavailable: $unavailableReason（外部歌词将无法注入官方引擎）")
            }
            resolved = true
        }
    }

    /** 沿继承链找 JavaCPP 的 `address` 字段（native 地址清零 = 已释放） */
    private fun findAddressField(ptrClass: Class<*>): Field? {
        var c: Class<*>? = ptrClass
        while (c != null) {
            if (c.name == CLS_JAVACPP_POINTER) {
                return runCatching { c.getDeclaredField("address").apply { isAccessible = true } }
                    .getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun nativeOf(ptr: Any?): Any? {
        if (ptr == null) return null
        resolve()
        if (!isAlive(ptr)) return null
        return runCatching { ptrGet?.invoke(ptr) }.getOrNull()
    }

    private fun complain(reason: String) {
        if (lastComplaint == reason) return
        lastComplaint = reason
        XLog.w("TtmlBridge: $reason")
    }

    // ─────────────────────────── 开机自检 ───────────────────────────

    /**
     * 自检：用一份最小 TTML 走一遍「解析 → 绑定 adamId → 读段落 → 让官方引擎求值」，把结果写进日志。
     *
     * 目的只有一个：**在不联网、不播放的前提下，验证外部歌词注入这条链路是通的**。
     * 只做解析与求值，不碰播放中的 ptr、不上屏，所以对正常播放零影响。
     *
     * @param peek 由调用方提供的"在某个位置求值"能力（用官方引擎的静默回调，见
     *   `BackgroundLyrics.probeLineAt`）；传 null 只验到"解析成功"这一步
     */
    fun selfTest(peek: ((Any, Long) -> String?)? = null) {
        val ptr = parse(sampleTtml())
        if (ptr == null) {
            XLog.e("TtmlBridge selfTest FAILED at parse: ${unavailableReason ?: "returned null"}", null)
            return
        }
        val bound = bindAdamId(ptr, SAMPLE_ID)
        XLog.i(
            "TtmlBridge selfTest: parsed OK, bindAdamId=$bound, " +
                "sections=${sectionCount(ptr)}, ${describe(ptr)}"
        )
        if (peek == null) return
        // 时间轴：L1 = 1.0s~4.0s（"自检"1.0~2.5 + "歌词"2.5~4.0），L2 = 4.0s~7.0s
        val probes = listOf(500L, 1500L, 3000L, 4500L, 6500L, 8000L)
        val out = probes.joinToString(", ") { pos ->
            "${pos}ms=${peek(ptr, pos) ?: "-"}"
        }
        XLog.i("TtmlBridge selfTest engine: $out")
    }

    /** 模块自己产生的 TTML 都要能被认出来（见 [LyricsInjector.onNativeTtml]） */
    const val SELF_TEST_MARK = "amlyric-selftest"

    private const val SAMPLE_ID = 3933000001L

    /** 最小可解析的 Word 逐字 TTML（结构照 AMLL TTML DB 的约定，见 [TtmlWriter]） */
    private fun sampleTtml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        // 自检样例也是走宿主解析器，会在解析 Hook 里露一次面 —— 带上标记让判据忽略它
        append("<!--").append(SELF_TEST_MARK).append("-->\n")
        append("<tt xmlns=\"http://www.w3.org/ns/ttml\"")
        append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"")
        append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
        append(" xml:lang=\"ko\" itunes:timing=\"Word\">")
        append("<head><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">")
        append("<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">")
        append("<text for=\"L1\">自检译文一</text><text for=\"L2\">自检译文二</text>")
        append("</translation></translations></iTunesMetadata></head>")
        append("<body><div>")
        append("<p itunes:key=\"L1\" begin=\"00:00:01.000\" end=\"00:00:04.000\" ttm:agent=\"v1\">")
        append("<span begin=\"00:00:01.000\" end=\"00:00:02.500\">自检</span>")
        append("<span begin=\"00:00:02.500\" end=\"00:00:04.000\">歌词</span></p>")
        append("<p itunes:key=\"L2\" begin=\"00:00:04.000\" end=\"00:00:07.000\" ttm:agent=\"v1\">")
        append("<span begin=\"00:00:04.000\" end=\"00:00:05.500\">第二</span>")
        append("<span begin=\"00:00:05.500\" end=\"00:00:07.000\">句啊</span></p>")
        append("</div></body></tt>")
    }
}
