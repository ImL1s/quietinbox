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
# notifications into the debug vault. Both layouts are handled: on a window narrower than 600dp the
# navigation is the bottom bar, on a wider one it is the left rail and the inbox keeps the conversation
# beside it (list-detail). The size floor is calibrated on QuietInbox_Phone (1080×2400); a tablet shot
# is larger still. Every shot is refused unless QuietInbox itself is the foreground app — the guard
# that was missing when the first tablet set captured the launcher and the system settings instead.
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
# Every screen in the set compresses to well over this once its content is on screen (the smallest
# real shot so far is ~140 KB); a loading placeholder is ~30 KB.
MIN_SHOT_BYTES=80000
# The one conversation DemoDataRepository pins, so the inbox always opens it first; DemoLocalisation
# renames it per app language.
case "$LOCALE" in
  ja-JP) DEMO_PINNED_TITLE="林 美咲 Misaki Hayashi" ;;
  ko-KR) DEMO_PINNED_TITLE="김미아 Mia Kim" ;;
  *)     DEMO_PINNED_TITLE="林小美 Mia Lin" ;;
esac
WORK_DIR="$(mktemp -d)"
# Narrow until the device says otherwise (screen_dp_width, in the run section). Declared here because
# tap_tab reads it and `set -u` would abort on a forward reference if a call ever moved earlier.
LAYOUT="narrow"

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


def screen(tree):
    width = height = 0
    for node in nodes(tree):
        box = bounds(node)
        if box:
            width = max(width, box[2])
            height = max(height, box[3])
    return width, height


def in_navigation_strip(box, layout, width, height):
    if layout == "narrow":
        return box[1] >= int(height * 0.85)
    return box[2] <= int(width * 0.15)


