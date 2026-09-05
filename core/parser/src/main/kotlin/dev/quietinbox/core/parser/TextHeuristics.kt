package dev.quietinbox.core.parser

/**
 * Shared, conservative text heuristics. Each rule must be explainable and is exercised by
 * synthetic fixtures. None of these were copied from any third-party rule set.
 */
object TextHeuristics {
    /**
     * Placeholder bodies used by messaging apps when the user disabled previews or the platform
     * redacted the notification. Compared after trimming and case folding.
     */
    private val previewPlaceholders: Set<String> = setOf(
        // English
        "new message", "you have a new message", "you have new messages", "new messages",
        "message", "sent you a message", "you may have new messages",
        "checking for new messages", "sensitive notification content hidden",
        // Traditional / Simplified Chinese
        "新訊息", "您有一則新訊息", "你有一則新訊息", "有新訊息", "您有新訊息", "新消息", "您有一条新消息", "你有一条新消息",
        "傳送了一則訊息", "傳送了訊息", "發送了一則訊息", "發送了訊息",
        // Japanese / Korean
        "新着メッセージがあります", "メッセージがあります", "새 메시지", "새로운 메시지가 있습니다",
    )

    private val summaryPatterns: List<Regex> = listOf(
        Regex("""^(\d+)\s+new\s+messages?(\s+from\s+(\d+)\s+chats?)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(\d+)\s+(unread\s+)?messages?(\s+from\s+(\d+)\s+(chats?|conversations?))?$""", RegexOption.IGNORE_CASE),
        Regex("""^(\d+)\s*(則|条|個)?\s*(新|未讀|未读)?(訊息|消息)(來自|来自)?\s*(\d+)?\s*(個|个)?(聊天室|對話|对话)?$"""),
        Regex("""^(來自|来自)\s*(\d+)\s*(個|个)?(聊天室|對話|对话)(的)?\s*(\d+)\s*(則|条)?(新)?(訊息|消息)$"""),
    )

    private val systemNoticePatterns: List<Regex> = listOf(
        Regex("""^(missed (voice|video )?call|incoming (voice|video )?call|calling…?|ongoing call)$""", RegexOption.IGNORE_CASE),
        Regex("""^(未接來電|未接来电|來電|来电|通話中|通话中|語音通話|视频通话|視訊通話)$"""),
        Regex("""^(backup|backing up|restoring|synchronising|syncing)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(正在備份|正在还原|正在同步|正在載入|正在加载).*"""),
    )

    fun isPreviewPlaceholder(body: String?): Boolean {
        if (body == null) return false
        val t = body.trim().trimEnd('.', '。', '…').lowercase()
        return t in previewPlaceholders
    }

    /** Returns (messageCount, conversationCount) if [text] is a bare summary line. */
    fun parseSummary(text: String?): Pair<Int?, Int?>? {
        if (text == null) return null
        val t = text.trim()
        for (rx in summaryPatterns) {
            val m = rx.matchEntire(t) ?: continue
            val nums = m.groupValues.drop(1).filter { it.isNotEmpty() && it.all(Char::isDigit) }.map { it.toInt() }
            // Convention: first number = messages, second = chats, except the "from N chats: M messages" form.
            return when {
                rx === summaryPatterns[3] -> Pair(nums.getOrNull(1), nums.getOrNull(0))
                else -> Pair(nums.getOrNull(0), nums.getOrNull(1))
            }
        }
        return null
    }

    fun looksLikeSystemNotice(text: String?): Boolean {
        if (text == null) return false
        val t = text.trim()
        return systemNoticePatterns.any { it.matches(t) }
    }

    /**
     * Splits `Sender: body` / `Sender：body` / `Sender - body` prefixes used by inbox-style lines.
     * Only accepted when the prefix is short and does not contain the separator itself.
     */
    fun splitSenderPrefix(line: String): Pair<String, String>? {
        val separators = listOf(": ", "：", " - ")
        for (sep in separators) {
            val idx = line.indexOf(sep)
            if (idx <= 0 || idx > 48) continue
            val sender = line.substring(0, idx).trim()
            val body = line.substring(idx + sep.length).trim()
            if (sender.isEmpty() || body.isEmpty()) continue
            if (sender.any { it == '\n' }) continue
            // Avoid splitting URLs / times like "12:30" or "http://".
            if (sep == ": " && sender.all(Char::isDigit)) continue
            if (sender.endsWith("http") || sender.endsWith("https")) continue
            return sender to body
        }
        return null
    }
}
