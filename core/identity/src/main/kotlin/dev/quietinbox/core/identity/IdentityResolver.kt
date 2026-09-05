package dev.quietinbox.core.identity

import dev.quietinbox.core.model.Confidence
import dev.quietinbox.core.model.ConversationKey
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.IdentityEvidenceKind
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParsedBatch
import dev.quietinbox.core.model.SourceScope

/**
 * The resolved identity of the conversation a snapshot belongs to, always inside one
 * [SourceScope]. [identityKey] is unique per scope; [streamKey] is the notification stream the
 * evidence came from and is what the reconciler keeps its window under.
 */
data class ConversationIdentity(
    val scope: SourceScope,
    val identityKey: String,
    val confidence: IdentityConfidence,
    val streamKey: String,
    val displayTitle: String?,
)

/**
 * Deterministic identity resolution. Order of trust:
 * 1. adapter-provided source chat id  -> `chat:` (VERIFIED)
 * 2. shortcut id                       -> `shortcut:` (INFERRED; apps sometimes reuse shortcuts)
 * 3. notification stream (tag / id)    -> `stream:` (INFERRED)
 * 4. conversation / title text only    -> `title:` (UNRESOLVED)
 *
 * Group keys are treated as a grouping hint only, never as a chat id. Same-named
 * conversations from different streams are *not* merged.
 */
class IdentityResolver {

    fun resolve(snapshot: NotificationSnapshot, batch: ParsedBatch): ConversationIdentity {
        val shape = snapshot.shape
        val scope = snapshot.source
        val streamKey = streamKey(snapshot)
        val title = batch.conversation?.displayTitle ?: shape.conversationTitle?.value ?: shape.title?.value

        val chatId = batch.identityEvidence.firstOrNull {
            it.kind == IdentityEvidenceKind.SOURCE_CHAT_ID && it.confidence == Confidence.VERIFIED
        }?.value ?: (batch.conversation?.key as? ConversationKey.SourceChatId)?.value
        if (!chatId.isNullOrBlank()) {
            return ConversationIdentity(scope, "chat:$chatId", IdentityConfidence.VERIFIED_SOURCE_ID, streamKey, title)
        }

        val shortcut = (batch.conversation?.key as? ConversationKey.ShortcutId)?.value ?: shape.shortcutId
        if (!shortcut.isNullOrBlank()) {
            return ConversationIdentity(scope, "shortcut:$shortcut", IdentityConfidence.INFERRED_FROM_STREAM, streamKey, title)
        }

        // A tagged notification stream is a fairly stable per-chat boundary for most messengers.
        if (!shape.tag.isNullOrBlank()) {
            return ConversationIdentity(scope, "stream:${shape.tag}|${shape.id}", IdentityConfidence.INFERRED_FROM_STREAM, streamKey, title)
        }

        // Untagged notifications with a distinct id per chat are still a stream boundary,
        // but id 0/1 is commonly reused for "the latest chat", so only trust ids that look assigned.
        if (shape.id > 1 && title != null) {
            return ConversationIdentity(scope, "stream:${shape.id}|$title", IdentityConfidence.INFERRED_FROM_STREAM, streamKey, title)
        }

        val key = title?.takeIf { it.isNotBlank() }?.let { "title:$it" } ?: "unknown:$streamKey"
        return ConversationIdentity(scope, key, IdentityConfidence.UNRESOLVED, streamKey, title)
    }

    /** The notification stream a window is kept under: scope + notification key (tag/id). */
    fun streamKey(snapshot: NotificationSnapshot): String = streamKey(snapshot.source, snapshot.shape.tag, snapshot.shape.id)

    fun streamKey(scope: SourceScope, tag: String?, id: Int): String = buildString {
        append(scope.key).append('#')
        if (tag != null) append("t:").append(tag).append('/')
        append("i:").append(id)
    }
}
