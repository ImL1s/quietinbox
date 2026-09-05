package dev.quietinbox.platform.storage.repo

import androidx.room.withTransaction
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.GapPrecision
import dev.quietinbox.core.model.GapReason
import dev.quietinbox.core.model.IdentityConfidence
import dev.quietinbox.core.model.MediaReferenceCandidate
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.SearchNormalizer
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.reconcile.Fingerprint
import dev.quietinbox.platform.storage.db.CaptureSessionEntity
import dev.quietinbox.platform.storage.db.ConversationEntity
import dev.quietinbox.platform.storage.db.DatabaseHolder
import dev.quietinbox.platform.storage.db.DiagnosticEventEntity
import dev.quietinbox.platform.storage.db.GapIntervalEntity
import dev.quietinbox.platform.storage.db.MessageEntity
import dev.quietinbox.platform.storage.db.MessageRevisionEntity
import dev.quietinbox.platform.storage.db.ObservationLinkEntity
import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import dev.quietinbox.platform.storage.db.SearchTokenEntity
import dev.quietinbox.platform.storage.db.SourceConfigurationEntity
import dev.quietinbox.platform.storage.db.SummaryObservationEntity
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What [DemoDataRepository.seed] wrote, for the developer-facing confirmation text. */
data class DemoCounts(val conversations: Int, val messages: Int)

/**
 * Fills the vault with obviously synthetic demo content so the app can be demonstrated and
 * screenshotted without a single real notification.
 *
 * Everything it writes is tagged: sources and conversations use the `demo.quietinbox.` package
 * prefix, capture sessions use a `demo-` generation, and every event id starts with `demo-`.
 * [clear] deletes strictly by those tags, so captured data is never touched. Rows are shaped the
 * way `IngestRepository.commit` shapes them — same fingerprint function, same sort-key rule, same
 * search tokenisation — so the demo exercises the real read paths rather than a parallel one.
 *
 * Debug affordance only: nothing in the release UI reaches it. Every name, group and message body
 * is invented; no real person, brand or application appears anywhere in the seeded data.
 */
