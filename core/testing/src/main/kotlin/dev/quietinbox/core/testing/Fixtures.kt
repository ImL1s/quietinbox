package dev.quietinbox.core.testing

import dev.quietinbox.core.model.ActionShape
import dev.quietinbox.core.model.BoundedText
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessagingMessageShape
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.SourceScope

/**
 * Synthetic fixture DSL. Everything produced here is invented for tests; no real chat content
 * or reverse-engineered rule assets are used anywhere in the repository.
 */
object Fixtures {
    const val PROFILE = "user:0"
    const val BOOT = "boot-fixture"

    fun scope(packageName: String = KnownSources.LINE, accountKey: String? = null) =
        SourceScope(packageName, PROFILE, accountKey)

    fun snapshot(
        shape: NotificationShape,
        packageName: String = KnownSources.LINE,
        eventId: String = "evt-1",
        notificationKey: String = "0|$packageName|${shape.id}|${shape.tag}|10001",
        generation: String = "gen-1",
        observedAt: Long = 1_700_000_000_000L,
        postedAt: Long? = observedAt - 50,
        origin: CaptureOrigin = CaptureOrigin.LIVE,
        accountKey: String? = null,
    ): NotificationSnapshot = NotificationSnapshot(
        eventId = eventId,
        source = scope(packageName, accountKey),
        notificationKey = notificationKey,
        notificationGeneration = generation,
        observedAtEpochMs = observedAt,
        elapsedRealtimeMs = 1_000L,
        bootSessionId = BOOT,
        postedAtEpochMs = postedAt,
        origin = origin,
        shape = shape,
    )

    fun messaging(
        conversationTitle: String? = null,
        isGroup: Boolean? = null,
        shortcutId: String? = null,
        tag: String? = null,
        id: Int = 1,
        title: String? = conversationTitle,
        text: String? = null,
        block: MessagingBuilder.() -> Unit,
    ): NotificationShape {
        val b = MessagingBuilder().apply(block)
        return NotificationShape(
            id = id,
            tag = tag,
            template = NotificationTemplate.MESSAGING,
            title = BoundedText.of(title),
            text = BoundedText.of(text ?: b.messages.lastOrNull()?.text?.value),
            conversationTitle = BoundedText.of(conversationTitle),
            isGroupConversation = isGroup,
            shortcutId = shortcutId,
            messages = b.messages,
            historicMessages = b.historic,
            selfDisplayName = BoundedText.of(b.selfName),
        )
    }

    fun bigText(
        title: String,
        text: String,
        bigText: String? = null,
        tag: String? = null,
        id: Int = 1,
        subText: String? = null,
        groupKey: String? = null,
        shortcutId: String? = null,
    ): NotificationShape = NotificationShape(
        id = id,
        tag = tag,
        template = NotificationTemplate.BIG_TEXT,
        title = BoundedText.of(title),
        text = BoundedText.of(text),
        bigText = BoundedText.of(bigText ?: text),
        subText = BoundedText.of(subText),
        groupKey = groupKey,
        shortcutId = shortcutId,
    )

    fun inbox(
        title: String,
        lines: List<String>,
        text: String? = lines.lastOrNull(),
        summaryText: String? = null,
        tag: String? = null,
        id: Int = 1,
        isGroupSummary: Boolean = false,
        groupKey: String? = null,
    ): NotificationShape = NotificationShape(
        id = id,
        tag = tag,
        template = NotificationTemplate.INBOX,
        title = BoundedText.of(title),
        text = BoundedText.of(text),
        summaryText = BoundedText.of(summaryText),
        textLines = lines.mapNotNull { BoundedText.of(it) },
        isGroupSummary = isGroupSummary,
        groupKey = groupKey,
    )

    fun summary(
        title: String?,
        text: String?,
        groupKey: String = "g1",
        id: Int = 0,
    ): NotificationShape = NotificationShape(
        id = id,
        template = NotificationTemplate.BASE,
        title = BoundedText.of(title),
        text = BoundedText.of(text),
        groupKey = groupKey,
        isGroupSummary = true,
    )

    fun base(
        title: String?,
        text: String?,
        tag: String? = null,
        id: Int = 1,
        actions: List<ActionShape> = emptyList(),
        category: String? = null,
        isOngoing: Boolean = false,
    ): NotificationShape = NotificationShape(
        id = id,
        tag = tag,
        template = NotificationTemplate.BASE,
        title = BoundedText.of(title),
        text = BoundedText.of(text),
        actions = actions,
        category = category,
        isOngoing = isOngoing,
    )

    class MessagingBuilder {
        internal val messages = ArrayList<MessagingMessageShape>()
        internal val historic = ArrayList<MessagingMessageShape>()
        var selfName: String? = null

        fun message(
            sender: String?,
            text: String?,
            timestamp: Long? = null,
            senderKey: String? = null,
            isSelf: Boolean = false,
            mimeType: String? = null,
            dataUri: String? = null,
        ) {
            messages += MessagingMessageShape(
                text = BoundedText.of(text),
                timestampEpochMs = timestamp,
                senderName = BoundedText.of(sender),
                senderKey = senderKey,
                isSelf = isSelf,
                dataMimeType = mimeType,
                dataUri = dataUri,
            )
        }

        fun historic(sender: String?, text: String?, timestamp: Long? = null) {
            historic += MessagingMessageShape(
                text = BoundedText.of(text),
                timestampEpochMs = timestamp,
                senderName = BoundedText.of(sender),
            )
        }
    }
}
