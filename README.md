# AMFlymeLyric — Apple Music 状态栏歌词（魅族 Flyme）

一个 LSPosed 模块：Hook Apple Music（`com.apple.android.music`），
实时提取当前播放歌曲的逐行歌词，并通过 **Flyme 官方状态栏歌词接口**
显示在魅族手机的状态栏上。

> 基于 **libxposed 现代 API（API 102，`io.github.libxposed:api`）** 开发，
> 不兼容仅支持 legacy XposedBridge API（`de.robv.android.xposed`）的旧框架。
> 需要 LSPosed 等支持现代 API（≥ API 100）的框架。

```
Apple Music 进程
 ┌────────────────────────────────────────────────────────────┐
 │  MediaMetadataCompat.fromMediaMetadata ──► 切歌事件        │
 │  PlayerLyricsViewModel                  ──► 歌词构建事件   │
 │    .buildTimeRangeToLyricsMap(Optional<SongNative>)        │
 │  LocalMediaPlayerController              ──► 播放状态事件  │
 │    .onPlaybackStateChanged(?, ?, int)                      │
 │  ExoMediaPlayer.getCurrentPosition()     ──► 播放进度(ms)  │
 │        │                                                   │
 │        ▼                                                   │
 │  NativeLyricsParser   原生 Song → sections → lines 反射解析 │
 │        ▼                                                   │
 │  LyricController      状态机：切歌/加载/暂停/停止/无歌词    │
 │        ▼                                                   │
 │  PlaybackScheduler    后台线程 300ms 轮询进度，二分定位当前行│
 │        ▼                                                   │
 │  FlymeStatusBarLyric  FLAG_ALWAYS_SHOW_TICKER 通知上屏      │
 └────────────────────────────────────────────────────────────┘
```

## 一、Hook 点（逆向定位依据）

以下 Hook 点经 jadx 逆向 Apple Music 并与社区项目 LyricProvider 的适配
实现交叉验证，适用于 Apple Music 4.x（2025 前后的版本）：

| # | Hook 目标 | 用途 |
|---|-----------|------|
| 1 | `android.app.Application.attach` | 获取宿主 Context / ClassLoader |
| 2 | `android.support.v4.media.MediaMetadataCompat` 的静态转换方法（参数 `android.media.MediaMetadata`） | 切歌：mediaId / 标题 / 歌手 / 时长 |
| 3 | `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel#buildTimeRangeToLyricsMap` | 歌词构建完成，参数 `Optional.get()` 后为 MediaCore 原生 `Song` 对象 |
| 4 | `com.apple.android.music.playback.controller.LocalMediaPlayerController#onPlaybackStateChanged`（3 参，末参 int） | 播放状态：`0=停止 1=播放 2=暂停` |
| 5 | `com.apple.android.music.playback.player.ExoMediaPlayer`（构造器 + `getCurrentPosition` + `seekToPosition`） | 播放进度毫秒值 / seek 重同步 |

**歌词数据提取链**（`NativeLyricsParser.kt`，全部走反射、逐步判空）：

```
SongNative.getAdamId()                → 歌曲标识（对应 MediaSession mediaId）
SongNative.getSections()              → 原生 vector<Section>
  Section.getLines()                  → 原生 vector<Line>
    Line.getBegin() / getEnd()        → 行起止时间（毫秒，Int）
    Line.getHtmlLineText()            → 歌词文本（含 HTML，需清洗）
    Line.getHtmlTranslationLineText() → 翻译文本
```

原生容器的统一特征：`size(): Long`、`get(i)` 返回智能指针包装、再 `.get()`
拿到原生对象。

### 版本更新后如何重新定位（jadx 指南）

Apple Music 更新后若类名/方法名变化，用 jadx-gui 打开新 APK，按以下特征搜索：

- `buildTimeRangeToLyricsMap` —— 搜方法名字符串（歌词 VM 构建时间轴→歌词映射）；
- `LocalMediaPlayerController` —— 类名一般不被混淆（activity/播放控制层）；
- `ExoMediaPlayer` —— 播放器封装类，搜 `seekToPosition`；
- `MediaMetadataCompat` 来自内置 support-v4，不受混淆影响。

