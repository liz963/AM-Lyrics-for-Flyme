# AMFlymeLyric 模块总结

> LSPosed 模块 · 让 Apple Music（Android 5.2.0）在魅族 Flyme 状态栏显示逐行滚动歌词
> 当前版本：**v1.2.1**（versionCode 4）· 签名：CN=AMFlymeLyric

---

## 一、这个模块做什么

在 Flyme 系统的状态栏顶部，以魅族官方「状态栏歌词」机制实时显示 Apple Music 正在唱的歌词：

- **逐行滚动**：唱到哪句显示哪句，与播放进度精确同步（TTML 时间轴驱动，非 LRC 整段解析）；
- **前台后台都工作**：退到后台、锁屏，歌词照样滚动；
- **无需打开播放界面**：直接在歌单里点歌播放，歌词自动加载上屏；
- **不产生任何多余通知**：歌词借用 Apple Music 自己的媒体通知承载；
- **智能留白**：开头伴奏不显示；中间间奏保持上一句，直到下一句出现；
- **可开关**：在 Apple Music 设置页顶部注入了「状态栏歌词」开关，无需去 LSPosed 关模块。

## 二、核心技术方案

### 1. Flyme 状态栏歌词官方机制（载波模式）

Flyme 的状态栏歌词本质是**带 Ticker 的常驻通知**，需要三个扩展 flag（反射 framework `Notification` 静态常量探测）：

| Flag | 值 | 作用 |
|---|---|---|
| `FLAG_ALWAYS_SHOW_TICKER` | 0x1000000 | Ticker 常驻显示 |
| `FLAG_ONLY_UPDATE_TICKER` | 0x2000000 | 只更新 Ticker 不弹横幅 |
| `FLAG_NO_CLEAR` | — | 不可滑动清除 |

v1.2.1 起**不再自建通知**，而是 Hook `NotificationManager.notify(String, int, Notification)`：识别宿主的 MediaStyle 媒体通知（extras 含 `android.mediaSession`），在提交前把歌词 Ticker 注入进去——歌词 `tickerText` + `ticker_icon_switch`/`ticker_icon` extras + 三个 flag。更新时**只改 tickerText 重发，其余字段全部不动**，因此 Flyme 只滚动歌词文字、图标静止。宿主取消通知（停止播放）时由 cancel/cancelAll Hook 同步标记载波失效，防止"复活"已取消的通知。

### 2. 歌词数据链路（全部来自 Apple Music 官方引擎）

```
播放界面路径：
  PlayerLyricsViewModel.loadLyrics(PlaybackItem)
    → C9/e 网络层取 TTML（CookieStorage 鉴权）
    → TTMLParser 解析 → SongInfoPtr
    → SongInfoTimeProcessor.processEvents 的 lineEventCallback  ← Hook：前台逐行推送

无播放界面路径（v1.2.1 新增）：
  轮询 LocalMediaPlayerController.getCurrentItem() 感知当前歌曲
    → LyricsLoader 反射自建 PlayerLyricsViewModel(Application)
    → 动态代理伪造 PlaybackItem → 触发同一条官方取词流水线

后台兜底路径：
  LyricsControllerNative.instance().get()   ← 注意：instance() 返回 Ptr 包装，须再 .get()
    → getLinesAtPosition(SongInfoPtr, posMs) 按播放位置直查当前行
```

### 3. 关键修复记录（踩坑史）

| 版本 | 问题 | 根因与修复 |
|---|---|---|
| v1.0 | Ticker 完全不显示 | `Application.attach` 阶段 `applicationContext` 为 null，appContext 从未赋值 → 加兜底 |
| v1.1 | 图标过大 | 换宿主自带小尺寸矢量图标 |
| v1.2.0 | 后台歌词停更 | 误用 `LyricsControllerPtr` 包装对象找方法 → NoSuchMethod → 兜底永久禁用 |
| v1.2.0 | 图标每句滚动一次 | 每次更新改了 `when`/`contentText` 触发整条通知刷新动画 |
| v1.2.1 | 全部上述问题 | 载波模式 + instance().get() 两步取 Native 实例 + 无 UI 加载器 |

