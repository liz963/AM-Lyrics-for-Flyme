# AM Lyrics for Flyme

[![Release](https://img.shields.io/github/v/release/liz963/AM-Lyrics-for-Flyme)](https://github.com/liz963/AM-Lyrics-for-Flyme/releases)
[![License](https://img.shields.io/github/license/liz963/AM-Lyrics-for-Flyme)](LICENSE)
[![LSPosed API](https://img.shields.io/badge/LSPosed-API%20102-blue)](https://github.com/LSPosed/LSPosed)

一个 LSPosed 模块：Hook Apple Music（`com.apple.android.music`），实时提取当前播放
歌曲的歌词，并通过 **Flyme 官方状态栏歌词接口**显示在魅族手机的状态栏上。

> 基于 **libxposed 现代 API（API 102，`io.github.libxposed:api`）** 开发，
> 需要 LSPosed 等支持现代 API（≥ API 100）的框架。
> 适配 **Apple Music 6.5.2（versionCode 1586）**，Android 16 / Flyme 12.6 真机实测。

## 特性

**状态栏歌词**

- 实时逐行歌词注入 Flyme 状态栏，与播放进度精准对齐（触发误差 < 100ms）；
- **播放即加载**：开始播放任意歌曲后立即主动调用取词接口，无需进入播放界面；
- **切到后台持续滚动**：通过官方歌词引擎反射驱动，离开播放界面 / 锁屏 / 切到桌面都不断更；
- **歌词提前上屏**：比实际人声进度提前约 `1000ms`（1 秒），抵消状态栏 ticker 渲染延迟；
- **前奏期不推送任何内容**：从开头播放（含**重播同一首 / 拖回进度 0**）时状态栏保持空白，
  直到「第一句歌词 − 1 秒」才上屏；中段续播 / 拖进度条则只推歌词。无歌词的曲目全程空白；
- **不额外发任何通知**：模块**不自己创建通知**，而是把歌词 Ticker 注入 Apple Music
  自己的媒体通知（「载波模式」）。状态栏零新增通知，通知设置里也不会多出渠道；
- **移除状态栏左侧音符图标**：仅保留歌词文本（`ticker_icon_switch = false`）；
- **只推主歌词、不推翻译**：宿主返回翻译轨时也只取主行，状态栏不出现「原文 · 译文」；
- **简繁转换**：可把歌词转成繁体或简体（两个方向**互斥**，都关 = 不转换）。

**歌词自动补全（可选，默认关）**

- 打开后，在**原生歌词缺失**或**原生只有行级时间轴（没有逐字）**时，从在线歌词源补一份
  **逐字歌词**，并且**同时落到两条通路**：
  - ① 驱动 Flyme 状态栏歌词；
  - ② **注入 Apple Music 播放页**，替换成真正的逐字 / 滚动歌词；
- **只要有原生逐字歌词就不补** —— 不管它有没有翻译。避免把官方歌词换成外部的、反而丢翻译；
- **伴奏 / 纯音乐轨跳过**：按标题关键词（`伴奏` / `纯音乐` / `instrumental` / `カラオケ`…）识别，
  不请求也不注入；
- 歌词源可选 **QQ 音乐（QRC）** 或 **网易云音乐（YRC）**，两者互斥；
- **关掉时播放过程中完全不请求任何歌词服务**（不联网、不搜索、不取词）。

**设置入口**

- **在 Apple Music 设置页里直接开关**：模块会在宿主「设置」页注入「状态栏歌词」「歌词补全」
  两个分组共 6 个开关，改完立即生效，不需要重启应用、也不需要去 LSPosed 里重新激活。

## 工作原理

### 主链路：驱动官方歌词引擎

模块不自己实现「逐行定位」算法，而是**直接驱动 Apple Music 播放界面所用的官方歌词引擎**
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
 │  LyricController      上屏出口：空行忽略 + 非歌词行过滤              │
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

### 在线歌词注入（v1.4.0，可选）

Apple Music 的很多曲目**根本没有歌词**，或者只有**行级时间轴**（一行一行跳，没有逐字）。
打开「自动实时补全」后，模块会从 QQ 音乐 / 网易云补一份逐字歌词，并把它**替换进宿主的歌词安装点**：

```
BackgroundLyrics（每 tick）                      I2(null/SongInfoPtr) 被调用
        │                                                  │
        │  判定：缺失？非逐字？                              │  只记账、先放行
        ▼                                                  ▼
  LyricsInjector.eligible()                        LyricsInjector.decide()
        │  伴奏/纯音乐 → 跳过                                │
        │  原生逐字   → 不补                                 │
        ▼                                                  │
  取词 worker 线程（QQ / 网易云 → TimedLyrics → TTML）        │
        │                                                  │
        ▼                                                  ▼
  TtmlBridge.parse(ttml) → SongInfoPtr          主线程重新进入 I2(ptr)
        │                                                  │
        ├──────────────► ① BackgroundLyrics.onSongInfo(ptr)：驱动状态栏
        └──────────────► ② 反射 `I2(fragment, 我们那份 ptr)`：播放页变成逐字
```

几个必须知道的实现约束（改这块之前先读代码注释）：

- **安装点在 6.5.2 里叫 `PlayerLyricsViewFragment.I2(SongInfoPtr) -> void`**，R8 恰好没改这个名字。
  定位契约与 AM++ 项目一致：**实例方法 + 返回 void + 单参且参数类型是 `SongInfo$SongInfoPtr`**。
  5.2.0 里同一位置叫 `M2`，所以**不能只按名字找，要按签名形状找再钉名字**。
- **`I2` 是同步方法，取词必须联网**，所以流程是「首帧放行 → 后台取词 → 主线程重新进入 `I2`」
  （`ready-late 重入`，fragment 用弱引用持有，重入前复核 `isAdded()` 与「当前曲目还是这首歌」）。
- **`XposedInterface.Chain` 没有可写 `args`**，换参数只能 `chain.proceed(Object[])`。
- **当前曲目字段不止一个**：`PlayerLyricsViewFragment` 上有继承链上的 `e.W` 与 `m.c` 两个
  `BaseContentItem`，真机实测 `e.W` 给的是**同一张专辑的另一个条目**。所以必须用「当前播放的
  adamId」去匹配 `getId()`，匹配不上就**不动**（选错＝整条注入静默失效）。
- **宿主会回传自己那份行级歌词**：补完之后宿主还会再解析一次并回传原生 ptr，
  所以 `BackgroundLyrics.onSongInfo` 里固定让补全结果优先，直到切歌或关开关。
- **判定依据全部从 ptr 自身读**：`SongInfoPtr.get() → getAvailableTiming()`（枚举
  `None` / `Line` / `Word`）。宿主解析完就丢掉了原始 TTML 文本，拿不到原文。

## 代码结构（维护须知）

```
app/src/main/java/com/amlyric/flyme/
├─ HookEntry.kt                  37   LSPosed 入口（onPackageLoaded → 安装 Hook）
├─ XLog.kt / Settings.kt        41+147  日志与开关（简繁 / 自动补全 / 歌词源都存这里）
├─ hook/
│  ├─ AppleMusicHooks.kt       287   全部 Hook 安装点（取词 / 句柄捕获 / 播放状态 / 载波 / 前台逐行回调）
│  ├─ SettingsInjector.kt     1091   ★ 往宿主「设置」页注入我们的开关（反射 + 运行时自证）
│  ├─ LyricsInjector.kt        705   ★ 在线歌词注入：判定 → 取词 → 重入 I2（改前必读文件头）
│  ├─ TtmlBridge.kt            284   ★ 外部 TTML → 官方 `SongInfo$SongInfoPtr`
│  └─ NativeLyricsParser.kt     76   原生歌词对象 → 纯文本（反射 + HTML 清洗）
├─ core/
│  ├─ BackgroundLyrics.kt      527   ★ 后台轮询编排层：唯一调度者、唯一改状态栏的人
│  ├─ LyricsLoader.kt          282   取词：Hook 捕获 + 主动 loadLyrics + 重试
│  ├─ LyricGate.kt             195   ★ 前奏期闸门：纯状态机，只出决策、不做动作
│  ├─ LyricsEngineDriver.kt    186   ★ 官方引擎反射驱动：唯一持有混淆名 / SAM / Proxy 细节
│  ├─ LyricController.kt       147   上屏出口（状态机 + 非歌词行过滤）
│  └─ HantConverter.kt          79   简繁转换
├─ lyric/                            ★ 在线歌词源（v1.4.0 新增）
│  ├─ LyricFetcher.kt          230   统一入口：按设置选源取词 → 清洗 → 转 Apple 格式 TTML
│  ├─ QQMusicClient.kt         301   QQ 音乐：搜索 → 会话 → GetPlayLyricInfo
│  ├─ QrcCrypto.kt             373   ★ QRC 解密（**改良版 DES + zlib**，见文件注释）
│  ├─ NeteaseClient.kt         127   网易云：搜索 → /api/song/lyric/v1
│  ├─ CreditLine.kt            277   ★ 版权/制作行 + `歌名 - 歌手` 尾注行过滤
│  ├─ TtmlWriter.kt            232   ★ `TimedLyrics` → Apple 认的 TTML（格式依据在类注释）
│  ├─ LrcTrack.kt              143   行级 LRC 解析 + **翻译轨按时间贴合到主轨**
│  ├─ LyricMatch.kt            118   候选匹配（标题归一化 + 版本标记扣分 + 时长核对）
│  ├─ LyricHttp.kt             103   HttpURLConnection 封装（**不引第三方网络库**，见文件注释）
│  ├─ KaraokeText.kt           100   QRC / YRC 的共同正文语法 → `TimedLyrics`
│  ├─ TimedLyric.kt             69   逐字歌词数据模型（`LyricWord` / `TimedLine` / `TimedLyrics`）
│  ├─ LrcParser.kt              79   本地 LRC 兜底
│  └─ LyricLine.kt              48   状态栏用的一行模型
├─ flyme/FlymeStatusBarLyric.kt 279  载波通道：Ticker 注入宿主媒体通知 / 清除
└─ util/Reflect.kt              51   反射小工具（`call` / `string` / `long`）
```

> `hook/SettingsInjector.kt` 很大不是因为它干了多少事，而是因为**宿主里没有可依赖的名字**：
> 类名被 R8 改成 `androidx.preference.b`、方法名压成单字母（`addPreference` → `P`、
> `findPreference` → `t0`）、setter 被内联掉。所以它只能按**签名**找、再在运行时
> **现场自证**每一步。所有"为什么必须这么写"的原因都写在文件头注释里，动它之前务必先读。
> `hook/LyricsInjector.kt` 同理——它的每一个常量都是真机反解出来的。

三个核心组件的职责边界（改代码前先看这里）：

| 组件 | 只负责 | 明确不负责 |
|------|--------|-----------|
| `BackgroundLyrics` | 轮询节拍、读宿主播放进度 / MediaItem、把三方串起来、**所有**状态栏推送 | 不判断"该不该推"（问闸门）、不碰反射细节（问驱动） |
| `LyricGate` | 前奏期抑制 / 首句探测 / 重播重武装的**决策**，`evaluate()` 返回决策 | 不发通知、不打日志、不知道引擎和宿主的存在 |
| `LyricsEngineDriver` | `ensureReady()` / `drive(ptr, queryPos) → 下一事件绝对位置` / `peek(ptr, atPos) → 文本` | 不做调度、不管前奏抑制、不知道 `LyricController` |
| `LyricsInjector` | 判定"该不该补"、取第三方词、把结果落进两条通路 | 不做状态栏调度、不碰播放进度、不改原生歌词 |

`BackgroundLyrics.onTick()` 的顺序是**约定**，改动请保持：读位置 → 处理 MediaItem（切歌/重播/取句柄）
→ 应用闸门决策 → 锁定期间压低轮询间隔 → 驱动引擎。任何"多推一次/少推一次"的 bug 基本都出在打乱这个顺序上。

> ⚠️ **最容易踩的坑：有两条上屏通路，抑制逻辑必须两边都堵**
> ① 后台驱动（`BackgroundLyrics` 反射自建引擎实例）；
> ② 前台 Hook（`AppleMusicHooks.hookLineCallback()`，播放界面打开时**引擎自己**回调当前行）。
> v1.3.12/v1.3.13 只堵了 ①，② 无条件调 `LyricController.onLyricLine()`，于是前奏期第一句
> 依旧顶掉了状态栏——这就是"歌词在前奏期提前冒出"反复修不好的真正原因。任何新增的抑制条件
> 都要经 `BackgroundLyrics.onForegroundLine()` 走同一把闸门。

> ⚠️ **音频链路里"我们自己驱动引擎"也会触发我们自己的 Hook**
> `LyricsEngineDriver` 反射调用官方 `processEvents` 时，宿主内部那个回调 wrapper 同样会走到
> `hookLineCallback`。若不在驱动期间把这条 Hook 掐掉，模块自己的探测调用会被当成"前台在显示歌词"
> 再推一次 —— 真机表现为**自检样例歌词跑到状态栏**、以及下一句被提前推出去。

## 一、Hook 点（v1.4.0，基于 Apple Music 6.5.2 逆向）

| # | Hook 目标 | 用途 |
|---|-----------|------|
| 1 | `android.app.Application.attach` | 获取宿主 Context / ClassLoader，初始化模块 |
| 2 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.loadLyrics` | 主动取词（歌曲同步，无需进入播放界面） |
| 3 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel.buildTimeRangeToLyricsMap` | 歌词构建完成，捕获原生歌词句柄 `SongInfoPtr`（缓存 + 后台驱动数据源） |
| 4 | `com.apple.android.music.ttml.SongInfoTimeProcessor.processEvents` | ★ 反射驱动官方歌词引擎，无 UI / 后台均逐行推送 |
| 5 | `com.apple.android.music.playback.controller.LocalMediaPlayerController.onPlaybackStateChanged`（3 参，末参 int） | 播放状态：`0=停止 1=播放 2=暂停` + 控制器实例捕获 |
| 6 | `android.app.NotificationManager.notify` / `cancel` / `cancelAll` | 载波模式：歌词 Ticker 注入宿主媒体通知（**模块自己不 notify**） |
| 7 | `settings.fragment.SettingsFragment.onViewCreated` / `onDestroyView` | ★ 设置页注入的挂点；方法实际声明在改名后的 `androidx.preference.b` 上，用 `getMethod` 沿继承链取即可（见 [SettingsInjector](app/src/main/java/com/amlyric/flyme/hook/SettingsInjector.kt) 头部注释） |
| 8 | `com.apple.android.music.player.fragment.PlayerLyricsViewFragment.I2`（单参 `SongInfoPtr`，返回 void） | ★ 在线歌词注入的安装点：把补来的歌词替换进去。**函数名恰好未被 R8 改名**，但定位仍按签名形状（见 [LyricsInjector](app/src/main/java/com/amlyric/flyme/hook/LyricsInjector.kt) 头部注释） |

**不走 Hook、直接反射调用的官方能力**：

| 目标 | 用途 |
|---|---|
| `com.apple.android.music.ttml.javanative.TTMLParser$TTMLParserNative.songInfoFromTTML(String)` | 把**外部** TTML 文本交给宿主自己的解析器，得到 `SongInfo$SongInfoPtr`；这是"补一份歌词"的唯一入口。同族方法都是 JNI native，**名字不会被 R8 混淆** |
| `...javanative.model.SongInfo$SongInfoPtr.get()` / `.setAdamId(long)` / `.getSections()` | ptr → 原生对象、绑定歌曲 ID、判断是否有内容 |
| `...javanative.model.SongInfo$SongInfoNative.getAvailableTiming()` | 时间轴类型枚举（`None` / `Line` / `Word`）—— "要不要补"与"注入是否真的变成逐字"的唯一判据 |
| `com.apple.android.music.model.PlaybackItem.hasCustomLyrics()` | 用户自己配过自定义歌词时为真，此时模块一律不动 |
| `org.bytedeco.javacpp.Pointer.address` | JavaCPP 包装对象的**存活判据**（地址非 0）；官方在歌词页 `onDestroyView` 会 `deallocate()`，踩了野指针会直接崩进程 |

**歌词数据提取**（`NativeLyricsParser.kt`，全部走反射、逐步判空）：

- 当前行文本由官方引擎 `processEvents` 的逐行回调直接给出（回调参数携带 `LyricsLineVector`），
  再由 `extractLineText()` 反射读取索引 0 的 `getHtmlLineText()`，并做 HTML 清洗。
  **刻意只取索引 0** —— `LyricsLineVector` 后面可能跟翻译行 / 发音行，早先用 `" · "` 全拼，
  状态栏会变成「残酷な天使のように · 就像那残酷的天使一样」；
- **逐行时间戳不由模型暴露**（`LyricsLineNative` 没有 `getBegin/getEnd`），时间由
  `SongInfoTimeProcessor` 在播放时实时计算（见上文「驱动机制要点」），因此歌词时间轴不在此处解析；
- 有无歌词判断：`SongInfoPtr → get() → getSections()`，`size() > 0` 即含歌词。

原生容器的统一特征：`size(): Long`、`get(i)` 返回智能指针包装、再 `.get()` 拿到原生对象。

### 版本更新后如何重新定位（jadx / 自研 dex 工具 指南）

Apple Music 更新后若类名/方法名变化，用 jadx-gui 打开新 APK，按以下特征搜索：

- `buildTimeRangeToLyricsMap` —— 搜方法名字符串（歌词 VM 构建时间轴→歌词映射）；
- `SongInfoTimeProcessor` / `processEvents` —— 官方歌词引擎入口，搜方法名；
- `LocalMediaPlayerController` —— 类名一般不被混淆（activity/播放控制层）；
- `MediaMetadataCompat` 来自内置 support-v4，不受混淆影响；
- **`.ttml.javanative.*` 是一整族 JNI 绑定**：`TTMLParser`、`SongInfo$SongInfoPtr`、
  `SongInfo$SongInfoNative` 的名字都不会被混淆，可以放心直接 `loadClass`。

搜索到新类名后只需改对应常量 / 字符串，其余逻辑不变。
（更稳妥的进阶方案：接入 [DexKit](https://github.com/LuckyPray/DexKit) 做特征匹配自动定位。）

> **务必从设备上 `adb pull` 真机 APK 再反解**，别用本地留存的旧安装包。
> 本项目就踩过这个坑：本地那份 `Apple Music_5.2.0.apks` 是 versionCode 1541，
> 而真机跑的是 6.5.2 / 1586，**两版的混淆名完全不同**（`M2` vs `I2`）。

## 二、Flyme 状态栏歌词对接（官方方案）

依据魅族开放平台《Flyme 状态栏歌词适配》文档（open.flyme.cn/docs?id=239）：

- 本质是**带 Ticker 的常驻通知**，需要同时添加两个 Flyme 扩展 flag：
  - `Notification.FLAG_ALWAYS_SHOW_TICKER`（0x1000000）：Ticker 一直显示；
  - `Notification.FLAG_ONLY_UPDATE_TICKER`（0x2000000）：只更新歌词，不刷新封面等；
- 外加 `Notification.FLAG_NO_CLEAR` 保证常驻；
- `extras` 中 `ticker_icon_switch = false`：**关闭状态栏左侧小图标，仅保留歌词文本**；
- 每次更新对**同一个通知 ID** 调用 `notify()`；清除时 `cancel(id)`。

**载波模式：模块自己不发通知**

模块不创建通知，而是 Hook `NotificationManager.notify`，识别宿主的 **MediaStyle 媒体通知**，
在提交前把歌词 Ticker 注入进去：

- 媒体通知本来就在（播放期间必然存在），挂上歌词后**状态栏零新增通知**；
- 歌词更新时只改 `tickerText` 重发同一条通知：`when`/内容均不动，
  Flyme 走「只更新歌词」动画 → 左侧图标不再跟着歌词一起滚动。

> ⚠️ **v1.4.0 彻底删除了「退回模式」（自带通知）**。旧实现在找不到宿主媒体通知时会自己
> `notify()` 一条通知来显示歌词，后果是：
> ① 通知栏 / 控制中心多出一条写着歌词的通知；
> ② 系统「通知设置」里给 Apple Music 多出一个「状态栏歌词」渠道（真机实测累计 12 条通知）。
> 现在载波拿不到时**什么都不做**，只记一条日志 —— 宁可这一句不显示，也绝不往通知栏塞东西。
> `init()` 里还会把历史遗留的渠道与通知清掉。

**兼容性处理**：

1. 两个 flag 是 Flyme 对 `android.app.Notification` 的私有扩展，模块通过**反射读取**；
   读不到（非 Flyme 或未移植该功能的 ROM）自动禁用，模块静默退出，不产生任何通知或异常。
2. 载波注入只改 `tickerText` 与 Flyme 扩展字段，**不改宿主的通知渠道、图标、文本与 action**。
3. 相同文本 2 秒内不重发，防止 Flyme 的 Ticker 动画反复触发造成闪烁。

## 三、状态与异常处理

| 状态 | 行为 |
|------|------|
| 切歌 | 立即清空状态栏，重算前奏抑制（从开头播放时保持空白到「第一句 − 1s」） |
| 前奏 / 间奏 | 从开头播放时**不推送任何内容**（前奏），直到第一句前一秒；间奏保持上一句不清屏 |
| 无歌词 | 状态栏保持空白（不推歌名、不弹「暂无歌词」占位）。开了自动补全则去第三方源补一份 |
| 暂停 | 停止轮询，状态栏**冻结**在当前行（不消失、不闪烁） |
| 停止 / 播完 | 停止轮询，清除状态栏歌词 |
| seek 拖动 | 立即重算当前行（`onSeek` 直接触发一次 tick），不等待下个轮询周期 |
| 播放器实例回收 | 进度提供者返回 null，轮询自动挂起，播放恢复后重启 |
| 在线补全取不到词 | 记一次日志并**记住这次失败**（`gaveUp`），本首不再重复请求 |

**稳定性设计**（保证不崩溃、不卡顿）：

- 每个 Hook 独立安装、独立 try/catch —— 单个 Hook 失败只损失对应功能；
- 所有原生对象反射调用都走 `runCatching` 包装，任何一步异常安全返回 null；
- 轮询跑在宿主主线程（与播放界面 Fragment 的 Handler 同线程，`processEvents` 内部调
  原生方法需同线程防并发），`MAX_DELAY_MS` 心跳兼顾跟手感与后台存活；
- **在线取词跑在独立 worker 线程**（单线程池，队列 2），结果回主线程落地；
  队列满时直接丢弃这次请求，不阻塞播放；
- Hook 回调里只做轻量状态记录，通知构建全部延迟到轮询线程；
- 播放器实例、歌词页 Fragment 都用 `WeakReference` 持有，不阻碍宿主 GC；
- 补全结果最多缓存 6 首（LRU 淘汰），避免长期占用内存。

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

> ⚠️ **工程路径含空格时不要用 `gradlew.bat`**：它内部的 `cd %APP_HOME%` 没加引号，
> 路径会在空格处被截断并报 `'xxx' 不是内部或外部命令`。改用 Git Bash 里的 `./gradlew`
> （或自行给 `.bat` 里的路径补引号）。

依赖说明：仅 `compileOnly("io.github.libxposed:api:102.0.0")`（Maven Central），
运行时由 LSPosed 注入实现，APK 体积增量极小。字节码基线为 Java 17（与 libxposed API 一致）。
在线歌词模块**不引任何第三方网络库**，直接用 `HttpURLConnection`。

### libxposed API 102 与 legacy API 的差异（维护须知）

| 事项 | legacy（API 82） | 本工程（API 102） |
|------|------------------|-------------------|
| 入口注册 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 模块元数据 | manifest 里 `xposed*` meta-data | `META-INF/xposed/module.prop`（`minApiVersion` / `targetApiVersion`） |
| 作用域 | manifest `xposedscope` | `META-INF/xposed/scope.list` + `staticScope=true` |
| 入口类 | 实现 `IXposedHookLoadPackage` | 继承 `XposedModule`，覆写 `onPackageLoaded()` |
| Hook 写法 | `XposedHelpers.findAndHookMethod` + `XC_MethodHook` | `hook(Executable).intercept(Hooker)` 拦截器链（OkHttp 风格） |
| 改参数 | `param.setResult` / 直接改 `args` | **`Chain` 没有可写 `args`**，只能 `chain.proceed(Object[])` |
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
6. 需要补全歌词时，进入 **Apple Music → 设置 → 状态栏歌词 / 歌词补全**，
   打开「自动实时补全」并选一个歌词源。

排错：

```bash
adb logcat -s AMFlymeLyric
```

- `Flyme status bar lyric NOT supported` → 当前 ROM 无此功能（非 Flyme 或未移植）；
- `hook [xxx] failed` → Apple Music 版本更新导致类名变化，按上文 jadx 指南重定位；
- `lyrics injected: ...` / `lyrics inject: 开始取词 —— <原因>` → 在线补全正在工作（正常）；
- `lyrics injector disabled: <原因>` → 注入安装点没找到（宿主版本变了）；
- `不补 —— 原生已经是逐字歌词` → 判定生效，没有请求任何歌词服务（正常）；
- 歌词不显示但无报错 → 该歌曲无歌词数据，且在线源也没查到。

## 六、已知限制

- 适配 **Apple Music 6.5.2 / versionCode 1586** 验证。大版本更新后需重新定位符号；
  **在线注入的安装点 `I2` 名字会随版本变**（5.2.0 叫 `M2`），改版后模块会自动禁用注入
  并打一条 `lyrics injector disabled`，不会崩、也不会影响状态栏歌词主链路；
- **原生歌词一律不做任何处理**：宿主给什么就显示什么，包括它自带的制作名单行。
  只对**第三方补来的歌词**做清洗（版权行 / `歌名 - 歌手` 尾注行）；
- 第三方歌词的清洗是**启发式**的，偶有漏网：
  - QQ 的 QRC 会把多个职称叠成一行（`后期母带处理制作人MASTERING PRODUCER：…`），
    已用"宽窗口 + 职衔词表"覆盖，但词表补不完；
  - `歌名 - 歌手` 尾注行的识别依赖**宿主元数据与歌词站一致**。实测当歌手名带别名括注时
    （Apple 给 `蔡恩雨 (Priscilla Abby)`、歌词站写 `蔡恩雨`）匹配会失败，尾注行可能残留一行；
- Apple Music 无损 / 杜比全景声歌曲的歌词时间轴与普通曲目一致，无额外处理；
- 伴奏 / 纯音乐轨按**标题关键词**识别（宿主不暴露"这是伴奏轨"的字段），
  标题里没写「伴奏 / instrumental」之类的纯音乐曲目仍会被尝试补全。

## 更新日志

- **v1.4.0**：
  - **在线歌词真正接进播放流程**（真机验证）：新增 `LyricsInjector`，把第三方补来的逐字歌词
    **同时**落到两条通路 —— ① 驱动 Flyme 状态栏；② 注入 Apple Music 播放页
    （重入宿主的歌词安装点 `PlayerLyricsViewFragment.I2`，注入后 `getAvailableTiming()`
    从 `None/Line` 变成 `Word`，即官方解析器认成了逐字时间轴）。
  - **补全判定按最终口径收窄**：只有「原生歌词缺失」或「原生不是逐字时间轴」才补；
    **只要有原生逐字歌词就一律不补，不管有没有翻译**；**伴奏 / 纯音乐轨跳过**
    （标题关键词识别）；用户自定义歌词（`hasCustomLyrics()`）优先，一律不动。
    每个判定都会在日志里打出"为什么补 / 为什么不补"，便于现场核对。
  - **状态栏不再推送翻译**：`LyricsLineVector` 只取索引 0（主歌词），不再用 `" · "` 拼接。
  - **模块不再创建任何通知**：删掉旧版"找不到宿主媒体通知就自己发一条"的退回模式。
    那条后路会在通知栏多出一条歌词通知，并在系统通知设置里给 Apple Music 多出一个
    「状态栏歌词」渠道（真机实测累计 12 条）。现在改为**只做载波注入**，
    拿不到宿主媒体通知时什么都不做，并清理历史残留的渠道与通知。
  - **修掉"我们自己的引擎探测触发自己的 Hook"**：反射驱动 `processEvents` 时，
    宿主内部的回调 wrapper 会再次走到 `hookLineCallback`，于是模块自己的探测被当成
    "前台在显示歌词"再推一次（表现为自检样例歌词跑到状态栏、下一句被提前推出）。
    现在驱动期间掐掉这条前台 Hook。
  - **新增 `CreditLine` 清洗**（只作用于第三方歌词）：版权 / 制作人员行 + `歌名 - 歌手` 尾注行。
    规则是照着真机抓到的原文调的 —— `曲：黄霄雲`（单字标签只能整段相等）、
    `OP/SP：回音如果`（复合标签先拆再判）、`（未经著作权人许可 不得翻唱 翻录或使用）`
    （整行括号 + 声明词）、`后期母带处理制作人MASTERING PRODUCER：…`（冒号前 27 字，需宽窗口）。
    兜底规则：长成 `标签：内容` 就过滤，只保留"主语 + 说话类动词"这一个例外
    （`我说：你好` 这类不能被误杀 —— 真歌词删掉是找不回来的）。
  - **设置页注入修好了**（真机验证）：旧实现写的 `androidx.preference.PreferenceFragmentCompat`、
    `setPreferenceScreen`、`SettingsActivity` 三个假设在宿主里**全部不成立**，异常被吞掉，
    所以「状态栏歌词」那行从来没出现过。现在改为**按签名定位 + 运行时自证**：从
    `SettingsFragment.onViewCreated` 拿页面，用一条一次性 `PreferenceCategory` 当真值试验台
    试出哪个同参方法是 `addPreference`（宿主里同参的有三条，按返回类型猜会拿到 `removePreference`，
    表现为"注入成功"其实在删），`key` 字段同样靠 `findPreference` 回查确认。
  - **新增「歌词补全」分组**：自动实时补全 + 歌词源（QQ 音乐 / 网易云音乐，单选）。
  - **简繁 / 歌词源改成正确语义的单选组**：已有一个开着时点另一个会自动关掉原来那个；
    都关着时点任意一个都能开。简繁允许两个都关（= 不转换），歌词源必须有一个。
  - **新增在线歌词源**（QQ 音乐 QRC / 网易云 YRC）：搜索 → 取词 → 解密 → 解析 → 逐字时间轴 +
    翻译轨 → 写成 **Apple 认的 TTML** → 交给宿主自己的 `TTMLParser` 转成 `SongInfoPtr`。
    **QRC 用的是被改过 S 盒的非标准 DES，标准 3DES 解不开**，所以按参考实现逐位移植（详见 `QrcCrypto.kt`）。
  - **前奏期改为不推送任何内容**：删掉 v1.3.x 的「推歌名 → 锁定 → 到第一句替换」那套
    （`SongTitleGate` → `LyricGate`），前奏期状态栏保持空白，到「第一句 − 1 秒」才上屏。
    「歌名一闪而过」这个纠缠三个版本的 bug 从根上消失（没有歌名可闪）。
  - 默认**不联网**：`自动实时补全` 关闭时播放过程中不请求任何歌词服务。
- **v1.3.17**：修复**切歌清屏被跳过**——宿主 UI 会**提前预加载**下一首的歌词（`loadLyrics` Hook 早于切歌触发），而该回调原先会更新"当前歌曲标识"，导致随后真正的切歌事件判定"id 未变"直接返回、跳过 `clear()`（实测 14 首里 8 首如此；平时被"紧接着推歌名"掩盖，但在**暂停中切歌**时会看到上一首的歌词残留）。现在该回调只记日志、不碰任何显示状态。
- **v1.3.16**：**修复「歌词提前冒出」的根因**——真机日志证实官方引擎 `processEvents` 的返回值**不是「下一行」而是「下一事件」**（行内字级事件也会被报出来，实测提前量小到 2ms）。当第一句来得很早、或歌词句柄迟到（拿到 ptr 时已进入第一句内部）时，算出的释放点会落在**过去**，于是抑制在下一 tick 就被解除。现在释放点加了下限「推出位置 + 3 秒」：无论首句时间多离谱都至少保持 3 秒。另修：位置读取失败原会返回 0，与「真的回到开头」不可区分（改为读取失败则跳过本 tick）；顺带清理死代码与重复状态。
- **v1.3.15**：**无歌词歌曲的处理**——部分歌曲宿主根本不产出歌词（日志表现为 `loadLyrics timeout ... no ptr after 15s`，此时连歌词句柄都没有，抑制永远等不到第一句）。改为：显示 **8 秒**（`TITLE_HOLD_MAX_MS`）后**清空状态栏**，本首剩余时间内**不再推送任何内容**。若期间引擎其实报出了歌词事件（说明只是加载慢），自动解除静默、恢复正常推送。
- **v1.3.14**：**真正修复前奏期歌词提前上屏**——v1.3.12/v1.3.13 只堵了后台驱动一条上屏通路，漏了**前台 `lineEventCallback` 路径**：播放界面打开时引擎会自己回调当前行，前奏期把第一句当活动行回传，直接把状态栏顶掉（真机实测抑制期间 ticker 仍被首句覆盖）。本版让前台路径共用同一把闸门，并借这条最可靠的路径捕获首句文本（后台 peek 偶发 null）；另修复**重播同一首 / 拖回开头**闸门不重武装的问题。
- **v1.3.13**：修复 v1.3.12 抑制逻辑导致**第一句歌词被吞**的问题——官方引擎对首句只回传一次且常在开头提前回传，抑制期间被丢弃后不会二次回传。改为放开时用前奏期 peek 到的首句文本**手动补推一次**，确保第一句在「第一句前 1 秒」准时出现。
- **v1.3.12**：修复前奏期第一句歌词过早出现的问题，引入「前奏抑制」：先记下首句时间戳，抑制歌词上屏，到「第一句歌词前 1 秒」才放开。
- **v1.3.11**：新增「播放最开头推送歌曲名-歌手名」——仅从头（position ≤ 1.5s）播放时先显示「歌曲名 - 歌手名」，拖动进度条 / 跳播 / 中段续播等非开头场景只推歌词、不推歌名；只在首次进入该歌曲时推一次。
- **v1.3.10**：`LEAD_MS` 由 `1200ms` 回调到 `1000ms`（提前约 1 秒上屏，B 方案下一行探测调度不变）；README 删除不实内容（5.2.0 无 `getBegin/getEnd` 时间戳、无 `SHOW_TRANSLATION` 开关）。
- **v1.3.9**：`LEAD_MS` 由 `2000ms` 回调到 `1200ms`（提前约 1.2 秒上屏，B 方案下一行探测调度不变）。
- **v1.3.8**：B 方案「下一行探测 + 时间戳精准调度」；`LEAD_MS` 由 `1000ms` 提升至 `2000ms`（提前约 2 秒上屏）。
- **v1.3.7**：歌词提前量 `LEAD_MS` 由 `400ms` 提升至 `1000ms`（状态栏歌词提前约 1 秒上屏）；调度逻辑不变。
- **v1.3.6**：纠正 `processEvents` 返回值语义（下一事件绝对位置而非延迟），新增提前量；后台持续滚动稳定。

## 致谢

- Hook 点验证参考了开源项目 LyricProvider（Apache-2.0）对 Apple Music 的适配；
- 播放页歌词注入的手法参考了开源项目 [AM-plus-plus](https://github.com/Zennmn/AM-plus-plus)；
- 在线歌词源接口与 QRC 解密参考了开源项目 [getl](https://github.com/ChouChiu/getl)；
- Flyme 状态栏歌词实现遵循魅族开放平台官方文档。

## License

本项目以 [MIT License](LICENSE) 开源。
