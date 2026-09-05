package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.Confidence
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.IdentityEvidence
import dev.quietinbox.core.model.IdentityEvidenceKind
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.ParsedBatch
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.SummaryObservation
import dev.quietinbox.core.parser.StandardParser
import dev.quietinbox.core.parser.TextHeuristics

/**
 * Shared skeleton for the per-app adapters.
 *
 * Everything here is built on public Android notification semantics that already exist in
 * [NotificationShape]. There is no reflection, no `RemoteViews`, no private extra and no rule
 * list taken from another product: every phrase below is an invented guess of common wording,
 * exercised only by synthetic fixtures (see `README.md`).
 *
 * The three candidate hooks are `final` on purpose: adapters extend behaviour through
 * [appSingleCandidates] and [postProcess] so app rules are applied exactly once.
 */
abstract class AppParser : StandardParser() {

    abstract override val id: String
    abstract override val packages: Set<String>
    override val version: String = "0.1.0"

    /** Templates this adapter models; anything else falls back to the standard behaviour. */
    protected open val supportedTemplates: Set<NotificationTemplate> = setOf(
        NotificationTemplate.MESSAGING,
        NotificationTemplate.BIG_TEXT,
        NotificationTemplate.INBOX,
        NotificationTemplate.BIG_PICTURE,
        NotificationTemplate.BASE,
    )

    /** Whole bodies the app uses when previews are hidden. Already folded by [foldForMatch]. */
    protected open val placeholderPhrases: Set<String> = emptySet()

    /** Body endings such as `sent you a message`, where only the sender name varies. */
    protected open val placeholderSuffixes: Set<String> = emptySet()

    /** Whole bodies/titles that are app status notices rather than messages. */
    protected open val noticePhrases: Set<String> = emptySet()

    /** Body/title prefixes that mark progress notices, e.g. `backing up`. */
    protected open val noticePrefixes: Set<String> = emptySet()

    final override fun parse(snapshot: NotificationSnapshot): ParsedBatch {
        val shape = snapshot.shape
        if (shape.isGroupSummary) return super.parse(snapshot)

        val structured = shape.messages.isNotEmpty() ||
            shape.historicMessages.isNotEmpty() ||
            shape.textLines.isNotEmpty()

        if (isSystemNotice(shape)) {
            // Structured items are never discarded: only a notice *without* items yields zero
            // messages; otherwise the batch is parsed normally and merely flagged.
            if (!structured) return noticeBatch(snapshot)
            val base = super.parse(snapshot)
            return base.copy(warnings = base.warnings + ParseWarning.POSSIBLE_SYSTEM_NOTICE)
        }

        if (!structured) {
            val body = pickBody(shape)
            val counts = TextHeuristics.parseSummary(body)
            if (body != null && counts != null) return summaryOnlyBatch(snapshot, body, counts)
        }

        if (shape.template !in supportedTemplates) {
            val base = super.parse(snapshot)
            return base.copy(warnings = base.warnings + ParseWarning.ADAPTER_FALLBACK_TO_STANDARD)
        }

        return super.parse(snapshot)
    }

    /** Ongoing/call notifications plus the adapter's own status wording. */
    protected open fun isSystemNotice(shape: NotificationShape): Boolean {
        if (shape.isOngoing) return true
        if (shape.category == "call") return true
        if (shape.template == NotificationTemplate.CALL) return true
        val body = pickBody(shape)
        if (body != null && (TextHeuristics.looksLikeSystemNotice(body) || matchesNotice(body))) return true
        val title = shape.title?.value
        return title != null && matchesNotice(title)
    }

    protected fun matchesNotice(text: String): Boolean {
        val t = foldForMatch(text)
        if (t.isEmpty()) return false
        return t in noticePhrases || noticePrefixes.any { t.startsWith(it) }
    }

    /** True when the body is one of *this app's* preview placeholders. */
    protected fun isAppPlaceholder(body: String?): Boolean {
        if (body == null) return false
        val t = foldForMatch(body)
        if (t.isEmpty()) return false
        return t in placeholderPhrases || placeholderSuffixes.any { t.endsWith(it) }
    }

