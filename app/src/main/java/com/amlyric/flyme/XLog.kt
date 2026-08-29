package com.amlyric.flyme

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 统一日志出口：同时写 logcat 与 LSPosed 框架日志。
 * RELEASE 构建建议把 DEBUG 置为 false，
 * 避免在 Apple Music 进程里刷日志造成性能损耗。
 */
object XLog {
    private const val TAG = "AMFlymeLyric"
    private const val DEBUG = true

    /** 框架侧日志通道（XposedModule 实例），attach 后生效 */
    @Volatile
    private var framework: XposedInterface? = null

    fun attach(xp: XposedInterface) {
        framework = xp
    }

    fun d(msg: String) {
        if (DEBUG) Log.d(TAG, msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        framework?.log(Log.INFO, TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        framework?.log(Log.WARN, TAG, msg)
    }

    fun e(msg: String, tr: Throwable? = null) {
        Log.e(TAG, msg, tr)
        framework?.log(Log.ERROR, TAG, msg, tr)
    }
}
