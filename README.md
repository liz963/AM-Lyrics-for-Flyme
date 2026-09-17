# AM Lyrics for Flyme

[![Release](https://img.shields.io/github/v/release/liz963/AM-Lyrics-for-Flyme)](https://github.com/liz963/AM-Lyrics-for-Flyme/releases)
[![License](https://img.shields.io/github/license/liz963/AM-Lyrics-for-Flyme)](LICENSE)
[![LSPosed API](https://img.shields.io/badge/LSPosed-API%20102-blue)](https://github.com/LSPosed/LSPosed)

Hook Apple Music（`com.apple.android.music`），把当前播放歌曲的歌词送进 **Flyme 官方状态栏歌词**通道；
当宿主拿不出能滚动的歌词时，可从第三方歌词源补一份，并同时注入播放页。

基于 **libxposed 现代 API（API 102，`io.github.libxposed:api`）**。
适配版本：**Apple Music 6.5.2 / versionCode 1586**。

---

## 一、Hook 点

| # | 目标 | 用途 |
|---|------|------|
| 1 | `android.app.Application.attach` | 拿宿主 Context / ClassLoader，初始化模块 |
| 2 | `PlayerLyricsViewModel.loadLyrics` | 宿主发起取词（用于歌曲同步与去重，**不改任何显示状态**） |
| 3 | `PlayerLyricsViewModel.buildTimeRangeToLyricsMap` | 歌词构建完成，捕获原生歌词句柄 `SongInfoPtr` |
| 4 | `SongInfoTimeProcessor.processEvents` | 官方歌词引擎入口。**模块不 Hook 它，而是反射调用它**（见 §3.2） |
| 5 | `LocalMediaPlayerController.onPlaybackStateChanged`（3 参，末参 int） | 播放状态 `0=停止 1=播放 2=暂停`，同时捕获控制器实例（读进度与当前曲目） |
| 6 | `NotificationManager.notify` / `cancel` / `cancelAll` | 载波模式：歌词 Ticker 注入**宿主自己的媒体通知**（见 §3.3） |
| 7 | `SettingsFragment.onViewCreated` / `onDestroyView` | 设置页注入挂点。方法实际声明在改名后的 `androidx.preference.b` 上，用 `getMethod` 沿继承链取 |
| 8 | `PlayerLyricsViewFragment.I2`（单参 `SongInfoPtr`，返回 void） | 在线歌词注入安装点（见 §3.5）。**名字恰好未被 R8 改名**，但定位仍按签名形状 |
| 9 | `SongInfoTimeProcessor$processEvents$lineEventCallback$1.call` | 引擎逐行回调（播放界面打开时**宿主自己**驱动的那条路，见 §3.1「两条上屏通路」） |

## 二、不走 Hook、直接反射调用的官方能力

| 目标 | 用途 |
|---|---|
| `TTMLParser$TTMLParserNative.songInfoFromTTML(String) -> SongInfoPtr` | 把**外部** TTML 文本交给宿主自己的解析器变成 `SongInfoPtr`。这是"补一份歌词"的唯一入口 |
| `SongInfo$SongInfoPtr.get()` / `setAdamId(long)` / `getSections()` | ptr → 原生对象；写歌曲标识；判断有没有内容 |
| `SongInfo$SongInfoNative.getSongwriters(...) -> StringVectorNative` | **次判据**：原生歌词的创作者名单，非空即"宿主有可用歌词"（向量读 `size()` / `get(long)`） |
| `SongInfo$SongInfoNative.getAvailableTiming()` | 时间轴类型枚举 `None` / `Line` / `Word`。作为第二判据；注入后也用它确认"真为逐字" |
| `SongInfoTimeProcessor.processEvents(ptr, pos, 5×callback) -> long` | 驱动官方引擎求值（见 §3.2） |
| `PlaybackItem.hasCustomLyrics()` | 用户自己配过歌词时为真，此时一律不动 |
| `org.bytedeco.javacpp.Pointer.address` | JavaCPP 包装的**存活判据**（地址非 0）。宿主在歌词页 `onDestroyView` 会 `deallocate()`，拿死指针调 native 就是 use-after-free |

> `.ttml.javanative.*` 是一整族 JNI 绑定，方法名与类名都不会被 R8 混淆 —— 这是全工程里唯一能安全写死名字的一批符号。其余一律按签名形状定位。

---

## 三、技术方案

### 3.1 状态栏歌词主链路

模块不自己实现「逐行定位」，而是驱动 Apple Music 播放界面所用的**同一个官方引擎**，因此节奏与官方完全一致。

```
Application.attach                     初始化
      │
      ▼
PlayerLyricsViewModel.loadLyrics       宿主取词（歌曲同步）
PlayerLyricsViewModel.buildTimeRangeToLyricsMap   捕获 SongInfoPtr
      │
      ▼
LyricsEngineDriver                     ★ 反射驱动 processEvents → 逐行回调
      │
      ▼
NativeLyricsParser                     原生 Song → 文本（反射 + HTML 清洗）
      │
      ▼
BackgroundLyrics                       编排：读进度 / 切歌 / 前奏闸门 / 算下一跳
      │
      ▼
LyricController                        上屏出口（空行忽略 + 非歌词行过滤）
      │
      ▼
FlymeStatusBarLyric                    载波：Ticker 注入宿主媒体通知
```

**★ 两条上屏通路，抑制逻辑必须两边都堵**

1. **后台通路**：`BackgroundLyrics` 用模块自建引擎实例驱动，display 回调上屏 —— 始终在位；
2. **前台通路**：播放界面打开时**引擎自己**会回调当前行，走 Hook #9（`lineEventCallback.call`）。

v1.3.12/v1.3.13 只堵了 ①，② 无条件调 `LyricController.onLyricLine()`，于是前奏期第一句照旧顶掉状态栏。
现在两条都经同一把闸门（② 走 `BackgroundLyrics.onForegroundLine()`）。**任何新增的上屏/抑制条件都要两边同时考虑。**

**★ 我们自己驱动引擎也会触发我们自己的 Hook**

`processEvents` 内部会把传入的回调包一层宿主自己的 lambda（`SongInfoTimeProcessor$processEvents$lineEventCallback$1`），
而 Hook #9 正是按类挂在这层 lambda 上。所以我们调 `processEvents` 时它同样会触发，被误认为"前台正在显示歌词"。

实测后果：① 引擎自检用的样例歌词（`自检歌词`）被推到状态栏；② 为算下一跳而做的只读探测把下一句**提前推**出去。
现在 `drive()` / `peek()` 期间置位 `LyricsEngineDriver.isInvoking`，前台 Hook 看到就整条跳过。

### 3.2 歌词引擎驱动语义

```
processEvents(ptr, positionMs, lineCb, wordCb, bgWordCb, prWordCb, prBgWordCb) -> long
```

- **返回值不是延迟，而是「下一歌词事件的位置(ms)」**。早期版本把它当 delay 用（拿到 15~45s 就一直睡），
  直接导致后台停更，这是 v1.3.4 的教训。
- **它也不是「下一行开始时间」**：行内字级事件也会被报出来（实测 `nextPos - queryPos` 大量落在 2~900ms
  且该位置取不到行文本）。所以这个返回值只能当**唤醒节奏的锚点**，不能当语义时间用。
- 喂给引擎的位置是 `queryPos = 实际位置 + LEAD_MS(1000)`，让歌词比人声早约 1 秒出现，抵消状态栏 Ticker 的渲染延迟。
- 下一跳间隔 `= nextEventAt − LEAD_MS − posMs`，被 `[100, 5000]` 夹住。
  **上限 5000 是后台保活心跳**：主线程长时间无消息会被系统推后延时消息，期间既不读进度也不感知切歌，一旦发生就卡死。
  行间隔 > 5s 时会被截成提前唤醒，但下一跳用新位置重算真实剩余，**最后一跳必然 < 5s 且是精确值**。
- 5 个回调里只处理 line（索引 0），其余 no-op。行文本从回调参数 `LyricsLineVector` 取**索引 0** 的
  `getHtmlLineText()` —— 刻意不取索引 1 及之后（那是翻译行/发音行，早先用 `" · "` 全拼，状态栏会变成
  「残酷な天使のように · 就像那残酷的天使一样」）。

**★ peek 与 drive 必须用两个引擎实例**

`processEvents` **带内部游标**（只回报游标之后的新事件）。而 `drive(q=当前位置)` 与
`peek(q=下一事件位置)` 的调用位置天然一前一后，共用一个实例时 peek 会把游标推到未来，
随后 drive 在更早的位置求值就被判成"没有新事件"。

实测症状（**转入后台后歌词延迟更新甚至不更新**，因为后台只有 drive 这一条通路）：

```
后台 25 秒：ticker = 0 条，cbInvoke 回调 7 条但全部是 null
同时：     engine nextPos=166858 ... nextText=[我猜着你的心 要再一次确定]   ← peek 却取得到行
```

前台看不出问题，是因为前台的上屏主要由宿主自己的引擎（经 Hook #9）提供。
现在 `LyricsEngineDriver` 持有 `processor`（drive 用）与 `peekProcessor`（peek 用）两个实例，各自单调前进。
**新增任何调用 `processEvents` 的地方都必须明确挑一个实例，绝不能让同一实例被两个不同节奏的位置交叉驱动。**

### 3.3 载波模式：模块自己不发通知

模块不创建通知，而是 Hook `NotificationManager.notify`，识别宿主的 MediaStyle 媒体通知，
在提交前把歌词 Ticker 注入进去（`tickerText` + Flyme 扩展 flag）。

- 媒体通知本来就在（播放期间必然存在），挂上歌词后**状态栏零新增通知**；
- 更新歌词只改 `tickerText` 重发同一条通知，`when` / 内容均不动 → Flyme 走「只更新歌词」动画；
- **载波拿不到时什么都不做**，只记一条日志。绝不往通知栏塞东西。

> 旧实现有一条「退回模式」：找不到宿主媒体通知时自己 `notify()` 一条通知来显示歌词。
> 后果是通知栏/控制中心多出一条写着歌词的通知，且系统「通知设置」里给 Apple Music 多出一个
> 「状态栏歌词」渠道（真机实测累计 12 条）。**v1.4.0 已整块删除**，`init()` 里还会清理历史残留的渠道与通知。

### 3.4 Flyme 状态栏歌词接口

依据魅族开放平台《Flyme 状态栏歌词适配》文档（open.flyme.cn/docs?id=239）：

| 项 | 值 |
|---|---|
| `Notification.FLAG_ALWAYS_SHOW_TICKER` | `0x1000000`，Ticker 常显 |
| `Notification.FLAG_ONLY_UPDATE_TICKER` | `0x2000000`，只更新歌词，不刷新封面等 |
| `Notification.FLAG_NO_CLEAR` | 保证常驻 |
| `extras: ticker_icon_switch` | `false` → 关掉状态栏左侧小图标，仅保留歌词文本 |

两个 flag 是 Flyme 对 `android.app.Notification` 的私有扩展，模块通过反射读取；
读不到（非 Flyme / 未移植该功能）就静默禁用，不产生任何通知或异常。

### 3.5 在线歌词注入

**安装点**：`PlayerLyricsViewFragment.I2(SongInfo$SongInfoPtr) -> void`。
6.5.2 里它是**唯一**同时满足「实例方法 + void + 单参且参数类型是 `SongInfo$SongInfoPtr`」的方法。
同形状的 `F2` / `G2` 返回 boolean，`R2` 是另一个 void，靠名字钉死。
（5.2.0 里同一位置叫 `M2`，所以**不能只按名字找，要先按签名形状找、再钉名字**。）

**流程**：

```
BackgroundLyrics（每 tick）                       I2(null/SongInfoPtr) 被调用
      │                                                    │
      │  判据：有没有原生歌词？                              │  只记账、本帧放行
      ▼                                                    ▼
LyricsInjector.eligible()                          LyricsInjector.decide()
      │  伴奏/纯音乐 → 跳过                                  │
      │  逐字 / 有创作者名单 → 不补                          │
      ▼                                                    │
取词 worker（QQ / 网易云 → TimedLyrics → TTML）                │
      │                                                    │
      ▼                                                    ▼
TtmlBridge.parse(ttml) → SongInfoPtr               主线程重新进入 I2(ptr)
      │                                                    │
      ├──────────► ① BackgroundLyrics.onSongInfo(ptr)：驱动状态栏
      └──────────► ② 反射 I2(fragment, 我们那份 ptr)：播放页变成逐字
```

**判定口径（最终版，改前必读）**

| 宿主状态 | 处理 |
|---|---|
| `args[0] == null`（没交句柄） | **补** |
| `timing = Word`（逐字） | **不补**（主判据） |
| `timing = None`（有歌词但**不滚动**） | **补** |
| 句柄带创作者名单（`getSongwriters` 非空 / 原生 TTML 有 `<songwriters>`） | **不补**（第二道保险） |
| 无名单、`timing = Line` | **补** |
| 伴奏 / 纯音乐轨（标题关键词） | 跳过，不请求也不注入 |
| `PlaybackItem.hasCustomLyrics()` 为真 | 不动 |

顺序是刻意的：`Word` 意味着官方引擎给了**字级时间轴**，这份歌词一定可用；
「创作者：xxx」只是"宿主有歌词"的旁证，所以是第二道保险。

**`None` 必须排在名单之前**：它表示"句柄交出来了，但里面没有可用时间轴"，
真机表现就是**有歌词、但不滚动**。名单只能证明"宿主有歌词数据"，**证明不了"这份歌词能滚"**，
让名单先说话就会把这些歌重新判成"不补"——用户明确要求这类也要补。

**名单为什么重要**：`getAvailableTiming()` 会把**行级**歌词也报成 `Line`，而行级在播放页上同样会逐行滚动 ——
按"非 Word 就替换"会成批误伤那些用户认为"有滚动歌词"的歌。名单正是用来把这类歌捞回来的。

名单有**两条读取路径，任一命中即算"有"**：

1. **文本侧（主）**：hook 宿主自己的解析入口 `TTMLParser$TTMLParserNative.songInfoFromTTML(String)`，
   在文本被丢掉之前看一眼有没有 `<songwriter>`。那行「创作者：xxx」就是宿主从这段 XML 渲染的，
   信息本来就在文本里，不必猜 JNI 参数。我们自己注入的那份靠来源标记（`TtmlWriter.SOURCE_LABEL`）排除。
2. **句柄侧（补充）**：`SongInfo$SongInfoNative.getSongwriters(String) -> StringVectorNative`
   （JavaCPP native 绑定，名字不被 R8 改写；向量有 `size()` / `get(long)`）。

**⚠️ 认歌只认"正数标识"**（v1.4.2 的 bug，v1.4.4 收敛）：宿主调 `songInfoFromTTML` 时，
**返回的 ptr 上还没有歌曲标识** —— 实测 `getAdamId()` 拿到的是**未初始化的垃圾值**
（形如 `-5476376653955703808`），不是 0。所以只取正数；认不出就落到**最近一份原生 TTML**（15s 窗口）。

**不要**在认不出时改用"当前正在播放的 adamId"记账 —— 它更新**滞后于解析**（实测落后一首），
会把 B 歌的名单状态写到 A 头上：好一点是少补一首，坏一点是白白替换掉有歌词的歌。

自检样例（`TtmlBridge.selfTest`）和我们的注入都会经过同一个解析入口，靠标记排除：
前者带 `TtmlBridge.SELF_TEST_MARK`，后者带 `TtmlWriter.SOURCE_LABEL`。
诊断看 `native ttml: native|own|selftest len=… songwriters=… ptrAdamId=… 采用=…` 这一行。

口径演进过多次，别改回去：最早"外语逐字缺翻译也补"（把宿主带翻译的歌词换掉，翻译反而丢），
后来"只要 Apple 有歌词就不替换"，再后来按 `timing` 判"能不能滚"（行级同样会滚，被误替换），
然后是"有创作者名单就不替换"，最后收敛成 **`Word` 为主、名单兜行级**。

**安装点的三个硬约束**

1. **`I2` 是同步方法，取词必须联网** → 首帧只能放行（宿主显示"暂无歌词"），取完再由模块回主线程**重新进入 `I2`**。
   fragment 用 `WeakReference` 持有；重入前复核 `isAdded()` 与「当前曲目还是这首歌」，对不上就放弃。
2. **`XposedInterface.Chain` 没有可写 `args`**（只有 `getArg(int)` / `proceed(Object[])`），
   换参数只能 `chain.proceed(arrayOf<Any?>(新ptr))`。
3. **必须用「当前播放的 adamId」去认 fragment 上的曲目字段**。`PlayerLyricsViewFragment` 继承链上有
   两个 `BaseContentItem` 字段（实测 6.5.2 是 `e.W` 与 `m.c`），其中 `e.W` 给的是**同一张专辑的另一个条目**
   （`6805436921` vs 正在播的 `6805436926`）。选错＝整条注入静默失效，所以匹配不上就**不动**。
   字段名太不稳定（AM++ 在 6.5.0 钉的 `m#c` 到 6.5.2 就变成了 `e#W`），所以只钉**类型**，名字交给运行时挑。

**宿主会回传它自己那份歌词**：补完之后宿主还会再解析一次并把原生 ptr 交回来（常常不重调 `I2`，
而走 `LyricsLoader.onPtrCaptured`）。所以 `BackgroundLyrics.onSongInfo` 固定让补全结果优先，直到切歌或关开关。

**「有没有原生歌词」是一场赛跑**：切歌时宿主取词是异步的，`I2(null)` 之后 200ms 它可能就拿到了 ptr。
所以 `args[0] == null` 那一帧**只记账、不请求**，"确实缺"的定性交给宽限期（`ONLINE_GRACE_MS = 3000`，
从**真正开始播放**起算 —— 启动时宿主会先恢复队列、切歌事件在第 1 秒就到，但真正开播要等十几秒，
按切歌起算会把"宿主还没取词"误判成"缺失"）。

即便如此仍会有窗口，所以结果落地前还有**最后一道复核**（`hostHasLyrics`）：取词这几百毫秒里只要宿主
交出了**逐字**歌词，就整份丢弃。注意判定用的是"逐字"而非"非空" —— 非滚动的原生歌词本来就是要被替换的对象。

### 3.6 写入宿主的 TTML 格式

外部歌词要变成官方引擎认的 `SongInfoPtr`，必须写成 Apple 的 TTML。格式不是抄文档，是真机试出来的：

| 写法 | 原因 |
|---|---|
| 根节点 `xml:lang="ko"` | Android 版 Apple Music **只在歌词是韩语时**才渲染翻译轨，写别的语言翻译不显示 |
| 翻译放 `<head><iTunesMetadata>`，用 `<text for="L1">` 挂到行上 | Apple 的格式：正文只放歌词，翻译/音译在头部声明一次，靠 `itunes:key` 关联 |
| **每一行都要有 `<text for="Ln">` 条目且顺序一致** | Android 版是**按顺序走条目**而不是按 `for` 解析 —— 少一条后面全部错位，有空洞整条轨会被拒 |
| 无翻译的行写一个空格 `" "` | 同上，占位保住顺序 |
| `<div>` / `<metadata>` 上不能有 `xmlns=""` | 会把歌词子树踢出 TTML 命名空间，宿主一行都读不到 |
| 逐字用 `<p><span begin end>字</span></p>` + 根节点 `itunes:timing="Word"` | 逐字高亮靠 span 的 begin/end |
| 纯行级写 `itunes:timing="Line"`，`<p>` 里直接放文本 | 没有 span 就别谎报 Word，否则官方按逐字解析会得到空行 |
| 背景和声 `ttm:role="x-bg"` 且**不带** begin/end | Apple 从和声自己的音节推时间范围，带了反而错 |
| 时间格式 `00:00:01.000`、从 0 起单调递增、每行 `end > begin` | TTML 标准 clock-time；Apple 解析器对空/逆序区间不容错 |
| 行 `end` 优先取下一行的 `begin` | 保证行与行不重叠，Apple 对叠加区间不友好 |

**逐字区间按比例缩放**：QQ 的 QRC 有 `[0,400]残(0,14)酷(14,15)…` 这类行（声明 400ms 塞了 60 多个字，
逐字时间一路涨到 900ms+）。直接夹紧会变成几十个 1ms 的 span 堆在行尾（高亮会"卡住"），
放任不管又会越过下一行。按比例压进本行区间最稳。

### 3.7 第三方来源标注

用户要求"替换进来的歌词要能在播放页看出来是第三方的"。落点只有一个：

```xml
<head><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
  <songwriters><songwriter>第三方歌词</songwriter></songwriters>
</iTunesMetadata></head>
```

`<songwriters>` 是整份 TTML 里**唯一承载非歌词文本**的元素（宿主自己那份 TTML 就在这个位置放词曲作者，
对应宿主资源里的 `songcredits_lyrics_stroke` / `credits_lyrics_button`），宿主会把它渲染成
歌词列表末尾的一行 `创作者： 第三方歌词`。

- 头部因此**无条件输出**（原先只在有翻译时才写），否则没翻译的歌就没有标注位置；
- 试过往 `<body>` 末尾追加一个 `<p>第三方歌词</p>` —— 宿主**完全不渲染**它（UI 树里查无此元素），别走这条路。

### 3.8 非歌词行过滤（只作用于第三方歌词）

两个来源会往歌词里塞非歌词文本：

1. **第三方源自带**：QQ 的 QRC 把制作名单当**正文行**返回且带真实时间戳
   （`曲：黄霄雲`、`音频编辑：商红阳`、`OP/SP：回音如果`、`配唱制作人Vocal Producer：…`），
   不清就会在前奏期顶在状态栏上好几秒；
2. **宿主的占位行**：歌词没就绪 / 这首歌没有歌词时，宿主的行回调会把 `歌曲名 - 歌手` 当歌词行回传。

过滤规则（`CreditLine`）：

- **冒号前的"标签段"**是判定锚点（正文里出现冒号很常见，但冒号前几乎不会恰好是"作词/编曲/制作人"）；
- 长词做**子串**匹配（`作词` / `编曲` / `Producer` / `Mastering` …）；
- **单字/极短词只做整段相等**（`曲` / `词` / `OP` / `SP`），否则「我想唱：…」这类正文会被误杀；
- 复合标签先按分隔符拆开逐段判（`OP/SP` → {OP, SP}）；
- 整行被括号包住且含声明词（`（未经著作权人许可 不得翻唱 翻录或使用）`、`推广：麻雀音乐人`）；
- 顿号前的标签段可达 27 字（`后期母带处理制作人MASTERING PRODUCER：`），所以是"窄窗口走兜底、
  宽窗口只匹配职衔词"两套；
- 兜底：长成 `标签：内容` 就过滤，只保留一个例外 —— 标签段以**说话类动词结尾**（`我说：你好`）。
  真歌词删掉是找不回来的，制作行最多是"没滤干净"。

⚠️ **原生歌词一律不做任何处理**：宿主给什么就显示什么，连它自带的制作名单行也算数。
过滤只在 `LyricsInjector.isThirdPartyActive()` 为真时生效。
`歌曲名 - 歌手` 占位行例外 —— 它与来源无关，一律挡掉（否则会被前奏闸门当成"第一句"，抑制提前放开）。

### 3.9 前奏期闸门

从**最开头**播放（含**重播同一首 / 拖回进度 0**）时，状态栏在「第一句 − 1 秒」之前不推送任何内容；
中段续播 / 拖进度条则不做等待，歌词立刻跟上；无歌词的曲目全程空白（不推歌名、不弹「暂无歌词」占位）。

闸门是纯状态机（`LyricGate`），只出决策不做动作：

- 首句时间来自前奏期 `processEvents` 回报的"下一事件位置" —— 但这只在**抑制窗口内**才可信
  （那时还没进入第一句内部，不存在行内字级事件干扰）；
- **安全网**：若引擎一直送行文本却始终探测不到首句时间，见到 3 个**互不相同**的文本就强制放开，
  否则闸门会永久关着。取 3 而不是 1，是为了不被引擎重复回报同一句干扰；
- 「回到开头」判定用位置（相对上次 tick 回退 > 2000ms 且落在 ≤1500ms），天然覆盖重播与拖回。
  ⚠️ 位置读取失败**必须返回 null 并跳过本 tick**，不能用 0 兜底 —— 0 与"真的回到开头"不可区分，
  会反复复位闸门。

---

## 四、代码结构

```
app/src/main/java/com/amlyric/flyme/
├─ HookEntry.kt                  LSPosed 入口（onPackageLoaded → 安装 Hook）
├─ XLog.kt / Settings.kt          日志与开关（简繁 / 自动补全 / 歌词源）
├─ hook/
│  ├─ AppleMusicHooks.kt          全部 Hook 安装点
│  ├─ SettingsInjector.kt         ★ 往宿主「设置」页注入开关（反射 + 运行时自证）
│  ├─ LyricsInjector.kt           ★ 在线歌词注入：判定 → 取词 → 重入 I2
│  ├─ TtmlBridge.kt               ★ 外部 TTML → 官方 SongInfoPtr（含存活判据）
│  └─ NativeLyricsParser.kt       原生歌词对象 → 纯文本
├─ core/
│  ├─ BackgroundLyrics.kt         ★ 轮询编排层：唯一调度者、唯一改状态栏的人
│  ├─ LyricsLoader.kt             取词：Hook 捕获 + 主动 loadLyrics + 重试
│  ├─ LyricGate.kt                ★ 前奏期闸门：纯状态机，只出决策
│  ├─ LyricsEngineDriver.kt       ★ 官方引擎反射驱动：唯一持有混淆名 / SAM / Proxy 细节
│  ├─ LyricController.kt          上屏出口（状态机 + 非歌词行过滤）
│  └─ HantConverter.kt            简繁转换
├─ lyric/                         在线歌词源
│  ├─ LyricFetcher.kt             统一入口：选源取词 → 清洗 → 转 TTML
│  ├─ QQMusicClient.kt            QQ 音乐：搜索 → 会话 → GetPlayLyricInfo
│  ├─ QrcCrypto.kt                ★ QRC 解密（改良版 DES + zlib）
│  ├─ NeteaseClient.kt            网易云：搜索 → /api/song/lyric/v1
│  ├─ CreditLine.kt               ★ 版权/制作行 + `歌名 - 歌手` 尾注行过滤
│  ├─ TtmlWriter.kt               ★ TimedLyrics → Apple 认的 TTML
│  ├─ LrcTrack.kt                 行级 LRC 解析 + 翻译轨按时间贴合到主轨
│  ├─ LyricMatch.kt               候选匹配（标题归一化 + 版本标记扣分 + 时长核对）
│  ├─ LyricHttp.kt                HttpURLConnection 封装（不引第三方网络库）
│  ├─ KaraokeText.kt              QRC / YRC 的共同正文语法 → TimedLyrics
│  ├─ TimedLyric.kt               逐字歌词数据模型
│  └─ LrcParser.kt                本地 LRC 兜底
├─ flyme/FlymeStatusBarLyric.kt   载波通道：Ticker 注入宿主媒体通知 / 清除
└─ util/Reflect.kt                反射小工具
```

**职责边界（改代码前先看这里）**

| 组件 | 只负责 | 不负责 |
|------|--------|--------|
| `BackgroundLyrics` | 轮询节拍、读宿主进度 / MediaItem、把三方串起来、**所有**状态栏推送 | 不判断"该不该推"（问闸门）、不碰反射细节（问驱动） |
| `LyricGate` | 前奏期抑制 / 首句探测 / 重播重武装的**决策** | 不发通知、不打日志、不知道引擎和宿主的存在 |
| `LyricsEngineDriver` | `drive(ptr, queryPos) → 下一事件位置` / `peek(ptr, atPos) → 文本` | 不做调度、不管前奏抑制 |
| `LyricsInjector` | 判定"该不该补"、取第三方词、把结果落进两条通路 | 不做状态栏调度、不碰播放进度、不改原生歌词 |

`BackgroundLyrics.onTick()` 的顺序是**约定**：读位置 → 处理 MediaItem（切歌/重播/取句柄）→ 应用闸门决策 → 驱动引擎。
"多推一次 / 少推一次"的 bug 基本都出在打乱这个顺序上。

> `SettingsInjector.kt` 很大不是因为它干了多少事，而是因为宿主里**没有可依赖的名字**：
> 类名被 R8 改成 `androidx.preference.b`、方法名压成单字母（`addPreference` → `P`、`findPreference` → `t0`）、
> setter 被内联掉。它只能按**签名**找，再在运行时**现场自证**每一步
> （例如用一条一次性 `PreferenceCategory` 当真值试验台，试出哪个同参方法是 `addPreference` ——
> 宿主里同参的有三条，按返回类型猜会拿到 `removePreference`，表现为"注入成功"其实在删）。
> `LyricsInjector.kt` 同理，它的每一个常量都是真机反解出来的。

---

## 五、宿主版本更新后如何重新定位

**务必从设备上 `adb pull` 真机 APK 再反解**，别用本地留存的旧安装包。本项目踩过：
本地 `Apple Music_5.2.0.apks` 是 versionCode 1541，真机跑的是 6.5.2 / 1586，**两版混淆名完全不同**（`M2` vs `I2`）。

按以下特征用 jadx-gui 搜索：

| 目标 | 特征 |
|---|---|
| 歌词 VM | 方法名字符串 `buildTimeRangeToLyricsMap` / `loadLyrics` |
| 官方歌词引擎 | `SongInfoTimeProcessor` / `processEvents`（方法名保留） |
| 播放控制器 | `LocalMediaPlayerController`（类名一般不被混淆） |
| 歌词安装点 | 在 `PlayerLyricsViewFragment` 里找**实例方法 + 返回 void + 单参 `SongInfo$SongInfoPtr`** |
| TTML / native 绑定 | `.ttml.javanative.*` 整族都不混淆，直接 `loadClass` |

搜索到新符号后只需改对应常量，其余逻辑不变。
（更稳妥的进阶方案：接入 [DexKit](https://github.com/LuckyPray/DexKit) 做特征匹配自动定位。）

**在线注入失败时是"软降级"**：`I2` 找不到就只打一条 `lyrics injector disabled`，
状态栏歌词主链路完全不受影响。

## 六、稳定性设计

- 每个 Hook 独立安装、独立 try/catch —— 单个 Hook 失败只损失对应功能；
- 所有原生对象反射调用都走 `runCatching`，任何一步异常安全返回 null，异常绝不外溢到宿主进程；
- 轮询跑在**宿主主线程**（与播放界面 Fragment 的 Handler 同线程；`processEvents` 内部调 native，需同线程防并发），
  `MAX_DELAY_MS` 心跳兼顾跟手感与后台存活；
- **在线取词跑在独立 worker 线程**（单线程池，队列 2），结果回主线程落地；队列满直接丢弃本次请求，不阻塞播放；
- Hook 回调里只做轻量状态记录，通知构建全部延迟到轮询线程；
- 播放器实例、歌词页 Fragment 都用 `WeakReference` 持有，不阻碍宿主 GC；
- 补全结果最多缓存 6 首，避免长期占用内存；
- 高频判定入口（`I2`、每 tick）全部按**句柄身份 / 歌曲 key** 去重，日志同样按 `adamId + 理由` 去重，避免刷屏掩盖有用信息。

## 七、已知限制

- 适配 **Apple Music 6.5.2 / versionCode 1586**。大版本更新后需按 §5 重新定位符号；
- **原生歌词一律不处理**，只清洗第三方补来的歌词。清洗是**启发式**的，偶有漏网：
  - QQ 的 QRC 会把多个职称叠成一行（`后期母带处理制作人MASTERING PRODUCER：…`），已用"宽窗口 + 职衔词表"覆盖，但词表补不完；
  - `歌名 - 歌手` 尾注行的识别依赖**宿主元数据与歌词站一致**。实测当歌手名带别名括注时
    （Apple 给 `蔡恩雨 (Priscilla Abby)`、歌词站写 `蔡恩雨`）匹配会失败，尾注行可能残留一行；
- 伴奏 / 纯音乐轨按**标题关键词**识别（宿主不暴露"这是伴奏轨"的字段），标题里没写「伴奏 / instrumental」
  之类的纯音乐曲目仍会被尝试补全；
- 来源标注依赖宿主的 `<songwriters>` 渲染，需滚动到歌词列表末尾才可见。

## 致谢

- Hook 点验证参考了开源项目 LyricProvider（Apache-2.0）对 Apple Music 的适配；
- 播放页歌词注入的手法参考了 [AM-plus-plus](https://github.com/Zennmn/AM-plus-plus)；
- 在线歌词源接口与 QRC 解密参考了 [getl](https://github.com/ChouChiu/getl)；
- Flyme 状态栏歌词实现遵循魅族开放平台官方文档。

## License

[MIT License](LICENSE)