搜索到新类名后只需改 `AppleMusicHooks.kt` 顶部的字符串常量，其余逻辑不变。
（更稳妥的进阶方案：接入 [DexKit](https://github.com/LuckyPray/DexKit) 做特征匹配自动定位。）

## 二、Flyme 状态栏歌词对接（官方方案）

依据魅族开放平台《Flyme 状态栏歌词适配》文档（open.flyme.cn/docs?id=239）：

- 本质是**带 Ticker 的常驻通知**，需要同时添加两个 Flyme 扩展 flag：
  - `Notification.FLAG_ALWAYS_SHOW_TICKER`（0x1000000）：Ticker 一直显示；
  - `Notification.FLAG_ONLY_UPDATE_TICKER`（0x2000000）：只更新歌词，不刷新封面等；
- 外加 `Notification.FLAG_NO_CLEAR` 保证常驻；
- `extras` 中放 `ticker_icon`（左侧小图标 resId）与 `ticker_icon_switch=false`；
- 每次更新对**同一个通知 ID** 调用 `notify()`；清除时 `cancel(id)`。

**兼容性处理**：

1. 两个 flag 是 Flyme 对 `android.app.Notification` 的私有扩展，模块通过
   **反射读取**；读不到（非 Flyme 或未移植该功能的 ROM）自动禁用，
   模块静默退出，不产生任何通知或异常 —— 这是判断机型是否支持的官方方法。
2. 通知渠道用 `IMPORTANCE_LOW`、无声音无振动、不显示角标，
   避免对用户造成打扰。
3. 通知从 Apple Music 进程发出，复用宿主的 `NotificationManager` 和图标资源，
   不需要任何额外权限；使用独立通知 ID（`0x414D4C59`），与宿主媒体通知互不干扰。
4. 相同文本 2 秒内不重发，防止 Flyme 的 Ticker 动画反复触发造成闪烁。

## 三、状态与异常处理

| 状态 | 行为 |
|------|------|
| 切歌 | 立即重置歌词状态，先显示「♪ 歌名 - 歌手」，杜绝上一首歌词残留 |
| 歌词加载中 | 保持显示歌名，歌词就绪后无缝切换到第一句 |
| 无歌词 | 播放约 8 秒仍无歌词数据 → 显示一次「♪ 暂无歌词」，不反复刷屏 |
| 前奏 / 间奏 | 当前无对应歌词行时显示 `♪ ♪ ♪` |
| 暂停 | 停止轮询，状态栏**冻结**在当前行（不消失、不闪烁） |
| 停止 / 播完 | 停止轮询并 `cancel` 通知，清除状态栏歌词 |
| seek 拖动 | 立即重算当前行（`onSeek` 直接触发一次 tick），不等待下个轮询周期 |
| 播放器实例回收 | 进度提供者返回 null，轮询自动挂起，播放恢复后重启 |

**稳定性设计**（保证不崩溃、不卡顿）：

- 每个 Hook 独立安装、独立 try/catch —— 单个 Hook 失败只损失对应功能；
- 所有原生对象反射调用都走 `runCatching` 包装，任何一步异常安全返回 null；
- 进度轮询在**独立低优先级后台线程**（`THREAD_PRIORITY_BACKGROUND`），
  不碰宿主主线程和音频线程；300ms 周期兼顾跟手感与开销；
- Hook 回调里只做轻量状态记录，通知构建全部延迟到轮询线程；
- 原生 vector 遍历设 10000 上限，防御异常数据导致的超长循环；
- 播放器实例用 `WeakReference` 持有，不阻碍宿主 GC。

## 四、构建

环境要求：

| 组件 | 版本 |
|------|------|
| JDK | **21**（推荐；Android Studio 内置 JBR 21 即可，最低 17） |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.20 |
| Gradle | 8.13（wrapper 已内置，首次构建自动下载） |
| Android SDK | **36（Android 16）**，`targetSdk = 36` |
| minSdk | 26（libxposed API 的最低要求） |

```bash
# 方式一：Android Studio（Ladybug 及以上）打开工程根目录，Sync 后：
./gradlew :app:assembleRelease
# Windows CMD/PowerShell 下：
gradlew.bat :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
```

依赖说明：仅 `compileOnly("io.github.libxposed:api:102.0.0")`（Maven Central），
运行时由 LSPosed 注入实现，APK 体积增量极小。字节码基线为 Java 17
（与 libxposed API 一致），Gradle 守护进程建议跑在 JDK 21 上。

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

1. 安装 APK 后，在 **LSPosed 管理器 → 模块** 中启用「AM 歌词 · Flyme」；
2. 作用域已通过 `scope.list` + `staticScope=true` **固定为 Apple Music**，
   无需（也无法）手动勾选其它应用；
3. 强制停止并重新打开 Apple Music，播放任意歌曲；
4. 状态栏左上角出现歌词即为成功。

排错：

```bash
adb logcat -s AMFlymeLyric
```

- `Flyme status bar lyric NOT supported` → 当前 ROM 无此功能（非 Flyme 或未移植）；
- `hook [xxx] failed` → Apple Music 版本更新导致类名变化，按上文 jadx 指南重定位；
- 歌词不显示但无报错 → 该歌曲无歌词数据（Apple Music 曲库限制）。

## 六、已知限制与路线图

- Hook 点针对 Apple Music 4.x 验证，大版本更新后需按指南重定位（计划接入 DexKit 自动匹配）；
- 翻译歌词默认关闭（`LyricController.SHOW_TRANSLATION`），状态栏宽度有限，开启后原文·译文同行显示；
- Apple Music 无损/杜比全景声歌曲的歌词时间轴与普通曲目一致，无额外处理；
- 未做模块设置界面；如需开关翻译、暂停时清除等偏好，可扩展 RemotePreferences。

## 致谢

- Hook 点验证参考了开源项目 LyricProvider（Apache-2.0）对 Apple Music 的适配；
- Flyme 状态栏歌词实现遵循魅族开放平台官方文档。
