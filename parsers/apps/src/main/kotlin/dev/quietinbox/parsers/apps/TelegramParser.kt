package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.KnownSources

/**
 * Telegram (`org.telegram.messenger`).
 *
 * `MessagingStyle` is the normal path and is left to the standard parser. The adapter adds:
 *  - transfer/connection wording (`Sending…`, `Uploading …`, `Connecting…`) as a notice;
 *  - a couple of extra hidden-preview phrases.
 *
 * The collapsed multi-chat form (`N new messages from M chats`) is handled by [AppParser], which
 * turns it into a `SummaryObservation` and raises `MULTIPLE_CONVERSATIONS_SUSPECTED`.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class TelegramParser : AppParser() {
    override val id: String = "telegram"
    override val packages: Set<String> = setOf(KnownSources.TELEGRAM)

    override val placeholderPhrases: Set<String> = setOf(
        "message hidden",
        "hidden message",
        "訊息內容已隱藏",
    )

    override val noticePhrases: Set<String> = setOf(
        "sending",
        "connecting",
        "updating",
        "正在連線",
    )

    override val noticePrefixes: Set<String> = setOf(
        "uploading",
        "downloading",
        "sending photo",
        "sending video",
        "正在上傳",
        "正在傳送",
    )
}
