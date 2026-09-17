# AM Lyrics for Flyme

[![Release](https://img.shields.io/github/v/release/liz963/AM-Lyrics-for-Flyme)](https://github.com/liz963/AM-Lyrics-for-Flyme/releases)
[![License](https://img.shields.io/github/license/liz963/AM-Lyrics-for-Flyme)](LICENSE)
[![LSPosed API](https://img.shields.io/badge/LSPosed-API%20102-blue)](https://github.com/LSPosed/LSPosed)

一个 LSPosed 模块：Hook Apple Music（`com.apple.android.music`），实时提取当前播放
歌曲的逐行歌词，并通过 **Flyme 官方状态栏歌词接口**显示在魅族手机的状态栏上。

> 基于 **libxposed 现代 API（API 102，`io.github.libxposed:api`）** 开发，
> 需要 LSPosed 等支持现代 API（≥ API 100）的框架。
> 适配 **Apple Music 5.2.0**（Android 16 / Flyme 12.6 实测）。

## 特性

- 实时逐行歌词注入 Flyme 状态栏，与播放进度精准对齐（触发误差 < 100ms）；
- **播放即加载**：开始播放任意歌曲后立即主动调用取词接口，无需进入播放界面；
- **切到后台持续滚动**：通过官方歌词引擎反射驱动，离开播放界面 / 锁屏 / 切到桌面都不断更；
- **歌词提前上屏**：状态栏歌词比实际人声进度提前约 `1000ms`（1 秒），抵消状态栏 ticker 渲染延迟（观感"歌词先到、人声后到"）；
- **移除状态栏左侧音符图标**：仅保留歌词文本（`ticker_icon_switch = false`）；
- **播放最开头推送「歌曲名 - 歌手名」**：仅当歌曲从开头（position ≤ 1.5s）开始播放时（含**重播同一首 / 拖回进度 0**），
  先在状态栏显示「歌曲名 - 歌手名」并**锁定保持至「第一句歌词前 1 秒」**（前奏再长也一直显示歌名，
  第一句才按原提前量替换）；拖动进度条 / 跳播 / 中段续播等非开头场景只推歌词、不推歌名；
- 同时支持在线歌词与本地 LRC 歌词（`LrcParser`）。

## 工作原理

模块不自己实现"逐行定位"算法，而是**直接驱动 Apple Music 播放界面所用的官方歌词引擎**
`SongInfoTimeProcessor.processEvents`，从而做到与官方完全一致的逐行节奏。

```
Apple Music 进程
 ┌──────────────────────────────────────────────────────────────────┐
 │  Application.attach                         初始化入口（LyricController / │
 │                                              LyricsLoader / BackgroundLyrics）│
 │        │                                                           │
 │        ▼                                                           │
 │  PlayerLyricsViewModel.loadLyrics        主动取词（歌曲同步，无 UI 也可）│
 │  PlayerLyricsViewModel.buildTimeRangeToLyricsMap  捕获歌词句柄 ptr（缓存）│
 │        │                                                           │
 │        ▼                                                           │
 │  SongInfoTimeProcessor.processEvents    ★ 官方歌词引擎反射驱动        │
 │     (SongInfoPtr, pos, 5×EventCallback) → 逐行回调（line/word/...） │
 │        │                                                           │
 │        ▼                                                           │
 │  NativeLyricsParser   原生 Song → sections → lines 反射解析（抽文本）│
 │        ▼                                                           │
 │  LyricController      状态机：切歌 / 加载 / 暂停 / 停止 / 无歌词     │
 │        ▼                                                           │
 │  FlymeStatusBarLyric  载波模式：歌词 Ticker 注入宿主媒体通知（去图标）│
 └──────────────────────────────────────────────────────────────────┘
```

**驱动机制要点**

- `processEvents(ptr, positionMs, lineCb, wordCb, bgWordCb, prWordCb, prBgWordCb)` 的
  返回值**不是延迟，而是「下一歌词事件的绝对位置(ms)」**。换算为下次轮询间隔：
  `delay = nextEventPos − queryPos`，其中 `queryPos = 实际位置 + LEAD_MS(1000)` 实现提前量。
- **B 方案（v1.3.8）下一行探测**：每次主查询拿到 `nextEventPos`（下一行绝对位置）后，立刻对
  `nextEventPos` 再做一次**只读探测调用**，缓存「下一行文本 + 下一行开始时间戳」。调度不再单纯依赖
  `processEvents` 的估算延迟，而是直接锚定 `nextLineStart − LEAD_MS`，在「下一行应出现的时间 − 2 秒」
  精准切换；探测走独立静默回调（不上屏），且使用模块自有的独立 `timeProcessor` 实例，不影响播放器 UI。
- 长间隔歌词行会被 `MAX_DELAY_MS = 5000` 截成「提前唤醒」，但下一跳会用新位置重新算出
  真实剩余，**最后一跳必然 < 5s 且为精确值**——因此行事件触发误差 < 100ms，且后台每 5s
  至少醒一次读进度、感知切歌 / seek（这是后台持续滚动的关键，长时挂起会导致后台停更）。
- 5 个回调里只处理 `line` 事件抽文本（其余按 no-op 处理），与播放界面走完全相同的原生路径。

## 一、Hook 点（v1.3.10 逆向定位依据）

| # | Hook 目标 | 用途 |
|---|-----------|------|
| 1 | `android.app.Application.attach` | 获取宿主 Context / ClassLoader，初始化模块 |
| 2 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.loadLyrics` | 主动取词（歌曲同步，无需进入播放界面） |
| 3 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.buildTimeRangeToLyricsMap` | 歌词构建完成，捕获原生歌词句柄 `SongInfoPtr`（缓存 + 后台驱动数据源） |
| 4 | `com.apple.android.music.ttml.SongInfoTimeProcessor.processEvents` | ★ 反射驱动官方歌词引擎，无 UI / 后台均逐行推送 |
| 5 | `com.apple.android.music.playback.controller.LocalMediaPlayerController.onPlaybackStateChanged`（3 参，末参 int） | 播放状态：`0=停止 1=播放 2=暂停` + 控制器实例捕获 |
| 6 | `android.app.NotificationManager.notify` / `cancel` | 载波模式：歌词 Ticker 注入宿主媒体通知 |

**歌词数据提取**（`NativeLyricsParser.kt`，全部走反射、逐步判空）：

- 当前行文本由官方引擎 `processEvents` 的逐行回调直接给出（回调参数携带 `LyricsLineVector`），
  再由 `extractLineText()` 反射读取 `getHtmlLineText()`（含翻译行则用 `getHtmlTranslationLineText()`，
  以 ` · ` 连接），并做 HTML 清洗；
- **5.2.0 的逐行时间戳不由模型暴露**（`LyricsLineNative` 没有 `getBegin/getEnd`），时间由
  `SongInfoTimeProcessor` 在播放时实时计算（见上文「驱动机制要点」），因此歌词时间轴不在此处解析；
- 有无歌词判断：`SongInfoPtr → get() → getSections()`，`size() > 0` 即含歌词。

原生容器的统一特征：`size(): Long`、`get(i)` 返回智能指针包装、再 `.get()` 拿到原生对象。

### 版本更新后如何重新定位（jadx 指南）

Apple Music 更新后若类名/方法名变化，用 jadx-gui 打开新 APK，按以下特征搜索：

- `buildTimeRangeToLyricsMap` —— 搜方法名字符串（歌词 VM 构建时间轴→歌词映射）；
- `SongInfoTimeProcessor` / `processEvents` —— 官方歌词引擎入口，搜方法名；
- `LocalMediaPlayerController` —— 类名一般不被混淆（activity/播放控制层）；
- `MediaMetadataCompat` 来自内置 support-v4，不受混淆影响。

搜索到新类名后只需改对应常量 / 字符串，其余逻辑不变。
（更稳妥的进阶方案：接入 [DexKit](https://github.com/LuckyPray/DexKit) 做特征匹配自动定位。）

## 二、Flyme 状态栏歌词对接（官方方案）

依据魅族开放平台《Flyme 状态栏歌词适配》文档（open.flyme.cn/docs?id=239）：

- 本质是**带 Ticker 的常驻通知**，需要同时添加两个 Flyme 扩展 flag：
  - `Notification.FLAG_ALWAYS_SHOW_TICKER`（0x1000000）：Ticker 一直显示；
  - `Notification.FLAG_ONLY_UPDATE_TICKER`（0x2000000）：只更新歌词，不刷新封面等；
- 外加 `Notification.FLAG_NO_CLEAR` 保证常驻；
- `extras` 中 `ticker_icon_switch = false`：**关闭状态栏左侧小图标，仅保留歌词文本**；
- 每次更新对**同一个通知 ID** 调用 `notify()`；清除时 `cancel(id)`。

**兼容性处理**：

1. 两个 flag 是 Flyme 对 `android.app.Notification` 的私有扩展，模块通过**反射读取**；
   读不到（非 Flyme 或未移植该功能的 ROM）自动禁用，模块静默退出，不产生任何通知或异常。
2. 通知渠道用 `IMPORTANCE_LOW`、无声音无振动、不显示角标，避免打扰用户。
3. 通知从 Apple Music 进程发出，复用宿主的 `NotificationManager` 和图标资源，
   不需要任何额外权限；使用独立通知 ID（`0x414D4C59`），与宿主媒体通知互不干扰。
4. 相同文本 2 秒内不重发，防止 Flyme 的 Ticker 动画反复触发造成闪烁。

## 三、状态与异常处理

| 状态 | 行为 |
|------|------|
| 切歌 | 立即重置歌词状态，状态栏回到空白（开头播放时先显示「歌曲名 - 歌手名」，随后无缝切到第一句） |
| 歌词加载中 | 保持显示歌名，歌词就绪后无缝切换到第一句 |
| 无歌词 | 15 秒内仍未取到歌词句柄（最多重试 3 次）→ 显示一次「♪ 暂无歌词」，不反复刷屏 |
| 前奏 / 间奏 | 当前无对应歌词行时显示 `♪ ♪ ♪` |
| 暂停 | 停止轮询，状态栏**冻结**在当前行（不消失、不闪烁） |
| 停止 / 播完 | 停止轮询并 `cancel` 通知，清除状态栏歌词 |
| seek 拖动 | 立即重算当前行（`onSeek` 直接触发一次 tick），不等待下个轮询周期 |
| 播放器实例回收 | 进度提供者返回 null，轮询自动挂起，播放恢复后重启 |

**稳定性设计**（保证不崩溃、不卡顿）：

- 每个 Hook 独立安装、独立 try/catch —— 单个 Hook 失败只损失对应功能；
- 所有原生对象反射调用都走 `runCatching` 包装，任何一步异常安全返回 null；
- 轮询跑在宿主主线程（与播放界面 Fragment 的 Handler 同线程，`processEvents` 内部调
  原生方法需同线程防并发），`MAX_DELAY_MS` 心跳兼顾跟手感与后台存活；
- Hook 回调里只做轻量状态记录，通知构建全部延迟到轮询线程；
- 播放器实例用 `WeakReference` 持有，不阻碍宿主 GC。

## 四、构建

环境要求：

| 组件 | 版本 |
|------|------|
| JDK | **21**（推荐；Android Studio 内置 JBR 21 即可，最低 17） |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.20 |
| Gradle | 8.13（wrapper 已内置，首次构建自动下载） |
| build-tools | 35.0.1 |
| Android SDK | **36（Android 16）**，`compileSdk = 36`，`targetSdk = 36` |
| minSdk | 26（libxposed API 的最低要求） |

```bash
# Android Studio（Ladybug 及以上）打开工程根目录，Sync 后：
./gradlew :app:assembleRelease
# Windows CMD/PowerShell 下：
gradlew.bat :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
# 需用你自己的签名密钥手动 apksigner 签名后安装（仓库不含签名密钥）
```

依赖说明：仅 `compileOnly("io.github.libxposed:api:102.0.0")`（Maven Central），
运行时由 LSPosed 注入实现，APK 体积增量极小。字节码基线为 Java 17（与 libxposed API 一致）。

### libxposed API 102 与 legacy API 的差异（维护须知）

| 事项 | legacy（API 82） | 本工程（API 102） |
|------|------------------|-------------------|
| 入口注册 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 模块元数据 | manifest 里 `xposed*` meta-data | `META-INF/xposed/module.prop`（`minApiVersion` / `targetApiVersion`） |
| 作用域 | manifest `xposedscope` | `META-INF/xposed/scope.list` + `staticScope=true` |
| 入口类 | 实现 `IXposedHookLoadPackage` | 继承 `XposedModule`，覆写 `onPackageLoaded()` |
| Hook 写法 | `XposedHelpers.findAndHookMethod` + `XC_MethodHook` | `hook(Executable).intercept(Hooker)` 拦截器链（OkHttp 风格） |
| 反射工具 | `XposedHelpers.callMethod` | 无官方替代，用自研 `util/Reflect.kt` |
| 日志 | `XposedBridge.log` | `XposedModule.log()`（框架日志）+ logcat 双通道 |

注意：**targetApiVersion ≥ 102 的模块不能再调用任何 `de.robv.android.xposed`
legacy API**，这是框架强制行为，不是建议。

## 五、安装使用

1. 从 [Releases](https://github.com/liz963/AM-Lyrics-for-Flyme/releases) 下载最新 APK，
   或按上文自行构建并用你的密钥签名；
2. 在 **LSPosed 管理器 → 模块** 中启用「AM 歌词 · Flyme」；
3. 作用域已通过 `scope.list` + `staticScope=true` **固定为 Apple Music**，
   无需（也无法）手动勾选其它应用；
4. 强制停止并重新打开 Apple Music，播放任意歌曲；
5. 状态栏出现歌词即为成功。

排错：

```bash
adb logcat -s AMFlymeLyric
```

- `Flyme status bar lyric NOT supported` → 当前 ROM 无此功能（非 Flyme 或未移植）；
- `hook [xxx] failed` → Apple Music 版本更新导致类名变化，按上文 jadx 指南重定位；
- 歌词不显示但无报错 → 该歌曲无歌词数据（Apple Music 曲库限制）。

## 六、已知限制与路线图

- 适配 Apple Music **5.2.0** 验证，大版本更新后需按 jadx 指南重定位（计划接入 DexKit 自动匹配）；
- Apple Music 无损 / 杜比全景声歌曲的歌词时间轴与普通曲目一致，无额外处理。

## 更新日志

- **v1.3.14**：**真正修复「歌名一闪而过」**——v1.3.12/v1.3.13 只堵了后台驱动一条上屏通路，漏了**前台 `lineEventCallback` 路径**：播放界面打开时引擎会自己回调当前行，前奏期把第一句当活动行回传，直接把「歌曲名 - 歌手名」顶掉（真机实测 `hold=true` 期间 ticker 仍被首句覆盖）。本版让前台路径共用同一把歌名锁定闸门，并借这条最可靠的路径捕获首句文本（后台 peek 偶发 null）；另修复**重播同一首 / 拖回开头不再推歌名**的问题（位置上大幅回退且落在开头阈值内 → 重新武装歌名推送）。
- **v1.3.13**：修复 v1.3.12 歌名锁定导致**第一句歌词被吞**的问题——官方引擎对首句只回传一次且常在开头提前回传，锁定期间被抑制后不会二次回传。改为释放锁定时用 prelude 期 peek 到的首句文本**手动补推一次**，确保第一句在「第一句前 1 秒」准时出现。
- **v1.3.12**：修复「歌曲名-歌手名」在前奏期被第一句歌词瞬间顶掉（一闪而过）的问题。改为**歌名锁定**：推完歌名后抑制歌词上屏，把歌名稳定保持到「第一句歌词前 1 秒」才放开，第一句按原提前量准时替换；纯伴奏/无歌词歌曲则歌名贯穿整曲（不再弹「暂无歌词」）。
- **v1.3.11**：新增「播放最开头推送歌曲名-歌手名」——仅从头（position ≤ 1.5s）播放时先显示「歌曲名 - 歌手名」，拖动进度条 / 跳播 / 中段续播等非开头场景只推歌词、不推歌名；只在首次进入该歌曲时推一次。
- **v1.3.10**：`LEAD_MS` 由 `1200ms` 回调到 `1000ms`（提前约 1 秒上屏，B 方案下一行探测调度不变）；README 删除不实内容（5.2.0 无 `getBegin/getEnd` 时间戳、无 `SHOW_TRANSLATION` 开关）。
- **v1.3.9**：`LEAD_MS` 由 `2000ms` 回调到 `1200ms`（提前约 1.2 秒上屏，B 方案下一行探测调度不变）。
- **v1.3.8**：B 方案「下一行探测 + 时间戳精准调度」；`LEAD_MS` 由 `1000ms` 提升至 `2000ms`（提前约 2 秒上屏）。
- **v1.3.7**：歌词提前量 `LEAD_MS` 由 `400ms` 提升至 `1000ms`（状态栏歌词提前约 1 秒上屏）；调度逻辑不变。
- **v1.3.6**：纠正 `processEvents` 返回值语义（下一事件绝对位置而非延迟），新增提前量；后台持续滚动稳定。

## 致谢

- Hook 点验证参考了开源项目 LyricProvider（Apache-2.0）对 Apple Music 的适配；
- Flyme 状态栏歌词实现遵循魅族开放平台官方文档。

## License

本项目以 [MIT License](LICENSE) 开源。
