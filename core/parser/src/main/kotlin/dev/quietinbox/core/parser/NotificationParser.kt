package dev.quietinbox.core.parser

import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParsedBatch

/**
 * Parser SPI. Implementations are pure: no database, no `Context`, no network, no clock.
 * They receive an allow-listed [NotificationSnapshot] and return a [ParsedBatch] with evidence.
 */
interface NotificationParser {
    /** Stable identifier, e.g. `standard`, `line`. */
    val id: String

    /** Semantic version of the parser's *behaviour*; bump whenever golden output changes. */
    val version: String

    /** Packages this parser is designed for; empty means "any". */
    val packages: Set<String>

    fun supports(snapshot: NotificationSnapshot): Boolean =
        packages.isEmpty() || snapshot.source.packageName in packages

    fun parse(snapshot: NotificationSnapshot): ParsedBatch
}

/** Resolves the parser for a snapshot: first matching adapter, otherwise the standard parser. */
class ParserRegistry(
    adapters: List<NotificationParser>,
    private val fallback: NotificationParser = StandardParser(),
) {
    private val adapters: List<NotificationParser> = adapters.filter { it.packages.isNotEmpty() }

    val all: List<NotificationParser> get() = adapters + fallback

    fun parserFor(snapshot: NotificationSnapshot): NotificationParser =
        adapters.firstOrNull { it.supports(snapshot) } ?: fallback

    fun parse(snapshot: NotificationSnapshot): ParsedBatch = parserFor(snapshot).parse(snapshot)

    fun adapterFor(packageName: String): NotificationParser? =
        adapters.firstOrNull { packageName in it.packages }
}
