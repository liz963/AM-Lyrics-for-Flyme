package com.amlyric.flyme.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.amlyric.flyme.LyricSource
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.flyme.FlymeStatusBarLyric
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 在 Apple Music「设置」页注入我们的开关（v1.4.0 **重写**）。
 *
 * ══════════════════════════ 为什么必须重写 ══════════════════════════
 *
 * 旧实现（≤v1.3.17）写的是：
 * ```
 * classLoader.loadClass("androidx.preference.PreferenceFragmentCompat")   // ← 宿主里不存在
 * fragClass.getDeclaredMethod("setPreferenceScreen", PreferenceScreen)    // ← 方法名也被改了
 * if (activity.javaClass.name !in setOf("...settings.activity.SettingsActivity", ...)) // ← 类名也不存在
 * ```
 * 三段假设**在宿主里全部不成立**，异常被 runCatching 吞掉，连日志都没留，
 * 所以「状态栏歌词」那一行从来没出现过。
 *
 * 从真机 `base.apk`（Apple Music 6.5.2 / vc1586，4 个 dex，37105 个类）解析出的**事实**：
 *
 * | 宿主里的真实情况 | 影响 |
 * |---|---|
 * | `PreferenceFragmentCompat` 被 R8 改名成 `androidx.preference.b` | 按原类名 loadClass 必抛 CNFE |
 * | `ListPreference` 被压成 `androidx.preference.istPreference`（后缀压缩） | 不能按名构造列表项 |
 * | 库内方法名全被压成单字母（`setTitle`→`J`、`setChecked`→`P`、`findPreference`→`t0`） | **不能按方法名反射调用** |
 * | `setOnPreferenceClickListener` / `setOnPreferenceChangeListener` 已被裁掉 | 挂监听器这条路彻底不通 |
 * | 框架重写的方法名保留：`onCreate` / `onViewCreated` / `onDestroyView` | **可作为 hook 挂点** |
 * | 宿主 Fragment 类名是真名：`settings.fragment.SettingsFragment` | 可 loadClass |
 * | `RecyclerView.getAdapter()` / `setAdapter()` 名字保留 | 可顺着它摸到 PreferenceScreen |
 * | XML 引用的类名保留：`Preference` / `PreferenceScreen` / `PreferenceCategory` / `SwitchPreference` | 可按名构造实例 |
 *
 * ══════════════════════════ 于是采用「签名定位 + 运行时自验证」 ══════════════════════════
 *
 * 不猜名字，只按**方法签名**找，且每一步都能自我验证：
 *
 * 1. **挂点**：hook `SettingsFragment.onViewCreated`（该方法实际声明在 `androidx.preference.b`，
 *    用 `getMethod` 从宿主 Fragment 类往上找即可拿到 Method 对象，无需知道改名后的类名）。
 *    该方法对所有偏好页 Fragment 都会触发，用 `thisObject` 的类名过滤出主设置页。
 * 2. **拿 PreferenceScreen**：`Fragment` 持有 `RecyclerView` 字段（字段名被改，但**类型没改**，
 *    按类型找字段）→ `getAdapter()` → 适配器里有个 `PreferenceGroup` 字段 → 它就是 PreferenceScreen。
 * 3. **addPreference**：**不能按返回类型猜**。这版 androidx 里同参的有三条：
 *    `P(Preference)->void` = `addPreference`、`S(Preference)->void` = 内部移除通知路径、
 *    `T(Preference)->boolean` = `removePreference`（v1.4.0 第一轮把它当成了 add，
 *    于是"加了 4 行"其实调用的是删除，界面上什么都不会出现）。
 *    所以先收集候选，再拿一条**一次性 `PreferenceCategory`** 当真值试验台现场试出来：
 *    加进去之后能从 `getPreference(i)` 里按 `===` 认出自己，才算命中（[resolveAdd]）。
 * 4. **标题/摘要**：`Preference` 上是两个 `(CharSequence)->void`（`setTitle`/`setSummary` 都还在）
 *    配 1 个 `()->CharSequence`（**`getSummary()` 被 R8 内联掉了，只剩 `getTitle()`**）。
 *    配对靠**写探针再看哪个 getter 变**；至于哪个 setter 是标题——看页面真值：
 *    一级条目里标题人人都有、摘要大多没有，**非空率过半的那个 getter 就是 `getTitle()`**。
 *    语言无关，不依赖任何中文字面量。（判据见 [identifyTitleSetter]）
 * 5. **开关状态**：见下节。**不能调 `isChecked()`，也不能调 `setOnPreferenceClickListener`。**
 *
 * ══════════════ addPreference 之后列表会自己刷（这里仍留两手保险） ══════════════
 *
 * 反解 `PreferenceGroup.P` 的指令流能看到它自己就干了刷新这件事：
 * ```
 * 取 Preference.f0（= 适配器 androidx.preference.c）
 *   → 取 c.h(Handler) 与 c.i(Runnable)
 *     → Handler.removeCallbacks(runnable); Handler.post(runnable)   // 异步合并刷新
 * ```
 * 也就是说：**只要用对了 addPreference，新条目会自己进可见列表**。
 * v1.4.0 第一轮"注入成功但列表里没有"的唯一根因就是用了 `removePreference`。
 * 这里仍然保留两步保险（成本近零，而且都能留下日志证据）：
 * 1. 立即 `adapter.notifyDataSetChanged()`（AM++ 的 `refreshNativePreferenceAdapter` 做法）；
 * 2. 800ms 后回查适配器手里的列表，若新条目仍不在其中，就把它的刷新任务**照框架那套**
 *    重新 post 一遍 —— Handler / Runnable 都按**类型**找出来，不写死字段名（[refreshAdapter]）。
 *
 * ══════════════════ key 字段：能设就设，设法自带反证 ══════════════════
 *
 * 条目最好带上 key：`PreferenceFragmentCompat` 保存/恢复层级状态是按 key 索引的。
 * 但 `setKey` 在宿主里被内联掉了，字段名也被压成单字母（本版是 `x`）——写死下次就废。
 * 于是同样靠自证：把候选 String 字段写上唯一探针值 → 加进试验台 →
 * 用 `PreferenceGroup.findPreference(CharSequence)` 按那个探针值回查，能查回同一个对象的
 * 那个字段才是 key（[resolveKeyField]）。找不到只记一条警告继续——
 * 本模块不依赖宿主的持久化，不设 key 也能正常显示和联动。
 *
 * ══════════════════ 开关状态怎么读（`isChecked()` 已被 R8 内联消除） ══════════════════
 *
 * 反解宿主字节码得到的事实（这是本类最容易踩的坑，v1.4.0 就在这里失败过一轮）：
 *
 * | 东西 | R8 之后 |
 * |---|---|
 * | `TwoStatePreference.isChecked()` | **方法不存在**。它只有 `return mChecked;` 一行，被内联到所有调用点 |
 * | `TwoStatePreference.setChecked(boolean)` | 还在，但是 `(Z)V` 里的唯一一个 → 可定位（`P(Z)V`） |
 * | `TwoStatePreference.mChecked` | 字段名被压成 `m0`（**下次编译可能又变，不能写死**） |
 * | `TwoStatePreference.mCheckedSet` | `p0`（`setChecked` 同时写它，是干扰项） |
 * | `Preference.M()Z` | 是 `shouldDisableDependents`，**不是** `isChecked`，别认错 |
 * | `Preference.getSummary()` | **方法不存在**（一行 getter 同样被内联），`Preference` 上只剩 1 个 `()CharSequence` |
 * | `setLayoutResource` / `setWidgetLayoutResource` | **被内联消除**（`Preference` 上一个 `(int)->void` 都不剩） |
 * | `setOnPreferenceClickListener` / `setOnPreferenceChangeListener` | 被裁掉 |
 *
 * 于是读状态用**翻转探测**（[findCheckedField]）：在一次性实例上 `setChecked(true)`
 * 再 `setChecked(false)`，取「写 true 时变 true、写 false 时变 false」的那个布尔字段。
 * `setChecked` 只碰 mChecked / mCheckedSet 两个字段，而后写 false 时保持 true → 结果唯一。
 *
 * 顺带确认了"用户拨动会写这个字段"：
 * - 拨杆：`SwitchPreference$R(view)` 挂的 `SwitchPreference$a.onCheckedChanged` → `callChangeListener` → `setChecked` ✓
 * - 点整行：`TwoStatePreference.y()` = 取 !m0 → `callChangeListener` → `setChecked` ✓
 * 所以两种操作都会落到同一个字段上。
 *
 * ══════════════════════════ 交互怎么来的（监听器被裁掉了） ══════════════════════════
 *
 * 我们**不挂任何监听器**，而是页面存活期间每 [SYNC_INTERVAL_MS] 毫秒读一次界面上所有开关的状态，
 * 变化就写进 [Settings]，再把**设置里的最终值**回写界面（互斥 / 单选 / "开方向自动开总开关"
 * 这些结果都由这一步体现）。页面销毁时再补做一次同步，避免"拨完立刻退出"漏掉。
 *
 * ══════════════════════════ 要加一个新开关？只改一处 ══════════════════════════
 *
 * 往 [GROUPS] 里加一条 [Spec] 就够了——注入、读取、同步、落库、互斥回写全部是数据驱动的，
 * 不需要动任何流程代码。新增分组也只是再包一层 [Group]。
 */
