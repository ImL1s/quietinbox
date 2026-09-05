package dev.quietinbox.platform.storage.repo

import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.Conversation
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.GapInterval
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.MessageRevision
import dev.quietinbox.core.model.SourceConfiguration
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.GapIntervalEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity

internal inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    scope = SourceScope(packageName, profileKey, accountKey),
    identityKey = identityKey,
    identityConfidence = identityConfidence.toEnumOr(IdentityConfidence.UNRESOLVED),
    title = title,
    isGroup = isGroup,
    pinned = pinned,
    archived = archived,
    lastActivityEpochMs = lastActivityEpochMs,
    lastViewedEpochMs = lastViewedEpochMs,
    messageCount = messageCount,
    ambiguousCount = ambiguousCount,
    summaryOnlyCount = summaryOnlyCount,
    lastMessagePreview = lastMessagePreview,
    lastSenderName = lastSenderName,
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    sourceMessageId = sourceMessageId,
    senderName = senderName,
    senderKey = senderKey,
    isSelf = isSelf,
    body = body,
    kind = kind.toEnumOr(MessageKind.UNKNOWN),
    sourceTimestampEpochMs = sourceTimestampEpochMs,
    timestampQuality = timestampQuality.toEnumOr(TimestampQuality.OBSERVED_ONLY),
    observedAtEpochMs = observedAtEpochMs,
    postedAtEpochMs = postedAtEpochMs,
    origin = origin.toEnumOr(CaptureOrigin.LIVE),
    contentStatus = contentStatus.toEnumOr(ContentStatus.UNKNOWN_FORMAT),
    dedupState = dedupState.toEnumOr(DedupState.CANDIDATE),
    revisionCount = revisionCount,
    observationCount = observationCount,
    mediaState = mediaState.toEnumOr(MediaState.NONE),
    mediaBlobId = mediaBlobId,
    sortKey = sortKey,
)

fun MessageRevisionEntity.toDomain(): MessageRevision = MessageRevision(id, messageId, body, observedAtEpochMs)

fun SourceConfigurationEntity.toDomain(): SourceConfiguration = SourceConfiguration(
    packageName = packageName,
    displayName = displayName,
    enabled = enabled,
    paused = paused,
    retentionDays = retentionDays,
    mediaEnabled = mediaEnabled,
    addedAtEpochMs = addedAtEpochMs,
    adapterId = adapterId,
)

fun GapIntervalEntity.toDomain(): GapInterval = GapInterval(
    id = id,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    reason = reason.toEnumOr(GapReason.UNKNOWN),
    precision = precision.toEnumOr(GapPrecision.UNKNOWN),
)
