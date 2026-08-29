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
- **歌词提前上屏**：状态栏歌词比实际人声进度提前约 `400ms`，抵消状态栏 ticker 渲染延迟（观感"歌词先到、人声后到"）；
- **移除状态栏左侧音符图标**：仅保留歌词文本（`ticker_icon_switch = false`）；
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
  `delay = nextEventPos − queryPos`，其中 `queryPos = 实际位置 + LEAD_MS(400)` 实现提前量。
- 长间隔歌词行会被 `MAX_DELAY_MS = 5000` 截成「提前唤醒」，但下一跳会用新位置重新算出
  真实剩余，**最后一跳必然 < 5s 且为精确值**——因此行事件触发误差 < 100ms，且后台每 5s
  至少醒一次读进度、感知切歌 / seek（这是后台持续滚动的关键，长时挂起会导致后台停更）。
- 5 个回调里只处理 `line` 事件抽文本（其余按 no-op 处理），与播放界面走完全相同的原生路径。

## 一、Hook 点（v1.3.6 逆向定位依据）

| # | Hook 目标 | 用途 |
|---|-----------|------|
| 1 | `android.app.Application.attach` | 获取宿主 Context / ClassLoader，初始化模块 |
| 2 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.loadLyrics` | 主动取词（歌曲同步，无需进入播放界面） |
| 3 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.buildTimeRangeToLyricsMap` | 歌词构建完成，捕获原生歌词句柄 `SongInfoPtr`（缓存 + 后台驱动数据源） |
| 4 | `com.apple.android.music.ttml.SongInfoTimeProcessor.processEvents` | ★ 反射驱动官方歌词引擎，无 UI / 后台均逐行推送 |
| 5 | `com.apple.android.music.playback.controller.LocalMediaPlayerController.onPlaybackStateChanged`（3 参，末参 int） | 播放状态：`0=停止 1=播放 2=暂停` + 控制器实例捕获 |
| 6 | `android.app.NotificationManager.notify` / `cancel` | 载波模式：歌词 Ticker 注入宿主媒体通知 |

**歌词数据提取链**（`NativeLyricsParser.kt`，全部走反射、逐步判空）：

```
SongInfoPtr → Song.getSections() → Section.getLines()
  Line.getBegin() / getEnd()   → 行起止时间（毫秒）
  Line.getHtmlLineText()       → 歌词文本（含 HTML，需清洗）
  Line.getHtmlTranslationLineText() → 翻译文本
```

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
| 切歌 | 立即重置歌词状态，先显示「♪ 歌名 - 歌手」，杜绝上一首歌词残留 |
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
- 翻译歌词默认关闭（`LyricController.SHOW_TRANSLATION`），状态栏宽度有限，开启后原文·译文同行显示；
- Apple Music 无损 / 杜比全景声歌曲的歌词时间轴与普通曲目一致，无额外处理。

## 致谢

- Hook 点验证参考了开源项目 LyricProvider（Apache-2.0）对 Apple Music 的适配；
- Flyme 状态栏歌词实现遵循魅族开放平台官方文档。

## License

本项目以 [MIT License](LICENSE) 开源。
