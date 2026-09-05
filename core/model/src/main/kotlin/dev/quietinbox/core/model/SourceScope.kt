package dev.quietinbox.core.model

import kotlinx.serialization.Serializable

/**
 * Identifies *where* a notification came from, at the finest granularity the platform can
 * reliably provide. [accountKey] is only populated when the source itself exposes a trustworthy
 * account discriminator; it is never inferred from display names.
 */
@Serializable
data class SourceScope(
    val packageName: String,
    val profileKey: String,
    val accountKey: String? = null,
)
