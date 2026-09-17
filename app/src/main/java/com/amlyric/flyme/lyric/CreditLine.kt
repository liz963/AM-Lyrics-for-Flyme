package com.amlyric.flyme.lyric

/**
 * 版权/制作人员行（credit line）过滤 + 「歌曲名 - 歌手」尾注行过滤。
 *
 * 两个入口，都别绕过：
 *  · [filter]        —— 取词后处理整首歌（注入到播放页的那份 TTML 走这里）；
 *  · [isCredit] / [isTitleArtist] —— **上屏前的单行判定**，由
 *   [com.amlyric.flyme.core.LyricController] 在推状态栏前调用。
 *   后者很重要：宿主自己那版歌词（我们没注入的那些歌）不经过 [filter]，
 *   但同样会把 `歌曲名 - 歌手` 当歌词行回传。
 *
 * ══════════════════════ 为什么需要它 ══════════════════════
 *
 * QQ 音乐的 QRC 会把制作人员名单当成**正文行**返回，而且带真实时间戳，例如：
 * ```
 * [8228,900]配唱制作人Vocal Producer：J.P.Han/靖成@Label IFP
 * ```
 * 网易云也偶有 `作词 : xxx` 这种行。这些行原本是给"歌词页底部滚动名单"用的，
 * 但我们的展示面是**状态栏**：不加处理时，前奏期第一句会被顶成这串制作人名单，
 * 而且要一直显示到真正的第一句歌词出现（真机实测约 6 秒）——非常难看。
 *
 * ══════════════════════ 判定规则（刻意保守）══════════════════════
 *
 * 只有当**行首到第一个冒号之间**出现制作类关键词时才算版权行。
 * 之所以锚定"冒号之前的那一小段"，是因为歌词正文里出现冒号是常事
 * （"我说：你好"），但正文的冒号前面几乎不会恰好是"作词/编曲/制作人"。
 *
 * 另外 [filter] 保证**至少留下 2 行**：万一某首歌整篇被误判（例如纯器乐作品
 * 只有版权行），宁可原样保留也不能交出一份空歌词。
 */
internal object CreditLine {

    /**
     * 关键词。**分两组**，因为两种写法的误判风险完全不同：
     *
     * · [LABELS_CONTAINS]：较长的词，出现在标签段里就足以定罪。
     *   例：「配唱制作人Vocal Producer」含「制作人」，「音频编辑」含「编辑」。
     * · [LABELS_EXACT]：单字或极短的词（「曲」「词」「唱」…）。它们只能**整段相等**才算，
     *   绝不能做子串匹配 —— 否则「我想唱：…」这类正文会被误杀。
     *   QQ 的 QRC 里 `曲：黄霄雲` `词：xxx` 就是这种单字写法。
     */
    private val LABELS_CONTAINS = listOf(
        "作词", "填词", "作曲", "编曲", "制作人", "监制", "总监", "出品", "发行", "统筹", "企划",
        "策划", "宣发", "经纪", "厂牌", "文案", "录音", "混音", "母带", "配唱", "和声", "和音",
        "声乐", "人声", "编辑", "后期", "制作", "监唱", "顾问", "乐手", "演奏", "乐器",
        "原唱", "翻唱", "演唱", "主唱", "合唱", "伴唱", "对唱", "其他",
        "吉他", "贝斯", "键盘", "弦乐", "小提琴", "大提琴", "钢琴", "古筝", "琵琶", "二胡",
        "封面", "视觉", "导演", "剪辑", "调色", "字幕", "鸣谢", "版权", "录音室", "录音棚",
        "工作室", "音乐制作", "出品方", "制作方",
        "推广", "赞助",
        "Producer", "Composer", "Lyricist", "Arranger", "Arrangement", "Mixing", "Mixer",
        "Mastering", "Engineer", "Studio", "Vocal", "Chorus", "Guitar", "Bass", "Drum",
        "Piano", "Strings", "Recorded", "Label", "Design", "Director", "Coordinator",
        "Programming", "Conductor", "Manager",
    )