internal object SettingsInjector {

    private const val CLS_FRAGMENT = "com.apple.android.music.settings.fragment.SettingsFragment"
    private const val CLS_RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    private const val CLS_PREF = "androidx.preference.Preference"
    private const val CLS_SCREEN = "androidx.preference.PreferenceScreen"
    private const val CLS_GROUP = "androidx.preference.PreferenceGroup"
    private const val CLS_CATEGORY = "androidx.preference.PreferenceCategory"
    private const val CLS_SWITCH = "androidx.preference.SwitchPreference"
    private const val CLS_TWO_STATE = "androidx.preference.TwoStatePreference"

    /** 界面状态同步间隔(ms)：用户拨动开关后最迟这么久落库 */
    private const val SYNC_INTERVAL_MS = 400L

    /** 注入重试：宿主可能异步往页面上加条目，前几次找不到真值就等一会儿再来 */
    private const val MAX_INJECT_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 500L

    /** 探针字符串（配对自检用，不会与任何真实文案相同） */
    private const val PROBE = "__am_flyme_probe__"

    /** 注入条目 key 的前缀（key 字段自证出来之后才会写） */
    private const val KEY_PREFIX = "am_flyme_"

    /** 注入后回查适配器列表的延迟(ms)：给 addPreference 自己 post 的刷新任务留出时间 */
    private const val ADAPTER_VERIFY_DELAY_MS = 800L

    /** 遍历 `PreferenceGroup` 子条目时的上限（防越界异常刷屏，也防页面异常时死循环） */
    private const val MAX_CHILD_SCAN = 64

    /**
     * 一个开关条目：界面 ↔ 设置 的**双向映射**全写在这里，同步逻辑因此可以完全数据驱动
     * （[syncFromUi] 不需要知道任何一个具体开关叫什么、属于哪个分组）。
     */
    private class Spec(
        /** 稳定标识：同时用来生成条目的 key（`am_flyme_<kind>`） */
        val kind: String,
        val title: String,
        val summary: String,
        /** 读当前设置值（回写界面、判断是否变化都用它） */
        val get: () -> Boolean,
        /** 写设置。互斥/派生不变式由 [Settings] 的 setter 负责，这里不做判断 */
        val set: (Boolean) -> Unit,
        /** 值**真的变了**之后跑一次（例如：打开转换方向时顺手打开总开关；关总开关时撤下歌词） */
        val afterSet: ((Boolean) -> Unit)? = null,
        /**
         * 单选组名：同组内**最多一个为真**（null = 独立开关）。
         *
         * 为什么需要它——见 [syncFromUi]：界面快照是"一次性全读"，用户点亮 A 的那一刻
         * B 在界面上**还是亮的**，若照快照逐条落库就会把 B 一起写回去（后写的赢），
         * 表现为"点了 QQ 音乐结果又跳回网易云"。同组必须整体裁决，不能逐条比。
         */
        val radioGroup: String? = null,
    )

    /** 一个分组（对应页面上一条标题 + 下面若干开关） */
    private class Group(val title: String, val specs: List<Spec>)

    /** 简繁两个方向互斥 */
    private const val RADIO_HANT = "hant"
    /** 歌词源二选一 */
    private const val RADIO_SOURCE = "source"

