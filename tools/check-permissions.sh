#!/usr/bin/env bash
# Fails when a built APK requests INTERNET (or any network permission) — plan §15 L5 release gate.
# Usage: tools/check-permissions.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail
APK="${1:?apk path required}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
if [[ -z "$AAPT2" ]]; then echo "aapt2 not found under $SDK/build-tools" >&2; exit 2; fi
PERMS="$("$AAPT2" dump permissions "$APK")"
echo "$PERMS"
if echo "$PERMS" | grep -Eq "android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE)"; then
  echo "FAIL: network permission present in $APK" >&2
  exit 1
fi
if echo "$PERMS" | grep -Eq "QUERY_ALL_PACKAGES"; then
  echo "FAIL: QUERY_ALL_PACKAGES present in $APK" >&2
  exit 1
fi
echo "OK: no network permission in $APK"
