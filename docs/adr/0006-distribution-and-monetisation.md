> 繁體中文：[docs/zh-Hant/adr/0006-distribution-and-monetisation.md](../zh-Hant/adr/0006-distribution-and-monetisation.md)

# ADR-0006: Distribution and monetisation — paid on Google Play, free on GitHub, no billing SDK

Date: 2026-09-06 · Status: accepted

## Context

The plan asks for a store build with "some" revenue and a fully open build. The obvious pattern
(free app + in-app "Pro" unlock) needs the Google Play Billing Library. Its 9.x POM pulls in
`com.google.android.datatransport:transport-backend-cct`, whose manifest declares
`android.permission.INTERNET` and `ACCESS_NETWORK_STATE` (checked against the published AARs on
2026-09-06). Those permissions would be merged into the app manifest, `tools/check-permissions.sh`
would fail, and the product's central promise — *no INTERNET permission, ever* — would be false for
the store build. Removing the permission with `tools:node="remove"` leaves a library that still
tries to upload telemetry and fails at runtime; that is not an honest configuration either.

## Decision

- **Google Play:** QuietInbox is a **paid app** (one-time purchase, no subscription, no in-app
  purchases, no ads). The binary is identical to the open build: same package, same features.
- **GitHub releases:** the same APK is published free under GPL-3.0-or-later, signed with the
  project's upload key. Google Play re-signs the store copy with its app-signing key, so the two
  installs cannot be updated over each other; this is documented in the README.
- **No feature gating.** Every feature, including analytics periods, search, backup and export, is
  available in both builds. Paying on Play buys convenience (auto-updates, one tap install) and
  supports development; it does not buy capability.
- The Play Billing Library, Play Services, ads SDKs and any analytics SDK stay out of the
  dependency graph. `tools/check-permissions.sh` remains the enforcement point.

## Consequences

- Revenue is upfront and smaller per install than a freemium funnel, but the privacy claim stays
  verifiable by anyone who dumps the manifest.
- A price change is possible, but a paid app cannot be turned into a free one on Play.
- If a future maintainer wants in-app purchases, this ADR must be superseded together with a
  rewrite of the "no INTERNET permission" promise in the README, PRIVACY.md and the store listing.