    /** 只允许"整段相等"的短标签（见上方说明） */
    private val LABELS_EXACT = listOf(
        "曲", "词", "唱", "编", "混", "录", "鼓", "OP", "SP", "MV", "PV", "词曲",
    )

    /**
     * 标签段内部的分隔符。
     *
     * 真机实测（v1.4.0）QQ 会出现 `OP/SP：回音如果` 这种**复合标签**，
     * 整段既不等于 "OP" 也不等于 "SP"，所以要先拆开再逐个判。
     */
    private val LABEL_SEPARATORS = charArrayOf('/', '\\', '、', ',', '，', '&', '+', '|', ' ', '\u3000')

    /**
     * 冒号前的"标签段"；限制长度，避免把整句正文吞进来。
     * **中英文冒号都算**（用户明确要求：制作名单那类行都长成 `标签：内容`）。
     */
    private val LABEL = Regex("^(.{1,24}?)[：:]")

    /**
     * 取标签段的**宽窗口**，只给关键词匹配用（不走 [isCredit] 的兜底规则）。
     *
     * 【为什么需要两个窗口】真机实测（v1.4.0）QQ 会把多个职称叠成一行：
     * ```
     * 后期母带处理制作人MASTERING PRODUCER：xxx
     * ```
     * 冒号前就有 27 个字符，[LABEL] 的 24 字窗口根本取不到标签段 → 整条漏网
     * （真机日志里它成了这首歌的第一行）。宽窗口拿到后再走 [LABELS_WIDE] 判定，
     * 既能把这种长复合职称抓住，又不会把长句正文误吞进兜底规则。
     */
    private val LABEL_WIDE = Regex("^(.{1,64}?)[：:]")

    /**
     * 宽窗口专用的关键词：**只放"一定是职称"的词**。
     *
     * ⚠️ 不能直接复用 [LABELS_CONTAINS] —— 那里面有 `合唱` `演唱` `制作` 这类
     * 在歌词正文里也会出现的词，窗口放宽到 64 之后误杀概率会明显上升
     * （「我喜欢和你合唱：一起去」会被判成制作行）。这里刻意只收职衔。
     */
    private val LABELS_WIDE = listOf(
        "制作人", "总监", "监制", "出品", "发行", "统筹", "企划", "策划", "宣发", "经纪",
        "厂牌", "母带", "混音", "编曲", "作曲", "作词", "填词", "配唱", "后期", "录音",
        "和声编写", "和声设计", "编辑", "演奏", "乐手", "推广", "赞助",
        "Producer", "Composer", "Lyricist", "Arranger", "Arrangement", "Mixing", "Mixer",
        "Mastering", "Engineer", "Studio", "Director", "Coordinator", "Programming",
        "Conductor", "Manager", "Label", "Design",
    )

    /**
     * 唯一的一类例外：标签段长成**句子**（主语 + 说话类动词）时，它是歌词而不是制作标签
     * （「我说：你好」「他问：为什么」「你不要再问：…」）。
     *
     * ⚠️ 必须带主语限定，**不能只判"以动词结尾"** ——
     * 真机实测 `原唱：音频怪物` 就被那种写法放过了（"原唱"以"唱"结尾）。
     *
     * 用户口径是"凡有冒号就当版权行过滤"，这条例外是唯一保留的防线 ——
     * 因为真歌词删掉是找不回来的，而制作行最多是"没滤干净"。
     */
    private val SENTENCE_LABEL = Regex(
        "^(我|你|他|她|它|祂|谁|咱|俺|人家|大家|他们|我们|你们|自己)" +
            ".{0,6}(说|问|唱|道|喊|想|笑|哭|看|答|数|记|讲|念)$"
    )

