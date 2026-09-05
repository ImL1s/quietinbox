package dev.quietinbox.core.model

/**
 * Resource limits applied to *untrusted* notification input before it reaches any parser.
 * Every limit that truncates must raise a [TruncationFlag] so the loss is observable.
 */
object Limits {
    const val MAX_TEXT_CHARS: Int = 4_096
    const val MAX_TEXT_LINES: Int = 32
    const val MAX_MESSAGES: Int = 64
    const val MAX_ACTIONS: Int = 8
    const val MAX_EXTRA_KEYS: Int = 64
    const val MAX_KEY_CHARS: Int = 256
    const val MAX_URI_CHARS: Int = 2_048

    /** Upper bound of the reconciliation window kept per conversation stream. */
    const val MAX_WINDOW_ITEMS: Int = 64

    /** Upper bound of the in-memory capture queue before the pipeline reports overflow. */
    const val MAX_QUEUE_DEPTH: Int = 512
}