    /**
     * 「必须有一个选中」的单选组。
     *
     * 两类单选的语义**不一样**，不能一刀切：
     *  · [RADIO_HANT]：两个都关是**正常状态**（= 不做简繁转换，也是出厂默认）。
     *    所以拨掉当前那个方向之后就该停在"两个都关"。
     *  · [RADIO_SOURCE]：歌词源不存在"没有源"这个状态——真的一个都不选，
     *    「自动实时补全」就会静默取不到词。所以拨掉当前那个时把它**拨回去**，
     *    这是单选按钮的常规行为（不能取消选中唯一选项）。
     */
    private val RADIO_ALWAYS_ONE = setOf(RADIO_SOURCE)

    private val GROUPS = listOf(
        Group(
            "状态栏歌词",
            listOf(
                Spec(
                    "enabled", "状态栏歌词", "在 Flyme 状态栏显示当前播放的歌词",
                    { Settings.enabled }, Settings::setEnabled,
                    // 关掉时立刻把状态栏上的歌词撤下来，别留着上一句
                    afterSet = { if (!it) FlymeStatusBarLyric.clear() },
                ),
                Spec(
                    "hant_s2t", "简体 → 繁体", "歌词转成繁体中文（两个方向只能开一个）",
                    { Settings.hantS2T }, Settings::setHantS2T,
                    // 只在方向由关变开时才会被调用：顺手打开总开关，否则"设了方向却没反应"是个坑
                    afterSet = { if (it && !Settings.enabled) Settings.setEnabled(true) },
                    radioGroup = RADIO_HANT,
                ),
                Spec(
                    "hant_t2s", "繁体 → 简体", "歌词转成简体中文（两个方向只能开一个）",
                    { Settings.hantT2S }, Settings::setHantT2S,
                    afterSet = { if (it && !Settings.enabled) Settings.setEnabled(true) },
                    radioGroup = RADIO_HANT,
                ),
            ),
        ),
        Group(
            "歌词补全",
            listOf(
                Spec(
                    "auto_complete", "自动实时补全",
                    // 措辞与 LyricsInjector.eligible() 的判定必须一致，改一处要改两处
                    "原生歌词缺失 / 不是逐字歌词时，从在线源补一份逐字歌词并注入播放页；" +
                        "已有原生逐字歌词、以及伴奏轨都不补",
                    { Settings.autoComplete }, Settings::setAutoComplete,
                ),
                Spec(
                    "src_qq", "歌词源：QQ 音乐", "使用 QQ 音乐的逐字歌词（QRC）",
                    { Settings.lyricSource == LyricSource.QQ },
                    { if (it) Settings.setLyricSource(LyricSource.QQ) },
                    radioGroup = RADIO_SOURCE,
                ),
                Spec(
                    "src_netease", "歌词源：网易云音乐", "使用网易云音乐的逐字歌词（YRC）",
                    { Settings.lyricSource == LyricSource.NETEASE },
                    { if (it) Settings.setLyricSource(LyricSource.NETEASE) },
                    radioGroup = RADIO_SOURCE,
                ),
            ),
        ),
    )

    private val ALL_SPECS: List<Spec> = GROUPS.flatMap { it.specs }

    /** 界面上真实存在的一行（注入成功后才进这个表） */
    private class Row(val pref: Any, val spec: Spec)

    /** 解析好的一组反射句柄 */
    private class Refs(
        val setTitle: Method,
        val setSummary: Method,
        val getTitle: Method?,
        val getSummary: Method?,
        val setChecked: Method,
        val checkedField: Field,
        /**
         * `(Preference)->void|boolean` 的**候选**，`void` 在前。
         * 不能直接取"唯一的一个"：`(Preference)->boolean` 那条其实是 `removePreference`，
         * 而 `(Preference)->void` 有两条（addPreference + 内部移除通知路径）。
         * 真正哪个是 add 由 [resolveAdd] 拿真实条目现场自证。
         */
        val addCandidates: List<Method>,
        val getPreference: Method,
        /** `PreferenceGroup.findPreference(CharSequence)`：认 key 字段时当探针用；可能为 null */
        val findPreference: Method?,
        /** `Preference` 继承链上的 String 字段候选（key 字段就在其中） */
        val stringFields: List<Field>,
        val newPreference: Constructor<*>,
        val newCategory: Constructor<*>,
        val newSwitch: Constructor<*>,
    )

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var classLoader: ClassLoader

    @Volatile private var refs: Refs? = null

    /** 现场自证出来的 `addPreference`（一旦确认就复用） */
    @Volatile private var addMethod: Method? = null

    /** 现场自证出来的 key 字段（可能为 null：认不出来就只记警告，不阻塞注入） */
    @Volatile private var keyField: Field? = null

    /** 自证 `addPreference` 时最后一次异常（成功与否都只用于日志诊断） */
    @Volatile private var lastAddError: Throwable? = null

    /** 结构无关的句柄（可提前解析） */
    @Volatile private var recyclerClass: Class<*>? = null
    @Volatile private var groupClass: Class<*>? = null

    private var injectedScreen: Any? = null
    private val rows = ArrayList<Row>()
    private var attempts = 0
    private var syncRunning = false

