package dev.quietinbox.core.model

import kotlinx.serialization.Serializable

/**
 * Identifies *where* a notification came from, at the finest granularity the platform can
 * reliably provide.
 *
 * - [packageName] always comes from the system `StatusBarNotification`, never from extras.
 * - [profileKey] distinguishes Android user profiles (work / personal).
 * - [accountKey] is only populated when the source itself exposes a trustworthy account
 *   discriminator; it is never inferred from display names.
 */
@Serializable
data class SourceScope(
    val packageName: String,
    val profileKey: String,
    val accountKey: String? = null,
) {
    /** Stable, opaque key usable as a database scope. */
    val key: String get() = buildString {
        append(packageName).append('|').append(profileKey)
        if (accountKey != null) append('|').append(accountKey)
    }
}
