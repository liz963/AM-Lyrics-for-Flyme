<div align="center">

# AM 歌词 · Flyme

**把第三方的逐字歌词补进 Apple Music，并送上 Flyme 状态栏。**

[![Release](https://img.shields.io/github/v/release/liz963/AM-Lyrics-for-Flyme)](https://github.com/liz963/AM-Lyrics-for-Flyme/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)]()
[![libxposed](https://img.shields.io/badge/libxposed%20API-102-orange)]()
[![Apple Music](https://img.shields.io/badge/Apple%20Music-6.5.2-black)]()

</div>

LSPosed 模块，运行在 **Apple Music 进程内**，做三件事：

1. 把正在唱的那句词送进 **Flyme 状态栏歌词**；
2. 原生歌词**滚不起来**时，从 QQ 音乐 / 网易云音乐补一份逐字歌词，注入**播放页**；
3. 歌词简繁转换。

模块**没有桌面图标**，开关嵌在 `Apple Music → 设置 → 歌词补全`；LSPosed 作用域固定为 Apple Music，不用手动勾。

## 功能

| 功能 | 说明 | 默认 |
| --- | --- | --- |
| 状态栏歌词 | 把歌词 Ticker 注入**宿主自己的媒体通知**（载波模式），模块零新增通知 | 开 |
| 歌词补全 | 原生歌词不能滚动时补一份逐字歌词并注入播放页 | 关 |
| 来源标注 | 补来的歌词在歌词页末尾标一行「创作者： 第三方歌词」 | 随补全 |
| 简繁转换 | 系统 ICU，只作用于状态栏文本 | 关 |
| 设置页注入 | 开关嵌进宿主设置页，改完立即生效 | — |

## 兼容性

| 项 | 要求 |
| --- | --- |
| Android | 8.0+（`minSdk 26`，实测 Android 16 / Flyme） |
| 框架 | LSPosed，libxposed API 102 |
| Apple Music | 6.5.2（vc1586），单进程 |
| 系统 | 状态栏歌词需 Flyme；非 Flyme 静默降级，播放页功能照常 |

**已知限制**：只接 QQ / 网易云两家（逐字覆盖最全）；简繁转换按字映射，一简对多繁不判上下文（`和面` → 不会变 `和麵`）；宿主换版本后混淆名会变，需要按签名重新定位（见文末）。

## 安装与使用

**前置**：LSPosed，宿主 **Apple Music 6.5.2**，Flyme 系统。

1. 装 APK，在 LSPosed 里启用「AM 歌词 · Flyme」（作用域已固定，不用勾）；
2. **重启 Apple Music**（模块只在进程启动时装载）；
3. 进 `Apple Music → 设置 → 歌词补全` 打开需要的开关。

| 想做的事 | 怎么做 |
| --- | --- |
| 状态栏歌词 | 打开「状态栏歌词」；播放页/锁屏/状态栏都会出现歌词，模块本身不发通知 |
| 补全歌词 | 打开「自动实时补全」并选源；**只有原生歌词滚不起来时才补**，能滚的一律不动 |
| 简繁转换 | 同一页选「简转繁」/「繁转简」；切换后当前这句立刻重刷 |
| 看日志 | `adb logcat -s AMFlymeLyric` |

## 技术方案

### 一、怎么 Hook 到歌词信息

**能 Hook 的就 Hook，Hook 不到就反射直调官方能力。**

| Hook 点 | 用途 |
| --- | --- |
| `PlayerLyricsViewModel.loadLyrics` | 宿主发起取词（歌曲同步 / 去重，不改显示） |
| `PlayerLyricsViewModel.buildTimeRangeToLyricsMap` | 歌词构建完成，捕获原生句柄 `SongInfoPtr` |
| `LocalMediaPlayerController.onPlaybackStateChanged` | 播放状态 `0停/1播/2暂停`，顺带抓控制器实例读进度与当前曲目 |
| `PlayerLyricsViewFragment.I2(SongInfoPtr)` | **在线歌词注入的安装点** |
| `NotificationManager.notify / cancel` | 载波模式：把 Ticker 注入宿主媒体通知 |
| `SongInfoTimeProcessor$…$lineEventCallback$1.call` | 引擎逐行回调（播放页打开时宿主自己驱动的那条路） |
| `SettingsFragment.onViewCreated / onDestroyView` | 设置页注入挂点 |

反射直调的官方能力：

| 符号 | 用途 |
| --- | --- |
| `TTMLParser$TTMLParserNative.songInfoFromTTML(String) -> SongInfoPtr` | 把外部 TTML 交给宿主自己的解析器 —— **「补一份歌词」的唯一入口** |
| `SongInfo$SongInfoPtr.get()` / `setAdamId(long)` / `getSections()` | ptr → 原生对象；写歌曲标识；判断有没有内容 |
| `SongInfo$SongInfoNative.getAvailableTiming()` | 时间轴类型：`None` / `Line` / `Word` |
| `SongInfoTimeProcessor.processEvents(ptr, pos, 5×callback)` | 驱动官方引擎求值 |
| `org.bytedeco.javacpp.Pointer.address` | JavaCPP 包装的**存活判据**（地址非 0）；拿死指针调 native 就是 use-after-free |

> 只有 `.ttml.javanative.*` 与 `org.bytedeco.javacpp.*` 这两族 JNI 绑定名**不被 R8 混淆**，可以写死。其余符号一律按**签名形状**定位、名字只用来钉最后一步 —— 同一个安装点在 5.2.0 叫 `M2`，6.5.2 变成 `I2`。

### 二、状态栏歌词：不自研引擎，用宿主那套

模块驱动 Apple Music 播放页同款的**官方歌词引擎**，所以节奏与官方完全一致：

```
loadLyrics / buildTimeRangeToLyricsMap   捕获 SongInfoPtr
        ↓
LyricsEngineDriver    反射驱动 processEvents → 逐行回调
        ↓
NativeLyricsParser    原生歌词对象 → 纯文本
        ↓
BackgroundLyrics      编排：读进度 / 切歌 / 前奏闸门 / 算下一跳
        ↓
LyricController       上屏出口（空行忽略 + 非歌词行过滤）
        ↓
FlymeStatusBarLyric   载波：Ticker 注入宿主媒体通知
```

几条改前必读的引擎语义：

- `processEvents(...)` 返回的是「下一歌词**事件**位置」（含字级事件），**不是下一行时间**，只能当唤醒节奏的锚点；
- 喂进去的位置 = 实际位置 + `1000ms`，让歌词比人声早约 1 秒出现，抵消渲染延迟；
- 下一跳间隔夹在 `[100, 5000]ms`，上限 5000 是**后台保活心跳**；
- 行文本只取 `LyricsLineVector` 的**索引 0**，刻意不拼翻译行；
- **`drive` 与 `peek` 必须用两个引擎实例** —— `processEvents` 带内部游标，两个节奏交叉驱动同一实例会让后台歌词不更新。

**两条上屏通路**（前台回调 + 后台自驱）必须**共用同一道闸门**，只堵一条必漏。

**载波模式**：Hook `notify` 识别宿主的 MediaStyle 通知，提交前把 Ticker 注入 —— 通知本来就在，挂上歌词后**状态栏零新增**；只改 `tickerText` 重发，Flyme 走「只更新歌词」动画。载波拿不到就什么都不做。用到的 Flyme 私有 flag：`FLAG_ALWAYS_SHOW_TICKER 0x1000000`、`FLAG_ONLY_UPDATE_TICKER 0x2000000`、`extras: ticker_icon_switch = false`；**读不到（非 Flyme）静默禁用**。

### 三、怎么注入播放界面

**安装点**：`PlayerLyricsViewFragment.I2(SongInfo$SongInfoPtr) -> void` —— 6.5.2 里唯一同时满足「实例方法 + 返回 void + 单参 `SongInfoPtr`」的方法，靠名字钉最后一步。

```
BackgroundLyrics（每 tick）              I2(null / SongInfoPtr) 被调用
      │ 判据：原生歌词能滚吗？                  │ null 那帧只记账
      ▼                                       ▼
LyricsInjector.eligible()                LyricsInjector.decide()
      │ 伴奏轨 → 跳过                          │
      │ Word / Line → 不补                     │
      ▼                                       │
取词 worker（QQ / 网易云 → TimedLyrics → TTML）
      │                                       │
      ▼                                       ▼
TtmlBridge.parse(ttml) → SongInfoPtr   主线程重新进入 I2(我们的 ptr)
      ├──► ① BackgroundLyrics.onSongInfo(ptr)：驱动状态栏
      └──► ② 反射调 I2(fragment, ptr)：播放页变成逐字
```

**判定口径（唯一判据是时间轴类型）**

| 宿主状态 | 处理 |
| --- | --- |
| 没交句柄（`args[0] == null`） | **补** |
| `timing = Word`（逐字） | **不补** |
| `timing = Line`（逐行） | **不补** |
| `timing = None`（有数据但不滚） | **补** |
| `timing` 读不到 | **补** |
| 伴奏 / 纯音乐轨（标题关键词） | 跳过，不请求也不注入 |

`Word` / `Line` 都算「能滚」：官方引擎给了字级或行级时间轴，播放页与状态栏都会动。`None` 是真机实测「有歌词但不滚动」的那种。读不到时间轴时按**保守**处理 —— 证明不了能滚就当不能滚。

> 试过用「原生歌词带创作者名单（`<songwriters>`）」当判据，**已被真机数据证伪**：逐行的 `Line` 原生歌词全都带名单，而名单只能证明「宿主有歌词数据」，证明不了「这份歌词能滚」。相关读取代码保留，仅作日志排查。

**三个硬约束**

1. **`I2` 是同步方法，而取词要联网** → 首帧只能放行，取完回主线程**重新进入 `I2`**；重入前复核 fragment 还在、当前曲目还是这首歌。
2. **`XposedInterface.Chain` 没有可写的 `args`** → 换参数只能 `chain.proceed(arrayOf(新 ptr))`。
3. **认 Fragment 上的曲目字段必须用「当前播放的歌曲 ID」** —— 继承链上有两个同类型字段，其中一个指向同专辑的另一个条目，选错整条注入静默失效。匹配不上**就不动**。

另外两处时序：`args[0] == null` **不等于这首歌没歌词**（宿主取词是异步的，200ms 后可能就有了），所以那一帧不请求，交给宽限期定性；取词完成落地前还有**最后一道复核**，期间宿主只要交出了能滚的歌词就整份丢弃。

**写入宿主的 TTML** —— 外部歌词要变成官方认的 `SongInfoPtr`，格式是真机试出来的：

| 写法 | 原因 |
| --- | --- |
| 根节点 `xml:lang="ko"` | Android 版**只在歌词是韩语时**才渲染翻译轨 |
| 翻译放 `<head><iTunesMetadata><text for="Ln">` | 正文只放歌词，翻译在头部声明一次 |
| **每行都要有 `<text for="Ln">` 且顺序一致**，无翻译写空格占位 | Android 版按顺序走条目，少一条后面全错位 |
| `<div>` / `<metadata>` 上不能有 `xmlns=""` | 会把子树踢出命名空间，一行都读不到 |
| 逐字用 `<span begin end>` + `itunes:timing="Word"`；纯行级写 `Line` | 谎报 `Word` 会让官方按逐字解析出空行 |
| 时间 `00:00:01.000`、从 0 单调递增、每行 `end > begin` | 解析器对空区间 / 逆序 / 叠加区间都不容错 |

逐字区间还要**按比例缩放**：源站偶尔给出溢出行区间的逐字时间，直接夹紧会变成一串 1ms 的 span 堆在行尾（高亮卡住）。

**来源标注**写在 TTML 里唯一承载非歌词文本的元素上，宿主渲染成歌词页末尾一行「创作者： 第三方歌词」：

```xml
<head><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
  <songwriters><songwriter>第三方歌词</songwriter></songwriters>
</iTunesMetadata></head>
```

**非歌词行过滤只作用于第三方歌词**（原生歌词一个字符不动）。源站会把制作名单当**正文行**返回且带真实时间戳，不清就会在前奏期顶在状态栏上。规则见 [`CreditLine.kt`](app/src/main/java/com/amlyric/flyme/lyric/CreditLine.kt)：长词子串匹配（`作词` / `Producer`），单字与极短词只做**整段相等**（`曲` / `词` / `OP` / `SP`，否则「我想唱：…」会被误杀），复合标签先拆再判（`OP/SP`），兜底认「标签：内容」的形状（唯一例外：标签段以说话类动词结尾，如「我说：你好」）。

### 四、怎么从第三方源取词

只接 **QQ 音乐**与**网易云音乐** —— 这俩的逐字时间轴（QRC / YRC）最完整且带翻译轨。

```
LyricFetcher.fetch() → QQMusicClient / NeteaseClient → TimedLyrics（逐字 + 翻译）
LyricFetcher.ttml()  → TtmlWriter.write() → Apple TTML → TtmlBridge.parse() → SongInfoPtr
```

- **QQ**：`搜索 → GetSession → musicu.fcg GetPlayLyricInfo → QRC 解密 → 解析`。用客户端自己的正式 RPC（`u.y.qq.com/cgi-bin/musicu.fcg`），会话拿一次复用；`lyric` 是 QRC XML 外壳，`trans` 是纯 LRC。
- **QRC 解密**走的是一份**被改过的 DES**（S 盒与 P 置换和标准实现不同），用 `DESede` 解出来是随机字节。按公开 Rust 实现逐位移植：

  `去空白 → hex 解码 → 三轮 DES（D(K3) → E(K2) → D(K1)）→ zlib → UTF-8`

- **网易云**：`搜索 → /api/song/lyric/v1`。**逐字（YRC）只有 v1 的 `yv=1` 才给**（`lv=1` 行级兜底、`tv=1`/`ytv=1` 翻译）；必须带 `Referer` 与 `pc` Cookie。不是所有歌都有逐字 —— 取 `yrc` 要用 `optJSONObject`，没有就退回行级。
- **候选匹配**（两家共用）：宿主给的歌名和曲库经常对不上（`残酷な天使のテーゼ` 可能带中文别名），搜索后必须自己挑 —— 标题归一化比对，并给「查询里没有、候选里却有」的**版本标记**扣分（Live / Cover / Remix / 伴奏 / DJ 版）。配错版本 = 永远慢半拍。
- 网络层用 `HttpURLConnection` 封装，**不引第三方网络库**。

### 五、简繁转换

用 **Android 系统自带的 ICU**（`android.icu.text.Transliterator`，API 29+），转换器 ID 是 CLDR 官方名 `Simplified-Traditional` / `Traditional-Simplified` —— **零体积**，不需要 OpenCC 词表。

- **落点只有一个**：`LyricController.onLyricLine`。两条上屏通路都汇到这里，不会有漏网之鱼；**只转状态栏文本，播放页不转**。
- 方向切换后当前这句**原地重刷**一次，不用等下一句。
- 已知局限：简→繁按字映射，一简对多繁判不了上下文；繁→简是多对一，几乎不会错。
- `Transliterator` **非线程安全**，两个方向都加锁串行；API < 29 没有这个类 → **原样返回，不转换也不崩**。
- 两个方向**互斥**，由 `Settings` 的 setter 强制维护。

## 从源码构建

```bash
# 环境：JDK 17+（建议 21）、Android SDK compileSdk 36 / buildTools 35.0.1
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release-unsigned.apk`。

> **Windows 下路径含空格时必须用 `./gradlew`，别用 `gradlew.bat`** —— 空格会把批处理参数切碎。

签名（密钥不入库）：

```bash
apksigner sign --ks <keystore> --ks-key-alias <别名> \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

重签后记得删掉旧的 `app-release-signed.apk.idsig`。日志：`adb logcat -s AMFlymeLyric`。

## 项目结构

```text
app/src/main/java/com/amlyric/flyme/
├─ HookEntry.kt / XLog.kt / Settings.kt     入口、日志、开关
├─ hook/
│  ├─ AppleMusicHooks.kt      全部 Hook 安装点
│  ├─ SettingsInjector.kt     往宿主设置页注入开关（反射 + 运行时自证）
│  ├─ LyricsInjector.kt       在线歌词注入：判定 → 取词 → 重入安装点
│  ├─ TtmlBridge.kt           外部 TTML → 官方 SongInfoPtr（含存活判据）
│  └─ NativeLyricsParser.kt   原生歌词对象 → 纯文本
├─ core/
│  ├─ BackgroundLyrics.kt     轮询编排层：唯一调度者、唯一改状态栏的人
│  ├─ LyricsLoader.kt         取词：Hook 捕获 + 主动触发 + 重试
│  ├─ LyricGate.kt            前奏期闸门（纯状态机，只出决策）
│  ├─ LyricsEngineDriver.kt   官方引擎反射驱动（唯一持混淆名/SAM/Proxy 处）
│  ├─ LyricController.kt      上屏出口（状态机 + 非歌词行过滤）
│  └─ HantConverter.kt        简繁转换（系统 ICU）
├─ lyric/                     在线歌词源
│  ├─ LyricFetcher.kt         统一入口：选源取词 → 清洗 → 转 TTML
│  ├─ QQMusicClient.kt / QrcCrypto.kt / NeteaseClient.kt
│  ├─ CreditLine.kt           版权 / 制作行过滤
│  ├─ TtmlWriter.kt / KaraokeText.kt / LrcTrack.kt / LrcParser.kt
│  └─ LyricMatch.kt / LyricHttp.kt / TimedLyric.kt / LyricLine.kt
├─ flyme/FlymeStatusBarLyric.kt   载波通道：Ticker 注入宿主媒体通知
└─ util/Reflect.kt                反射小工具
```

**职责边界（改代码前先看）**

| 组件 | 只负责 | 不负责 |
| --- | --- | --- |
| `BackgroundLyrics` | 轮询节拍、读宿主进度与当前曲目、串起三方、**所有**状态栏推送 | 不判「该不该推」（问闸门）、不碰反射细节（问驱动） |
| `LyricGate` | 前奏期抑制 / 首句探测 / 重播重武装的**决策** | 不发通知、不打日志、不知道引擎与宿主 |
| `LyricsEngineDriver` | `drive()` → 下一事件位置 / `peek()` → 文本 | 不做调度、不管前奏抑制 |
| `LyricsInjector` | 判「该不该补」、取第三方词、把结果落进两条通路 | 不做状态栏调度、不碰播放进度、不改原生歌词 |

`BackgroundLyrics.onTick()` 的顺序是**约定**：读位置 → 处理曲目变更 → 应用闸门决策 → 驱动引擎。「多推一次 / 少推一次」基本都出在打乱这个顺序上。

`SettingsInjector.kt` 大不是因为干得多，而是宿主里**没有可依赖的名字**（类名改成 `androidx.preference.b`、方法名压成单字母、setter 被内联），只能按**签名**找，再在运行时**现场自证**每一步。

### 换了 Apple Music 版本怎么重新定位符号

**务必 `adb pull` 真机 APK 再反解**，别用本地留存的旧包 —— 本项目踩过：本地是 5.2.0、真机是 6.5.2，**混淆名完全不同**。

| 目标 | 搜索特征 |
| --- | --- |
| 歌词 VM | 方法名字符串 `buildTimeRangeToLyricsMap` / `loadLyrics` |
| 官方引擎 | `SongInfoTimeProcessor` / `processEvents`（方法名保留） |
| 播放控制器 | `LocalMediaPlayerController`（类名一般不动） |
| 歌词安装点 | `PlayerLyricsViewFragment` 里找**实例方法 + void + 单参 `SongInfo$SongInfoPtr`** |
| TTML / native 绑定 | `.ttml.javanative.*` 整族不混淆，直接 `loadClass` |

找到新符号只需改对应常量，逻辑不变。

## 路线图

- [x] 状态栏歌词（载波注入，模块零通知）
- [x] 设置页注入，改完立即生效
- [x] 在线歌词补全（QQ / 网易云）
- [x] 播放页歌词注入（逐字 + 翻译轨）
- [x] 第三方来源标注
- [x] 简繁转换（系统 ICU，零体积）
- [x] 判定口径收敛为「时间轴类型」单一判据
- [ ] 接入 DexKit 之类的特征匹配框架，降低换版本重新定位符号的成本
- [ ] 适配后续 Apple Music 版本

## 参考项目

- [AM-plus-plus](https://github.com/Zennmn/AM-plus-plus)（GPL-3.0）—— 播放页歌词注入的手法，以及本 README 的版式；
- [LyricProvider](https://github.com/tomakino/LyricProvider)（Apache-2.0）—— Apple Music Hook 点的验证；
- [getl](https://github.com/ChouChiu/getl) —— QRC 解密算法与在线歌词源接口的参考实现；
- [Flyme 状态栏歌词适配](https://open.flyme.cn/docs?id=239)（魅族开放平台）—— 状态栏歌词的私有 flag 与 extras。

## 开源许可

[MIT License](LICENSE) © 2026 liz963

上述参考项目版权归各自作者所有，本项目仅参考其实现思路与接口约定。
