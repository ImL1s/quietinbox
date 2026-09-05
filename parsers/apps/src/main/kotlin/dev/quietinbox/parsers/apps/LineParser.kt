package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParseWarning

/**
 * LINE (`jp.naver.line.android`).
 *
 * Adds two things on top of the standard behaviour:
 *  - a few extra preview placeholders in Traditional Chinese / Japanese / English;
 *  - the spaced `Sender : text` group form, which LINE uses in the plain body while leaving
 *    `isGroupConversation` unset. Only the spaced separator is accepted, so a 1:1 body such as
 *    `12:30 見` is never split.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class LineParser : AppParser() {
    override val id: String = "line"
    override val packages: Set<String> = setOf(KnownSources.LINE)

    override val placeholderPhrases: Set<String> = setOf(
        "您有新的訊息",
        "你有新的訊息",
        "有新的訊息",
        "新着メッセージ",
        "new message received",
    )

    override val noticePhrases: Set<String> = setOf(
        "語音通話中",
        "視訊通話中",
        "正在通話中",
        "call in progress",
    )

    override fun appSingleCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = splitUnflaggedGroupBody(snapshot, warnings, ::splitSpacedColon)

    /** `小明 : 晚餐吃什麼` → `小明` + `晚餐吃什麼`. Requires spaces on both sides of the colon. */
    private fun splitSpacedColon(body: String): Pair<String, String>? {
        val index = body.indexOf(" : ")
        if (index <= 0 || index > MAX_SENDER_CHARS) return null
        val sender = body.substring(0, index).trim()
        val rest = body.substring(index + 3).trim()
        if (sender.isEmpty() || rest.isEmpty()) return null
        if (sender.contains('\n')) return null
        return sender to rest
    }

    private companion object {
        const val MAX_SENDER_CHARS = 48
    }
}
