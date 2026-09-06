#!/usr/bin/env bash
# Store screenshots of every QuietInbox screen, filled with the synthetic demo vault.
#
#   tools/demo-screenshots.sh <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>
#
# It installs the debug APK on the named device, wipes its data, grants the notification listener
# and POST_NOTIFICATIONS, walks onboarding, seeds the demo data through the debug-only broadcast
# receiver, and captures 1_inbox.png … 7_inbox_dark.png.
#
# Nothing here reads a real notification: every conversation in the shots comes from
# DemoDataRepository. Use an emulator — on a real phone the listener would copy the owner's own
# notifications into the debug vault.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <adb-serial> <en-US|zh-TW|zh-CN|ja-JP|ko-KR> <out-dir>" >&2
  exit 2
fi

SERIAL="$1"
LOCALE="$2"
OUT_DIR="$3"

case "$LOCALE" in
  en-US|zh-TW|zh-CN|ja-JP|ko-KR) ;;
  *) echo "locale must be one of en-US zh-TW zh-CN ja-JP ko-KR, got: $LOCALE" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="dev.quietinbox.app.debug"
MAIN_ACTIVITY="$APP_ID/dev.quietinbox.MainActivity"
LISTENER="$APP_ID/dev.quietinbox.platform.capture.QuietInboxListenerService"
DEMO_RECEIVER="$APP_ID/dev.quietinbox.debug.DemoReceiver"
DEMO_ACTION="dev.quietinbox.debug.DEMO"
# Matches DemoDataRepository.SEARCH_SAMPLE — several seeded bodies contain it, and it is ASCII, so
# `adb shell input text` can type it (the input command cannot send CJK).
SEARCH_QUERY="meeting"
# The one conversation DemoDataRepository pins, so the inbox always opens it first.
DEMO_PINNED_TITLE="林小美 Mia Lin"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

log() { printf '• %s\n' "$*" >&2; }
warn() { printf '! %s\n' "$*" >&2; }
die() { printf 'x %s\n' "$*" >&2; exit 1; }

device() { adb -s "$SERIAL" "$@"; }
shell() { adb -s "$SERIAL" shell "$@"; }

# ---------------------------------------------------------------- UI helpers

# A single python3 helper: reads a uiautomator dump on stdin and answers questions about it.
# Keeping it in one file avoids re-quoting XML through the shell.
HELPER="$WORK_DIR/uihelper.py"
cat > "$HELPER" <<'PYTHON'
import re
import sys
import xml.etree.ElementTree as ET


def nodes(tree):
    for node in tree.iter("node"):
        yield node


def bounds(node):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        return None
    left, top, right, bottom = (int(value) for value in match.groups())
    return left, top, right, bottom


def centre(box):
    left, top, right, bottom = box
    return (left + right) // 2, (top + bottom) // 2


def main():
    command = sys.argv[1]
    tree = ET.parse(sys.stdin)

    if command == "tap-text":
        wanted = set(sys.argv[2:])
        for node in nodes(tree):
            text = (node.get("text") or "").strip()
            description = (node.get("content-desc") or "").strip()
            if text in wanted or description in wanted:
                box = bounds(node)
                if box:
                    print("%d %d" % centre(box))
                    return 0
        return 1

    if command == "has-text":
        wanted = set(sys.argv[2:])
        for node in nodes(tree):
            if (node.get("text") or "").strip() in wanted:
                return 0
        return 1

    if command == "first-list-item":
        # The topmost clickable node that spans most of the width and sits between the app bar and
        # the navigation bar: the first row of whatever list is on screen, with no fixed position.
        # The width test is what separates a list row from a button, chip or navigation item.
        width = height = 0
        for node in nodes(tree):
            box = bounds(node)
            if box:
                width = max(width, box[2])
                height = max(height, box[3])
        top_limit = int(height * 0.14)
        bottom_limit = int(height * 0.88)
        minimum_width = int(width * 0.6)
        best = None
        for node in nodes(tree):
            if node.get("clickable") != "true":
                continue
            box = bounds(node)
            if not box:
                continue
            left, top, right, bottom = box
            if top < top_limit or bottom > bottom_limit:
                continue
            if (right - left) < minimum_width or (bottom - top) < 60:
                continue
            if best is None or top < best[1]:
                best = box
        if best is None:
            return 1
        print("%d %d" % centre(best))
        return 0

    raise SystemExit("unknown command: " + command)


sys.exit(main())
PYTHON

