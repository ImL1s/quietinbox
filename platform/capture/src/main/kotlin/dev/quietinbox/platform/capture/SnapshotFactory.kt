package dev.quietinbox.platform.capture

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import dev.quietinbox.core.model.ActionShape
import dev.quietinbox.core.model.BoundedText
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.Limits
import dev.quietinbox.core.model.MessagingMessageShape
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.NotificationTemplate
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.core.model.TruncationFlag
import java.util.UUID

/** A snapshot plus the one non-serialisable thing we keep briefly: a notification bitmap. */
class CapturedNotification(
    val snapshot: NotificationSnapshot,
    val bitmap: Bitmap?,
)

/**
 * Converts a `StatusBarNotification` into an allow-listed, size-bounded [NotificationSnapshot].
 * Runs on the listener callback thread, so it only *reads* extras: no decoding, no inflation,
 * no reflection, no `RemoteViews`, no `PendingIntent` (plan section 5).
 */
class SnapshotFactory(
    private val bootSessionId: String,
) {
    private companion object {
        const val MAX_BITMAP_BYTES = 4 * 1024 * 1024
    }

    fun create(sbn: StatusBarNotification, origin: CaptureOrigin, generation: String, nowEpochMs: Long): CapturedNotification {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle.EMPTY
        val truncated = LinkedHashSet<TruncationFlag>()

        fun text(key: String, flag: TruncationFlag): BoundedText? {
            val cs = runCatching { extras.getCharSequence(key) }.getOrNull() ?: return null
            val b = BoundedText.of(cs) ?: return null
            if (b.truncated) truncated += flag
            return b
        }

        val lines: List<BoundedText> = runCatching { extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) }.getOrNull()
            ?.let { arr ->
                if (arr.size > Limits.MAX_TEXT_LINES) truncated += TruncationFlag.LINES
                arr.takeLast(Limits.MAX_TEXT_LINES).mapNotNull { BoundedText.of(it) }
            }.orEmpty()

        val messaging = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()

        // MessagingStyle semantics: a message without a Person, or whose Person is the style's user,
        // was sent by the device owner. A stable key / uri on either side decides; a display name is
        // only compared when neither side carries one (two contacts may share a name). Erring towards
        // "not self" is the deliberate failure mode: a contact's message must never be shown as mine.
        val self = messaging?.user
        val selfName = self?.name?.toString()
        val messages = messaging?.messages?.let { bound(it, TruncationFlag.MESSAGES, truncated, self, selfName) }.orEmpty()
        val historic = messaging?.historicMessages?.let { bound(it, TruncationFlag.HISTORIC_MESSAGES, truncated, self, selfName) }.orEmpty()

        val actions = n.actions?.toList()?.let { list ->
            if (list.size > Limits.MAX_ACTIONS) truncated += TruncationFlag.ACTIONS
            list.take(Limits.MAX_ACTIONS).map { a ->
                ActionShape(
                    title = BoundedText.of(a.title, 256),
                    hasRemoteInput = !a.remoteInputs.isNullOrEmpty(),
                    semanticAction = if (Build.VERSION.SDK_INT >= 28) a.semanticAction else 0,
                )
            }
        }.orEmpty()

        val keys = extras.keySet().sorted().let { ks ->
            if (ks.size > Limits.MAX_EXTRA_KEYS) truncated += TruncationFlag.EXTRAS
            ks.take(Limits.MAX_EXTRA_KEYS).map { it.take(Limits.MAX_KEY_CHARS) }
        }

        val pictureUri = pictureUri(extras)?.let { uri ->
            if (uri.length > Limits.MAX_URI_CHARS) {
                truncated += TruncationFlag.URI
                null
            } else {
                uri
            }
        }
        // A referenced Bitmap is kept only when small enough to sit in the queue safely (no decode here).
        val bitmap = if (pictureUri == null) {
            runCatching { extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap }.getOrNull()?.takeIf { it.byteCount <= MAX_BITMAP_BYTES }
        } else {
            null
        }

        val shape = NotificationShape(
            id = sbn.id,
            tag = sbn.tag?.take(Limits.MAX_KEY_CHARS),
            channelId = if (Build.VERSION.SDK_INT >= 26) n.channelId?.take(Limits.MAX_KEY_CHARS) else null,
            category = n.category?.take(64),
            groupKey = n.group?.take(Limits.MAX_KEY_CHARS),
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            shortcutId = n.shortcutId?.take(Limits.MAX_KEY_CHARS),
            template = template(extras.getString(Notification.EXTRA_TEMPLATE)),
            title = text(Notification.EXTRA_TITLE, TruncationFlag.TITLE),
            titleBig = text(Notification.EXTRA_TITLE_BIG, TruncationFlag.TITLE),
            text = text(Notification.EXTRA_TEXT, TruncationFlag.TEXT),
            bigText = text(Notification.EXTRA_BIG_TEXT, TruncationFlag.BIG_TEXT),
            subText = text(Notification.EXTRA_SUB_TEXT, TruncationFlag.TEXT),
            summaryText = text(Notification.EXTRA_SUMMARY_TEXT, TruncationFlag.TEXT),
            infoText = text(Notification.EXTRA_INFO_TEXT, TruncationFlag.TEXT),
            textLines = lines,
            conversationTitle = messaging?.conversationTitle?.let { BoundedText.of(it) } ?: text(Notification.EXTRA_CONVERSATION_TITLE, TruncationFlag.TITLE),
            isGroupConversation = messaging?.isGroupConversation ?: extras.takeIf { it.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION) }?.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION),
            selfDisplayName = messaging?.user?.name?.let { BoundedText.of(it, 256) },
            messages = messages,
            historicMessages = historic,
            hasLargeIcon = extras.containsKey(Notification.EXTRA_LARGE_ICON) && runCatching { extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON) }.getOrNull() != null,
            hasPicture = bitmap != null || pictureUri != null,
            pictureUri = pictureUri,
            actions = actions,
            extraKeys = keys,
            whenEpochMs = n.`when`.takeIf { it > 0 },
            visibility = n.visibility,
            isOngoing = sbn.isOngoing,
            isLocalOnly = (n.flags and Notification.FLAG_LOCAL_ONLY) != 0,
            truncated = truncated,
        )

        val snapshot = NotificationSnapshot(
            eventId = UUID.randomUUID().toString(),
            source = SourceScope(packageName = sbn.packageName, profileKey = "user:${sbn.user.hashCode()}", accountKey = null),
            notificationKey = sbn.key,
            notificationGeneration = generation,
            observedAtEpochMs = nowEpochMs,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            bootSessionId = bootSessionId,
            postedAtEpochMs = sbn.postTime.takeIf { it > 0 },
            origin = origin,
            shape = shape,
        )
        return CapturedNotification(snapshot, bitmap)
    }

    private fun bound(list: List<NotificationCompat.MessagingStyle.Message>, flag: TruncationFlag, truncated: MutableSet<TruncationFlag>, self: androidx.core.app.Person?, selfName: String?): List<MessagingMessageShape> {
        if (list.size > Limits.MAX_MESSAGES) truncated += flag
        return list.takeLast(Limits.MAX_MESSAGES).map { m ->
            val person = m.person
            val text = BoundedText.of(m.text)
            if (text?.truncated == true) truncated += flag
            val uri = m.dataUri?.takeIf { it.scheme == "content" }?.toString()?.take(Limits.MAX_URI_CHARS)
            MessagingMessageShape(
                text = text,
                timestampEpochMs = m.timestamp.takeIf { it > 0 },
                senderName = person?.name?.let { BoundedText.of(it, 256) },
                senderKey = person?.key?.take(Limits.MAX_KEY_CHARS),
                senderUri = person?.uri?.take(Limits.MAX_URI_CHARS),
                isSelf = when {
                    person == null -> true
                    self?.key != null || person.key != null -> self?.key != null && person.key == self.key
                    self?.uri != null || person.uri != null -> self?.uri != null && person.uri == self.uri
                    else -> !selfName.isNullOrBlank() && person.name?.toString() == selfName
                },
                dataMimeType = m.dataMimeType?.take(64),
                dataUri = uri,
                isRemoteInputHistory = false,
            )
        }
    }

    private fun pictureUri(extras: Bundle): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val icon = runCatching { BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java) }.getOrNull() ?: return null
        if (icon.type != Icon.TYPE_URI && icon.type != Icon.TYPE_URI_ADAPTIVE_BITMAP) return null
        val uri = runCatching { icon.uri }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "content" }?.toString()
    }

    private fun template(name: String?): NotificationTemplate = when {
        name == null -> NotificationTemplate.BASE
        name.endsWith("MessagingStyle") -> NotificationTemplate.MESSAGING
        name.endsWith("BigTextStyle") -> NotificationTemplate.BIG_TEXT
        name.endsWith("InboxStyle") -> NotificationTemplate.INBOX
        name.endsWith("BigPictureStyle") -> NotificationTemplate.BIG_PICTURE
        name.endsWith("MediaStyle") -> NotificationTemplate.MEDIA
        name.endsWith("CallStyle") -> NotificationTemplate.CALL
        else -> NotificationTemplate.UNKNOWN
    }
}