def navigation_item(tree, layout, wanted, width, height):
    # Every node carrying one of the labels and sitting in the navigation strip is a candidate; the
    # one that is furthest into the strip wins — lowest on a bottom bar, leftmost on a rail. Taking
    # the first match in document order instead would depend on where Compose happens to emit the
    # navigation relative to the content: on a 2076px tablet 15% is 311px, and a two-character CJK
    # heading in the content pane ends at about 300px, so it is a candidate too, just a worse one.
    # (The rail is 80dp wide; if this ever moves to the Expressive WideNavigationRail, up to 220dp
    # expanded, widen the strip with it.)
    best = None
    for node in nodes(tree):
        text = (node.get("text") or "").strip()
        description = (node.get("content-desc") or "").strip()
        if text not in wanted and description not in wanted:
            continue
        box = bounds(node)
        if not box or not in_navigation_strip(box, layout, width, height):
            continue
        if best is None:
            best = box
        elif layout == "narrow":
            best = box if box[1] > best[1] else best
        else:
            best = box if box[2] < best[2] else best
    return best


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

    if command == "tap-tab":
        # Like tap-text, but only the navigation item: the bottom bar (lowest 15%) on a narrow window,
        # the left rail (leftmost 15%) on a wide one, and the rail only on the layout that has one —
        # a short CJK heading in the top-left corner of a phone screen would otherwise pass the same
        # test. Accepting the rail at all is what makes the tablet run navigate: the bottom-bar-only
        # rule matched nothing there, every tab tap silently did nothing, and the shots were of
        # whatever was behind the app.
        layout = sys.argv[2]
        wanted = set(sys.argv[3:])
        width, height = screen(tree)
        box = navigation_item(tree, layout, wanted, width, height)
        if not box:
            return 1
        print("%d %d" % centre(box))
        return 0

    if command == "tab-selected":
        # The navigation item carrying one of these labels must be the selected one. Compose puts
        # selected=true on the item container (Modifier.selectable(role = Role.Tab)), not on the text
        # node, so the label's centre has to fall inside a node that is marked selected — and that
        # node must belong to the app and sit in the navigation strip itself, or a pulled-down
        # notification shade (its quick-settings tiles are selected too) or a selected filter chip
        # would answer for it. Without this check a tap that was swallowed — a busy frame, a stale
        # coordinate after a relayout — leaves the previous page on screen and the capture proceeds
        # against the wrong screen.
        package, layout = sys.argv[2], sys.argv[3]
        wanted = set(sys.argv[4:])
        width, height = screen(tree)
        box = navigation_item(tree, layout, wanted, width, height)
        if not box:
            return 1
        x, y = centre(box)
        for node in nodes(tree):
            if node.get("selected") != "true" or (node.get("package") or "") != package:
                continue
            marked = bounds(node)
            if not marked or not in_navigation_strip(marked, layout, width, height):
                continue
            if marked[0] <= x <= marked[2] and marked[1] <= y <= marked[3]:
                return 0
        return 1

    if command == "app-foreground":
        # At least one node on screen belongs to the app. The launcher, the system settings and a
        # crashed app all fail this; every screenshot is gated on it.
        package = sys.argv[2]
        for node in nodes(tree):
            if (node.get("package") or "") == package:
                return 0
        return 1

    if command == "has-english-clock":
        # An AM/PM time or an English month before a day number in one of the app's own nodes
        # (argv[2] is the package; the status bar clock belongs to SystemUI and is ignored): what
        # a CJK locale shows when the process default locale lagged behind the app language.
        package = sys.argv[2]
        pattern = re.compile(r"\b(AM|PM)\b|\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d")
        for node in nodes(tree):
            if (node.get("package") or "") != package:
                continue
            for value in (node.get("text") or "", node.get("content-desc") or ""):
                if pattern.search(value):
                    print(value.strip())
                    return 0
        return 1

    if command == "conversation-ready":
        # Narrow layout: the pinned title is on screen and no bottom-bar item with the inbox label
        # remains (the inbox row carries the same title; the bar is hidden on the conversation page).
        # Wide layout: the inbox stays beside the conversation, so the bar test cannot apply — the
        # detail pane has opened once the title appears twice, in the list row and in the detail
        # header. Either way "the inbox is still all there is" never passes as a conversation shot.
        title, inbox_tab, layout = sys.argv[2], sys.argv[3], sys.argv[4]
        height = 0
        for node in nodes(tree):
            box = bounds(node)
            if box:
                height = max(height, box[3])
        titles = 0
        for node in nodes(tree):
            text = (node.get("text") or "").strip()
            description = (node.get("content-desc") or "").strip()
            if text == title or description == title:
                titles += 1
            if layout == "narrow" and (text == inbox_tab or description == inbox_tab):
                box = bounds(node)
                if box and box[1] >= int(height * 0.85):
                    return 1
        if layout == "wide":
            return 0 if titles >= 2 else 1
        return 0 if titles >= 1 else 1

    if command == "has-text":
        wanted = set(sys.argv[2:])
        for node in nodes(tree):
            if (node.get("text") or "").strip() in wanted or (node.get("content-desc") or "").strip() in wanted:
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

# On Android 13+ the keyboard follows the app language, and a kana, hangul or pinyin layout composes
# the injected key events instead of passing the Latin letters through (round-19 finding). The
# default input method is disabled *before the app is launched* — once it has attached to a field,
# neither disabling nor switching it stops the composition — and restored at the end, also on exit.
# Android keeps at least one input method enabled, so this needs a second one (the AVD's voice
# input is enough); with a single one the query may still be composed and the search shot is refused.
IME_DEFAULT=""
IME_DISABLED=0
imes_off() {
  IME_DEFAULT="$(shell settings get secure default_input_method | tr -d '\r')"
  if [ -z "$IME_DEFAULT" ] || [ "$IME_DEFAULT" = "null" ]; then
    warn "no default input method is set; the search query may be composed by whatever handles the keys"
    return 0
  fi
  if [ "$(shell ime list -s | tr -d '\r' | grep -c .)" -lt 2 ]; then
    warn "only one input method is enabled; the search query may be composed by its layout"
  fi
  shell ime disable "$IME_DEFAULT" >/dev/null 2>&1 && IME_DISABLED=1 || warn "could not disable $IME_DEFAULT"
}
imes_on() {
  if [ "${IME_DISABLED:-0}" = 1 ]; then
    shell ime enable "$IME_DEFAULT" >/dev/null 2>&1 || true
    shell ime set "$IME_DEFAULT" >/dev/null 2>&1 || true
    IME_DISABLED=0
  fi
}
# Registered after the helpers it calls: under `set -e` a trap whose first command is missing would
# skip the rest of the cleanup as well.
trap 'imes_on; rm -rf "$WORK_DIR"' EXIT

