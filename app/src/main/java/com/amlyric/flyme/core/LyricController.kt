package com.amlyric.flyme.core

import android.content.Context
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.flyme.FlymeStatusBarLyric
import com.amlyric.flyme.hook.LyricsInjector
import com.amlyric.flyme.lyric.CreditLine

/**
 * 歌词总控制器（状态机）。
 *
 * 【展示规则（v1.4.0）】
 *  - 歌曲开头是前奏：状态栏**什么都不显示**，直到「第一句 − 1s」由 [BackgroundLyrics]
 *    放开闸门后才上屏；
 *  - 中间间奏（当前行回调给空行）：保持上一句歌词，直到下一句出现；
 *  - 无歌词的歌曲：保持空白（不推歌名、不弹「暂无歌词」占位）；
 *  - 暂停：冻结最后一句；停止：清空。
 *
 * 【事件来源】见 AppleMusicHooks / BackgroundLyrics：
 *  - [onSongChanged]           ：切歌 → 清掉上一首；
 *  - [onSongMeta]              ：同步歌名/歌手（只为挡掉 `歌曲名 - 歌手` 占位行）；
 *  - [onLyricLine]             ：当前行变化 → 上屏（空行忽略）；
 *  - [onLyricsLoaded]          ：宿主发起取词（仅记日志，见下）；
 *  - [onPlaybackStateChanged]  ：播放状态。
 *
 * 【历史包袱已清理】v1.3.x 曾在这里推「歌曲名-歌手名」、并在无歌词时弹占位文案；
 * v1.4.0 需求变更为"前奏期不推送"，`onSongMeta` / `onNoLyrics` / `onSilence` 三个入口
 * 以及配套的 `noLyricHintShown` 状态已全部删除。
 *
 * 所有公开方法都加 @Synchronized，保证多 Hook 线程下的状态一致。
 */
object LyricController {

    /** 播放状态常量（LocalMediaPlayerController.onPlaybackStateChanged 第 3 参） */
    private const val STATE_STOPPED = 0
    private const val STATE_PLAYING = 1
    private const val STATE_PAUSED = 2

    private var initialized = false
    private var currentSongId: String? = null

    /** 当前歌曲名 / 歌手名，由 [BackgroundLyrics] 每个 tick 同步过来 */
    @Volatile private var songTitle: String? = null
    @Volatile private var songArtist: String? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Settings.init(context)
        FlymeStatusBarLyric.init(context)
    }

    // ─────────────────────────── 事件入口 ───────────────────────────

    @Synchronized
    fun onSongChanged(songId: String?) {
        if (songId != null && songId == currentSongId) return
        currentSongId = songId
        // 切歌：清掉上一首歌词。新歌若开头是前奏，状态栏保持空白直到第一句。
        FlymeStatusBarLyric.clear()
        XLog.d("song changed: $songId")
    }

    @Synchronized
    fun onLyricLine(text: String?) {
        if (text.isNullOrBlank()) {
            // 间奏/空行：保持上一句，直到下一句歌词出现
            return
        }
        // ★ 上屏前的最后一道过滤（**这是状态栏唯一的出口，所有通路都汇到这里**）。
        // 宿主自己那版歌词不经过取词侧的 CreditLine.filter，占位行只能在这里挡；
        // 制作名单行则**仅**在歌词来自第三方补全时才清（详见 [isNonLyricLine]）。
        if (isNonLyricLine(text)) {
            XLog.d("drop non-lyric line: [$text]")
            return
        }
        FlymeStatusBarLyric.update(text)
    }

    /**
     * 这行是不是"不该当成歌词"的行。分两类，**适用条件不同**（务必区分）：
     *
     * ① `歌曲名 - 歌手` 占位/尾注 —— **与来源无关，一律挡掉**。
     *    这串文字永远不可能是歌词；宿主在"这首歌没有可用歌词 / 歌词还没解析出来"时
     *    就会回传它（真机实测：切歌后 200ms 到达，而我们的补全要等宽限期才发起）。
     *    必须先挡，否则它会被前奏闸门当成"第一句"，抑制提前放开，
     *    状态栏顶着歌名空等十几秒（真机实测日文歌 `EGO`）。
     *
     * ② 版权/制作行（`作词：…`、`OP/SP：…`）—— **只过滤第三方补来的歌词**。
     *    用户口径：**原生歌词一律不做处理**，宿主给什么就显示什么，
     *    连它自带的制作名单行也算数。而第三方源（QQ 的 QRC）会把制作名单当
     *    **正文行**返回且带真实时间戳，不清就会顶在状态栏/播放页上。
     *    判据见 [LyricsInjector.isThirdPartyActive]。
     *
     * 两处调用：[onLyricLine] 上屏前拦一道；[BackgroundLyrics] 的前奏闸门取"第一句"时。
     */
    fun isNonLyricLine(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (CreditLine.isTitleArtist(text, songTitle, songArtist)) return true
        if (!LyricsInjector.isThirdPartyActive()) return false
        return CreditLine.isCredit(text)
    }

    /**
     * 同步"当前歌是谁"（歌名 + 歌手）。
     *
     * 只服务于 [onLyricLine] 里那条尾注/占位行过滤：判断一行是不是
     * `歌曲名 - 歌手` 必须知道当前歌名和歌手。由 [BackgroundLyrics] 每 tick 调，
     * 值没变时直接返回（反射取值已在上游完成）。
     */
    @Synchronized
    fun onSongMeta(title: String?, artist: String?) {
        if (title == songTitle && artist == songArtist) return
        songTitle = title
        songArtist = artist
    }

    /**
     * 宿主 UI 发起了取词（`PlayerLyricsViewModel.loadLyrics` Hook 触发）。
     *
     * ⚠️ **只记日志，绝不改任何显示状态**（v1.3.16 修正）。
     * 该方法会在**歌曲真正切过去之前**就被调用（宿主会预加载下一首的歌词），
     * 若在此处更新 [currentSongId]，随后的 [onSongChanged] 会因"id 未变"而提前返回，
     * 于是**切歌清屏被跳过**——实测 14 首里 8 首如此，只是被"紧接着推歌名"掩盖，
     * 一旦在暂停状态切歌就会看到上一首的歌词残留。
     */
    @Synchronized
    fun onLyricsLoaded(songId: String?) {
        XLog.d("lyrics load requested by host: $songId")
    }

    @Synchronized
    fun onPlaybackStateChanged(state: Int) {
        when (state) {
            STATE_PLAYING -> BackgroundLyrics.setPlaying(true)
            // 暂停：冻结当前行，不清除（状态栏保留最后一句，不闪烁）
            STATE_PAUSED -> BackgroundLyrics.setPlaying(false)
            STATE_STOPPED -> {
                FlymeStatusBarLyric.clear()
                BackgroundLyrics.stop()
            }
            else -> Unit
        }
        XLog.d("playback state: $state")
    }
}
