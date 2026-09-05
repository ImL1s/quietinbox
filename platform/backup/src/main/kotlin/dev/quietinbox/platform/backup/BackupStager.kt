package dev.quietinbox.platform.backup

import dev.quietinbox.platform.storage.db.QuietInboxDatabase
import kotlinx.serialization.json.Json
import java.io.BufferedReader

/** Staging refused the stream; [reason] is reported verbatim as [BackupResult.Failed]. */
internal class StagingException(val reason: BackupResult.Reason, message: String? = null) : Exception(message)

/** Everything a restore holds in memory after the whole stream verified. */
internal class Staged(
    val manifest: BackupRecord.Manifest,
    val sources: List<BackupRecord.Source>,
    val conversations: List<BackupRecord.Conversation>,
    val messages: List<BackupRecord.Message>,
    val revisions: List<BackupRecord.Revision>,
    val media: List<BackupRecord.Media>,
    val end: BackupRecord.End,
)

/**
 * Reads the decrypted newline-delimited record stream and holds it until the manifest, the end
 * record and every count agree. Nothing here touches the vault: a rejected stream leaves the
 * existing data untouched by construction.
 *
 * The limits are constructor parameters only so unit tests can hit them without allocating the
 * production bounds (16 MB lines, 2 M records, 256 MB of media); production always uses the
 * [BackupLimits] defaults.
 */
internal class BackupStager(
    private val json: Json,
    internal val maxLineChars: Int = BackupLimits.MAX_LINE_CHARS,
    internal val maxRecords: Int = BackupLimits.MAX_RECORDS,
    internal val maxStagedMediaBytes: Long = BackupLimits.MAX_STAGED_MEDIA_BYTES,
    internal val maxStagedTextChars: Long = BackupLimits.MAX_STAGED_TEXT_CHARS,
) {
    fun stage(reader: BufferedReader): Staged {
        var manifest: BackupRecord.Manifest? = null
        val sources = ArrayList<BackupRecord.Source>()
        val conversations = ArrayList<BackupRecord.Conversation>()
        val messages = ArrayList<BackupRecord.Message>()
        val revisions = ArrayList<BackupRecord.Revision>()
        val media = ArrayList<BackupRecord.Media>()
        var end: BackupRecord.End? = null
        var records = 0
        var stagedMediaBytes = 0L
        var stagedTextChars = 0L
        while (true) {
            val line = readBoundedLine(reader, maxLineChars) ?: break
            if (end != null) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "data after end")
            if (++records > maxRecords) throw StagingException(BackupResult.Reason.TOO_LARGE, "records")
            val r = json.decodeFromString(BackupRecord.serializer(), line)
            if (r !is BackupRecord.Media) {
                // Staging holds every record until the counts check out; bound the total, not just each line.
                stagedTextChars += line.length
                if (stagedTextChars > maxStagedTextChars) throw StagingException(BackupResult.Reason.TOO_LARGE, "text")
            }
            when (r) {
                is BackupRecord.Manifest -> {
                    if (manifest != null) throw StagingException(BackupResult.Reason.BAD_HEADER, "duplicate manifest")
                    if (r.formatVersion != BackupCrypto.FORMAT_VERSION.toInt() || r.schemaVersion > QuietInboxDatabase.VERSION) {
                        throw StagingException(BackupResult.Reason.UNSUPPORTED_VERSION, "format ${r.formatVersion} schema ${r.schemaVersion}")
                    }
                    manifest = r
                }
                is BackupRecord.Source -> sources += r
                is BackupRecord.Conversation -> conversations += r
                is BackupRecord.Message -> messages += r
                is BackupRecord.Revision -> revisions += r
                is BackupRecord.Media -> {
                    stagedMediaBytes += r.dataBase64.length * 3L / 4
                    if (stagedMediaBytes > maxStagedMediaBytes) throw StagingException(BackupResult.Reason.TOO_LARGE, "media")
                    media += r
                }
                is BackupRecord.End -> end = r
            }
            if (manifest == null) throw StagingException(BackupResult.Reason.BAD_HEADER, "manifest must come first")
        }
        val m = manifest ?: throw StagingException(BackupResult.Reason.TRUNCATED, "no manifest")
        val e = end ?: throw StagingException(BackupResult.Reason.TRUNCATED, "no end record")
        val actual = Counts(sources.size, conversations.size, messages.size, revisions.size, media.size)
        if (actual != e.actual) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "end counts")
        if (m.expected.copy(media = actual.media) != actual) throw StagingException(BackupResult.Reason.COUNT_MISMATCH, "manifest counts")
        return Staged(m, sources, conversations, messages, revisions, media, e)
    }

    /** Reads one line but refuses to buffer more than [maxChars]; a bomb cannot exhaust the heap first. */
    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c == '\r'.code) continue
            sb.append(c.toChar())
            if (sb.length > maxChars) throw StagingException(BackupResult.Reason.TOO_LARGE, "line")
        }
    }
}
