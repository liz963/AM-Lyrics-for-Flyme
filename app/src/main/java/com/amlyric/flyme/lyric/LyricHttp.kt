package com.amlyric.flyme.lyric

import com.amlyric.flyme.XLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 歌词服务用的极简 HTTP 客户端。
 *
 * ══════════════════ 为什么不用 OkHttp ══════════════════
 *
 * 本模块跑在 **Apple Music 进程里**（Xposed 模块）。往里塞第三方网络库有两个问题：
 *  ① 宿主的类加载器能看见我们打进去的类，宿主自己就用 OkHttp，
 *     两边版本一旦不同会互相顶掉（`NoSuchMethodError` 这类事故排查成本极高）；
 *  ② 模块只是"发一个 GET/POST、拿一段文本"，[HttpURLConnection] 完全够用。
 *
 * ══════════════════ 约定 ══════════════════
 *
 * **所有方法都不会抛异常**，失败一律返回 null 并留一条 warn 日志。
 * 调用方（播放中的补全流程）不能因为一次网络抖动就把播放路径带崩。
 */
internal object LyricHttp {

    /** 播放中发请求，超时必须短——宁可这次不补，也不能拖住后台线程 */
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000

    /**
     * 两家服务都会按 UA 判断"这个客户端配不配拿逐字歌词"。
     * 写一个普通的移动端浏览器 UA 最稳，不要写模块自己的名字。
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        request(url, "GET", null, headers, "application/json, text/plain, */*")

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? =
        request(url, "POST", json, headers, "application/json")

    /** `application/x-www-form-urlencoded` POST（网易云的搜索口认这个） */
    fun postForm(url: String, form: String, headers: Map<String, String> = emptyMap()): String? =
        request(
            url, "POST", form, headers, "application/json, text/plain, */*",
            contentType = "application/x-www-form-urlencoded",
        )

    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        accept: String,
        contentType: String = "application/json",
    ): String? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", accept)
                // 不自己声明 Accept-Encoding：交给平台透明处理 gzip，省一层出错点
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                }
            }
            if (body != null) {
                conn!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn!!.responseCode
            if (code !in 200..299) {
                XLog.w("lyric http $code $method ${url.take(90)}")
                return@runCatching null
            }
            val stream = conn!!.inputStream
            val gzipped = conn!!.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val bytes = (if (gzipped) GZIPInputStream(stream) else stream).use { readAll(it) }
            String(bytes, Charsets.UTF_8)
        }.onFailure {
            XLog.w("lyric http failed $method ${url.take(90)}: ${it.javaClass.simpleName} ${it.message}")
        }.getOrNull().also { runCatching { conn?.disconnect() } }
    }

    private fun readAll(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