dump_ui() {
  # uiautomator writes to the device, so the dump has to be pulled back before parsing.
  shell uiautomator dump /sdcard/quietinbox-ui.xml >/dev/null 2>&1 || return 1
  device pull /sdcard/quietinbox-ui.xml "$WORK_DIR/ui.xml" >/dev/null 2>&1 || return 1
  [ -s "$WORK_DIR/ui.xml" ]
}

# tap_text "Label A" "標籤 B" — taps the first node whose text or description matches exactly.
tap_text() {
  dump_ui || return 1
  local point
  if ! point="$(python3 "$HELPER" tap-text "$@" < "$WORK_DIR/ui.xml")"; then
    return 1
  fi
  # shellcheck disable=SC2086
  shell input tap $point
  sleep 1
  return 0
}

has_text() {
  dump_ui || return 1
  python3 "$HELPER" has-text "$@" < "$WORK_DIR/ui.xml"
}

tap_first_list_item() {
  # The seed pins this conversation, so it is the inbox's first row in either language. Matching it
  # by title is steadier than any geometry; the geometric and fixed paths below are the fallbacks.
  if tap_text "$DEMO_PINNED_TITLE"; then
    sleep 1
    return 0
  fi
  if dump_ui; then
    local point
    if point="$(python3 "$HELPER" first-list-item < "$WORK_DIR/ui.xml")"; then
      # shellcheck disable=SC2086
      shell input tap $point
      sleep 2
      return 0
    fi
  fi
  warn "no list row found in the dump; falling back to a fixed position"
  local size width height
  size="$(shell wm size | tr -d '\r' | awk -F': *' '{print $2}' | tail -1)"
  width="${size%x*}"
  height="${size#*x}"
  shell input tap "$((width / 2))" "$((height / 4))"
  sleep 2
}

# ------------------------------------------------------------- screenshots

# `screencap -p` prepends a "[Warning] Multiple displays…" line on some foldable AVDs, which
# corrupts the PNG. Everything before the 8-byte PNG signature is dropped.
strip_png_prefix() {
  python3 - "$1" <<'PYTHON'
import sys

path = sys.argv[1]
with open(path, "rb") as handle:
    data = handle.read()
signature = b"\x89PNG\r\n\x1a\n"
offset = data.find(signature)
if offset < 0:
    raise SystemExit("no PNG signature in %s (%d bytes)" % (path, len(data)))
if offset > 0:
    with open(path, "wb") as handle:
        handle.write(data[offset:])
    print("stripped %d leading bytes from %s" % (offset, path), file=sys.stderr)
PYTHON
}

shot() {
  local name="$1"
  local path="$OUT_DIR/$name.png"
  sleep 1
  device exec-out screencap -p > "$path"
  strip_png_prefix "$path"
  log "captured $name.png"
}

# ------------------------------------------------------------------ labels

# Tab labels and the search hint per locale (nav_* / search_hint in core/designsystem strings).
case "$LOCALE" in
  zh-TW) NAV_INBOX="收件匣"; NAV_SEARCH="搜尋"; NAV_ACTIVITY="活動"; NAV_CAPTURE="擷取"; NAV_SETTINGS="設定"; SEARCH_HINT="搜尋已保存的副本" ;;
  zh-CN) NAV_INBOX="收件箱"; NAV_SEARCH="搜索"; NAV_ACTIVITY="活动"; NAV_CAPTURE="捕获"; NAV_SETTINGS="设置"; SEARCH_HINT="搜索已保存的副本" ;;
  ja-JP) NAV_INBOX="受信箱"; NAV_SEARCH="検索"; NAV_ACTIVITY="活動"; NAV_CAPTURE="キャプチャ"; NAV_SETTINGS="設定"; SEARCH_HINT="保存したコピーを検索" ;;
  ko-KR) NAV_INBOX="받은편지함"; NAV_SEARCH="검색"; NAV_ACTIVITY="활동"; NAV_CAPTURE="캡처"; NAV_SETTINGS="설정"; SEARCH_HINT="저장된 사본 검색" ;;
  *)     NAV_INBOX="Inbox"; NAV_SEARCH="Search"; NAV_ACTIVITY="Activity"; NAV_CAPTURE="Capture"; NAV_SETTINGS="Settings"; SEARCH_HINT="Search saved copies" ;;
