# Source compatibility matrix

Status values (plan §14): `UNTESTED / SYNTHETIC_ONLY / REAL_DEVICE_PASSED / PARTIAL / REGRESSED / BLOCKED`.
A new source-app version never inherits an older row's status.

| Source | Package | Adapter | Adapter version | Status | QuietInbox commit | Source versionCode | OS / device | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| LINE | `jp.naver.line.android` | `line` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `parsers/apps/src/test/.../LineParserTest.kt` |
| WhatsApp | `com.whatsapp` | `whatsapp` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `WhatsAppParserTest.kt` |
| Telegram | `org.telegram.messenger` | `telegram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `TelegramParserTest.kt` |
| Instagram | `com.instagram.android` | `instagram` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `InstagramParserTest.kt` |
| Messenger | `com.facebook.orca` | `messenger` | 0.1.0 | SYNTHETIC_ONLY | HEAD | — | — | `MessengerParserTest.kt` |
| Any other app | — | `standard` | 1.0.0 | SYNTHETIC_ONLY | HEAD | — | — | `core/parser/src/test/.../StandardParserTest.kt` |
| QuietInbox synthetic publisher | `dev.quietinbox.app.debug` | `standard` | 1.0.0 | REAL_DEVICE_PASSED | afa7818 | 1 | Android 16 / Samsung SM-S9280 | Onboarding step 4 captured 3/3 messages (2026-09-06) |

No adapter emits `sourceMessageId` or `SOURCE_CHAT_ID` evidence at `VERIFIED` confidence, because no
fixture from a real device exists yet. Promoting a row to `REAL_DEVICE_PASSED` requires the T001 /
T004 / T016 / T017 / T045 scenarios from `TEST_MATRIX.md` with two consenting test accounts and
synthetic markers in the message bodies; real private messages never enter the repository.

Compile/target baseline: compileSdk 37 (required by the AndroidX versions used), targetSdk 36
(plan §4 baseline). An API 37 target lane is tracked but not yet exercised.