    /**
     * 整行被括号包起来的"声明/告示"行。
     *
     * 真机实测（v1.4.0）QQ 源的第一行常是版权声明，**不带冒号**，
     * 于是 [LABEL] 那条规则完全抓不到：
     * ```
     * （未经著作权人许可 不得翻唱 翻录或使用）
     * ```
     * 它会成为状态栏/播放页的第一句 —— 必须挡掉。
     */
    private val WRAPPED = Regex("^[（(\\[【].*[）)\\]】]$")

    /**
     * 声明行里的特征词。配合 [WRAPPED] 使用。
     *
     * ⚠️ **刻意只收法律/声明类词**，不收"未经""许可""允许"这类：
     * 歌词正文里完全可能出现"（未经你的同意 就爱上你）"，
     * 收宽了就会把真歌词删掉 —— 而真歌词删掉是找不回来的。
     */
    private val NOTICE_WORDS = listOf(
        "著作权", "版权", "翻唱", "翻录", "复制", "传播", "未经授权", "本歌曲", "此歌曲",
        "copyright", "all rights reserved",
    )

    /**
     * 该行是不是版权/制作行。
     *
     * ══════════════ 判定顺序（改前必读）══════════════
     *  ① 整行被括号包住的声明行（`（未经著作权人许可…）`）→ 版权行；
     *  ② 标签段命中关键词表（`作词` / `音乐总监` / `Producer`…）→ 版权行；
     *  ③ 标签段命中单字表（`曲` `OP` `SP`…，**只能整段相等**）→ 版权行；
     *  ④ **兜底（用户口径）**：只要长成 `标签：内容` 且标签段不是歌词句子 → 版权行。
     *     这一条是用户明确要求加的（真机实测 QQ 总会漏几个没收录的职称，
     *     例如"音乐总监"，逐个补词表永远补不完）。
     */
    fun isCredit(text: String): Boolean {
        val t = text.trim().replace('\u3000', ' ')
        if (t.isEmpty()) return false

        // ① 整行括号包着的声明行（`（未经著作权人许可…）`）——
        //    必须"整行被包住 + 含特征词"两个条件同时成立，避免误伤括号里的正常和声标注
        if (WRAPPED.matches(t) && NOTICE_WORDS.any { t.contains(it, ignoreCase = true) }) return true

        // ② 关键词命中：**宽窗口**取标签段（长复合职称见 [LABEL_WIDE]），
        //    但只用"一定是职称"的 [LABELS_WIDE]，避免误杀正文
        val wide = LABEL_WIDE.find(t)?.groupValues?.get(1)?.trim().orEmpty()
        if (wide.isNotEmpty() &&
            LABELS_WIDE.any { wide.contains(it, ignoreCase = true) }
        ) {
            return true
        }

        // ③④ `标签：内容` 形式（窄窗口，用户口径的兜底规则只在这里生效）
        val label = LABEL.find(t)?.groupValues?.get(1)?.trim().orEmpty()
        if (label.isEmpty()) return false
        if (LABELS_CONTAINS.any { label.contains(it, ignoreCase = true) }) return true
        // 复合标签拆开逐段看：`OP/SP` → {OP, SP}
        if (label.split(*LABEL_SEPARATORS).any { part ->
                part.isNotBlank() && LABELS_EXACT.any { part.equals(it, ignoreCase = true) }
            }
        ) {
            return true
        }
        // ④ 兜底：有冒号的就是制作行（唯一例外见 [SENTENCE_LABEL]）
        return !SENTENCE_LABEL.containsMatchIn(label)
    }

    /**
     * 该行被过滤的原因；不过滤返回 null。
     *
     * 给日志用：**与 [filter] 共用同一判据**，所以日志里"删了哪些行"永远不会
     * 和实际行为漂移（各写一份判据迟早会对不上）。
     */
    fun reason(text: String, title: String?, artist: String?): String? = when {
        isCredit(text) -> "credit"
        isTitleArtist(text, title, artist) -> "title-artist"
        else -> null
    }

