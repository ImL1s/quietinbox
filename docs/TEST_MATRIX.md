> 繁體中文：[docs/zh-Hant/TEST_MATRIX.md](zh-Hant/TEST_MATRIX.md)

# Test layers and current coverage

Layers follow plan §15. Tooling and product are separated: the fixture DSL and synthetic publisher
are test tools, never evidence about a real source app.

| Layer | Oracle | What exists | Run |
| --- | --- | --- | --- |
| L0 contracts & fixtures | Hand-written expectations | `core:testing` Fixtures DSL; every parser test is a synthetic fixture with an explicit expected batch | JVM |
| L1 JVM replay | Pure Kotlin parser / identity / reconcile / analytics | 76 tests in `core:*` (model 5, parser 10, identity 5, reconcile 22 incl. `ReconcilerIdAlignmentTest`, analytics 34), 43 in `parsers:apps`, 5 in `app` (reminders incl. `ReminderPolicy`); two 1,000-iteration property tests: distinct content must be accepted exactly once (seed 20260905), repeated id-less content must never duplicate and replays must never shrink the window (seed 20260906) | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test` |
| L2 Android publisher | Real notification callbacks through our own package | `SyntheticNotifications` (MessagingStyle, BigText); onboarding step 4 | Device, manual |
| L3 real source apps | Two consenting test accounts, recordings | **Not run** | — |
| L4 fault & performance | Process kill, Doze, first unlock, revoke, disk limits | **Not run** (commit fence exists in code; no injected-failure test yet) | — |
| L5 release artifact | Merged manifest, permission dump, reproducible build | `tools/check-permissions.sh` (CI); no SBOM / rebuild comparison yet | CI |
| Design system | Avatar monograms; locale-explicit time formatting | `MonogramTest` (4: Han, kana and hangul names give one glyph, Latin names two initials, blank gives `?`), `TimeFormatTest` (2: Japanese and Korean dates and times render in the given locale, never the process default) | `./gradlew :core:designsystem:testDebugUnitTest` |
| String catalogues | Every locale carries the default catalogue's names, placeholders and plurals | `tools/check-strings.py` (en, zh-Hant, zh-Hans, ja, ko in `core:designsystem` and `platform:capture`); Android lint `MissingTranslation` is an error too | `python3 tools/check-strings.py` (CI: Assemble + permission gate) |
| Instrumented storage | Real SQLCipher + Room migrations | `VaultRoundTripTest` (journal → commit → search → suppression → reopen with persisted key; deleted conversation does not resurrect on replay), `MigrationTest` (1→2 and 2→3 against exported schemas), `DemoDataTest` (debug demo seed → counts and projection → idempotent re-seed → clear leaves nothing; the seed is localised through `DemoLocalisation` for zh-Hans / ja / ko app languages), `DeletionGraphTest` (5: journal payload cleared on leaving PENDING; deleting the newest message rebuilds the projection; expired copies hidden before retention and projection rebuilt after it; removing a source with data leaves nothing behind; delete-everything verified and no cached cipher outlives the old key), `SearchPagingTest` (2: 250 false-positive candidates neither hide a true hit nor under-fill a page, pages resume by cursor without overlap; a deletion token suppresses a replay of the same post but not a later post with the same text), `MediaExportBoundTest` (1: `maxId` is 0 on an empty table and the bounded export page excludes a blob committed after the snapshot) — 16 tests | `./gradlew :platform:storage:connectedDebugAndroidTest` |
| Instrumented backup | Export → wipe → import on a real vault; the maintenance gate | `BackupRoundTripTest` (2: the backup holds only visible copies, reports media it could not read, restore rebuilds the projection and the media file decrypts under the current key; an export during an exclusive run is refused) | `./gradlew :platform:backup:connectedDebugAndroidTest` |
| Instrumented crypto | Durable key write on a real filesystem; KEK creation race | `WrappedSecretFileTest` (data fsync → rename → `Os.fsync` on the directory; create-once, read-back, nested directory), `KeystoreWrapperTest` (three secrets created in parallel under a fresh alias, five rounds: one KEK, every secret readable from a new wrapper) | `./gradlew :platform:crypto:connectedDebugAndroidTest` |
| Crypto | RFC 5869 vectors, codec round trips | `HkdfTest`, `RecoveryKeyCodecTest` | JVM |
| Backup staging | Format and limit enforcement of the restore reader | `BackupStagerTest` (21 tests: manifest first, duplicate manifest, unsupported version, data after end, count mismatches, every size limit, unknown record) | `./gradlew :platform:backup:testDebugUnitTest` |
| Capture coordinator | Commit fence, cold start and maintenance with mocked repositories | `CaptureCoordinatorTest` (32 tests: pause discards queued events, resume rotates generation and session, non-source packages dropped after the source list loads, cancellation propagates; a source disabled while an event waits for the lock is never journaled, a pause between acceptance and commit leaves the event pending, replay is held while paused and runs on resume, replay discards a row whose source was disabled, a maintenance run drops the queue and records an exact gap, every maintenance run records its own gap; a notification is held unread before the policy is known and only sources are snapshotted, held notifications are dropped with a bounded gap when the vault does not open; bitmaps in flight still count against the queue bound; an overflowed hold buffer records the drop and keeps only sources; a journal insert that throws is recorded as a gap; a cold-start loss and a pipeline lock-out the locked vault could not record are written as bounded gaps once it opens; a loss whose settle failed is kept and written on the next policy load, a gap row that lands after the policy loaded is closed at once, a notification held across a disconnect gets a gap of its own, but none when its source is paused or the resync captured it again; a gap row landing between the settle and the flag flip is closed by the policy load; an overflow gap whose write failed is kept and written on the next policy load; a stale copy with the same key but an older post time is a loss of its own; a disconnect landing during the release gives the later notifications a gap instead of letting them suppress themselves; an app the user never enabled has only its package name read) | `./gradlew :platform:capture:testDebugUnitTest` |
| Storage logic (JVM) | Maintenance gate ordering, reset failure branches, suppression rule | `VaultMaintenanceTest` (5: work runs and returns; refused while exclusive is active; exclusive cancels and joins work in flight; a listener sees start/end exactly once even for an instant run; exclusive runs and pipeline-lock holders are serialised), `VaultRepositoryTest` (3: database / media failure names the step, keeps keys and reopens the vault; happy path), `SuppressionRuleTest` (4) | `./gradlew :platform:storage:testDebugUnitTest` |
| Search / conversation ViewModels | Locked and opening vault with mocked repositories | `SearchViewModelTest` (2: locked is reported as locked and the query is not run; a query typed while opening runs once ready and again after a retry), `ConversationViewModelTest` (1: opening keeps loading, locked is shown as locked, ready shows the rows) | `./gradlew :feature:search:testDebugUnitTest :feature:conversation:testDebugUnitTest` |
| Analytics ViewModel | State rules of the activity page with mocked repositories | `AnalyticsViewModelTest` (8 tests: first report computed off the collector's thread, period switch shows a clean placeholder without the previous period's capped label, vault change recomputes without a loading state, locked vault shown as locked and recovers once unlocked, a vault that locks while the page is open recovers without a count change, opening vault keeps loading then computes, failing count query does not leave the page loading, a failing query marks the report as degraded) | JVM |

## Scenario ids referenced by the plan (subset implemented as tests)

| Plan example (§7.2) | Test |
| --- | --- |
| `[A] → [A,B] → [A,B,C]` ⇒ A B C | `ReconcilerTest` "yields exactly A B C" |
| only `[A,B,C]` ⇒ three messages | "stores all three, not just C" |
| `[A,B,C] → [B,C,D]` ⇒ A B C D | "keeps A B C D" |
| `[好(id=1), 好(id=2)]` ⇒ two | "are two messages" |
| `[好(?)] → [好(?)]` ⇒ ambiguous | "is an ambiguous observation" |
| `[A,B,C]` then old `[A]` ⇒ keep B C | "does not delete B C" |
| closed `[A,B,C]` → `[C]` (new post) → `[B,C,D]` ⇒ B C known, only D new | `ReconcilerAmbiguousKeepTest` |

## Demo mode (debug builds only)

`DemoDataRepository` (`platform:storage`) fills the vault with obviously synthetic content so the
app can be demonstrated, walked through and screenshotted without exposing a real notification. It
writes three fictional sources under the `demo.quietinbox.` package prefix, eight invented
conversations (bilingual titles, one pinned, one archived, all three identity-confidence levels) and
roughly 130 messages spread over the last 30 days with an evening-weighted hour distribution, plus
one closed capture session, two gap intervals, three diagnostic events and two summary-only
observations. Deliberately included: an `AMBIGUOUS_REPEAT` pair with its observation link, a revised
message with its previous body, a `PLACEHOLDER_ONLY` image, a preview-restricted body, self
messages, long text, emoji and URLs — so every honesty label in the UI has a row behind it. Rows are
shaped exactly as `IngestRepository.commit` shapes them (same fingerprint, sort-key rule and search
tokenisation), so the demo exercises the real read paths.

Nothing about it is real: no name, group, brand or app in the seeded data refers to anything that
exists, and no source notification is ever read.

- Trigger from adb (debug APK only; the receiver lives in `app/src/debug`):
  ```bash
  adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op seed \
      -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
  adb shell am broadcast -a dev.quietinbox.debug.DEMO --es op clear \
      -n dev.quietinbox.app.debug/dev.quietinbox.debug.DemoReceiver
  ```
- Trigger from the app: Settings → Developer → "Fill with demo data" / "Remove demo data". The
  section is rendered only when the injected `BuildInfo.debug` is true, so it does not exist in a
  release build.
- `seed()` is idempotent (it clears first) and `clear()` deletes strictly by the demo tags — the
  `demo.quietinbox.` package prefix and the `demo-` capture generation — so captured copies are
  never touched. No schema change: the seeder adds queries, not tables or columns.
- Screenshot harness: `tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>` installs the
  debug APK, wipes app data, grants the listener and `POST_NOTIFICATIONS`, walks onboarding by
  matching button text in both languages, seeds the demo vault and captures
  `1_inbox.png … 7_inbox_dark.png`. Use an emulator: on a real phone the listener would copy the
  owner's own notifications into the debug vault. On Android 13+ the keyboard follows the app language, so the tool disables the default input method before it launches the app, restores it afterwards, types the query one key at a time once the input method has settled, dismisses it with ENTER (the single-line field's Done action; the search is live, nothing is submitted) and refuses to shoot the search screen if the field does not show the query or an input method is still showing; every shot must also be at least 80 KB (calibrated on the 1080×2400 `QuietInbox_Phone`), the conversation shot waits (on the narrow layout) until the pinned title is on screen and the bottom bar is gone, and fails the run otherwise, the app language is set and confirmed before anything starts the process (and the process is stopped again before the launch), the seed broadcast names the demo's language (`--es lang`), and a non-English run refuses the inbox, conversation, activity and capture shots if an English AM/PM time or month appears in the app's own nodes — the sign that the process locale lagged behind the app language (the detector assumes an English device language, and the English run proves it still bites on its inbox clock).
  Several guards decide whether a file is written at all. **The app must be the app on screen**: before
  every capture the UI dump must contain a node belonging to `dev.quietinbox.app.debug`. **A navigation
  tap must take**: the tap is re-sent up to five times and the run fails unless the tapped item becomes
  the selected one (a node of the app, marked `selected`, inside the navigation strip and covering the
  label) — proving the app is on screen does not prove the right page is. **A dark shot must be dark**:
  failing to switch the device into night mode fails the run. The UI dump itself retries, since every
  capture now depends on one. The first two are what the tablet set was missing. Without those two the first tablet set captured
  the launcher and the system settings, and the size floor passed them (a wallpaper compresses to 3.3 MB).
  **Both layouts are supported**: the harness reads the window width in dp and taps the navigation rail
  at ≥ 600dp, the bottom bar below it; on a wide window the conversation counts as ready when the pinned
  title is on screen twice (the list row and the detail header, since the inbox stays beside it) and no
  BACK is sent, because the rail never went away. Tablet shots go to `docs/screenshots/tablet/<locale>/`
  and `fastlane/metadata/android/<locale>/images/tenInchScreenshots/` (en-US and zh-TW so far, captured on
  `Foldable_Test`, 2076×2152).
- Coverage: `DemoDataTest` (instrumented, `platform:storage`) seeds, asserts the row counts and the
  conversation projection every screen reads, checks that seeding twice does not duplicate, then
  clears and asserts nothing demo-tagged remains. It runs on a device
  (`./gradlew :platform:storage:connectedDebugAndroidTest`) because SQLCipher's native library
  cannot load on the JVM.

## Not covered yet

- `feature/*` ViewModels other than `AnalyticsViewModel` have no JVM tests; the locked-vault state and the
  "report may be incomplete" label of the activity page are covered by `AnalyticsViewModelTest` but have not
  been exercised on a device. The test harness does not cancel `viewModelScope` (each test owns its ViewModel).

## Quantitative targets (plan §15) — status

All numeric targets (callback p95 < 10 ms, commit p95 < 500 ms, search p95 < 300 ms on 100k rows,
72 h soak) are **unmeasured**. No benchmark module has been run; the values in the plan remain
planning thresholds, not results.
