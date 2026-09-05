package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.parser.TextHeuristics

/**
 * Instagram (`com.instagram.android`).
 *
 * Adds three things on top of the standard behaviour:
 *  - `<name> sent you a message` style bodies are treated as hidden previews;
 *  - a group thread body of the form `Alice: hey` is split when the prefix differs from the
 *    notification title (a 1:1 chat repeats the contact name in the title, so it is left alone);
 *  - reactions are kept verbatim but marked `MessageKind.SYSTEM` and flagged, because users do
 *    want to see them yet they are not chat messages.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class InstagramParser : AppParser() {
    override val id: String = "instagram"
    override val packages: Set<String> = setOf(KnownSources.INSTAGRAM)

    override val placeholderSuffixes: Set<String> = setOf(
        "sent you a message",
        "傳送了一則訊息給你",
        "向你傳送了一則訊息",
    )

    override fun appSingleCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> {
        val title = snapshot.shape.title?.value
        return splitUnflaggedGroupBody(snapshot, warnings) { body ->
            TextHeuristics.splitSenderPrefix(body)?.takeIf { it.first != title }
        }
    }

    override fun postProcess(
        candidates: List<MessageCandidate>,
        shape: NotificationShape,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = super.postProcess(candidates, shape, warnings).map { candidate ->
        if (isReaction(candidate.body)) {
            warnings += ParseWarning.POSSIBLE_SYSTEM_NOTICE
            candidate.copy(kind = MessageKind.SYSTEM)
        } else {
            candidate
        }
    }

    /** Deliberately narrow: a bare "reacted" is not enough, it must name the message. */
    private fun isReaction(body: String): Boolean {
        val t = foldForMatch(body)
        if (t.contains("liked your message") || t.contains("讚了你的訊息")) return true
        if (t.contains("reacted") && t.contains("your message")) return true
        return t.contains("回應") && t.contains("你的訊息")
    }
}
