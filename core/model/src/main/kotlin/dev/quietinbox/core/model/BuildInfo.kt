package dev.quietinbox.core.model

/**
 * What the running artifact is, injected once by the app module so feature modules can gate
 * developer-only affordances without depending on a generated `BuildConfig`.
 */
data class BuildInfo(
    val debug: Boolean,
    val flavor: String,
)