esac
# Onboarding buttons are matched in English and in the requested language, so a device that
# ignores the locale request still completes the walkthrough.
case "$LOCALE" in
  zh-TW) OB_NEXT_ZH="下一步"; OB_START_ZH="開始使用"; OB_SKIP_ZH="略過" ;;
  zh-CN) OB_NEXT_ZH="下一步"; OB_START_ZH="开始使用"; OB_SKIP_ZH="跳过" ;;
  ja-JP) OB_NEXT_ZH="次へ"; OB_START_ZH="開始"; OB_SKIP_ZH="スキップ" ;;
  ko-KR) OB_NEXT_ZH="다음"; OB_START_ZH="시작"; OB_SKIP_ZH="건너뛰기" ;;
  *)     OB_NEXT_ZH="下一步"; OB_START_ZH="開始使用"; OB_SKIP_ZH="略過" ;;
esac
OB_NEXT_EN="Next"; OB_START_EN="Start"; OB_SKIP_EN="Skip"

# -------------------------------------------------------------------- run

command -v adb >/dev/null 2>&1 || die "adb is not on PATH"
command -v python3 >/dev/null 2>&1 || die "python3 is not on PATH"
device get-state >/dev/null 2>&1 || die "device $SERIAL is not available (adb devices)"

mkdir -p "$OUT_DIR"

APK=""
for candidate in \
  "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" \
  "$REPO_ROOT"/app/build/outputs/apk/*/debug/*.apk
do
  if [ -f "$candidate" ]; then APK="$candidate"; break; fi
done
[ -n "$APK" ] || die "no debug APK under app/build/outputs/apk — run ./gradlew :app:assembleDebug first"
log "installing $(basename "$APK")"
device install -r -d "$APK" >/dev/null

log "resetting app data and granting access"
shell pm clear "$APP_ID" >/dev/null
shell cmd notification allow_listener "$LISTENER" >/dev/null 2>&1 ||
  warn "could not grant the notification listener; the Capture page will show it as not granted"
shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

log "requesting app locale $LOCALE"
shell cmd locale set-app-locales "$APP_ID" --user 0 --locales "$LOCALE" >/dev/null 2>&1 ||
  warn "per-app locales need API 33+; the device language decides instead"

shell cmd uimode night no >/dev/null 2>&1 || true
log "launching $MAIN_ACTIVITY"
shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
sleep 3

log "walking through onboarding"
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if tap_text "$OB_START_EN" "$OB_START_ZH"; then
    log "onboarding finished"
    break
  fi
  # Step 4 offers "Skip" instead of sending a synthetic test notification: the demo data is what
  # these screenshots are for, and a real capture would add an unrelated conversation.
  tap_text "$OB_SKIP_EN" "$OB_SKIP_ZH" || tap_text "$OB_NEXT_EN" "$OB_NEXT_ZH" || sleep 1
done
sleep 2

log "seeding demo data"
shell am broadcast -a "$DEMO_ACTION" --es op seed -n "$DEMO_RECEIVER" >/dev/null
# The seed writes ~130 rows into an encrypted database; give it room before the first shot.
sleep 6

# 1 — inbox
tap_text "$NAV_INBOX" || warn "could not reach the inbox tab"
shot "1_inbox"

# 2 — a conversation (first row of the inbox)
tap_first_list_item
shot "2_conversation"
shell input keyevent KEYCODE_BACK
sleep 2

# 3 — search with a query typed
tap_text "$NAV_SEARCH" || warn "could not reach the search tab"
sleep 1
tap_text "$SEARCH_HINT" || warn "could not focus the search field"
shell input text "$SEARCH_QUERY"
sleep 3
shot "3_search"
shell input keyevent KEYCODE_BACK
sleep 1

# 4 — activity statistics
tap_text "$NAV_ACTIVITY" || warn "could not reach the activity tab"
sleep 3
shot "4_activity"

# 5 — capture health
tap_text "$NAV_CAPTURE" || warn "could not reach the capture tab"
sleep 2
shot "5_capture"

# 6 — settings
tap_text "$NAV_SETTINGS" || warn "could not reach the settings tab"
sleep 2
shot "6_settings"

# 7 — inbox in dark mode
tap_text "$NAV_INBOX" || warn "could not reach the inbox tab"
shell cmd uimode night yes >/dev/null 2>&1 || warn "could not switch the device to night mode"
sleep 3
shot "7_inbox_dark"
shell cmd uimode night no >/dev/null 2>&1 || true

log "done — screenshots are in $OUT_DIR"
ls -1 "$OUT_DIR"
