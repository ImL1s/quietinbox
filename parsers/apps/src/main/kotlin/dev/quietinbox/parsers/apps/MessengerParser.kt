package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MediaReferenceCandidate
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.ParseWarning

/**
 * Messenger (`com.facebook.orca`).
 *
 * `MessagingStyle` is the normal path and is left to the standard parser. The adapter adds:
 *  - `<name> sent you a message` style bodies as hidden previews;
 *  - attachment wording (`sent a photo`, `傳送了一張相片`) is marked `MessageKind.MEDIA` with a
 *    `MediaReferenceCandidate` that only records whether the notification carried a bitmap. The
 *    body text is kept exactly as posted and no URI is invented.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class MessengerParser : AppParser() {
    override val id: String = "messenger"
    override val packages: Set<String> = setOf(KnownSources.MESSENGER)

    override val placeholderSuffixes: Set<String> = setOf(
        "sent you a message",
        "傳送了一則訊息給你",
    )

    private val mediaSuffixes: Set<String> = setOf(
        "sent a photo",
        "sent you a photo",
        "sent a video",
        "sent you a video",
        "sent an attachment",
        "傳送了一張相片",
        "傳送了一張照片",
        "傳送了一段影片",
        "傳送了一個檔案",
    )

    override fun postProcess(
        candidates: List<MessageCandidate>,
        shape: NotificationShape,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = super.postProcess(candidates, shape, warnings).map { candidate ->
        if (candidate.media == null && isMediaWording(candidate.body)) {
            candidate.copy(
                kind = MessageKind.MEDIA,
                media = MediaReferenceCandidate(fromNotificationBitmap = shape.hasPicture),
            )
        } else {
            candidate
        }
    }

    private fun isMediaWording(body: String): Boolean {
        val t = foldForMatch(body)
        return mediaSuffixes.any { t.endsWith(it) }
    }
}
