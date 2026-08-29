package com.amlyric.flyme.core

import android.content.Context
import com.amlyric.flyme.Settings
import com.amlyric.flyme.XLog
import com.amlyric.flyme.flyme.FlymeStatusBarLyric

/**
 * 歌词总控制器（状态机，v1.2.1 展示策略）。
 *
 * 用户明确的展示规则：
 *  - 歌曲开头是伴奏（第一句歌词还没到）：状态栏什么都不显示；
 *  - 中间间奏（当前行回调给空行）：保持上一句歌词，直到下一句出现；
 *  - 不显示任何「加载中」占位文案（之前 "♪ 歌词加载中…" 会在伴奏阶段刷屏）。
 *
 * 事件来源（见 AppleMusicHooks / BackgroundLyrics）：
 *  - onSongChanged(id)      ：切歌 → 清掉上一首（状态栏回到空白，等第一句）。
 *  - onLyricLine(text)      ：当前行变化 → 上屏；空行 → 保持上一句不动。
 *  - onNoLyrics()           ：该歌曲确实无歌词 → 提示一次「暂无歌词」。
 *  - onPlaybackStateChanged ：暂停冻结最后一句；停止清空。
 *
 * 所有公开方法都加 @Synchronized，保证多 Hook 线程下的状态一致。
 */
object LyricController {

    /** 播放状态常量（LocalMediaPlayerController.onPlaybackStateChanged 第 3 参） */
    private const val STATE_STOPPED = 0
    private const val STATE_PLAYING = 1
    private const val STATE_PAUSED = 2

    private var initialized = false
    private var playing = false
    private var currentSongId: String? = null
    private var noLyricHintShown = false

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
        // 切歌：清掉上一首歌词。若新歌开头是伴奏，状态栏保持空白直到第一句。
        FlymeStatusBarLyric.clear()
        noLyricHintShown = false
        XLog.d("song changed: $songId")
    }

    @Synchronized
    fun onLyricLine(text: String?) {
        if (text.isNullOrBlank()) {
            // 间奏/空行：保持上一句，直到下一句歌词出现
            return
        }
        noLyricHintShown = false
        FlymeStatusBarLyric.update(text)
    }

    @Synchronized
    fun onLyricsLoaded(songId: String?) {
        // 仅同步歌曲标识（无 UI 加载兜底 + UI 路径都会走到这里），
        // 不做任何占位显示。
        if (songId != null && songId != currentSongId) {
            currentSongId = songId
            noLyricHintShown = false
        }
        XLog.d("lyrics loaded: $songId")
    }

    @Synchronized
    fun onNoLyrics() {
        if (noLyricHintShown) return
        noLyricHintShown = true
        FlymeStatusBarLyric.update("♪ 暂无歌词")
    }

    @Synchronized
    fun onPlaybackStateChanged(state: Int) {
        when (state) {
            STATE_PLAYING -> {
                playing = true
                BackgroundLyrics.setPlaying(true)
            }
            STATE_PAUSED -> {
                // 暂停：冻结当前行，不清除（状态栏保留最后一句，不闪烁）
                playing = false
                BackgroundLyrics.setPlaying(false)
            }
            STATE_STOPPED -> {
                playing = false
                FlymeStatusBarLyric.clear()
                BackgroundLyrics.stop()
            }
            else -> Unit
        }
        XLog.d("playback state: $state (playing=$playing)")
    }
}
