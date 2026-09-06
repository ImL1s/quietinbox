## What / 做了什麼

<!-- One paragraph. Link the issue: Refs #N. -->

## Hard rules / 硬規則

- [ ] No `INTERNET` permission or SDK that merges one (`tools/check-permissions.sh` passes)
- [ ] Never acts on a source notification (no reply / dismiss / mark-read / `PendingIntent`)
- [ ] No real-app notification captures, decompiled sources or vendor assets added
- [ ] Strings added to both `values/` and `values-b+zh+Hant/`; docs in both languages where they exist

## Evidence / 證據

- [ ] `./gradlew test` green (count: ) — new tests listed below
- [ ] Instrumented tests, if storage / crypto / backup changed (`connectedDebugAndroidTest` on an emulator)
- [ ] Device walkthrough of every user-visible change (which device / AVD:)
- [ ] `docs/SCOPE.md`, `docs/TEST_MATRIX.md`, `CHANGELOG.md` updated — docs never run ahead of code
- [ ] Schema changed → `platform/storage/schemas/<n>.json` exported, migration + `MigrationTest` added, no destructive migration
- [ ] Dependencies changed → `gradle/verification-metadata.xml` regenerated from a cold cache (`docs/RELEASE.md`)

## Review / 審查

<!-- Independent review round (docs/reviews/README.md): reviewers, verdicts, fix commit. -->