    private val syncTask = object : Runnable {
        override fun run() {
            runCatching { syncFromUi() }.onFailure { XLog.w("settings sync: ${it.message}") }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    // ═══════════════════════════ 安装 ═══════════════════════════

    fun install(xp: XposedInterface, cl: ClassLoader) {
        classLoader = cl
        runCatching {
            recyclerClass = cl.loadClass(CLS_RECYCLER)
            groupClass = cl.loadClass(CLS_GROUP)

            val fragment = cl.loadClass(CLS_FRAGMENT)
            // onViewCreated 声明在 androidx.preference.b（改名后的 PreferenceFragmentCompat），
            // 但 getMethod 会沿继承链找到它——所以我们不需要知道那个改名后的类名。
            val onViewCreated = fragment.getMethod("onViewCreated", View::class.java, android.os.Bundle::class.java)
            val onDestroyView = fragment.getMethod("onDestroyView")
            XLog.i("hook OK: settingsUI (onViewCreated / onDestroyView)")

            xp.hook(onViewCreated)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    // 方法声明在基类上 → 所有偏好页都会进来，只认主设置页
                    val self = chain.thisObject
                    if (self != null && isTargetFragment(self.javaClass.name)) {
                        runCatching { scheduleInject(self) }
                            .onFailure { XLog.w("settings inject hook: ${it.message}") }
                    }
                    result
                })

            xp.hook(onDestroyView)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject
                    if (self != null && isTargetFragment(self.javaClass.name)) {
                        runCatching {
                            // 退出页面：先把最后一次拨动同步掉，再停轮询
                            syncFromUi()
                            stopSync()
                        }.onFailure { XLog.w("settings teardown: ${it.message}") }
                    }
                    result
                })
        }.onFailure {
            XLog.e("hook [settingsUI] failed: ${it.message}", it)
        }
    }

    private fun isTargetFragment(name: String): Boolean =
        name == CLS_FRAGMENT || name.endsWith("settings.fragment.SettingsFragment")

    // ═══════════════════════════ 注入 ═══════════════════════════

    private fun scheduleInject(fragment: Any) {
        attempts = 0
        attemptInject(fragment)
    }

    private fun attemptInject(fragment: Any) {
        val done = runCatching { inject(fragment) }
            .onFailure { XLog.w("settings inject: ${it.message}") }
            .getOrDefault(false)
        if (done) return

        attempts++
        if (attempts >= MAX_INJECT_ATTEMPTS) {
            XLog.e("settings inject gave up after $attempts attempts (page structure changed?)", null)
            return
        }
        handler.postDelayed({ attemptInject(fragment) }, RETRY_DELAY_MS)
    }

    /** @return true = 本页已就绪（注入完成或已存在） */
    private fun inject(fragment: Any): Boolean {
        val rvClass = recyclerClass ?: return false
        val group = groupClass ?: return false

        // ① Fragment → RecyclerView（按字段类型找，字段名已被 R8 改掉）
        val rv = fieldOfType(fragment.javaClass, rvClass)?.get(fragment) as? ViewGroup ?: return false
        val ctx: Context = rv.context ?: return false

        // ② RecyclerView.getAdapter() → 适配器 → 里面的 PreferenceGroup 字段 = PreferenceScreen
        val adapter = rvClass.getMethod("getAdapter").invoke(rv) ?: return false
        val screen = fieldOfType(adapter.javaClass, group)?.get(adapter) ?: return false

        // ③ 反射句柄（只需解析一次；需要页面上已有条目才能认出标题 getter）
        val r = refs ?: resolve(ctx, screen).also {
            refs = it
            XLog.i(
                "settingsUI resolved: title/summary setter + checked field '${it.checkedField.name}' " +
                    "+ ${it.addCandidates.size} addPreference candidate(s)"
            )
        }

        if (screen === injectedScreen) {
            // 同一页面再次 onViewCreated（如旋转/返回）：不重复加行，只把状态刷成设置值
            refreshFromSettings(r)
            startSync()
            return true
        }

        // ④ 现场自证 addPreference（不能按返回类型猜：`(Preference)->boolean` 那条是 remove）
        if (!resolveAdd(ctx, r, screen)) return false

        rows.clear()
        val created = ArrayList<Any>()

        // ⑤ 逐个分组：一条标题 + 它下面的开关
        GROUPS.forEachIndexed { groupIndex, group ->
            val category = r.newCategory.newInstance(ctx, null as Any?)
            r.setTitle.invoke(category, group.title)
            applyKey(category, "$KEY_PREFIX" + "group_$groupIndex")
            if (!addAndVerify(screen, category, r)) {
                XLog.e("settings inject: 分组 '${group.title}' 未能加入页面", null)
                return false
            }
            created += category
            for (spec in group.specs) {
                val sw = r.newSwitch.newInstance(ctx, null as Any?)
                r.setTitle.invoke(sw, spec.title)
                r.setSummary.invoke(sw, spec.summary)
                applyKey(sw, KEY_PREFIX + spec.kind)
                r.setChecked.invoke(sw, spec.get())
                if (!addAndVerify(category, sw, r)) {
                    XLog.e("settings inject: '${spec.title}' 未能加入分组", null)
                    return false
                }
                rows += Row(sw, spec)
                created += sw
            }
        }

        injectedScreen = screen
        startSync()
        refreshAdapter(rv, adapter, created)

        // 回读一遍：能读到 = setChecked 与 checked 字段确实配上了（否则这里必然全是 null）
        val echo = rows.joinToString(" ") { "${it.spec.kind}=${read(r, it)}" }
        XLog.i(
            "settings injected: ${rows.size} switches in ${GROUPS.size} groups " +
                "[$echo] screen=${screen.javaClass.name}"
        )
        return true
    }

    /**
     * 现场自证 `addPreference`。
     *
     * 不猜名字、也不猜返回类型——这版 androidx 里同参的有三条，按返回类型挑必错（v1.4.0 就错在这）。
     * 办法是拿真值试验一把：把一条全新 `Preference` 塞进一个 `PreferenceGroup`，
     * 能从 `getPreference(i)` 里按 `===` 认出自己才算命中；`removePreference` 过不了这一关。
     *
     * 试验台优先用**一次性 `PreferenceCategory`**（不挂界面，用完即弃，失败不留痕迹）；
     * 万一在游离的 group 上跑不通（框架内部依赖适配器），再退回真页面本身试一次，
     * 试出来的探针用 `(Preference)->boolean`（= `removePreference`）删掉。
     *
     * @return true 表示 [addMethod] 已确认
     */
    private fun resolveAdd(ctx: Context, r: Refs, screen: Any): Boolean {
        addMethod?.let { return true }

        val bench = runCatching { r.newCategory.newInstance(ctx, null as Any?) }.getOrNull()
        if (bench != null && probeAdd(ctx, r, bench, "一次性分组")) return true
        if (probeAdd(ctx, r, screen, "真页面")) return true

        // 自证全失败：退回字节码已经确认过的事实（本版 `P(Preference)->void` = addPreference），
        // 由 inject() 的 addAndVerify 兜底。宁可试一次并留下明确日志，也不要"什么都没做就放弃"。
        val guess = r.addCandidates.first()
        addMethod = guess
        XLog.w(
            "settingsUI addPreference self-check failed (last=${lastAddError?.message}), " +
                "fallback -> '${guess.name}'（字节码里 P 就是 addPreference）"
        )
        return true
    }

    /**
     * 在 [group] 上试出 addPreference。
     *
     * 顺序：先试**不带 key**（本版宿主里 `addPreference` 没有 key 校验）；
     * 若候选全抛异常，再逐个 String 字段当 key 试一遍——顺便把 key 字段一起自证出来。
     * 每次成功的探针都会立刻删掉，group 不会被污染。
     */
    private fun probeAdd(ctx: Context, r: Refs, group: Any, where: String): Boolean {
        // ① 不带 key
        for (cand in r.addCandidates) {
            val probe = newProbe(ctx, r) ?: return false
            val err = runCatching { cand.invoke(group, probe) }.exceptionOrNull()
            if (err != null) lastAddError = err
            if (err != null || !dropIfLanded(r, group, probe)) continue
            addMethod = cand
            XLog.i("settingsUI addPreference = '${cand.name}'  [在${where}上自证, 不带 key]")
            if (keyField == null) resolveKeyField(ctx, r, group)
            return true
        }

        // ② 带 key（字段名被压成单字母，只能逐个试）
        for (f in r.stringFields) {
            for (cand in r.addCandidates) {
                val probe = newProbe(ctx, r) ?: return false
                f.isAccessible = true
                if (runCatching { f.set(probe, PROBE + "key") }.isFailure) continue
                val err = runCatching { cand.invoke(group, probe) }.exceptionOrNull()
                if (err != null) lastAddError = err
                if (err != null || !dropIfLanded(r, group, probe)) continue
                addMethod = cand
                keyField = f
                XLog.i("settingsUI addPreference = '${cand.name}'  [在${where}上自证, 需要 key 字段 '${f.name}']")
                return true
            }
        }
        return false
    }

    /** 探针进了列表就删掉它，返回"它到底进没进" */
    private fun dropIfLanded(r: Refs, group: Any, probe: Any): Boolean {
        if (childrenOf(group, r).none { it === probe }) return false
        val remove = r.addCandidates.firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (remove != null) runCatching { remove.invoke(group, probe) }
        return true
    }

    /**
     * 认 key 字段：字段名被压成单字母，不能写死。
     *
     * 判据是**回查自证**：给候选 String 字段写上唯一探针值 → 加进 [group] →
     * `PreferenceGroup.findPreference(CharSequence)` 按探针值回查，能查回同一个对象才算。
     * 认不出只记警告——本模块不依赖宿主的持久化，没 key 也能正常显示与联动。
     */
    private fun resolveKeyField(ctx: Context, r: Refs, group: Any) {
        val find = r.findPreference ?: return
        val add = addMethod ?: return
        for (f in r.stringFields) {
            val probe = newProbe(ctx, r) ?: return
            val key = PROBE + "key"
            f.isAccessible = true
            if (runCatching { f.set(probe, key) }.isFailure) continue
            if (runCatching { add.invoke(group, probe) }.isFailure) continue
            if (childrenOf(group, r).none { it === probe }) continue
            // 注意顺序：必须**先回查、后删探针**——findPreference 就是在这条列表里找
            val found = runCatching { find.invoke(group, key) }.getOrNull()
            dropIfLanded(r, group, probe)
            if (found === probe) {
                keyField = f
                XLog.i("settingsUI key field = '${f.name}' (findPreference 回查确认)")
                return
            }
        }
        XLog.w("settingsUI key field not identified; 注入条目将不带 key")
    }

    /**
     * 造一条一次性探针 `Preference`。
     *
     * 注意：`Preference` 在宿主里能用的构造器只有 `(Context)`（`setLayoutResource` 之类都被裁了），
     * 所以这里**必须只传 1 个实参**——写成 `newInstance(ctx, null)` 会变成两参调用，
     * 直接 NoSuchMethodException；一旦被静默吞掉，现场就只剩"候选一个都没试"的假象。
     */
    private fun newProbe(ctx: Context, r: Refs): Any? =
        runCatching { r.newPreference.newInstance(ctx) }
            .onFailure { XLog.w("settingsUI probe ctor failed: ${it.message}") }
            .getOrNull()

    /** 加进去并且**回读确认**（`removePreference` 这类调用不会让条目出现在列表里） */
    private fun addAndVerify(group: Any, pref: Any, r: Refs): Boolean {
        val m = addMethod ?: return false
        runCatching { m.invoke(group, pref) }
            .onFailure { XLog.w("settingsUI addPreference invoke: ${it.message}") }
        return childrenOf(group, r).any { it === pref }
    }

    /**
     * 把 key 写进条目（`setKey` 已被内联消除，只能直接写字段）。
     * [resolveKeyField] 没认出来就跳过——本模块不依赖宿主的持久化，没 key 也能用。
     */
    private fun applyKey(pref: Any, key: String) {
        val f = keyField ?: return
        runCatching { f.set(pref, key) }
            .onFailure { XLog.w("settingsUI set key '$key': ${it.message}") }
    }

    // ═══════════════════════ 反射句柄解析（含自验证） ═══════════════════════

    private fun resolve(ctx: Context, screen: Any): Refs {
        val cl = classLoader
        val prefClass = cl.loadClass(CLS_PREF)
        val screenClass = cl.loadClass(CLS_SCREEN)
        val switchClass = cl.loadClass(CLS_SWITCH)
        val categoryClass = cl.loadClass(CLS_CATEGORY)

        // ── addPreference 候选 / getPreference / findPreference ──────────
        // addPreference 只收集候选，不在这里定案：`(Preference)->void` 有两条，
        // `(Preference)->boolean` 那条其实是 removePreference，必须拿真实条目现场试（见 [resolveAdd]）。
        val addCandidates = methodsOf(screenClass)
            .filter {
                it.parameterCount == 1 && it.parameterTypes[0] == prefClass &&
                    (it.returnType == Void.TYPE || it.returnType == Boolean::class.javaPrimitiveType)
            }
            .sortedBy { if (it.returnType == Void.TYPE) 0 else 1 } // void 优先（addPreference 是 void）
            .onEach { it.isAccessible = true }
        require(addCandidates.isNotEmpty()) {
            "no (Preference)->void|boolean on ${screenClass.name}"
        }

        val get = methodsOf(screenClass).filter {
            it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == prefClass
        }
        require(get.size == 1) { "getPreference candidates=${get.size}" }
        get[0].isAccessible = true

        // findPreference(CharSequence)：只用来认 key 字段，认不到不影响注入
        val find = methodsOf(screenClass).firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == CharSequence::class.java &&
                it.returnType == prefClass
        }?.also { it.isAccessible = true }

        // Preference 继承链上的 String 字段（key 字段就在其中，但名字不可信）
        val stringFields = ArrayList<Field>()
        var sc: Class<*>? = prefClass
        while (sc != null && sc != Any::class.java) {
            for (f in sc.declaredFields) {
                if (f.type == String::class.java && !Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    stringFields += f
                }
            }
            sc = sc.superclass
        }

        // ── setChecked / checked 字段 ────────────────────────────────────
        // `(Z)V` 在 SwitchPreference 自己没有、在 TwoStatePreference 恰好一个（= setChecked），
        // 再往上 Preference 有 4 个，所以「第一个只有 1 个 (Z)V 的类」就是答案。
        val checkedSetter = firstClassWithSingleMethod(
            switchClass, "setChecked",
            { it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType && it.returnType == Void.TYPE }
        )
        checkedSetter.isAccessible = true
        val newSwitch = switchClass.getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
        val newCategory = categoryClass.getDeclaredConstructor(Context::class.java, AttributeSet::class.java)
        val checkedField = findCheckedField(switchClass, checkedSetter, newSwitch, ctx)

        // ── setTitle / setSummary（靠写探针 + 页面真值定位） ────────────────
        // 两个 setter 都在；但 **getter 会被 R8 内联掉**——宿主里 `getSummary()` 是
        // 一行 `return mSummary;`，调用点全在库内部，于是被整体内联、方法本身被删，
        // 只剩下 1 个 `()CharSequence`。所以 getter 数量按 1~2 容忍。
        val setters = methodsOf(prefClass).filter {
            it.parameterCount == 1 && it.parameterTypes[0] == CharSequence::class.java && it.returnType == Void.TYPE
        }
        val getters = methodsOf(prefClass).filter {
            it.parameterCount == 0 && it.returnType == CharSequence::class.java
        }
        require(setters.size == 2) { "CharSequence setters = ${setters.size}, expected 2" }
        require(getters.size in 1..2) { "CharSequence getters = ${getters.size}, expected 1..2" }

        // 配对：写入探针后哪个 getter 变化，就和哪个 setter 是一对
        val pairs = HashMap<Method, Method>()
        val prefCtor = prefClass.getDeclaredConstructor(Context::class.java)
        for (setter in setters) {
            val probe = prefCtor.newInstance(ctx)
            setter.isAccessible = true
            setter.invoke(probe, PROBE)
            for (getter in getters) {
                getter.isAccessible = true
                if (PROBE == getter.invoke(probe)) pairs[getter] = setter
            }
        }
        require(pairs.size == getters.size) {
            "accessor pairing failed (pairs=${pairs.size}/${getters.size})"
        }

        // 认 setTitle：优先用 toString() 的拼接顺序（语义判据，最硬），失败再退回页面统计
        val titleSetter = identifyTitleSetter(ctx, prefCtor, setters)
            ?: identifyTitleSetterByPage(screen, get[0], getters, setters, pairs)
            ?: error("cannot identify setTitle on this page")
        val summarySetter = setters.first { it != titleSetter }
        // getter 只用于自检日志；宿主里 getTitle() 被内联掉了，所以可能是 null
        val titleGetter = pairs.entries.firstOrNull { it.value == titleSetter }?.key
        val summaryGetter = getters.firstOrNull { it != titleGetter }

        return Refs(
            setTitle = titleSetter,
            setSummary = summarySetter,
            getTitle = titleGetter,
            getSummary = summaryGetter,
            setChecked = checkedSetter,
            checkedField = checkedField,
            addCandidates = addCandidates,
            getPreference = get[0],
            findPreference = find,
            stringFields = stringFields,
            newPreference = prefCtor,
            newCategory = newCategory,
            newSwitch = newSwitch,
        )
    }

    /**
     * 认出 `setTitle`——判据取自 `Preference.toString()` 的拼接顺序。
     *
     * 宿主里 `toString()` 反解出来是：
     * ```
     * sb.append(mTitle).append(' ')      // 标题先拼
     * sb.append(getSummary()).append(' ')  // 摘要后拼（getSummary 已被内联成字段读）
     * sb.setLength(len - 1); return sb.toString();
     * ```
     * 于是给两个 setter 各写一个带标记的探针，**先出现在 `toString()` 里的那个就是 `setTitle`**。
     *
     * 这是纯语义判据：不看方法名、不看中英文案、不依赖页面结构，
     * 而且自带反证——探针同时出现且顺序明确才算成功，否则返回 null 让调用方换办法。
     */
    private fun identifyTitleSetter(
        ctx: Context,
        prefCtor: Constructor<*>,
        setters: List<Method>,
    ): Method? = runCatching {
        val tagA = PROBE + "A"
        val tagB = PROBE + "B"
        val probe = prefCtor.newInstance(ctx)
        setters[0].isAccessible = true
        setters[1].isAccessible = true
        setters[0].invoke(probe, tagA)
        setters[1].invoke(probe, tagB)
        val dump = probe.toString()
        val ia = dump.indexOf(tagA)
        val ib = dump.indexOf(tagB)
        XLog.d("settingsUI toString probe: \"$dump\"")
        require(ia >= 0 && ib >= 0 && ia != ib) { "toString() hides the accessors" }
        if (ia < ib) setters[0] else setters[1]
    }.getOrNull()

    /**
     * 兜底：靠页面统计认 `setTitle`。
     *
     * 一级条目里标题人人都有、摘要大多没有，所以「非空率过半的 getter」配到的 setter 是标题；
     * 若非空率很低（宿主实测 3/18，取到的都是"在你听歌时将音乐暂时储存在此设备上…"这类描述），
     * 说明活下来的那个 getter 其实是 `getSummary`，标题就用**排除法**取另一个 setter。
     * 判不出来返回 null，由上层报错重试——宁可不注入，也不能把标题写进摘要栏。
     */
    private fun identifyTitleSetterByPage(
        screen: Any,
        getPreference: Method,
        getters: List<Method>,
        setters: List<Method>,
        pairs: Map<Method, Method>,
    ): Method? {
        val votes = IntArray(getters.size)
        val sample = ArrayList<String>()
        var scanned = 0
        var index = 0
        while (scanned < 40) {
            // getPreference(index) 越界会抛 IndexOutOfBounds → 视作扫完了
            val child = runCatching { getPreference.invoke(screen, index) }.getOrNull() ?: break
            for (i in getters.indices) {
                val v = runCatching { getters[i].invoke(child) }.getOrNull()
                if (v != null) {
                    votes[i]++
                    if (sample.size < 4) sample += v.toString()
                }
            }
            index++
            scanned++
        }
        XLog.d("settingsUI page vote=${votes.toList()} scanned=$scanned sample=$sample")

        if (scanned < 3 || votes.none { it > 0 }) return null
        val best = votes.indices.maxByOrNull { votes[it] } ?: return null
        val bestSetter = pairs.getValue(getters[best])
        if (votes[best] * 2 > scanned) return bestSetter

        // 非空率偏低 → best 那个更像 getSummary，标题取另一个 getter 的 setter，再退一步用排除法
        val otherGetter = getters.firstOrNull { it != getters[best] }
        return if (otherGetter != null) pairs.getValue(otherGetter)
        else setters.firstOrNull { it != bestSetter }
    }

    /**
     * 找出「已勾选」那一个布尔字段（[TwoStatePreference.mChecked]，宿主里叫 `m0`）。
     *
     * 为什么要探测而不是写死名字 / 调 `isChecked()`：
     * - `isChecked()` 只有一行 `return mChecked;`，已被 R8 **内联消除**；
     * - 字段名被压成 `m0`，R8 每次编译都可能给出不同名字，写死必然在某次更新后失效。
     *
     * 探测原理：`setChecked` 只写两个布尔字段——`mChecked` 和 `mCheckedSet`。
     * 一次性实例上依次 `setChecked(false)` → `setChecked(true)` → `setChecked(false)`：
     * - 写 false 时变 false、写 true 时变 true 的是 **mChecked**（本轮要的）
     * - `mCheckedSet` 写 true 后一直是 true，第二轮不会变回 false → 被排除
     * 所以「两次都翻转」的字段有且只有一个，取到即自证。
     */
    private fun findCheckedField(
        switchClass: Class<*>,
        setChecked: Method,
        newSwitch: Constructor<*>,
        ctx: Context,
    ): Field {
        val candidates = ArrayList<Field>()
        var c: Class<*>? = switchClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    candidates += f
                }
            }
            c = c.superclass
        }
        require(candidates.isNotEmpty()) { "no boolean field in ${switchClass.name}" }

        val probe = newSwitch.newInstance(ctx, null as Any?)
        setChecked.invoke(probe, false)
        setChecked.invoke(probe, true)
        val becameTrue = candidates.filter { it.getBoolean(probe) }
        setChecked.invoke(probe, false)
        val becameFalse = candidates.filter { !it.getBoolean(probe) }

        val hit = becameTrue.filter { it in becameFalse }
        require(hit.size == 1) {
            "checked field ambiguous: [${hit.joinToString { it.name }}] " +
                "of ${candidates.joinToString { it.name }}"
        }
        return hit[0]
    }

    /** 沿继承链找「第一个恰好只声明了一个匹配方法」的类上的那个方法 */
    private fun firstClassWithSingleMethod(
        cls: Class<*>,
        what: String,
        predicate: (Method) -> Boolean,
    ): Method {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val hits = c.declaredMethods.filter(predicate)
            if (hits.size == 1) return hits[0]
            if (hits.size > 1) error("$what ambiguous in ${c.name}: ${hits.size}")
            c = c.superclass
        }
        error("$what not found in ${cls.name}")
    }

    // ═══════════════════════ 状态同步（双向） ═══════════════════════

    private fun startSync() {
        if (syncRunning) return
        syncRunning = true
        handler.post(syncTask)
    }

    private fun stopSync() {
        syncRunning = false
        handler.removeCallbacks(syncTask)
    }

    /** 把设置值刷到界面（首次注入 / 页面重建时用）——顺带把互斥的结果也拨正 */
    private fun refreshFromSettings(r: Refs) {
        for (row in rows) {
            write(r, row, row.spec.get())
        }
    }

    /**
     * 界面 → 设置：读界面上每个开关的真实状态，变化就落库，再把**设置里的最终值**
     * 回写界面（互斥、单选、"方向开了就自动开总开关"这些结果全靠这一步体现）。
     *
     * ══════════════ 为什么单选组不能逐条比（v1.4.0 修过的第二个 bug）══════════════
     *
     * 界面是**一次性快照**读出来的（步骤 ①）。用户点亮 QQ 的那一刻，网易云那个开关
     * 在界面上**还是亮的**——快照里两个都是 true。若照快照逐条落库，就是"写 QQ、再写网易云"，
     * 后写的赢，用户看到的是"点了 QQ 结果跳回网易云"。
     *
     * 所以同组必须**整体裁决**：拿组内"当前设置值"和"快照里亮的那些"比，
     * 只有在"亮着的不是当前那个"时才切换过去，且**只切一次**。
     * 全组都没亮（用户把当前那个也关了）不算合法状态，只触发回写把它拨回去。
     *
     * 整段逻辑与具体开关无关：新增开关只要往 [GROUPS] 里加一条 [Spec] 即可。
     */
    private fun syncFromUi() {
        val r = refs ?: return
        if (rows.isEmpty()) return

        // ① 读界面。任何一个读不到就整轮跳过——宁可这次不同步，
        //    也不能把"读失败"当成"用户关掉了"写进设置（v1.4.0 修过的 bug）。
        val ui = HashMap<String, Boolean>(rows.size)
        for (row in rows) {
            ui[row.spec.kind] = read(r, row) ?: return
        }

        // ② 界面 → 设置
        var changed = false

        // ②-a 单选组：整组一起裁决（见方法注释）
        val done = HashSet<String>(2)
        for (row in rows) {
            val group = row.spec.radioGroup ?: continue
            if (!done.add(group)) continue
            val members = rows.filter { it.spec.radioGroup == group }
            val current = members.firstOrNull { it.spec.get() }
            val lit = members.filter { ui.getValue(it.spec.kind) }
            val target = when {
                lit.size == 1 -> lit[0]
                // 快照里不止一个亮 = 用户刚点亮了另一个，取"不是当前那个"的那个
                lit.size > 1 -> lit.firstOrNull { it !== current } ?: lit[0]
                // 一个都没亮。必选组（歌词源）用当前那个兜底，于是被拨回去；
                // 可选组（简繁）无所谓，交给下面 branches
                else -> current.takeIf { group in RADIO_ALWAYS_ONE }
            }
            when {
                // 可选组全关：必须把设置里那个**真正清掉**。
                // 少这一步，"关掉转换方向"就只改到界面——设置里还是旧的 true，
                // 下一次任何 refreshFromSettings 都会把它按旧值拨回来（真机踩过：
                // 关了繁体→简体之后再点歌词源，那个开关自己又亮了）。
                target == null -> if (current != null) {
                    current.spec.set(false)
                    current.spec.afterSet?.invoke(false)
                    changed = true
                }
                // 真的换了选中项
                target !== current -> {
                    target.spec.set(true)
                    target.spec.afterSet?.invoke(true)
                    changed = true
                }
                // 必选组被拨灭：值没变，但界面得拨回去
                lit.isEmpty() -> changed = true
            }
        }

        // ②-b 独立开关：逐条比即可
        for (row in rows) {
            val spec = row.spec
            if (spec.radioGroup != null) continue
            val wanted = ui.getValue(spec.kind)
            if (wanted == spec.get()) continue
            spec.set(wanted)
            changed = true
            // 只在"真的变了"之后跑 afterSet：像"打开方向就自动开总开关"这种派生动作
            // 不能每轮都执行，否则用户单独关掉总开关会被立刻又打开
            spec.afterSet?.invoke(wanted)
        }
        if (!changed) return

        // ③ 设置 → 界面（把互斥/单选的最终结果拨正）
        refreshFromSettings(r)
        FlymeStatusBarLyric.onConversionChanged()

        XLog.i(
            "settings changed: statusBarLyric=${Settings.enabled} " +
                "s2t=${Settings.hantS2T} t2s=${Settings.hantT2S} " +
                "autoComplete=${Settings.autoComplete} source=${Settings.lyricSource.name}"
        )
    }

    /** 读界面上的开关状态；读不到返回 null（调用方必须处理，不能当成 false） */
    private fun read(r: Refs, row: Row): Boolean? =
        runCatching { r.checkedField.get(row.pref) as? Boolean }.getOrNull()

    private fun write(r: Refs, row: Row, value: Boolean) {
        if (read(r, row) == value) return
        runCatching { r.setChecked.invoke(row.pref, value) }
    }

    // ═══════════════════════════ 小工具 ═══════════════════════════

    /**
     * 一个 `PreferenceGroup` 当前的直接子条目。
     *
     * 用 `getPreference(int)` 逐个取、越界即停（`getPreferenceCount()` 是一行 `return size;`
     * 被 R8 内联消除了，方法不存在）。拿到的是**活列表**，所以可以直接按 `===` 判身份——
     * 这正是自证 `addPreference` 时"它到底加没加进去"的判据。
     */
    private fun childrenOf(group: Any, r: Refs): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < MAX_CHILD_SCAN) {
            val child = runCatching { r.getPreference.invoke(group, i) }.getOrNull() ?: break
            out += child
            i++
        }
        return out
    }

    /**
     * 让刚加进去的原生条目进可见列表。三保险：
     *
     * 1. 正常情况下 `addPreference` 自己会把适配器的刷新任务 post 到它的 Handler 上，不用我们管；
     * 2. 这里再显式 `notifyDataSetChanged()` 一次（AM++ 的 `refreshNativePreferenceAdapter`，无害）；
     * 3. [ADAPTER_VERIFY_DELAY_MS] 后回查适配器手里的列表；若新条目仍不在其中，
     *    就照框架那套把它的刷新任务重新 post 一遍（Handler / Runnable 都按**类型**找，不写死字段名）。
     */
    private fun refreshAdapter(rv: ViewGroup, adapter: Any, expect: List<Any>) {
        runCatching { adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter) }
            .onFailure { XLog.w("adapter notifyDataSetChanged: ${it.message}") }
        handler.postDelayed({ verifyAdapterAndRepair(rv, adapter, expect) }, ADAPTER_VERIFY_DELAY_MS)
    }

    /** 回查 + （必要时）用适配器自己的刷新任务补救一把；全程只留日志，不抛异常 */
    private fun verifyAdapterAndRepair(rv: ViewGroup, adapter: Any, expect: List<Any>) {
        if (runCatching { adapterHolds(adapter, expect) }.getOrDefault(false)) {
            XLog.i(
                "adapter holds all ${expect.size} new rows (itemCount=${itemCountOf(adapter)} " +
                    "rvChildCount=${rv.childCount})"
            )
            return
        }
        XLog.w("adapter missing new rows -> re-post its own refresh task")
        val h = runCatching {
            fieldOfType(adapter.javaClass, Handler::class.java)?.get(adapter) as? Handler
        }.getOrNull()
        val task = runnableOf(adapter)
        if (h == null || task == null) {
            XLog.w("adapter repair unavailable (handler=${h != null} task=${task != null})")
            return
        }
        h.removeCallbacks(task)
        h.post(task)
        handler.postDelayed({
            XLog.i(
                "adapter after re-post: holds=${expect.count { e -> adapterHolds(adapter, listOf(e)) }}" +
                    "/${expect.size} itemCount=${itemCountOf(adapter)} rvChildCount=${rv.childCount}"
            )
        }, 400L)
    }

    /** 适配器手里任何一个列表里有没有这个条目（列表字段名也被改了，全部按类型扫一遍） */
    private fun adapterHolds(adapter: Any, items: List<Any>): Boolean {
        var c: Class<*>? = adapter.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = runCatching { f.get(adapter) as? List<Any> }.getOrNull() ?: continue
                if (items.all { item -> list.any { it === item } }) return true
            }
            c = c.superclass
        }
        return false
    }

    private fun itemCountOf(adapter: Any): Int =
        runCatching { adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int }.getOrNull() ?: -1

    /** 适配器里那个 Runnable 字段（androidx 的适配器用它做"异步合并刷新"） */
    private fun runnableOf(adapter: Any): Runnable? {
        var c: Class<*>? = adapter.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!Runnable::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                (runCatching { f.get(adapter) }.getOrNull() as? Runnable)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    /** 按字段**类型**查字段（字段名已被 R8 改成单字母，不可依赖） */
    private fun fieldOfType(cls: Class<*>, type: Class<*>): Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val f = c.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }

    private fun methodsOf(cls: Class<*>): List<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredMethods
            c = c.superclass
        }
        return out
    }
}
