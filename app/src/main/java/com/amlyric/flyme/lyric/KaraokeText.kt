package com.amlyric.flyme.lyric

/**
 * **QRC（QQ 音乐）与 YRC（网易云音乐）的共同正文语法**。
 *
 * 两家格式名字不同、元信息约定不同，但正文是同一套：
 *
 * ```
 * [1200,4800]字(1200,300)字(1500,420)字(1920,260)…
 * ```
 *
 * | 位置 | 含义 |
 * |---|---|
 * | `[开始,时长]` | 行级：开始毫秒 + 持续毫秒 |
 * | `字(开始,时长)` | 字级：**开始毫秒是绝对值**，不是相对行首 |
 * | `字(开始,时长,第三字段)` | YRC 多一个恒为 0 的字段（QRC 没有），解析时忽略 |
 *
 * 两条真机实测结论（**改前必读**）：
 *  ① **字级时间是绝对毫秒**。网易云 `<data>` 的 YRC 是
 *     `[1120,5790](1120,1560,0)残(2680,1150,0)酷…`——首字时间等于行首时间；
 *     QQ 的 QRC 同样（`[18815,2821]圆(18815,339)圈(19154,421)…`）。
 *     所以**不要**再叠加行首偏移，否则整行会整体后移。
 *  ② 行首到 `]` 之前可能有 `[ti:]` `[ar:]` `[offset:]` `[kana:]` 这类元信息行，
 *     以及网易云 YRC 开头/结尾的 `{"t":0,"c":[…]}` JSON 信息行——全部跳过，
 *     否则会把"作词: 某某"当成一句歌词推进去。
 *
 * 另一处需要容忍的脏数据：QQ 的 QRC 里**标题/词曲credit行**会把 60 多个字
 * 挤进 400ms（逐字时间一路涨到 900ms+）。这里不改数据，交给
 * [TtmlWriter] 按比例压回行区间。
 */
internal object KaraokeText {

    /** `[ti:…]` / `[offset:0]` / `[kana:…]` 这类元信息行 */
    private val META_LINE = Regex("""^\[[A-Za-z]+:""")

    /** 行头：`[开始,时长]` */
    private val LINE_HEAD = Regex("""^\[(\d+),(\d+)]""")

    /** 音节：`字(开始,时长)`，第三字段可有可无 */
    private val SYLLABLE = Regex("""(.*?)\((\d+),(\d+)(?:,\d+)?\)""")

    /**
     * @param body 正文（QRC 的 `LyricContent` 或网易云 `yrc.lyric`）
     * @param language 原文档语言（写 TTML 根节点时用到），未知传 null
     * @return 逐行歌词；没有任何有效行时返回 null
     */
    fun parse(body: String, language: String? = null): TimedLyrics? {
        if (body.isBlank()) return null
        val lines = ArrayList<TimedLine>(64)

        // QRC 里 `\r` 是字面字符、`\n` 被编码成 `&#10;`（还原后就是 \n），
        // 于是行尾常见 `\r\n`；也有只给 \r 的老数据。统一成 \n 再切。
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')

        for (raw in normalized.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 元信息行 / 网易云的 JSON 信息行
            if (line.startsWith("{")) continue
            if (META_LINE.containsMatchIn(line)) continue

            val head = LINE_HEAD.find(line) ?: continue
            val begin = head.groupValues[1].toLongOrNull() ?: continue
            val duration = head.groupValues[2].toLongOrNull() ?: 0L
            val rest = line.substring(head.value.length)

            val words = parseSyllables(rest)
            val text = if (words.isEmpty()) rest.trim() else words.joinToString("") { it.text }
            if (text.isBlank()) continue

            lines += TimedLine(
                begin = begin,
                end = if (duration > 0) begin + duration else begin,
                words = words,
            )
        }

        return if (lines.isEmpty()) null else TimedLyrics(lines, language)
    }

    /**
     * 解析一行里的所有音节。
     *
     * 用惰性 `(.*?)\((\d+),(\d+)\)` 而不是按 `)` 切：歌词正文里**本身就可能含括号**
     * （实测 QQ 的词曲 credit 行是 `…高橋洋子 ((237,29)た(266,15)か…`），
     * 惰性匹配会把 `" ("` 正确当成前一个音节的文本，而按分隔符切会把括号吃掉。
     */
    private fun parseSyllables(rest: String): List<LyricWord> {
        if (rest.isEmpty()) return emptyList()
        val words = ArrayList<LyricWord>(32)
        for (m in SYLLABLE.findAll(rest)) {
            val text = m.groupValues[1]
            val start = m.groupValues[2].toLongOrNull() ?: continue
            val dur = m.groupValues[3].toLongOrNull() ?: 0L
            if (text.isEmpty()) continue
            words += LyricWord(begin = start, end = start + dur, text = text)
        }
        return words
    }
}
