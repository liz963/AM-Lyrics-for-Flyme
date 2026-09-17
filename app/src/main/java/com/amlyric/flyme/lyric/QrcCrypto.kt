package com.amlyric.flyme.lyric

import com.amlyric.flyme.XLog
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ 音乐 QRC 歌词解密。
 *
 * ══════════════════ 为什么不能直接用平台自带的 DES / 3DES ══════════════════
 *
 * 第一版按公开资料写成 `Cipher("DESede/ECB/NoPadding")` + 24 字节密钥，
 * 结果解出来是一片随机字节，zlib 直接报 `unknown compression method`
 * （首字节 0xC1 而不是 0x78）。**不是密钥错，是算法错**：
 *
 * QQ 音乐的 QRC 用的是一份**被改过的 DES**——
 *   · S 盒与标准 DES 不完全一致（例如 S2 的某一位是 15 而不是标准值 14）；
 *   · F 函数里的 P 置换走的是这套实现自己的规则。
 * 这些差异是从 QQ 客户端里连表带码抄出来的，**任何标准实现都解不开**。
 * 所以这里按 Lyrics-Helper（ChouChiu/getl）的 Rust 实现**逐位移植**，
 * 表也原样保留（包括那个"看着像笔误"的 15）。
 *
 * ══════════════════ 解密流程 ══════════════════
 *
 * ```
 * 去空白 → hex 解码 → 三轮 DES（D(K3) → E(K2) → D(K1)）→ zlib 解压 → UTF-8（去 BOM）
 * ```
 * 密钥 24 字节 = `!@#)(*$%123ZXC!@!@#)(NHL`，按 8 字节切成 K1/K2/K3。
 * 末尾不足 8 字节的块补零到 8 字节再解，但**只取回原长度**（zlib 流在补零之前就结束了）。
 *
 * ══════════════════ 真机验证 ══════════════════
 * 用《年轮》的真实 QRC（6912 个 hex 字符）解出 5804 字节合法 XML，
 * `<QrcInfos><LyricInfo><Lyric_1 LyricContent="[ti:年轮…]…">`，
 * 逐字时间戳与 QQ 客户端一致。
 *
 * 【移植注意】Rust 的 `>>` 是无符号右移，Kotlin 里对应 `ushr`。
 * 这里一律用 `Int`（32 位）承载 u32，溢出回绕语义与 Rust 相同。
 */
internal object QrcCrypto {

    private val KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private const val ROUNDS = 16

    /** 一轮 DES 的子密钥：6 字节 */
    private const val SUBKEY_LEN = 6

    // ─────────────────────────── 表（勿手改，见类注释） ───────────────────────────

