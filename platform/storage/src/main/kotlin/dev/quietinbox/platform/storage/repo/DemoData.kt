package dev.quietinbox.platform.storage.repo

/** What [DemoData.seed] wrote, for the developer-facing confirmation text. */
data class DemoCounts(val conversations: Int, val messages: Int)

/**
 * Demo vault for screenshots and manual walkthroughs. The seeding implementation and its fictional
 * content live in the `debug` source set of `platform:storage`; release builds bind [NoDemoData],
 * so no demo text, seeder or trigger exists in a published binary.
 */
interface DemoData {
    /** [locale] picks the demo's language; null means the app's current configuration. */
    suspend fun seed(now: Long = System.currentTimeMillis(), locale: java.util.Locale? = null): DemoCounts

    suspend fun clear()
}

/** Release binding: nothing to seed, nothing to clear. */
object NoDemoData : DemoData {
    override suspend fun seed(now: Long, locale: java.util.Locale?): DemoCounts = DemoCounts(0, 0)

    override suspend fun clear() = Unit
}
