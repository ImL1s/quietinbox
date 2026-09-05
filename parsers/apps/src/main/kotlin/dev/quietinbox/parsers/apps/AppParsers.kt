package dev.quietinbox.parsers.apps

import dev.quietinbox.core.parser.NotificationParser

/** The versioned per-app adapters, in registry order. Anything else uses the standard parser. */
object AppParsers {
    fun all(): List<NotificationParser> = listOf(
        LineParser(),
        WhatsAppParser(),
        TelegramParser(),
        InstagramParser(),
        MessengerParser(),
    )
}
