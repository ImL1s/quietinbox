# Test layers and current coverage

Layers follow plan §15. Tooling and product are separated: the fixture DSL and synthetic publisher
are test tools, never evidence about a real source app.

| Layer | Oracle | What exists | Run |
| --- | --- | --- | --- |
| L0 contracts & fixtures | Hand-written expectations | `core:testing` Fixtures DSL; every parser test is a synthetic fixture with an explicit expected batch | JVM |
| L1 JVM replay | Pure Kotlin parser / identity / reconcile / analytics | 39 tests in `core:*`, 43 in `parsers:apps`, 4 in `app` (reminders); two 1,000-iteration property tests: distinct content must be accepted exactly once (seed 20260905), repeated id-less content must never duplicate and replays must never shrink the window (seed 20260906) | `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test` |
| L2 Android publisher | Real notification callbacks through our own package | `SyntheticNotifications` (MessagingStyle, BigText); onboarding step 4 | Device, manual |
| L3 real source apps | Two consenting test accounts, recordings | **Not run** | — |
| L4 fault & performance | Process kill, Doze, first unlock, revoke, disk limits | **Not run** (commit fence exists in code; no injected-failure test yet) | — |
| L5 release artifact | Merged manifest, permission dump, reproducible build | `tools/check-permissions.sh` (CI); no SBOM / rebuild comparison yet | CI |
| Instrumented storage | Real SQLCipher + Room migrations | `VaultRoundTripTest` (journal → commit → search → suppression → reopen with persisted key), `MigrationTest` (1→2 against exported schema) | `./gradlew :platform:storage:connectedDebugAndroidTest` |
| Instrumented crypto | Durable key write on a real filesystem | `WrappedSecretFileTest` (data fsync → rename → `Os.fsync` on the directory; create-once, read-back, nested directory) | `./gradlew :platform:crypto:connectedDebugAndroidTest` |
| Crypto | RFC 5869 vectors, codec round trips | `HkdfTest`, `RecoveryKeyCodecTest` | JVM |

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

## Quantitative targets (plan §15) — status

All numeric targets (callback p95 < 10 ms, commit p95 < 500 ms, search p95 < 300 ms on 100k rows,
72 h soak) are **unmeasured**. No benchmark module has been run; the values in the plan remain
planning thresholds, not results.
