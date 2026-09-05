# Review round 4 — QuietInbox pre-publication diff

DO NOT activate workflow modes; READ-ONLY review only. Do not edit, create or delete any file in the repository; no git commands that change state; no instrumented tests; never touch devices or emulators (a phone R5CX10VFFBA and emulators belong to other people/sessions).

## What to review
Repository: <repo>, branch main, HEAD 1f7b182.
Review `git diff 96b0cf9..1f7b182` (the pre-publication change set) in the context of the whole codebase.
Earlier rounds (docs/reviews/2026-09-06-round{1,2,3}/, plus `.omc/research/code-review-whole-repo.md`) ended
APPROVE WITH MINOR FIXES; this diff implements that whole-repo review's Important items and adds features
before the first public release (Google Play, paid; GitHub, free — see docs/adr/0006-distribution-and-monetisation.md).

Hard product rules (unchanged): no INTERNET permission; never act on source notifications; honest data-quality
labels; no destructive migrations; nothing in a release build may expose demo/debug hooks.

Focus, in order of risk:
1. `IngestRepository.commit` — the conversation row is now created lazily (`conversationIdOrCreate()`); check every
   path that used the old eager id (preExisting guard, projection, CommitOutcome) and the deleted-conversation replay
   scenario the whole-repo review described (I1).
2. `SnapshotFactory.bound` `isSelf` semantics (I3); `CaptureCoordinator` `offerCaptured`/`enqueue` seam added for tests —
   does it weaken the callback-thread rules or the commit fence?
3. `BackupService` / new `BackupStager`: identical staging semantics; expiry re-basing (I2); media encrypted before the
   transaction (I6); duplicate multiplicity by counts; restored sources disabled.
4. Demo mode: `DemoDataRepository`, `DemoDao`, `app/src/debug/**` (`DemoReceiver`, manifest) and the Settings Developer
   section gated on `BuildInfo.debug` — confirm nothing reaches release builds (source sets, manifest merge, R8), the
   receiver cannot be abused in debug builds beyond seeding/clearing demo rows, and `clear()` removes only demo rows.
5. Analytics: `core/analytics/Insights.kt` formulas (heat map, rankings, best time, chattiness, quiet rate, catchphrases,
   periods/timezones) and `feature/analytics` UI; honesty of labels ("observed messages only"); performance on large
   vaults (how much is loaded per period change?).
6. Release plumbing: `app/build.gradle.kts` signing config (keystore.properties / env; no secrets committed),
   `.github/workflows/release.yml`, `gradle/verification-metadata.xml` (trusted aapt2), `tools/demo-screenshots.sh`.
7. Docs honesty: README (bilingual), docs/RELEASE.md, docs/SCOPE.md, CHANGELOG, ADR-0006, PRIVACY, store texts under
   `fastlane/metadata/android/*` — every claim must match the code.

You may run: `export ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew test --console=plain` (JVM only).

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES
- Critical / Important / Minor findings (file:line, why, concrete fix)
- Other observations