# ime_shown — true while an input method window is on screen (field names differ across API levels).
ime_shown() {
  # API 36 prints `mInputShown=true` / `mImeWindowVis=3` (decimal); older builds print hex or
  # `isInputShown`. The dump is captured first: under `pipefail` a `grep -q` that exits early would
  # hand adb a SIGPIPE and turn a match into a failed pipeline.
  local dump
  dump="$(shell dumpsys input_method 2>/dev/null | tr -d '\r')"
  # `mIsInputViewShown` is the service's own flag and stays true after the window is gone.
  grep -qE '(mInputShown|isInputShown)=true|mImeWindowVis=(0x)?[1-9a-f]' <<< "$dump"
}


dump_ui() {
  # uiautomator writes to the device, so the dump has to be pulled back before parsing. It refuses
  # while the window is not idle, and every screenshot now depends on a dump, so one retry keeps a
  # busy frame from failing a run that would have succeeded a second later.
  local attempt
  for attempt in 1 2 3; do
    rm -f "$WORK_DIR/ui.xml"
    if shell uiautomator dump /sdcard/quietinbox-ui.xml >/dev/null 2>&1 &&
       device pull /sdcard/quietinbox-ui.xml "$WORK_DIR/ui.xml" >/dev/null 2>&1 &&
       [ -s "$WORK_DIR/ui.xml" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
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

# tap_tab "Label" — taps a navigation item (bottom bar or rail) by its label, ignoring look-alikes,
# and confirms afterwards that this item is the selected one. Tapping and hoping is exactly how a
# whole tablet set of screenshots came to be of the wrong screen: the tap did nothing, nothing said
# so, and the capture went ahead. A tap that does not take fails the run.
tap_tab() {
  dump_ui || { warn "tap_tab $1: could not read the screen"; return 1; }
  local point
  if ! point="$(python3 "$HELPER" tap-tab "$LAYOUT" "$@" < "$WORK_DIR/ui.xml")"; then
    warn "tap_tab $1: no navigation item with that label in the $LAYOUT navigation strip"
    return 1
  fi
  local attempt
  for attempt in 1 2 3 4 5; do
    # The tap is repeated, not just re-checked: a swallowed tap is the whole reason this guard
    # exists, and re-sending it costs a second where failing costs the locale's entire run.
    # shellcheck disable=SC2086
    shell input tap $point
    sleep 1
    dump_ui || continue
    python3 "$HELPER" tab-selected "$APP_ID" "$LAYOUT" "$@" < "$WORK_DIR/ui.xml" && return 0
  done
  warn "tap_tab $1: tapped $attempt times but the item never became the selected one"
  return 1
}

# assert_locale_clock — a CJK locale must not show an English AM/PM time or "Sep 3" date in the app's
# own nodes; that is the process default locale lagging behind the app language (round-21 finding),
# not a translation gap. It assumes an English device language (the project AVDs).
# In English the same detector must *find* the inbox clock ("7:33 AM"): the positive control that
# proves the package filter still sees the app's nodes and the pattern still bites.
assert_locale_clock() {
  dump_ui || die "uiautomator could not dump the screen before $1"
  local hit
  if [ "$LOCALE" = "en-US" ]; then
    if [ "$1" = "1_inbox" ] && ! python3 "$HELPER" has-english-clock "$APP_ID" < "$WORK_DIR/ui.xml" >/dev/null; then
      die "$1: the English inbox shows no AM/PM time — the clock detector no longer sees the app's nodes"
    fi
    return 0
  fi
  if hit="$(python3 "$HELPER" has-english-clock "$APP_ID" < "$WORK_DIR/ui.xml")"; then
    die "$1: English date/time on a $LOCALE screen (\"$hit\") — the process locale did not follow the app language"
  fi
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

# assert_app_foreground — QuietInbox itself must be the app on screen. The first tablet set was
# taken without this: every tab tap missed the rail, the run walked out to the launcher and the size
# floor happily passed 3.3 MB of wallpaper. A shot of anything else now fails the whole run.
assert_app_foreground() {
  dump_ui || die "$1: uiautomator could not dump the screen"
  python3 "$HELPER" app-foreground "$APP_ID" < "$WORK_DIR/ui.xml" \
    || die "$1: QuietInbox is not on screen (the navigation tap missed, or the app was left)"
}

shot() {
  local name="$1"
  local path="$OUT_DIR/$name.png"
  assert_app_foreground "$name"
  sleep 1
  device exec-out screencap -p > "$path"
  strip_png_prefix "$path"
  # A loading placeholder or a blank page compresses to a few tens of KB; a filled screen is far larger.
  local bytes
  bytes="$(wc -c < "$path" | tr -d ' ')"
  if [ "$bytes" -lt "$MIN_SHOT_BYTES" ]; then
    die "$name.png is only $bytes bytes — the screen was not ready (loading placeholder or empty page)"
  fi
  log "captured $name.png ($bytes bytes)"
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

# The app follows the window size class: below 600dp of width the navigation is a bottom bar and the
# conversation replaces the inbox; at or above it the navigation is a left rail and the conversation
# opens beside the inbox (`ListDetailSceneStrategy`). Everything that depends on where the navigation
# sits, or on whether the inbox stays on screen, reads this.
# Reads the physical display, so it assumes the device is upright and the app has the whole screen:
# a landscape phone or a split-window run would be judged narrow. A wrong judgement makes the tab
# taps fail loudly (tap_tab dies) rather than produce a wrong screenshot.
screen_dp_width() {
  local size density px
  size="$(shell wm size | tr -d '\r' | awk -F': *' '{print $2}' | tail -1)"
  density="$(shell wm density | tr -d '\r' | awk -F': *' '{print $2}' | tail -1)"
  px="${size%x*}"
  [ -n "$px" ] && [ -n "$density" ] && [ "$density" -gt 0 ] 2>/dev/null || return 1
  echo $(( px * 160 / density ))
}
if DP_WIDTH="$(screen_dp_width)"; then
  [ "$DP_WIDTH" -ge 600 ] && LAYOUT="wide"
  log "window width ${DP_WIDTH}dp -> $LAYOUT layout ($([ "$LAYOUT" = wide ] && echo 'navigation rail, list-detail' || echo 'bottom bar'))"
else
  warn "could not read the screen size; assuming the narrow (bottom bar) layout"
fi

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

# The app language goes in *before* anything that can start the process (granting the listener binds
# it and brings the process up): a per-app language applied to a live process reaches the resources
# but not the process default locale. `pm clear` also resets the language asynchronously, so the
# request is confirmed with get-app-locales and the process is stopped again before the launch.
log "requesting app locale $LOCALE"
app_locale_is() {
  local current
  current="$(shell cmd locale get-app-locales "$APP_ID" --user 0 2>/dev/null | tr -d '\r')"
  case "$current" in *"$LOCALE"*) return 0 ;; *) return 1 ;; esac
}
if shell cmd locale set-app-locales "$APP_ID" --user 0 --locales "$LOCALE" >/dev/null 2>&1; then
  for _ in 1 2 3 4 5; do
    app_locale_is && break
    sleep 1
    shell cmd locale set-app-locales "$APP_ID" --user 0 --locales "$LOCALE" >/dev/null 2>&1 || true
  done
  app_locale_is || warn "the app locale is not $LOCALE; screens will be in the device language"
else
  warn "per-app locales need API 33+; the device language decides instead"
fi

shell cmd notification allow_listener "$LISTENER" >/dev/null 2>&1 ||
  warn "could not grant the notification listener; the Capture page will show it as not granted"
shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
# Whatever the grants started must start over with the language in place.
shell am force-stop "$APP_ID" >/dev/null 2>&1 || true

shell cmd uimode night no >/dev/null 2>&1 || true
imes_off
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
# The demo names its language explicitly so the seed cannot lag behind the locale request.
shell am broadcast -a "$DEMO_ACTION" --es op seed --es lang "$LOCALE" -n "$DEMO_RECEIVER" >/dev/null
# The seed writes ~130 rows into an encrypted database; give it room before the first shot.
sleep 6

# 1 — inbox
tap_tab "$NAV_INBOX" || die "could not reach the inbox tab (reason above)"
assert_locale_clock "1_inbox"
shot "1_inbox"

# 2 — a conversation (first row of the inbox)
tap_first_list_item
# The conversation loads asynchronously: wait for the pinned conversation's title in the app bar (and
# a moment more for the list to settle at its newest message) instead of trusting a fixed delay.
# Ready = the pinned title is on screen *and* the bottom bar is gone on the narrow layout (the inbox
# shows the same title in its first row, and the bar is hidden on the conversation page); on the wide
# layout the inbox stays beside it, so ready means the title appears twice — once in the list row and
# once in the detail header. One UI dump per attempt.
conversation_ready() {
  dump_ui || return 1
  python3 "$HELPER" conversation-ready "$DEMO_PINNED_TITLE" "$NAV_INBOX" "$LAYOUT" < "$WORK_DIR/ui.xml"
}
for _ in 1 2 3 4 5 6 7 8 9 10; do
  conversation_ready && break
  sleep 1
done
conversation_ready || die "the conversation page did not settle after 10 attempts (a store screenshot must be the conversation, not the inbox)"
sleep 2
assert_locale_clock "2_conversation"
shot "2_conversation"
# Only the narrow layout needs to come back from the conversation: on the wide one the rail never
# left, and a BACK there pops the scene the app is standing on.
if [ "$LAYOUT" = "narrow" ]; then
  shell input keyevent KEYCODE_BACK
  sleep 2
fi

# 3 — search with a query typed
tap_tab "$NAV_SEARCH" || die "could not reach the search tab (reason above)"
sleep 1
tap_text "$SEARCH_HINT" || die "could not focus the search field"
# Let the input method window settle before the first key: keys injected while it is still coming
# up were seen to arrive out of order ("metinge"). One character per call keeps the order strict.
sleep 2
for ((i = 0; i < ${#SEARCH_QUERY}; i++)); do
  shell input text "${SEARCH_QUERY:$i:1}"
  sleep 0.3
done
sleep 1
# ENTER triggers the single-line field's Done action, which dismisses the input method (with the
# keyboard disabled that is the voice panel) and keeps the page and the query; the search itself is
# live, nothing is submitted.
shell input keyevent KEYCODE_ENTER
sleep 2
if ime_shown; then
  sleep 2
  ime_shown && die "an input method is still showing; a store screenshot must not include it"
fi
# The shot must show the query and its results, not what a keyboard layout made of the keystrokes.
# The field text can lag the last key by a moment, so the check is retried before it fails the run.
query_shown() {
  dump_ui || die "uiautomator could not dump the search screen"
  python3 "$HELPER" has-text "$SEARCH_QUERY" < "$WORK_DIR/ui.xml"
}
for _ in 1 2 3 4 5; do
  query_shown && break
  sleep 1
done
query_shown || die "the search field does not show \"$SEARCH_QUERY\" (an input method composed the keystrokes, or the page was left)"
shot "3_search"
if [ "$LAYOUT" = "narrow" ]; then
  shell input keyevent KEYCODE_BACK
  sleep 1
fi

# 4 — activity statistics
tap_tab "$NAV_ACTIVITY" || die "could not reach the activity tab (reason above)"
sleep 3
assert_locale_clock "4_activity"
shot "4_activity"

# 5 — capture health
tap_tab "$NAV_CAPTURE" || die "could not reach the capture tab (reason above)"
sleep 2
assert_locale_clock "5_capture"
shot "5_capture"

# 6 — settings
tap_tab "$NAV_SETTINGS" || die "could not reach the settings tab (reason above)"
sleep 2
shot "6_settings"

# 7 — inbox in dark mode
tap_tab "$NAV_INBOX" || die "could not reach the inbox tab (reason above)"
shell cmd uimode night yes >/dev/null 2>&1 || die "could not switch the device to night mode — 7_inbox_dark would be the light inbox"
sleep 3
shot "7_inbox_dark"
shell cmd uimode night no >/dev/null 2>&1 || true

log "done — screenshots are in $OUT_DIR"
ls -1 "$OUT_DIR"