    /**
     * 过滤整首歌的版权行 + 「歌曲名 - 歌手」尾注行。
     *
     * @param title  当前歌曲名，用于识别尾注行（null = 只过滤版权行）
     * @param artist 当前歌手名，同上
     * @return 过滤后的行；若过滤会把歌词掏空（少于 2 行），原样返回
     */
    fun filter(lines: List<TimedLine>, title: String? = null, artist: String? = null): List<TimedLine> {
        if (lines.size < 3) return lines
        val kept = lines.filterNot { reason(it.wordsText, title, artist) != null }
        return if (kept.size >= 2) kept else lines
    }

    /**
     * 该行是不是「歌曲名 - 歌手」这种**尾注 / 占位**行。
     *
     * ══════════════════ 真机实测（v1.4.0，两种来源都会出现）══════════════════
     *
     * ① **歌词源自带尾注**：QQ 的 QRC 在正文之后会补一行 `留不住的夏天 - 黄霄雲`，
     *    带真实时间戳，位置落在歌曲末尾 —— 于是状态栏最后一句变成了歌名，
     *    注入到播放页也会在最底部多出这么一行。
     * ② **宿主歌词页的占位文本**：宿主在"没有可用歌词 / 歌词还没解析出来"时，
     *    行回调仍会回传一行 `歌曲名 - 歌手`（真机日志：切歌后 200ms 就捕获到这串，
     *    而这首歌当时 `loadLyrics` 超时、根本没有歌词）。
     *
     * ══════════════════ 判定规则（两条判据取「或」，务必保守）══════════════════
     *
     * **判据 A（形状）**：把行按 `-` `–` `|` `/` `·` 等分隔符切开，若「头段≈歌名」
     * 且「其余段含歌手名」，就是拼出来的标题行。
     * 这条专门治宿主那种长歌手串 —— 真机实测遇到过
     * `EGO - 澤野弘之 (さわの ひろゆき)/小林未郁 (こばやし みか)`，
     * 而 `getArtistName()` 只给了 `小林未郁`，靠"长度接近"是判不出来的。
     *
     * **判据 B（兜底）**：整行同时含歌名与歌手名，且长度不超过两者之和的 3 倍。
     *
     * ⚠️ **绝不用"只等于歌名"就判掉** —— 副歌里出现歌名是常态
     * （《留不住的夏天》的歌词里就有"怪你和我 消失的薄荷味夏天"这类行），
     * 只按歌名匹配会把真歌词删掉。
     */
    fun isTitleArtist(text: String, title: String?, artist: String?): Boolean {
        val nTitle = normalize(title)
        val nArtist = normalize(artist)
        if (nTitle.isEmpty() || nArtist.isEmpty()) return false

        // 判据 A：形状 `<歌名> <分隔符> <歌手…>`
        val parts = text.trim().split(SPLIT)
        if (parts.size >= 2) {
            val head = normalize(parts.first())
            val tail = normalize(parts.drop(1).joinToString(""))
            if (head.isNotEmpty() && tail.isNotEmpty() &&
                (head == nTitle || head.contains(nTitle) || nTitle.contains(head)) &&
                tail.contains(nArtist)
            ) {
                return true
            }
        }

        // 判据 B：短行里同时出现歌名与歌手名
        val t = normalize(text)
        if (!t.contains(nTitle) || !t.contains(nArtist)) return false
        return t.length <= (nTitle.length + nArtist.length) * 3 + 12
    }

    /** 标题/歌手行的分隔符（连字符、竖线、斜杠、间隔号、顿号…） */
    private val SPLIT = Regex("\\s*[-–—_~～·•|/／\\\\、,，&＋+]\\s*")

    /**
     * 归一化：只保留字母与数字（中日韩汉字 `isLetterOrDigit()` 为真），
     * 去掉空白、标点、括号、连字符 —— 这样 `留不住的夏天 - 黄霄雲`、
     * `留不住的夏天(伴奏) - 黄霄雲`、`留不住的夏天/黄霄雲` 都能对上。
     */
    private fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (c in raw) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }
}
