# Contributing

Thanks for helping. A few rules keep this project honest and its users safe.

## Ground rules
- **No reverse-engineered assets.** Do not contribute rule lists, dictionaries, constants, decompiled
  code or fixtures taken from any other app or from research reports about them. Describe behaviour,
  write your own heuristics, and cover them with synthetic fixtures (`core:testing` DSL).
- **No real messages.** Fixtures and bug reports must be synthetic. If you need to share a real
  notification shape, replace every name, id, URI and body with placeholders first.
- **Evidence, not claims.** A PR that changes a parser must include fixture tests; a PR that claims a
  real-device result must include the device, OS build, source app version and the scenario id from
  `docs/TEST_MATRIX.md`. "Compiles" is not "done".
- **Keep the invariants.** No `INTERNET`, no acting on source notifications, no cross-stream merging,
  no silent deletion of user data, no body text in logs or diagnostics.

## Workflow
1. Fork and branch from `main`.
2. `./gradlew :core:model:test :core:parser:test :core:identity:test :core:reconcile:test :core:analytics:test :parsers:apps:test`
3. `./gradlew :app:assembleDebug && tools/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk`
4. For storage or crypto changes: `./gradlew :platform:storage:connectedDebugAndroidTest` on a device.
5. Open a PR with the checklist filled in. Crypto, schema and identity/dedup changes need a second
   reviewer and an ADR update.

## Style
- Kotlin official style, 120 columns, trailing commas.
- Every user-visible string in both `values/strings.xml` and `values-b+zh+Hant/strings.xml`.
- Colour is never the only signal for a state; add text + icon.

## Maintainer
Provisional maintainer: the repository owner (see git history). Package id `dev.quietinbox` and the
name are placeholders until clearance.
