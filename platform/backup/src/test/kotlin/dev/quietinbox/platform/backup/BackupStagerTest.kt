package dev.quietinbox.platform.backup

import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedReader

/**
 * Staging rules for a restore stream. Every line is produced with the real [BackupRecord]
 * serialisers so the fixtures cannot drift from the on-disk format, and the size limits are
 * injected small because the production bounds (16 MB per line, 2 M records, 256 MB of media)
 * cannot be allocated in a unit test.
 */
class BackupStagerTest : FunSpec({

    // Same configuration BackupService uses; a mismatch here would test a format nothing writes.
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    fun encode(r: BackupRecord): String = json.encodeToString(BackupRecord.serializer(), r)

    fun reader(vararg lines: String): BufferedReader =
        (if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")).reader().buffered()

    val format = BackupCrypto.FORMAT_VERSION.toInt()

    fun manifest(
        counts: Counts,
        formatVersion: Int = format,
        schemaVersion: Int = QuietInboxDatabase.VERSION,
    ) = BackupRecord.Manifest(formatVersion, schemaVersion, "1.0.0", 1_700_000_000_000L, counts)

    fun source(packageName: String = "com.example.chat", displayName: String = "Chat") =
        BackupRecord.Source(packageName, displayName, true, false, 30, true, 1_699_000_000_000L, "standard")

    fun conversation(id: Long = 1L) = BackupRecord.Conversation(
        id = id, packageName = "com.example.chat", profileKey = "user:0", accountKey = null,
        identityKey = "conv-$id", identityConfidence = "EXACT", title = "Ada", isGroup = false,
        pinned = false, archived = false, createdAtEpochMs = 1_700_000_000_000L,
        lastActivityEpochMs = 1_700_000_100_000L, lastViewedEpochMs = null,
    )

    fun message(id: Long, conversationId: Long = 1L) = BackupRecord.Message(
        id = id, conversationId = conversationId, sourceMessageId = "src-$id", senderName = "Ada",
        senderKey = "ada", isSelf = false, body = "hello $id", kind = "TEXT",
        sourceTimestampEpochMs = 1_700_000_000_000L + id, timestampQuality = "EXACT",
        observedAtEpochMs = 1_700_000_000_000L + id, postedAtEpochMs = 1_700_000_000_000L + id,
        origin = "LIVE", contentStatus = "FULL", dedupState = "NEW", revisionCount = 0,
        observationCount = 1, mediaState = "NONE", mediaBlobId = null, mediaMimeType = null,
        fingerprint = "fp-$id", sortKey = id, expiresAtEpochMs = null,
    )

    fun media(id: Long, bytes: ByteArray) = BackupRecord.Media(
        id = id, messageId = 1L, mimeType = "image/png", width = 1, height = 1,
        createdAtEpochMs = 1_700_000_000_000L,
        dataBase64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes),
    )

    /** manifest + 1 source + 1 conversation + 2 messages + end, all counts consistent. */
    val minimalCounts = Counts(sources = 1, conversations = 1, messages = 2, revisions = 0, media = 0)
    fun minimalStream(): Array<String> = arrayOf(
        encode(manifest(minimalCounts)),
        encode(source()),
        encode(conversation()),
        encode(message(1L)),
        encode(message(2L)),
        encode(BackupRecord.End(minimalCounts)),
    )

    test("a valid minimal stream stages every record") {
        val staged = BackupStager(json).stage(reader(*minimalStream()))

        staged.manifest.formatVersion shouldBe format
        staged.manifest.schemaVersion shouldBe QuietInboxDatabase.VERSION
        staged.manifest.expected shouldBe minimalCounts
        staged.sources.map { it.packageName } shouldBe listOf("com.example.chat")
        staged.conversations.map { it.id } shouldBe listOf(1L)
        staged.messages.map { it.id } shouldBe listOf(1L, 2L)
        staged.messages.map { it.body } shouldBe listOf("hello 1", "hello 2")
        staged.revisions shouldBe emptyList()
        staged.media shouldBe emptyList()
        staged.end.actual shouldBe minimalCounts
    }

    test("carriage returns in the stream are ignored") {
        val staged = BackupStager(json).stage(minimalStream().joinToString("\r\n", postfix = "\r\n").reader().buffered())
        staged.messages.map { it.id } shouldBe listOf(1L, 2L)
    }

    test("the media count in the manifest is advisory, unlike every other count") {
        // Export skips blobs it cannot decrypt, so the manifest may promise more media than the
        // end record carries; only the non-media counts have to match exactly.
        val counts = Counts(sources = 1, conversations = 0, messages = 0, revisions = 0, media = 0)
        val staged = BackupStager(json).stage(
            reader(
                encode(manifest(counts.copy(media = 7))),
                encode(source()),
                encode(BackupRecord.End(counts)),
            ),
        )
        staged.manifest.expected.media shouldBe 7
        staged.media shouldBe emptyList()
    }

    test("a stream that does not start with the manifest is rejected") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(reader(encode(source()), encode(manifest(Counts()))))
        }
        e.reason shouldBe BackupResult.Reason.BAD_HEADER
        e.message shouldBe "manifest must come first"
    }

    test("a second manifest is rejected") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(reader(encode(manifest(Counts())), encode(manifest(Counts()))))
        }
        e.reason shouldBe BackupResult.Reason.BAD_HEADER
        e.message shouldBe "duplicate manifest"
    }

    test("an unknown container format version is rejected") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(reader(encode(manifest(Counts(), formatVersion = format + 1))))
        }
        e.reason shouldBe BackupResult.Reason.UNSUPPORTED_VERSION
        e.message shouldBe "format ${format + 1} schema ${QuietInboxDatabase.VERSION}"
    }

    test("a schema newer than this build is rejected, an older one is not") {
        val newer = QuietInboxDatabase.VERSION + 1
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(reader(encode(manifest(Counts(), schemaVersion = newer))))
        }
        e.reason shouldBe BackupResult.Reason.UNSUPPORTED_VERSION
        e.message shouldBe "format $format schema $newer"

        val older = BackupStager(json).stage(
            reader(encode(manifest(Counts(), schemaVersion = 1)), encode(BackupRecord.End(Counts()))),
        )
        older.manifest.schemaVersion shouldBe 1
    }

    test("a record after the end record is rejected") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(
                reader(
                    encode(manifest(Counts())),
                    encode(BackupRecord.End(Counts())),
                    encode(source()),
                ),
            )
        }
        e.reason shouldBe BackupResult.Reason.COUNT_MISMATCH
        e.message shouldBe "data after end"
    }

    test("more records than the limit is rejected") {
        // maxRecords 2: the manifest and the source fit, the conversation is the third line.
        val e = shouldThrow<StagingException> {
            BackupStager(json, maxRecords = 2).stage(
                reader(encode(manifest(Counts())), encode(source()), encode(conversation())),
            )
        }
        e.reason shouldBe BackupResult.Reason.TOO_LARGE
        e.message shouldBe "records"
    }

    test("a line longer than the limit is rejected while it is still being read") {
        val manifestLine = encode(manifest(Counts()))
        val longLine = encode(source(displayName = "n".repeat(manifestLine.length)))
        // The limit is exactly the manifest's length, so the manifest passes at the boundary.
        val e = shouldThrow<StagingException> {
            BackupStager(json, maxLineChars = manifestLine.length).stage(reader(manifestLine, longLine))
        }
        e.reason shouldBe BackupResult.Reason.TOO_LARGE
        e.message shouldBe "line"
    }

    test("more staged text than the limit is rejected") {
        val manifestLine = encode(manifest(Counts()))
        val e = shouldThrow<StagingException> {
            BackupStager(json, maxStagedTextChars = manifestLine.length + 1L)
                .stage(reader(manifestLine, encode(source())))
        }
        e.reason shouldBe BackupResult.Reason.TOO_LARGE
        e.message shouldBe "text"
    }

    test("media bytes do not count towards the staged text limit") {
        // A media line far longer than the text budget must still stage: only non-media records
        // are held against MAX_STAGED_TEXT_CHARS.
        val manifestLine = encode(manifest(Counts(media = 1)))
        val endLine = encode(BackupRecord.End(Counts(media = 1)))
        val mediaLine = encode(media(1L, ByteArray(512) { it.toByte() }))
        val textBudget = manifestLine.length + endLine.length.toLong()
        check(mediaLine.length > textBudget) { "the media line must be the thing that would blow the budget" }
        val staged = BackupStager(json, maxStagedTextChars = textBudget).stage(
            reader(manifestLine, mediaLine, endLine),
        )
        staged.media.map { it.id } shouldBe listOf(1L)
    }

    test("more staged media bytes than the limit is rejected") {
        // 16 base64 characters decode to 12 bytes, over a 10-byte budget.
        val e = shouldThrow<StagingException> {
            BackupStager(json, maxStagedMediaBytes = 10L).stage(
                reader(encode(manifest(Counts(media = 1))), encode(media(1L, ByteArray(12) { it.toByte() }))),
            )
        }
        e.reason shouldBe BackupResult.Reason.TOO_LARGE
        e.message shouldBe "media"
    }

    test("a stream with no manifest at all is truncated") {
        val e = shouldThrow<StagingException> { BackupStager(json).stage(reader()) }
        e.reason shouldBe BackupResult.Reason.TRUNCATED
        e.message shouldBe "no manifest"
    }

    test("a blank line is a serialisation failure, not an early end of stream") {
        // readBoundedLine returns an empty string for a blank line and only null at EOF, so a
        // stray newline is reported as a corrupt record rather than silently ending the stream.
        shouldThrow<SerializationException> {
            BackupStager(json).stage("\n".reader().buffered())
        }
    }

    test("a stream that stops before the end record is truncated") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(reader(encode(manifest(minimalCounts)), encode(source())))
        }
        e.reason shouldBe BackupResult.Reason.TRUNCATED
        e.message shouldBe "no end record"
    }

    test("end counts that disagree with what was read are rejected") {
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(
                reader(
                    encode(manifest(minimalCounts)),
                    encode(source()),
                    encode(conversation()),
                    encode(message(1L)),
                    // One message short of what the end record claims.
                    encode(BackupRecord.End(minimalCounts)),
                ),
            )
        }
        e.reason shouldBe BackupResult.Reason.COUNT_MISMATCH
        e.message shouldBe "end counts"
    }

    test("manifest counts that disagree with the end record are rejected") {
        val actual = Counts(sources = 1, conversations = 0, messages = 0, revisions = 0, media = 0)
        val e = shouldThrow<StagingException> {
            BackupStager(json).stage(
                reader(
                    encode(manifest(actual.copy(conversations = 3))),
                    encode(source()),
                    encode(BackupRecord.End(actual)),
                ),
            )
        }
        e.reason shouldBe BackupResult.Reason.COUNT_MISMATCH
        e.message shouldBe "manifest counts"
    }

    test("an unknown record type is a serialisation failure, not a staging one") {
        // The one hand-written line in this spec: no serialiser can emit an unknown discriminator.
        shouldThrow<SerializationException> {
            BackupStager(json).stage(reader(encode(manifest(Counts())), """{"type":"nope"}"""))
        }
    }

    test("a truncated record line is a serialisation failure") {
        val half = encode(source()).dropLast(12)
        shouldThrow<SerializationException> {
            BackupStager(json).stage(reader(encode(manifest(Counts())), half))
        }
    }

    test("the production defaults are the BackupLimits constants") {
        // The limits are only injectable for tests; a wrong default would silently relax the
        // bounds that keep a hostile backup from exhausting the heap.
        val stager = BackupStager(json)
        stager.maxLineChars shouldBe BackupLimits.MAX_LINE_CHARS
        stager.maxRecords shouldBe BackupLimits.MAX_RECORDS
        stager.maxStagedMediaBytes shouldBe BackupLimits.MAX_STAGED_MEDIA_BYTES
        stager.maxStagedTextChars shouldBe BackupLimits.MAX_STAGED_TEXT_CHARS
    }
})
