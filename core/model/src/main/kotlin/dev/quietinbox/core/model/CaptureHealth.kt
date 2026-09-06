package dev.quietinbox.core.model

/** Capture health — the first user-facing dimension. Connected never implies the source posts. */
enum class ListenerState {
    NOT_GRANTED,
    GRANTED_DISCONNECTED,
    CONNECTED,
    PAUSED,
    RECONNECTING,
    DEGRADED,
}

enum class GapReason {
    LISTENER_DISCONNECTED,
    PROCESS_RESTART,
    NOT_GRANTED,
    PAUSED_BY_USER,
    QUEUE_OVERFLOW,
    BEFORE_FIRST_UNLOCK,

    /** A reset or restore held the vault; events during that window were not captured (QI-SEC-003). */
    MAINTENANCE,
    UNKNOWN,
}

enum class GapPrecision { EXACT, BOUNDED, UNKNOWN }

/**
 * A possible capture gap. Either bound may be null when no reliable timestamp exists; the
 * pipeline never fabricates precise times.
 */
data class GapInterval(
    val id: Long,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val reason: GapReason,
    val precision: GapPrecision,
)

data class CaptureHealth(
    val listenerState: ListenerState,
    val connectedSinceEpochMs: Long?,
    val lastEventAtEpochMs: Long?,
    val queueDepth: Int,
    val overflowCount: Long,
    val acceptedCount: Long,
    val gaps: List<GapInterval>,
    val activeGeneration: String?,
)

data class SourceConfiguration(
    val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    val paused: Boolean,
    val retentionDays: Int?,
    val mediaEnabled: Boolean,
    val addedAtEpochMs: Long,
    val adapterId: String?,
)

/** Well-known sources with versioned adapters. Anything else goes through the standard parser. */
object KnownSources {
    const val LINE = "jp.naver.line.android"
    const val WHATSAPP = "com.whatsapp"
    const val TELEGRAM = "org.telegram.messenger"
    const val INSTAGRAM = "com.instagram.android"
    const val MESSENGER = "com.facebook.orca"

    val ALL: List<String> = listOf(LINE, WHATSAPP, TELEGRAM, INSTAGRAM, MESSENGER)
}