    /** Trim, drop trailing sentence punctuation, case fold. Mirrors `TextHeuristics`. */
    protected fun foldForMatch(text: String): String =
        text.trim().trimEnd('.', '。', '…').trim().lowercase()

    final override fun singleCandidate(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = postProcess(appSingleCandidates(snapshot, warnings), snapshot.shape, warnings)

    final override fun inboxCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = postProcess(super.inboxCandidates(snapshot, warnings), snapshot.shape, warnings)

    final override fun messagingCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = postProcess(super.messagingCandidates(snapshot, warnings), snapshot.shape, warnings)

    /** The plain-text path, before [postProcess]. Adapters override this, not `singleCandidate`. */
    protected open fun appSingleCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = super.singleCandidate(snapshot, warnings)

    /** Applied to every candidate of every path. Bodies are classified, never rewritten. */
    protected open fun postProcess(
        candidates: List<MessageCandidate>,
        shape: NotificationShape,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> = candidates.map { candidate ->
        if (candidate.contentStatus != ContentStatus.PREVIEW_RESTRICTED_SUSPECTED && isAppPlaceholder(candidate.body)) {
            warnings += ParseWarning.PREVIEW_PLACEHOLDER
            candidate.copy(contentStatus = ContentStatus.PREVIEW_RESTRICTED_SUSPECTED)
        } else {
            candidate
        }
    }

    /**
     * Splits a one-item plain-text body of the form `Sender<sep>text` when the app does not set
     * `isGroupConversation` at all. A declared `false` is a known 1:1 chat and is left alone.
     */
    protected fun splitUnflaggedGroupBody(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
        split: (String) -> Pair<String, String>?,
    ): List<MessageCandidate> {
        val base = super.singleCandidate(snapshot, warnings)
        if (snapshot.shape.isGroupConversation != null || base.size != 1) return base
        val only = base[0]
        val parts = split(only.body) ?: return base
        warnings += ParseWarning.SENDER_SPLIT_HEURISTIC
        return listOf(only.copy(body = parts.second, sender = SenderCandidate(displayName = parts.first)))
    }

    private fun noticeBatch(snapshot: NotificationSnapshot): ParsedBatch {
        val shape = snapshot.shape
        val warnings = LinkedHashSet<ParseWarning>()
        if (shape.truncated.isNotEmpty()) warnings += ParseWarning.TRUNCATED_INPUT
        warnings += ParseWarning.POSSIBLE_SYSTEM_NOTICE
        val hasText = shape.title != null || shape.text != null || shape.bigText != null
        return ParsedBatch(
            conversation = null,
            messages = emptyList(),
            summary = null,
            contentStatus = if (hasText) ContentStatus.NOTIFICATION_TEXT else ContentStatus.EMPTY,
            identityEvidence = emptyList(),
            warnings = warnings,
            parserId = id,
            parserVersion = version,
        )
    }

    private fun summaryOnlyBatch(
        snapshot: NotificationSnapshot,
        body: String,
        counts: Pair<Int?, Int?>,
    ): ParsedBatch {
        val shape = snapshot.shape
        val warnings = LinkedHashSet<ParseWarning>()
        if (shape.truncated.isNotEmpty()) warnings += ParseWarning.TRUNCATED_INPUT
        val conversationCount = counts.second
        if (conversationCount != null && conversationCount > 1) {
            warnings += ParseWarning.MULTIPLE_CONVERSATIONS_SUSPECTED
        }
        val evidence = shape.groupKey
            ?.let { listOf(IdentityEvidence(IdentityEvidenceKind.GROUP_KEY, it, Confidence.LOW)) }
            .orEmpty()
        return ParsedBatch(
            conversation = null,
            messages = emptyList(),
            summary = SummaryObservation(
                text = body,
                messageCount = counts.first,
                conversationCount = conversationCount,
            ),
            contentStatus = ContentStatus.SUMMARY_ONLY,
            identityEvidence = evidence,
            warnings = warnings,
            parserId = id,
            parserVersion = version,
        )
    }
}
