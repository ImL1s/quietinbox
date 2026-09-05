> 繁體中文：[docs/zh-Hant/TEST_MATRIX.md](zh-Hant/TEST_MATRIX.md)

# Test layers and current coverage

Layers follow plan §15. Tooling and product are separated: the fixture DSL and synthetic publisher
are test tools, never evidence about a real source app.

| Layer | Oracle | What exists | Run |
| --- | --- | --- | --- |
| L0 contracts & fixtures | Hand-written expectations | `core:testing` Fixtures DSL; every parser test is a synthetic fixture with an explicit expected batch | JVM |
| L1 JVM replay | Pure Kotlin parser / identity / reconcile / analytics | 72 tests in `core:*` (model 5, parser 10, identity 5, reconcile 20, analytics 32), 43 in `parsers:apps`, 4 in `app` (reminders); two 1,000-iteration property tests: distinct content must be accepted exactly once (seed 20260905), repeated id-less content must never duplicate and replays must never shrink the window (seed 20260906) | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test` |
| L2 Android publisher | Real notification callbacks through our own package | `SyntheticNotifications` (MessagingStyle, BigText); onboarding step 4 | Device, manual |
| L3 real source apps | Two consenting test accounts, recordings | **Not run** | — |
| L4 fault & performance | Process kill, Doze, first unlock, revoke, disk limits | **Not run** (commit fence exists in code; no injected-failure test yet) | — |
| L5 release artifact | Merged manifest, permission dump, reproducible build | `tools/check-permissions.sh` (CI); no SBOM / rebuild comparison yet | CI |
| Instrumented storage | Real SQLCipher + Room migrations | `VaultRoundTripTest` (journal → commit → search → suppression → reopen with persisted key), `MigrationTest` (1→2 against exported schema), `DemoDataTest` (debug demo seed → counts and projection → idempotent re-seed → clear leaves nothing) | `./gradlew :platform:storage:connectedDebugAndroidTest` |
| Instrumented crypto | Durable key write on a real filesystem | `WrappedSecretFileTest` (data fsync → rename → `Os.fsync` on the directory; create-once, read-back, nested directory) | `./gradlew :platform:crypto:connectedDebugAndroidTest` |
| Crypto | RFC 5869 vectors, codec round trips | `HkdfTest`, `RecoveryKeyCodecTest` | JVM |
| Backup staging | Format and limit enforcement of the restore reader | `BackupStagerTest` (21 tests: manifest first, duplicate manifest, unsupported version, data after end, count mismatches, every size limit, unknown record) | `./gradlew :platform:backup:testDebugUnitTest` |
| Capture coordinator | Commit fence and cold start with mocked repositories | `CaptureCoordinatorTest` (11 tests: pause discards queued events, resume rotates generation and session, non-source packages dropped after the source list loads, cancellation propagates) | `./gradlew :platform:capture:testDebugUnitTest` |

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
- Screenshot harness: `tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW> <out-dir>` installs the
  debug APK, wipes app data, grants the listener and `POST_NOTIFICATIONS`, walks onboarding by
  matching button text in both languages, seeds the demo vault and captures
  `1_inbox.png … 7_inbox_dark.png`. Use an emulator: on a real phone the listener would copy the
  owner's own notifications into the debug vault.
- Coverage: `DemoDataTest` (instrumented, `platform:storage`) seeds, asserts the row counts and the
  conversation projection every screen reads, checks that seeding twice does not duplicate, then
  clears and asserts nothing demo-tagged remains. It runs on a device
  (`./gradlew :platform:storage:connectedDebugAndroidTest`) because SQLCipher's native library
  cannot load on the JVM.

## Quantitative targets (plan §15) — status

All numeric targets (callback p95 < 10 ms, commit p95 < 500 ms, search p95 < 300 ms on 100k rows,
72 h soak) are **unmeasured**. No benchmark module has been run; the values in the plan remain
planning thresholds, not results.