    /** S 盒，8 × 64，行优先压平 */
    private val SBOX_FLAT = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,)

    private val KEY_RND_SHIFT = intArrayOf(
        1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1,)

    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,)

    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,)

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,)

    /** 初始置换 IP：左 32 位 */
    private val IP_LEFT = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
    )

    /** 初始置换 IP：右 32 位 */
    private val IP_RIGHT = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )

    /** 末置换 IP⁻¹ 用到的位号（R 侧 / L 侧交替，见 [inversePermutation]） */
    private val INV_ORDER = intArrayOf(7, 6, 5, 4, 3, 2, 1, 0)

    /** F 函数 P 置换的取位顺序 */
    private val P_PERM = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    /** 三轮 DES 的子密钥表（[schedules][轮][子密钥]） */
    private val schedules: Array<Array<IntArray>> by lazy { tripleDesKeySetup(KEY) }

    // ─────────────────────────── 对外接口 ───────────────────────────

    /**
     * 解密一段 QRC 密文。
     *
     * @param encrypted 服务端给的内容：hex 字符串，允许夹带空白与换行
     * @return 解密后的明文（QRC XML）；失败返回 null（**不会抛异常**）
     */
    fun decrypt(encrypted: String): String? {
        val hex = encrypted.filterNot { it.isWhitespace() }
        if (hex.isEmpty() || hex.length % 2 != 0) {
            XLog.w("qrc decrypt: bad hex length ${hex.length}")
            return null
        }
        val data = runCatching { hexToBytes(hex) }
            .onFailure { XLog.w("qrc decrypt: hex decode failed: ${it.message}") }
            .getOrNull() ?: return null

        val plain = ByteArray(data.size)
        val sched = schedules
        var i = 0
        while (i < data.size) {
            val end = minOf(i + 8, data.size)
            val block = ByteArray(8)
            System.arraycopy(data, i, block, 0, end - i)
            val out = tripleDesCrypt(block, sched)
            // 末块的补零部分不要（zlib 流在补零之前就已经结束）
            System.arraycopy(out, 0, plain, i, end - i)
            i += 8
        }

        return inflate(plain)
    }

    // ─────────────────────────── zlib ───────────────────────────

    /**
     * zlib 解压。
     *
     * 末尾那几字节是补零凑出来的垃圾，Java 的 [Inflater] 可能在读到流末尾后
     * 还想继续要数据，所以这里**不要求 `finished()` 才收工**：
     * 只要还能吐出字节就继续，吐不出来就停，最后有多少算多少。
     */
    private fun inflate(data: ByteArray): String? {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2 + 64)
        val buf = ByteArray(8192)
        runCatching {
            var idle = 0
            while (idle < 2) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    out.write(buf, 0, n)
                    idle = 0
                    continue
                }
                // 连续两次吐不出东西就认为结束了（可能是 finished，也可能是数据被截断）
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                idle++
            }
        }.onFailure { XLog.w("qrc inflate: ${it.message}") }
        inflater.end()

        val raw = out.toByteArray()
        if (raw.isEmpty()) {
            XLog.w("qrc inflate: empty output")
            return null
        }
        // UTF-8 BOM
        val start = if (raw.size >= 3 && raw[0] == 0xEF.toByte() &&
            raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
        ) 3 else 0
        return String(raw, start, raw.size - start, Charsets.UTF_8)
    }

    // ─────────────────────────── 三轮 DES ───────────────────────────

    /**
     * D(K1) → E(K2) → D(K3)。
     * 表按"解密方向"预排（见 [keySchedule] 的 `reverse` 参数），
     * 所以这里对三轮一视同仁地顺序调用即可。
     */
    private fun tripleDesCrypt(input: ByteArray, sched: Array<Array<IntArray>>): ByteArray =
        desCrypt(desCrypt(desCrypt(input, sched[0]), sched[1]), sched[2])

    /**
     * K1/K2/K3 取自 24 字节密钥的三个 8 字节段。
     *
     * 顺序对应 `D(K1) → E(K2) → D(K3)`：三轮都按"解密方向"排表时，
     * 一、三轮要 `reverse`（倒序子密钥 = 解密），第二轮不 reverse（正序 = 加密）。
     */
    private fun tripleDesKeySetup(key: ByteArray): Array<Array<IntArray>> = arrayOf(
        keySchedule(key.copyOfRange(16, 24), reverse = true),
        keySchedule(key.copyOfRange(8, 16), reverse = false),
        keySchedule(key.copyOfRange(0, 8), reverse = true),
    )

    /** 单轮 DES */
    private fun desCrypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        val (ip0, ip1) = initialPermutation(input)
        var left = ip0
        var right = ip1
        for (round in 0 until ROUNDS - 1) {
            val t = right
            right = desF(right, key[round]) xor left
            left = t
        }
        left = left xor desF(right, key[ROUNDS - 1])
        return inversePermutation(left, right)
    }

    private fun initialPermutation(input: ByteArray): Pair<Int, Int> {
        var left = 0
        for (i in 0 until 32) left = left or bitnum(input, IP_LEFT[i], 31 - i)
        var right = 0
        for (i in 0 until 32) right = right or bitnum(input, IP_RIGHT[i], 31 - i)
        return left to right
    }

    /** IP⁻¹：把 (L, R) 按 R/L 交替、每字节 8 位拼回 8 字节 */
    private fun inversePermutation(left: Int, right: Int): ByteArray {
        val out = ByteArray(8)
        // Rust 里的赋值顺序是 3,2,1,0,7,6,5,4 —— 与 INV_ORDER 一一对应
        val targets = intArrayOf(3, 2, 1, 0, 7, 6, 5, 4)
        for (k in INV_ORDER.indices) {
            val r = INV_ORDER[k]
            out[targets[k]] = (
                bitnumIntr(right, r, 7) or bitnumIntr(left, r, 6) or
                    bitnumIntr(right, r + 8, 5) or bitnumIntr(left, r + 8, 4) or
                    bitnumIntr(right, r + 16, 3) or bitnumIntr(left, r + 16, 2) or
                    bitnumIntr(right, r + 24, 1) or bitnumIntr(left, r + 24, 0)
                ).toByte()
        }
        return out
    }

    /** F 函数（含改良 S 盒与 P 置换） */
    private fun desF(state: Int, key: IntArray): Int {
        val t1 = bitnumIntl(state, 31, 0) or
            ((state and 0xF0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
            bitnumIntl(state, 3, 6) or ((state and 0x0F000000) ushr 3) or
            bitnumIntl(state, 8, 11) or bitnumIntl(state, 7, 12) or
            ((state and 0x00F00000) ushr 5) or bitnumIntl(state, 12, 17) or
            bitnumIntl(state, 11, 18) or ((state and 0x000F0000) ushr 7) or
            bitnumIntl(state, 16, 23)

        val t2 = bitnumIntl(state, 15, 0) or
            ((state and 0x0000F000) shl 15) or bitnumIntl(state, 20, 5) or
            bitnumIntl(state, 19, 6) or ((state and 0x00000F00) shl 13) or
            bitnumIntl(state, 24, 11) or bitnumIntl(state, 23, 12) or
            ((state and 0x000000F0) shl 11) or bitnumIntl(state, 28, 17) or
            bitnumIntl(state, 27, 18) or ((state and 0x0000000F) shl 9) or
            bitnumIntl(state, 0, 23)

        // 展开成 6 字节后与子密钥异或
        val s = IntArray(6)
        s[0] = ((t1 ushr 24) and 0xFF) xor (key[0] and 0xFF)
        s[1] = ((t1 ushr 16) and 0xFF) xor (key[1] and 0xFF)
        s[2] = ((t1 ushr 8) and 0xFF) xor (key[2] and 0xFF)
        s[3] = ((t2 ushr 24) and 0xFF) xor (key[3] and 0xFF)
        s[4] = ((t2 ushr 16) and 0xFF) xor (key[4] and 0xFF)
        s[5] = ((t2 ushr 8) and 0xFF) xor (key[5] and 0xFF)

        var st = sbox(0, s[0] ushr 2) shl 28
        st = st or (sbox(1, ((s[0] and 0x03) shl 4) or (s[1] ushr 4)) shl 24)
        st = st or (sbox(2, ((s[1] and 0x0F) shl 2) or (s[2] ushr 6)) shl 20)
        st = st or (sbox(3, s[2] and 0x3F) shl 16)
        st = st or (sbox(4, s[3] ushr 2) shl 12)
        st = st or (sbox(5, ((s[3] and 0x03) shl 4) or (s[4] ushr 4)) shl 8)
        st = st or (sbox(6, ((s[4] and 0x0F) shl 2) or (s[5] ushr 6)) shl 4)
        st = st or sbox(7, s[5] and 0x3F)

        var out = 0
        for (i in P_PERM.indices) out = out or bitnumIntl(st, P_PERM[i], i)
        return out
    }

    private fun sbox(index: Int, bits: Int): Int = SBOX_FLAT[index * 64 + sboxBit(bits)]

    /** 6 位 → 行(2 位) + 列(4 位)，但行列位置是这套实现特有的（首尾位在低位） */
    private fun sboxBit(a: Int): Int = (a and 0x20) or ((a and 0x1F) ushr 1) or ((a and 0x01) shl 4)

    /**
     * PC-1 / 循环左移 / PC-2。
     *
     * @param sub     8 字节密钥段（K1/K2/K3 之一）
     * @param reverse true 时把 16 轮子密钥**倒序**安放，
     *                这样 [desCrypt] 顺序取用就等价于 DES 解密（标准做法）
     */
    private fun keySchedule(sub: ByteArray, reverse: Boolean): Array<IntArray> {
        var c = 0
        var d = 0
        for (i in KEY_PERM_C.indices) c = c or bitnum(sub, KEY_PERM_C[i], 31 - i)
        for (i in KEY_PERM_D.indices) d = d or bitnum(sub, KEY_PERM_D[i], 31 - i)

        val schedule = Array(ROUNDS) { IntArray(SUBKEY_LEN) }
        for (i in 0 until ROUNDS) {
            val shift = KEY_RND_SHIFT[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xFFFFFFF0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xFFFFFFF0.toInt()

            val target = if (reverse) ROUNDS - 1 - i else i
            for (j in 0 until 24) {
                schedule[target][j / 8] =
                    schedule[target][j / 8] or bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8))
            }
            for (j in 24 until 48) {
                schedule[target][j / 8] =
                    schedule[target][j / 8] or bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
            }
        }
        return schedule
    }

    // ─────────────────────────── 取位原语 ───────────────────────────

    /**
     * 从字节数组里取第 `bit` 位（MSB 优先编号），放进结果的第 `dest` 位。
     *
     * 注意这里的 32 位字边界：位 0..31 落在 `a[0..4]`，位 32..63 落在 `a[4..8]`，
     * 与原始实现（把 8 字节按两个大端 u32 看）一致。`offset` 用来支持密钥分段。
     */
    private fun bitnum(a: ByteArray, bit: Int, dest: Int): Int {
        val word = bit / 32
        val byteIdx = word * 4 + 3 - (bit % 32) / 8
        val b = a[byteIdx].toInt() and 0xFF
        return ((b ushr (7 - (bit % 8))) and 1) shl dest
    }

    /** 取 32 位值的第 `bit` 位 */
    private fun bitnumIntr(a: Int, bit: Int, dest: Int): Int =
        ((a ushr (31 - bit)) and 1) shl dest

    /** 取 32 位值的**高**端第 `bit` 位，并右移到 `dest` */
    private fun bitnumIntl(a: Int, bit: Int, dest: Int): Int =
        ((a shl bit) and 0x80000000.toInt()) ushr dest

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex at ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
