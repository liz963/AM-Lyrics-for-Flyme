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

    /**
     * 推送「歌曲名-歌手名」(v1.3.11 新功能，v1.3.12 起改为歌名锁定)。
     * 仅在播放最开头时由 BackgroundLyrics 调用一次：作为状态栏首条内容先露歌名，
     * 随后 BackgroundLyrics 进入"歌名锁定"抑制歌词上屏，直到第一句前 LEAD_MS 才放开，
     * 第一句按原提前量准时替换歌名。
     */
    @Synchronized
    fun onSongMeta(text: String?) {
        if (text.isNullOrBlank()) return
        noLyricHintShown = false
        FlymeStatusBarLyric.update(text)
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
    fun onNoLyrics() {
        // 歌名锁定中：保持「歌曲名-歌手」，不弹「暂无歌词」占位（纯伴奏场景下歌名贯穿整曲）
        if (BackgroundLyrics.isTitleHolding()) {
            XLog.d("onNoLyrics suppressed: title holding")
            return
        }
        if (noLyricHintShown) return
        noLyricHintShown = true
        FlymeStatusBarLyric.update("♪ 暂无歌词")
    }

    /**
     * 无歌词静默（v1.3.15）：宿主根本没有可播歌词时，歌名展示若干秒后调用本方法
     * **清空状态栏**，并在本首剩余时间内不再推送任何内容（标题/歌词/占位）。
     *
     * 置 `noLyricHintShown = true` 是为了顺带堵掉「♪ 暂无歌词」占位——用户明确要求
     * 这种歌曲清空后不要出现任何文案。切歌时 `onSongChanged` 会复位它。
     */
    @Synchronized
    fun onSilence() {
        noLyricHintShown = true
        FlymeStatusBarLyric.clear()
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
