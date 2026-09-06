> 繁體中文：[docs/zh-Hant/COMPATIBILITY.md](zh-Hant/COMPATIBILITY.md)

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

## Work profiles, Device Policy and low-RAM devices (QI-ID-008)

- Android delivers a work-profile app's notifications to a listener installed in the **personal**
  profile (the copy is tagged with the profile: `profileKey` is `user:<id>`, and the inbox shows a
  "Work profile" tag on such conversations). A listener installed *inside* a work profile is
  ignored by the system, so QuietInbox must be installed in the personal profile.
- A Device Policy Controller (MDM) can block notifications from work-profile apps from reaching
  personal-profile listeners. QuietInbox cannot detect this; the source simply never appears.
  The capture health page shows the listener as connected while nothing arrives.
- Sources are configured per package, not per profile: enabling LINE captures both the personal
  and the work LINE. Per-profile source control and a non-null account key in the conversation
  identity are planned schema work (see `docs/SCOPE.md`, "Not done").
- Android Q and older on low-RAM ("Go") devices do not bind notification listeners at all
  (`ActivityManager.isLowRamDevice`); QuietInbox cannot capture there.

## Submitting an anonymised fixture (QI-PARSER-017)

Only fixtures with synthetic content are accepted; a real conversation never enters the
repository. To promote a source row:

1. Use two test accounts you own. Send messages whose bodies are test markers only
   (`T001 alpha`, `T004 sticker`, …), one per scenario of `TEST_MATRIX.md` (T001 / T004 / T016 /
   T017 / T045).
2. On a debug build, Capture → Copy summary gives the body-free diagnostic summary; the parser
   warnings and the notification template are what matter.
3. Record: source app versionCode, Android version, OEM, system language, notification settings
   (preview on/off), the shape (MessagingStyle / BigText / Inbox / summary) and the extras **key
   names** (never values that could carry text).
4. Confirm that reading the copy in QuietInbox did not mark the chat as read on the source side.
5. Open a "Source compatibility report" issue (`.github/ISSUE_TEMPLATE/compatibility_report.yml`).
   A maintainer turns it into a Kotest fixture under `parsers/apps/src/test/` with the same synthetic
   text, and the matrix row moves to `REAL_DEVICE_PASSED` with the commit, versionCode and device.
