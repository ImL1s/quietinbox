# Code Review Request — QuietInbox v0.1 vertical slice (pre-push)

DO NOT activate workflow modes; READ-ONLY review only. Do not modify any file except your own report file.

## Context
- Repo: /Users/iml1s/Documents/mine/quietinbox (git, branch main, no remote yet). HEAD = output of `git log -1 --oneline`.
- Product: "QuietInbox／靜讀" — an Android app (Kotlin, Jetpack Compose, Material 3 Expressive, Nav3, Hilt, Room + SQLCipher, Tink) that keeps encrypted on-device copies of messaging-app notifications via NotificationListenerService. The governing spec is `/Users/iml1s/Downloads/QuietInbox_開源專案完整計劃.md` (read §2, §5, §6, §7, §8, §9, §11, §12 at minimum; it is long — skim the rest).
- Hard product rules from the spec (violations are CRITICAL):
  1. No INTERNET permission, no analytics/crash SDKs, no automatic upload; message bodies/titles/URIs must never reach logs or diagnostics.
  2. Never act on source notifications: no contentIntent/deleteIntent/RemoteInput, no cancelNotification, no marking read. Opening the source app must be an explicit, confirmed user action.
  3. NotificationListenerService callbacks must not do DB, network, bitmap decoding, RemoteViews inflation or reflection.
  4. Journal-first durability: an event counts as accepted only after the encrypted journal row is written; a commit fence must discard events queued before a revoke/pause (generation token).
  5. Dedup: proven sourceMessageId decides identity; otherwise bounded window alignment; identical id-less single items are AMBIGUOUS_REPEAT (stored, linked, never silently dropped); replays never delete stored content; user deletions must not be resurrected by active-notification replay.
  6. Identity: never merge conversations across notification streams / accounts; group keys are not chat ids.
  7. Keys: per-installation random DB key wrapped by an Android Keystore key WITHOUT user-auth binding (listener writes while locked); key failure ⇒ locked state with user choice, never silent wipe. `fallbackToDestructiveMigration()` forbidden.
  8. Backup import must verify header, every AEAD segment/EOF, manifest vs end counts, size limits; wrong key/truncation/tampering must leave the existing vault untouched.
  9. UI: data-quality states need text + icon (not colour only); zh-Hant and en strings both present; statistics only describe observed data (no reply/read/recall rates).
- Build/test status claimed by the author (verify what you can): 74 JVM tests green; 2 instrumented SQLCipher tests green on Samsung SM-S9280 (Android 16); release APK (R8) runs; `aapt2 dump permissions` shows no INTERNET.
- Known/declared gaps are listed in `docs/SCOPE.md` — do not re-report those as findings unless the code contradicts the document.

## Reviewer task — read the repo directly
You have Bash + Read access. Suggested commands:
- `git log --oneline` ; `git show --stat HEAD~2` ; `find . -name "*.kt" -not -path "*/build/*" | xargs wc -l | tail -1`
- Core pipeline: `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/*.kt`, `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt`, `core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt`, `core/identity/.../IdentityResolver.kt`, `core/parser/.../StandardParser.kt`
- Keys/crypto: `platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/*.kt`, `platform/storage/.../db/DatabaseHolder.kt`
- Backup: `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/*.kt`
- Storage: `platform/storage/.../db/Entities.kt`, `Daos.kt`, `repo/*.kt`, `retention/RetentionWorker.kt`, `settings/SettingsRepository.kt`
- Media: `platform/media/.../MediaCopier.kt`
- UI: `app/src/main/kotlin/dev/quietinbox/**`, `feature/*/src/main/kotlin/**`, `core/designsystem/**`
- Manifest & permissions: `app/src/main/AndroidManifest.xml`, `platform/capture/src/main/AndroidManifest.xml`, `tools/check-permissions.sh`
- Tests: `core/*/src/test/**`, `parsers/apps/src/test/**`, `platform/storage/src/androidTest/**`, `app/src/test/**`
- You MAY run JVM tests: `export ANDROID_HOME=$HOME/Library/Android/sdk && ./gradlew :core:reconcile:test :core:parser:test --console=plain` (Gradle wrapper 9.7.1 is pre-downloaded). Do NOT run instrumented tests or install anything on devices.

## Review dimensions (cover all)
1. Capture pipeline correctness: generation/commit fence races (revoke while queued, reconnect generation change), journal-first ordering, replay path (`replayJournal`), `onRemoved` closing windows, active-resync origin tagging, own-package loop prevention (synthetic marker vs reminders), queue overflow handling, exception handling that could crash the process or silently drop events.
2. Dedup/identity: `Reconciler` edge cases (overlap detection with duplicates inside windows, stale window vs ambiguous, historic messages, ids mixed with id-less items, checkpoint id mapping in `IngestRepository.commit` — verify the index arithmetic `storedIds[reconcile.decisions.size - reconcile.newWindow.items.size + i]`), suppression semantics, the new checkpoint-loss fingerprint guard.
3. Keystore/SQLCipher: key lifecycle, `WrappedSecretFile` atomicity, AAD binding, error mapping, whether any path can zero or lose the key array SQLCipher holds, WAL + pooled connections, `DatabaseHolder` state machine and `retry()`.
4. Backup: `BackupService.export/import` — streaming correctness, header/AAD binding, staging limits, count verification, transaction atomicity, media file cleanup, id remapping, merge semantics, anything that could partially apply.
5. Room/transactions/coroutines: `withTransaction` usage with suspend DAOs, flows from `DatabaseHolder.flowWithDb`, `CoroutineScope` leaks in singletons/ViewModels, `SharingStarted.WhileSubscribed`, `runCatching` swallowing errors that should surface, `RetentionWorker` correctness.
6. Compose/UI state: ViewModel state combination correctness, `hiltViewModel` with assisted factory keying per conversation, navigation back stack handling (`goTop`, list-detail), permission launchers, dialogs, recomposition issues, accessibility (content descriptions, text+icon), string resources missing in either locale (grep both `values/strings.xml` and `values-b+zh+Hant/strings.xml` for parity).
7. Security/privacy: any logging of bodies, manifest permissions, `<queries>`, FLAG_SECURE handling (debug exemption), data extraction rules, clipboard diagnostics content, ProGuard rules sufficiency for R8 (serialization, Room, Tink, SQLCipher, Hilt).
8. Build hygiene: version catalog, convention plugins, alpha dependency (material3 1.5.0-alpha27) risk, CI workflow correctness (`.github/workflows/ci.yml` paths/task names).
9. Docs honesty: README / SCOPE / ADR claims vs code.

## Output format (繁體中文)
- Verdict: APPROVE | APPROVE WITH MINOR FIXES | REQUEST CHANGES | REJECT
- Critical (must fix before push) — itemized, each with file:line, why, and a concrete fix
- Important (should fix before push)
- Minor / nitpicks
- Other observations (including anything you verified to be correct that a reader might doubt)