## 三、模块结构

```
app/src/main/java/com/amlyric/flyme/
├── HookEntry.kt              LSPosed 入口（libxposed API 102）
├── Settings.kt               开关持久化（SharedPreferences，注入宿主设置页）
├── XLog.kt                   日志（logcat tag: AMFlymeLyric）
├── hook/
│   ├── AppleMusicHooks.kt    全部 Hook 装载（7 个拦截点）
│   └── NativeLyricsParser.kt  TTML 原生结构抽取（adamId / 行文本 / 有无歌词）
├── core/
│   ├── LyricController.kt    歌词状态机（切歌/间奏保持/暂停冻结/停止清除）
│   ├── BackgroundLyrics.kt   后台兜底轮询（主线程 500ms，前台回调 1.5s 内让位）
│   └── LyricsLoader.kt       无 UI 歌词加载（Proxy PlaybackItem → 官方流水线）
└── flyme/
    └── FlymeStatusBarLyric.kt 载波模式：注入/更新/清除宿主媒体通知的 Ticker
```

**Hook 点清单（Apple Music 5.2.0）：**

1. `Application.attach` — 初始化入口
2. `PlayerLyricsViewModel.loadLyrics` — UI 路径歌曲同步
3. `PlayerLyricsViewModel.buildTimeRangeToLyricsMap` — 歌词句柄捕获
4. `SongInfoTimeProcessor$processEvents$lineEventCallback$1.call` — 前台逐行推送
5. `LocalMediaPlayerController.onPlaybackStateChanged` — 播放状态 + 控制器捕获
6. `NotificationManager.notify / cancel / cancelAll` — 载波注入与生命周期（v1.2.1）
7. `PreferenceFragmentCompat.setPreferenceScreen` — 设置页开关注入

## 四、兼容性与限制

- **仅适用**：Apple Music Android **5.2.0**（所有 Hook 点均经 dexdump 拆包逐一验证）+ Flyme 状态栏歌词（需要系统支持 `FLAG_ALWAYS_SHOW_TICKER` 扩展）
- **仅作用域**：`com.apple.android.music`（module.prop 已锁定 staticScope，不能勾选其它应用）
- **依赖**：LSPosed 2.x（libxposed API 102+），minSdk 26
- Apple Music 升级版本后混淆名可能变化，需重新拆包对照
- 需要歌曲本身有 time-synced（逐行）歌词；纯 LRC 整段歌词的歌曲不滚动

## 五、构建与签名

```bash
# Gradle 8.13（缓存直连）+ JDK 21（.build-env/jdk21）
export JAVA_HOME="$PWD/.build-env/jdk21"
/c/Users/Administrator/.gradle/wrapper/dists/gradle-8.13-bin/<hash>/gradle-8.13/bin/gradle :app:assembleRelease --no-daemon

# apksigner（Git Bash 需 export MSYS_NO_PATHCONV=1）
java -jar E:/am/android-sdk/build-tools/35.0.1/lib/apksigner.jar sign \
  --ks am-release.keystore --ks-key-alias amlyric \
  --ks-pass pass:android --key-pass pass:android \
  --out app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

调试日志：`adb logcat -s AMFlymeLyric`

## 六、版本历史

| 版本 | 日期 | 要点 |
|---|---|---|
| 1.0.0 | — | 首版：渲染通道打通、逐行抽取成功 |
| 1.1.0 | — | appContext 修复、设置页开关注入、图标缩小 |
| 1.2.0 | 2026-08-23 | 后台兜底轮询、图标 36px、去提示、日志降级 |
| **1.2.1** | 2026-08-23 | 载波模式（零新增通知 + 图标静止）、instance().get() 后台停更根因修复、无 UI 歌词加载、去占位（间奏保持上一句）、图标等高 |