@Singleton
class DemoDataRepository @Inject constructor(
    private val holder: DatabaseHolder,
) {

    /**
     * Replaces any previous demo content with a fresh set. Idempotent: it clears first, so calling
     * it twice leaves the same rows rather than duplicates. [now] is the "present" the data is laid
     * out behind; it is a parameter so tests get a deterministic window.
     */
    suspend fun seed(now: Long = System.currentTimeMillis()): DemoCounts {
        val db = holder.db()
        return db.withTransaction {
            clearRows(db)
            val zone = ZoneId.systemDefault()
            val random = Random(RANDOM_SEED)

            for (source in SOURCES) {
                db.sourceDao().upsert(
                    SourceConfigurationEntity(
                        packageName = source.packageName,
                        displayName = source.displayName,
                        enabled = true,
                        paused = false,
                        retentionDays = null,
                        mediaEnabled = true,
                        addedAtEpochMs = now - 30L * DAY_MS,
                        adapterId = null,
                    ),
                )
            }

            var messages = 0
            for (spec in CONVERSATIONS) messages += seedConversation(db, spec, now, zone, random)
            seedCaptureHealth(db, now)

            DemoCounts(CONVERSATIONS.size, messages)
        }
    }

    /** Removes every row this seeder can have produced, and nothing else. */
    suspend fun clear() {
        val db = holder.db()
        db.withTransaction { clearRows(db) }
    }

    // ----------------------------------------------------------------- clearing

    private suspend fun clearRows(db: QuietInboxDatabase) {
        val dao = db.demoDao()
        // media_blob has no cascade from message, so it goes first, through the conversation join.
        dao.deleteMediaBlobs(PACKAGE_LIKE)
        dao.deleteSuppression(PACKAGE_LIKE)
        dao.deleteCheckpoints(PACKAGE_LIKE)
        // Cascades to message → message_revision, observation_link, search_token.
        dao.deleteConversations(PACKAGE_LIKE)
        dao.deleteSources(PACKAGE_LIKE)
        dao.deleteDiagnostics(PACKAGE_LIKE)
        dao.deleteSummaries(PACKAGE_LIKE)
        // Gaps are stamped with the demo session start, so they must go before the sessions.
        dao.deleteGaps(GENERATION_LIKE)
        dao.deleteSessions(GENERATION_LIKE)
    }

    // ------------------------------------------------------------- conversations

    private suspend fun seedConversation(
        db: QuietInboxDatabase,
        spec: ConversationSpec,
        now: Long,
        zone: ZoneId,
        random: Random,
    ): Int {
        val rows = ArrayList<PendingMessage>(spec.messageCount + 4)
        repeat(spec.messageCount) { index -> rows += bulkMessage(spec, index, now, zone, random) }
        rows += spec.extras(spec, rows.size, now)
        rows.sortBy { it.sortKey }

        val conversationId = db.conversationDao().insert(
            ConversationEntity(
                packageName = spec.packageName,
                profileKey = PROFILE_KEY,
                accountKey = null,
                identityKey = spec.identityKey,
                identityConfidence = spec.confidence.name,
                title = spec.title,
                isGroup = spec.isGroup,
                pinned = spec.pinned,
                archived = spec.archived,
                createdAtEpochMs = rows.first().sortKey,
                lastActivityEpochMs = rows.last().sortKey,
                lastViewedEpochMs = null,
                messageCount = 0,
                ambiguousCount = 0,
                summaryOnlyCount = 0,
                lastMessagePreview = null,
                lastSenderName = null,
            ),
        )

        val idsByEventId = HashMap<String, Long>(rows.size)
        val ids = ArrayList<Long>(rows.size)
        for (row in rows) {
            val id = insertMessage(db, conversationId, row)
            ids += id
            idsByEventId[row.eventId] = id
        }

        // Links and revisions need ids from the pass above, hence the second loop.
        for ((index, row) in rows.withIndex()) {
            row.ambiguousLinkToEventId?.let { targetEventId ->
                // The link hangs on the *earlier* identical message and carries the repeat's own
                // event id, exactly as IngestRepository.commit records an AMBIGUOUS_REPEAT.
                idsByEventId[targetEventId]?.let { targetId ->
                    db.observationLinkDao().insert(
                        ObservationLinkEntity(
                            messageId = targetId,
                            eventId = row.eventId,
                            kind = DedupState.AMBIGUOUS_REPEAT.name,
                            observedAtEpochMs = row.observedAt,
                        ),
                    )
                }
            }
            row.previousBody?.let { previous ->
                db.revisionDao().insert(
                    MessageRevisionEntity(
                        messageId = ids[index],
                        body = previous,
                        observedAtEpochMs = row.observedAt - 4L * 60_000,
                        eventId = row.eventId + "-original",
                    ),
                )
            }
        }

        val ambiguous = rows.count { it.dedupState == DedupState.AMBIGUOUS_REPEAT }
        val last = rows.last()
        db.conversationDao().get(conversationId)?.let { current ->
            db.conversationDao().update(
                current.copy(
                    // Matches the arithmetic InboxRepository.deleteMessages undoes: an ambiguous
                    // observation counts as ambiguous, never as a message.
                    messageCount = rows.size - ambiguous,
                    ambiguousCount = ambiguous,
                    lastMessagePreview = last.candidate.body.take(200),
                    lastSenderName = last.candidate.sender?.displayName,
                    lastViewedEpochMs = if (spec.unread) null else last.sortKey + 60_000,
                ),
            )
        }
        return rows.size
    }

    private suspend fun insertMessage(db: QuietInboxDatabase, conversationId: Long, row: PendingMessage): Long {
        val candidate = row.candidate
        val media = candidate.media
        val id = db.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                sourceMessageId = candidate.sourceMessageId,
                senderName = candidate.sender?.displayName,
                senderKey = candidate.sender?.senderKey,
                isSelf = candidate.sender?.isSelf ?: false,
                body = candidate.body,
                kind = candidate.kind.name,
                sourceTimestampEpochMs = candidate.sourceTimestampEpochMs,
                timestampQuality = candidate.timestampQuality.name,
                observedAtEpochMs = row.observedAt,
                postedAtEpochMs = row.postedAt,
                origin = row.origin.name,
                contentStatus = candidate.contentStatus.name,
                dedupState = row.dedupState.name,
                revisionCount = if (row.previousBody != null) 1 else 0,
                observationCount = 1,
                // Mirrors IngestRepository.commit: a media reference with no usable URI and no
                // notification bitmap can only ever have been a placeholder.
                mediaState = when {
                    media == null -> MediaState.NONE.name
                    media.uri == null && !media.fromNotificationBitmap -> MediaState.PLACEHOLDER_ONLY.name
                    else -> MediaState.PENDING.name
                },
                mediaBlobId = null,
                mediaUri = media?.uri,
                mediaMimeType = media?.mimeType,
                fingerprint = Fingerprint.of(candidate),
                eventId = row.eventId,
                sortKey = row.sortKey,
                expiresAtEpochMs = null,
            ),
        )
        indexTokens(db, id, candidate.body)
        return id
    }

    /** The indexing `IngestRepository.indexTokens` does, step for step, so search behaves the same. */
    private suspend fun indexTokens(db: QuietInboxDatabase, messageId: Long, body: String) {
        val tokens = SearchNormalizer.tokens(SearchNormalizer.normalize(body))
        if (tokens.isEmpty()) return
        db.searchDao().insertTokens(tokens.map { SearchTokenEntity(it, messageId) })
    }

    // ------------------------------------------------------------- capture health

    private suspend fun seedCaptureHealth(db: QuietInboxDatabase, now: Long) {
        val sessionStart = now - 30L * DAY_MS
        // Deliberately a *closed* session. HealthRepository.startSession reads any other row with
        // endedAtEpochMs = NULL as evidence that the process died: it force-ends the row and records
        // a real PROCESS_RESTART gap that this seeder has no way to clean up again. "Connected" in
        // the UI comes from the live listener, not from these rows, so closing it costs nothing.
        db.healthDao().insertSession(
            CaptureSessionEntity(
                generation = GENERATION,
                bootSessionId = "demo-boot-0001",
                startedAtEpochMs = sessionStart,
                endedAtEpochMs = now - 6L * HOUR_MS,
                endReason = GapReason.LISTENER_DISCONNECTED.name,
            ),
        )
        // createdAtEpochMs = the demo session start is the only tag a gap row can carry: reason and
        // precision are both read back through enums, so an invented value would render "unknown".
        db.healthDao().insertGap(
            GapIntervalEntity(
                startEpochMs = now - 5L * DAY_MS - 3L * HOUR_MS,
                endEpochMs = now - 5L * DAY_MS,
                reason = GapReason.LISTENER_DISCONNECTED.name,
                precision = GapPrecision.EXACT.name,
                createdAtEpochMs = sessionStart,
            ),
        )
        db.healthDao().insertGap(
            GapIntervalEntity(
                startEpochMs = now - 12L * DAY_MS,
                endEpochMs = now - 12L * DAY_MS + 95L * 60_000,
                reason = GapReason.PROCESS_RESTART.name,
                precision = GapPrecision.BOUNDED.name,
                createdAtEpochMs = sessionStart,
            ),
        )

        // Inside the seven-day window HealthViewModel asks DiagnosticsDao for.
        db.diagnosticsDao().insert(
            DiagnosticEventEntity(
                code = "PARSE_WARNINGS",
                detail = listOf(ParseWarning.NO_TIMESTAMP, ParseWarning.SENDER_SPLIT_HEURISTIC).joinToString(",") { it.name },
                packageName = SOURCE_CHAT,
                atEpochMs = now - 2L * DAY_MS,
            ),
        )
        db.diagnosticsDao().insert(
            DiagnosticEventEntity(
                code = "RECONCILE_DEGRADED",
                detail = null,
                packageName = SOURCE_TEAM,
                atEpochMs = now - 4L * DAY_MS,
            ),
        )
        db.diagnosticsDao().insert(
            DiagnosticEventEntity(
                code = "PARSE_WARNINGS",
                detail = ParseWarning.PREVIEW_PLACEHOLDER.name,
                packageName = SOURCE_FAMILY,
                atEpochMs = now - 6L * DAY_MS,
            ),
        )

        // Summary-only observations carry no body and belong to no conversation; they are what makes
        // the "summary only" counter on the Activity page non-zero.
        db.healthDao().insertSummary(
            SummaryObservationEntity(
                packageName = SOURCE_TEAM,
                observedAtEpochMs = now - 3L * DAY_MS,
                messageCount = 5,
                conversationCount = 2,
                eventId = "demo-summary-1",
            ),
        )
        db.healthDao().insertSummary(
            SummaryObservationEntity(
                packageName = SOURCE_CHAT,
                observedAtEpochMs = now - 9L * DAY_MS,
                messageCount = 3,
                conversationCount = 1,
                eventId = "demo-summary-2",
            ),
        )
    }

    // ---------------------------------------------------------------- generation

    private fun bulkMessage(
        spec: ConversationSpec,
        index: Int,
        now: Long,
        zone: ZoneId,
        random: Random,
    ): PendingMessage {
        val timestamp = timestampWithin(random, now, zone)
        val isSelf = random.nextInt(100) < spec.selfPercent
        val body = if (isSelf) SELF_BODIES[index % SELF_BODIES.size] else spec.bodies[index % spec.bodies.size]
        val sender = if (isSelf) SELF_NAME else spec.senders[index % spec.senders.size]
        val candidate = MessageCandidate(
            ordinal = index,
            body = body,
            sender = SenderCandidate(
                displayName = sender,
                // An unresolved conversation is unresolved precisely because no stable key existed.
                senderKey = if (spec.confidence == IdentityConfidence.UNRESOLVED) null else "demo-person-" + Integer.toHexString(sender.hashCode()),
                isSelf = isSelf,
            ),
            sourceMessageId = if (spec.verifiedIds) "demo-msg-${spec.slug}-$index" else null,
            sourceTimestampEpochMs = if (spec.quality == TimestampQuality.OBSERVED_ONLY) null else timestamp,
            timestampQuality = spec.quality,
            kind = MessageKind.TEXT,
            media = null,
            contentStatus = spec.contentStatus,
            isHistoric = false,
        )
        return pending(
            candidate = candidate,
            postedAt = timestamp,
            observedAt = timestamp + 900,
            dedupState = if (spec.verifiedIds) DedupState.CONFIRMED else DedupState.CANDIDATE,
            eventId = "demo-${spec.slug}-$index",
        )
    }

    /**
     * A timestamp inside the last 29 days with an evening-weighted hour, in the device time zone so
     * the Activity chart peaks where a reader expects it to. Bounded away from "now" so nothing
     * lands outside the range the Activity page queries.
     */
    private fun timestampWithin(random: Random, now: Long, zone: ZoneId): Long {
        val earliest = now - 29L * DAY_MS
        val latest = now - 10L * 60_000
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(12) {
            val candidate = today.minusDays(random.nextInt(0, 30).toLong())
                .atTime(weightedHour(random), random.nextInt(60), random.nextInt(60))
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            if (candidate in earliest..latest) return candidate
        }
        return earliest + random.nextLong(0, latest - earliest)
    }

    private fun weightedHour(random: Random): Int {
        var pick = random.nextInt(HOUR_WEIGHTS.sum())
        for (hour in HOUR_WEIGHTS.indices) {
            pick -= HOUR_WEIGHTS[hour]
            if (pick < 0) return hour
        }
        return 20
    }

    companion object {
        /** Every seeded package starts with this; [clear] deletes strictly by the prefix. */
        const val PACKAGE_PREFIX: String = "demo.quietinbox."
        const val PACKAGE_LIKE: String = "demo.quietinbox.%"

        /** Capture generation of the seeded session, and the tag its gaps are removed by. */
        const val GENERATION: String = "demo-seed"
        const val GENERATION_LIKE: String = "demo-%"

        const val SOURCE_CHAT: String = PACKAGE_PREFIX + "chat"
        const val SOURCE_TEAM: String = PACKAGE_PREFIX + "team"
        const val SOURCE_FAMILY: String = PACKAGE_PREFIX + "family"

        /** An ASCII word present in several bodies, so `adb shell input text` can drive a search. */
        const val SEARCH_SAMPLE: String = "meeting"

        internal const val PROFILE_KEY = "user:0"
        private const val RANDOM_SEED = 20260906L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val SELF_NAME = "我"

        /** Evening-weighted, so the hour-of-day chart has a shape rather than a flat line. */
        private val HOUR_WEIGHTS = intArrayOf(
            1, 1, 1, 1, 1, 1,
            2, 4, 6, 6, 6, 5,
            7, 5, 5, 5, 6, 8,
            12, 14, 16, 15, 10, 4,
        )

        private val SELF_BODIES = listOf(
            "好，我等一下回你 👍",
            "On my way, give me ten minutes.",
            "收到，晚點再確認一次。",
            "Sounds good — let's keep the meeting short.",
            "我先去吃飯，回來再看 🍜",
            "Agreed. I'll write it down tonight.",
            "已經處理好了，你再看看。",
            "Thanks! That helps a lot 🙏",
        )

        private val SOURCES = listOf(
            SourceSpec(SOURCE_CHAT, "Demo Chat"),
            SourceSpec(SOURCE_TEAM, "Demo Team"),
            SourceSpec(SOURCE_FAMILY, "Demo Family"),
        )

        private val CONVERSATIONS = listOf(
            ConversationSpec(
                slug = "mia",
                packageName = SOURCE_CHAT,
                identityKey = "chat:demo-mia",
                confidence = IdentityConfidence.VERIFIED_SOURCE_ID,
                title = "林小美 Mia Lin",
                isGroup = false,
                pinned = true,
                unread = true,
                verifiedIds = true,
                messageCount = 24,
                senders = listOf("林小美 Mia Lin"),
                bodies = listOf(
                    "早安！今天的 meeting 改到下午三點了 ☕",
                    "I finished the draft last night — want to read it before the meeting?",
                    "剛剛路過那家新開的書店，週末一起去逛好嗎？📚",
                    "Quick question: is the demo still scheduled for Friday?",
                    "我把行事曆更新了，你那邊看得到嗎？",
                    "This article is worth ten minutes: https://example.invalid/demo/reading-list",
                    "晚餐想吃什麼？我這邊隨便都可以 🍲",
                    "Weather looks terrible tomorrow, maybe we move the walk to Sunday.",
                    "謝謝你今天幫我看那份文件，真的幫了大忙 🙏",
                    "還記得上次說的那個計畫嗎？我想我們可以先做一個小版本試試看，先不要一次做完，這樣比較容易調整方向，你覺得呢？",
                ),
            ),
            ConversationSpec(
                slug = "wen",
                packageName = SOURCE_CHAT,
                identityKey = "shortcut:demo-wen",
                confidence = IdentityConfidence.INFERRED_FROM_STREAM,
                title = "陳大文 Wen Chen",
                isGroup = false,
                quality = TimestampQuality.NOTIFICATION_WHEN,
                messageCount = 16,
                senders = listOf("陳大文 Wen Chen"),
                bodies = listOf(
                    "下班了嗎？路上小心 🚲",
                    "Did you get the package? It said delivered at 14:20.",
                    "我明天請假，有事打電話給我。",
                    "Let's sync before the meeting so we don't repeat ourselves.",
                    "那個檔案我放在共用資料夾了。",
                    "Photos from the trip are uploaded 📷",
                    "今天的雨真的很誇張 ☔",
                ),
                extras = { spec, index, now ->
                    // A source that hides previews: the stored body is the placeholder the
                    // notification actually carried, and the label says so rather than pretending.
                    listOf(
                        pending(
                            candidate = MessageCandidate(
                                ordinal = index,
                                body = "您有一則新訊息 You have a new message",
                                sender = SenderCandidate(displayName = spec.title, senderKey = null, isSelf = false),
                                sourceMessageId = null,
                                sourceTimestampEpochMs = null,
                                timestampQuality = TimestampQuality.OBSERVED_ONLY,
                                kind = MessageKind.TEXT,
                                media = null,
                                contentStatus = ContentStatus.PREVIEW_RESTRICTED_SUSPECTED,
                                isHistoric = false,
                            ),
                            postedAt = now - 2L * DAY_MS - 3L * HOUR_MS,
                            observedAt = now - 2L * DAY_MS - 3L * HOUR_MS + 1_100,
                            dedupState = DedupState.CANDIDATE,
                            eventId = "demo-${spec.slug}-preview-restricted",
                        ),
                    )
                },
            ),
            ConversationSpec(
                slug = "product",
                packageName = SOURCE_TEAM,
                identityKey = "chat:demo-product",
                confidence = IdentityConfidence.VERIFIED_SOURCE_ID,
                title = "產品團隊 Product Team",
                isGroup = true,
                unread = true,
                verifiedIds = true,
                selfPercent = 20,
                messageCount = 22,
                senders = listOf("王雅琪 Yaqi Wang", "Diego Ramos", "黃冠宇 Kuan-Yu Huang", "Priya Nair"),
                bodies = listOf(
                    "站立會議 standup 十分鐘後開始 ⏰",
                    "I pushed the fix; the flaky test is green on three runs now.",
                    "設計稿更新了，麻煩大家在明天前給回饋 🎨",
                    "Reminder: the retro meeting moved to Thursday 16:00.",
                    "這週的數字比上週好一點，但樣本還太小，先不要下結論。",
                    "Notes from the meeting are here: https://example.invalid/demo/notes/42",
                    "有人可以幫忙 review 這個小改動嗎？只有兩行 🙋",
                    "Shipping on Friday still feels tight. Let's cut the optional part.",
                    "我把待辦清單整理成三類：必須做、可以做、不做。第三類其實最重要，因為寫下來之後大家就不會一直重新討論它了。",
                    "Great work everyone 🎉",
                ),
                extras = { spec, index, now ->
                    // An edited message: the row carries the new body, the revision row the old one,
                    // which is what the conversation screen offers to show.
                    listOf(
                        pending(
                            candidate = MessageCandidate(
                                ordinal = index,
                                body = "更正：發布時間改成下週二 10:00（Correction: release moved to Tuesday 10:00）",
                                sender = SenderCandidate(displayName = "王雅琪 Yaqi Wang", senderKey = "demo-person-yaqi", isSelf = false),
                                sourceMessageId = "demo-msg-${spec.slug}-revised",
                                sourceTimestampEpochMs = now - DAY_MS - 5L * HOUR_MS,
                                timestampQuality = TimestampQuality.SOURCE_MESSAGE,
                                kind = MessageKind.TEXT,
                                media = null,
                                contentStatus = ContentStatus.FULL_STRUCTURED,
                                isHistoric = false,
                            ),
                            postedAt = now - DAY_MS - 5L * HOUR_MS,
                            observedAt = now - DAY_MS - 5L * HOUR_MS + 1_400,
                            dedupState = DedupState.CONFIRMED,
                            eventId = "demo-${spec.slug}-revision",
                            previousBody = "發布時間是下週一 10:00（Release is Monday 10:00）",
                        ),
                    )
                },
            ),
            ConversationSpec(
                slug = "design",
                packageName = SOURCE_TEAM,
                identityKey = "stream:demo-design|17",
                confidence = IdentityConfidence.INFERRED_FROM_STREAM,
                title = "設計評審 Design Review",
                isGroup = true,
                quality = TimestampQuality.NOTIFICATION_WHEN,
                contentStatus = ContentStatus.NOTIFICATION_TEXT,
                selfPercent = 15,
                messageCount = 12,
                senders = listOf("Ana Costa", "李哲宇 Che-Yu Li", "Noor Haddad"),
                bodies = listOf(
                    "第三版的間距看起來舒服多了 ✨",
                    "Can we try the darker surface for the empty state?",
                    "字級在小螢幕上還是有點擠。",
                    "I'll bring printouts to the meeting.",
                    "深色模式的對比通過了嗎？",
                    "Two options attached — I prefer the second one.",
                ),
            ),
            ConversationSpec(
                slug = "family",
                packageName = SOURCE_FAMILY,
                identityKey = "chat:demo-family",
                confidence = IdentityConfidence.VERIFIED_SOURCE_ID,
                title = "家人群組 Family",
                isGroup = true,
                verifiedIds = true,
                selfPercent = 25,
                messageCount = 20,
                senders = listOf("媽媽 Mom", "爸爸 Dad", "姊姊 Sis"),
                bodies = listOf(
                    "晚餐煮好了，記得回來吃 🍚",
                    "Grandma says hello. She's doing fine.",
                    "禮拜天要不要一起去公園走走？🌳",
                    "The car is booked for Saturday morning.",
                    "冰箱裡還有水果，記得吃 🍎",
                    "Don't forget to call your aunt on her birthday.",
                    "今天的夕陽好漂亮 🌇",
                    "我下週三會晚點回家，你們先吃。",
                ),
                extras = { spec, index, now ->
                    // A photo the source only announced and never handed over: placeholder, no bytes.
                    listOf(
                        pending(
                            candidate = MessageCandidate(
                                ordinal = index,
                                body = "[照片] Photo from the weekend 🌊",
                                sender = SenderCandidate(displayName = "姊姊 Sis", senderKey = "demo-person-sis", isSelf = false),
                                sourceMessageId = "demo-msg-${spec.slug}-photo",
                                sourceTimestampEpochMs = now - 3L * DAY_MS - 2L * HOUR_MS,
                                timestampQuality = TimestampQuality.SOURCE_MESSAGE,
                                kind = MessageKind.MEDIA,
                                media = MediaReferenceCandidate(mimeType = "image/jpeg", uri = null, fromNotificationBitmap = false),
                                contentStatus = ContentStatus.FULL_STRUCTURED,
                                isHistoric = false,
                            ),
                            postedAt = now - 3L * DAY_MS - 2L * HOUR_MS,
                            observedAt = now - 3L * DAY_MS - 2L * HOUR_MS + 1_600,
                            dedupState = DedupState.CONFIRMED,
                            eventId = "demo-${spec.slug}-media",
                        ),
                    )
                },
            ),
            ConversationSpec(
                slug = "landlord",
                packageName = SOURCE_FAMILY,
                identityKey = "title:房東 Landlord",
                confidence = IdentityConfidence.UNRESOLVED,
                title = "房東 Landlord",
                isGroup = false,
                quality = TimestampQuality.OBSERVED_ONLY,
                contentStatus = ContentStatus.NOTIFICATION_TEXT,
                selfPercent = 40,
                messageCount = 8,
                senders = listOf("房東 Landlord"),
                bodies = listOf(
                    "水電費這個月是 1,240 元，麻煩月底前繳。",
                    "The plumber can come Tuesday between 9 and 11.",
                    "樓下的垃圾車時間改了，晚上七點半。",
                    "I'll send the renewal document next week.",
                ),
                extras = { spec, index, now ->
                    // Two identical, id-less, time-less observations: exactly the case the product
                    // labels AMBIGUOUS_REPEAT instead of guessing which it was (plan §7.2).
                    val repeated = MessageCandidate(
                        ordinal = index,
                        body = "好",
                        sender = SenderCandidate(displayName = "房東 Landlord", senderKey = null, isSelf = false),
                        sourceMessageId = null,
                        sourceTimestampEpochMs = null,
                        timestampQuality = TimestampQuality.OBSERVED_ONLY,
                        kind = MessageKind.TEXT,
                        media = null,
                        contentStatus = ContentStatus.NOTIFICATION_TEXT,
                        isHistoric = false,
                    )
                    val firstPosted = now - 4L * DAY_MS - 7L * HOUR_MS
                    val secondPosted = firstPosted + 42L * 60_000
                    val firstEventId = "demo-${spec.slug}-repeat-a"
                    listOf(
                        pending(
                            candidate = repeated,
                            postedAt = firstPosted,
                            observedAt = firstPosted + 800,
                            dedupState = DedupState.CANDIDATE,
                            eventId = firstEventId,
                        ),
                        pending(
                            candidate = repeated,
                            postedAt = secondPosted,
                            observedAt = secondPosted + 800,
                            dedupState = DedupState.AMBIGUOUS_REPEAT,
                            eventId = "demo-${spec.slug}-repeat-b",
                            origin = CaptureOrigin.ACTIVE_RESYNC,
                            ambiguousLinkToEventId = firstEventId,
                        ),
                    )
                },
            ),
            ConversationSpec(
                slug = "bookclub",
                packageName = SOURCE_CHAT,
                identityKey = "shortcut:demo-bookclub",
                confidence = IdentityConfidence.INFERRED_FROM_STREAM,
                title = "Book club 讀書會",
                isGroup = true,
                quality = TimestampQuality.NOTIFICATION_WHEN,
                selfPercent = 20,
                messageCount = 14,
                senders = listOf("Ines Moreau", "張書豪 Shu-Hao Chang", "Tomas Berg"),
                bodies = listOf(
                    "這個月選的書比想像中好看 📖",
                    "Next meeting is on the 18th, same place.",
                    "第三章的論點我不太同意，想聽聽大家怎麼想。",
                    "I found a cheaper edition here: https://example.invalid/demo/books/9",
                    "有人要順便帶咖啡嗎？☕",
                    "Reading pace feels right — 40 pages a week.",
                ),
            ),
            ConversationSpec(
                slug = "classmates",
                packageName = SOURCE_FAMILY,
                identityKey = "title:舊班級群組 Old classmates",
                confidence = IdentityConfidence.UNRESOLVED,
                title = "舊班級群組 Old classmates",
                isGroup = true,
                archived = true,
                quality = TimestampQuality.OBSERVED_ONLY,
                contentStatus = ContentStatus.NOTIFICATION_TEXT,
                selfPercent = 10,
                messageCount = 8,
                senders = listOf("班長 Class rep", "Marco Silva", "吳庭安 Ting-An Wu"),
                bodies = listOf(
                    "同學會的日期先訂在十一月 🎓",
                    "Anyone still in touch with the old teacher?",
                    "照片我掃描好了，晚點傳到群組。",
                    "Congratulations on the new job! 🎊",
                ),
            ),
        )
    }
}

