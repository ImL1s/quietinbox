# ADR-0001: Toolchain and module layout

Date: 2026-09-06 · Status: accepted

## Decision

- Gradle 9.7.1 wrapper, AGP 9.4.0 (built-in Kotlin, new DSL), Kotlin 2.4.10, KSP 2.3.11, Hilt 2.60.1,
  Room 2.8.4, Compose BOM 2026.08.00 (ui/foundation 1.12.0), Navigation 3 1.1.7, WorkManager 2.11.2,
  DataStore 1.2.1, Tink 1.23.0, SQLCipher for Android 4.18.0 with androidx.sqlite 2.7.0.
- compileSdk 37 (forced by androidx.core 1.19 / compose 1.12 / sqlcipher 4.18 AAR metadata),
  targetSdk 36 (plan §4 baseline), minSdk 26.
- Convention plugins as precompiled script plugins in `build-logic/` (`quietinbox.kotlin.jvm`,
  `quietinbox.android.library`, `.library.compose`, `.hilt`, `.feature`).
- `core:*` and `parsers:apps` are `kotlin("jvm")` modules with Kotest on JUnit Platform; Android
  modules use JUnit4 + Kotest assertions so instrumented tests share matchers.

## Why

The plan requires the parser / identity / reconcile / analytics logic to run on the JVM without
Android, and a single encrypted SQL data layer. Built-in Kotlin in AGP 9 removes the
`kotlin-android` plugin; `kotlin { compilerOptions { … } }` at module level still works and is used
for opt-ins. A three-module spike was built and installed before the full layout was created.

## Consequences

- `-Xjvm-default=all` is gone in Kotlin 2.4 (`-jvm-default` only accepts `enable|no-compatibility|disable`); the default is sufficient.
- KSP 2.3.11 targets Kotlin 2.3.20 but compiles fine with 2.4.10; if a KSP incompatibility appears, downgrade Kotlin to 2.3.x rather than pinning KSP.