/** One row on its way into the vault, before conversation ids exist. */
private class PendingMessage(
    val candidate: MessageCandidate,
    val observedAt: Long,
    val postedAt: Long?,
    val origin: CaptureOrigin,
    val dedupState: DedupState,
    val eventId: String,
    val sortKey: Long,
    /** Non-null makes this a revised message: the value is the body from before the edit. */
    val previousBody: String?,
    /** Event id of the earlier identical message this repeat is linked back to. */
    val ambiguousLinkToEventId: String?,
)

/**
 * Builds a row the way `IngestRepository` does: the sort key is the source time when one is
 * trusted, else the post time, else the observation time.
 */
private fun pending(
    candidate: MessageCandidate,
    postedAt: Long?,
    observedAt: Long,
    dedupState: DedupState,
    eventId: String,
    origin: CaptureOrigin = CaptureOrigin.LIVE,
    previousBody: String? = null,
    ambiguousLinkToEventId: String? = null,
): PendingMessage {
    val sortKey = when {
        candidate.sourceTimestampEpochMs != null && candidate.timestampQuality != TimestampQuality.OBSERVED_ONLY ->
            candidate.sourceTimestampEpochMs!!
        postedAt != null -> postedAt
        else -> observedAt
    }
    return PendingMessage(candidate, observedAt, postedAt, origin, dedupState, eventId, sortKey, previousBody, ambiguousLinkToEventId)
}

private class SourceSpec(val packageName: String, val displayName: String)

private class ConversationSpec(
    val slug: String,
    val packageName: String,
    val identityKey: String,
    val confidence: IdentityConfidence,
    val title: String,
    val isGroup: Boolean,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val unread: Boolean = false,
    val verifiedIds: Boolean = false,
    val quality: TimestampQuality = TimestampQuality.SOURCE_MESSAGE,
    val contentStatus: ContentStatus = ContentStatus.FULL_STRUCTURED,
    val selfPercent: Int = 30,
    val messageCount: Int,
    val senders: List<String>,
    val bodies: List<String>,
    /** Hand-placed rows that make one specific quality label appear in the UI. */
    val extras: (ConversationSpec, Int, Long) -> List<PendingMessage> = { _, _, _ -> emptyList() },
)
