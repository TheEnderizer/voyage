#!/usr/bin/env bash
# Emulator test harness for Voyage / Better Audio.
#
#   ./scripts/emu.sh boot         # start the AVD headless and wait for boot
#   ./scripts/emu.sh build        # assembleDebug + unit tests
#   ./scripts/emu.sh install      # install the debug APK + grant permissions
#   ./scripts/emu.sh seedlib      # generate a synthetic library and push it
#   ./scripts/emu.sh launch       # cold-start the app, reporting TotalTime
#   ./scripts/emu.sh shot NAME    # screenshot -> scripts/shots/NAME.png
#   ./scripts/emu.sh ui           # dump the visible text + bounds (uiautomator)
#   ./scripts/emu.sh log [TAGS]   # app log tail (default: the app's own tags)
#   ./scripts/emu.sh sql "QUERY"  # run SQL against the on-device database
#   ./scripts/emu.sh baseline     # dump DB tables + Home/Series/Book Info/Player shots
#   ./scripts/emu.sh upgrade [OLD_APK]  # fresh-install OLD_APK (default: latest beta via
#                                  # gh), onboard+scan, then install current build over it
#                                  # and assert row counts survive (the real migration test)
#   ./scripts/emu.sh all          # boot + build + install + seedlib + launch
#
# Notes
#  - Run from Git Bash. MSYS_NO_PATHCONV=1 is required or /sdcard/... is
#    rewritten into a Windows path; every adb call here sets it.
#  - Avoid spaces in on-device paths: `adb push` mangles them under MSYS.
#  - The app has no native libraries any more (Vosk went with the EPUB reader),
#    so the release APK is ABI-agnostic and everything runs on an x86_64 image.

set -uo pipefail
export MSYS_NO_PATHCONV=1

SDK="${ANDROID_SDK_ROOT:-C:/Users/hajmo/AppData/Local/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
EMULATOR="$SDK/emulator/emulator.exe"
AVD="${VOYAGE_AVD:-onplus_13_cph2649}"
SERIAL="${VOYAGE_SERIAL:-emulator-5554}"

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Android\\Android Studio\\jbr}"
FFMPEG="${FFMPEG:-C:/ffmpeg/ffmpeg-8.1.2-essentials_build/bin/ffmpeg}"
GH="${GH:-C:/Program Files/GitHub CLI/gh.exe}"

PKG="com.betteraudio"
DB="/data/data/$PKG/databases/betteraudio.db"
REMOTE_LIB="/sdcard/Audiobooks"
SHOTS="$PROJ/scripts/shots"
SEED="$PROJ/scripts/.testlib"

a() { "$ADB" -s "$SERIAL" "$@"; }

boot() {
  if a get-state >/dev/null 2>&1; then echo "already running"; return 0; fi
  echo "booting $AVD (headless)…"
  "$EMULATOR" -avd "$AVD" -no-window -no-boot-anim -no-snapshot-save \
    -gpu swiftshader_indirect -memory 4096 -netdelay none -netspeed full \
    >/dev/null 2>&1 &
  until [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
  echo "booted: Android $(a shell getprop ro.build.version.release | tr -d '\r') / $(a shell getprop ro.product.cpu.abi | tr -d '\r')"
}

build() {
  # MSYS_NO_PATHCONV=1 (needed for adb's /sdcard/... calls elsewhere in this script) also
  # suppresses the POSIX->Windows conversion java.exe needs for -classpath, so convert by hand.
  local proj_win; proj_win="$(cygpath -w "$PROJ")"
  ( cd "$PROJ" && JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java.exe" \
      -Dorg.gradle.appname=gradlew \
      -classpath "$proj_win/gradle/wrapper/gradle-wrapper.jar" \
      org.gradle.wrapper.GradleWrapperMain \
      :app:assembleDebug :app:testDebugUnitTest --console=plain ) | tail -20
}

install() {
  local apk_win; apk_win="$(cygpath -w "$PROJ/app/build/outputs/apk/debug/app-debug.apk")"
  a install -r -t "$apk_win" | tail -2
  a shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  a shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null
  a shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  echo "installed + permissions granted"
}

# Drive first-run onboarding (import-structure picker -> theme picker -> scan) on a truly
# fresh install. Needed by `upgrade` below, which must start from a real "just installed,
# never configured" state to exercise the actual migration path a real user hits.
onboard() {
  sleep 2
  tap_cd "Automatic" || return 1
  sleep 1
  tap_cd "Continue" || return 1     # closes the import-structure dialog
  sleep 1
  tap_cd "Continue" || return 1     # closes the theme-picker dialog (Material You default)
  sleep 1
  tap_cd "Scan Library" || return 1
  sleep 3
}

# The single highest-leverage item in Gate 0: fresh-install testing (what `all` does) proves
# nothing about migrations, since Room never runs one against a database that doesn't exist
# yet. This installs an OLD released APK, onboards + scans a real library, then installs the
# CURRENT locally-built APK over it (data preserved) and asserts the row counts survive.
upgrade() {
  local old_apk="${1:-}"
  if [ -z "$old_apk" ]; then
    echo "no OLD_APK given — fetching the latest beta prerelease via gh..."
    local tag; tag="$("$GH" release list --repo TheEnderizer/voyage --limit 10 2>/dev/null \
      | awk -F'\t' '$2=="Pre-release"{print $3; exit}')"
    if [ -z "$tag" ]; then echo "upgrade: could not find a prerelease via gh" >&2; return 1; fi
    old_apk="$PROJ/scripts/.upgrade_old.apk"
    "$GH" release download "$tag" --repo TheEnderizer/voyage --pattern "*.apk" \
      -O "$(cygpath -w "$old_apk")" --clobber || return 1
    echo "fetched $tag -> $old_apk"
  fi
  [ -f "$old_apk" ] || { echo "upgrade: OLD_APK not found: $old_apk" >&2; return 1; }

  echo "== fresh install: $old_apk =="
  a uninstall $PKG >/dev/null 2>&1
  a install -r -t "$(cygpath -w "$old_apk")" | tail -2
  a shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  a shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null
  a shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null

  seedlib
  a shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1
  onboard || { echo "upgrade: onboarding automation failed — aborting" >&2; return 1; }

  local before
  before="$(a shell "run-as $PKG sqlite3 $DB \"SELECT (SELECT COUNT(*) FROM books)||'/'||(SELECT COUNT(*) FROM series)||'/'||(SELECT COUNT(*) FROM playback_progress)\"" | tr -d '\r')"
  echo "before upgrade: books/series/progress = $before"
  if [ -z "$before" ] || [ "$before" = "0/0/0" ]; then
    echo "upgrade: old build imported nothing — aborting before touching it" >&2; return 1
  fi

  echo "== build + install the current tree over it (data preserved) =="
  build
  install >/dev/null
  a shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1
  sleep 3

  local after
  after="$(a shell "run-as $PKG sqlite3 $DB \"SELECT (SELECT COUNT(*) FROM books)||'/'||(SELECT COUNT(*) FROM series)||'/'||(SELECT COUNT(*) FROM playback_progress)\"" | tr -d '\r')"
  echo "after upgrade:  books/series/progress = $after"

  if [ "$before" = "$after" ]; then
    echo "upgrade: OK — row counts identical, database opened fine after upgrade"
  else
    echo "upgrade: MISMATCH before='$before' after='$after'" >&2
    return 1
  fi
}

# Synthetic library covering each scanner heuristic in AudioFileScanner.
# MSYS_NO_PATHCONV=1 (set globally, needed for the /sdcard/... adb calls below) also stops
# MSYS from converting this POSIX output path for ffmpeg.exe (a native binary) — convert by
# hand, or every file silently fails to write and seedlib "succeeds" with zero files.
mk() {
  local out; out="$(cygpath -w "$1")"
  "$FFMPEG" -f lavfi -i "sine=frequency=220:duration=$2" \
    -metadata album="$3" -metadata artist="$4" -metadata title="$5" \
    -metadata track="$6" -b:a 32k -y "$out" >/dev/null 2>&1
}

seedlib() {
  rm -rf "$SEED"; mkdir -p "$SEED"
  # one book, sequential tracks
  mkdir -p "$SEED/TheHobbit"
  for i in 1 2 3; do mk "$SEED/TheHobbit/0$i - Chapter $i.mp3" 3 "The Hobbit" "J.R.R. Tolkien" "Chapter $i" $i; done
  # two ALBUM tags in one folder -> split into two books
  mkdir -p "$SEED/MixedFolder"
  for i in 1 2; do mk "$SEED/MixedFolder/dune-0$i.mp3"  3 "Dune"        "Frank Herbert"  "Part $i" $i; done
  for i in 1 2; do mk "$SEED/MixedFolder/neuro-0$i.mp3" 3 "Neuromancer" "William Gibson" "Part $i" $i; done
  # disc-split -> merged into ONE book
  mkdir -p "$SEED/Mistborn/Mistborn (1 of 2)" "$SEED/Mistborn/Mistborn (2 of 2)"
  for i in 1 2; do mk "$SEED/Mistborn/Mistborn (1 of 2)/0$i - track.mp3" 3 "" "Brandon Sanderson" "d1t$i" $i; done
  for i in 1 2; do mk "$SEED/Mistborn/Mistborn (2 of 2)/0$i - track.mp3" 3 "" "Brandon Sanderson" "d2t$i" $i; done
  # inline volume numbers -> split into three books
  mkdir -p "$SEED/ShadowSlave"
  for i in 7 8 9; do mk "$SEED/ShadowSlave/Shadow Slave Volume $i.mp3" 3 "" "Guiltythree" "Vol $i" 1; done
  # "pt N" -> must NOT split
  mkdir -p "$SEED/SingleBookParts"
  for i in 1 2 3; do mk "$SEED/SingleBookParts/story pt $i.mp3" 3 "" "Test Author" "pt $i" $i; done
  # a long file, for position/resume and sleep-timer testing
  mkdir -p "$SEED/LongBook"
  mk "$SEED/LongBook/01 - Ch1.mp3" 600 "Long Book" "Test" "Ch1" 1
  # genuine nested series (container with >1 book sub-folders, no direct audio, not a
  # disc/part split) -> a real Series row with 2 ordered members, for G1-1's depth-guard
  # fixture and so the baseline has an actual Series screen to capture.
  mkdir -p "$SEED/Foundation/Foundation 01 - Foundation" "$SEED/Foundation/Foundation 02 - Foundation and Empire"
  for i in 1 2; do mk "$SEED/Foundation/Foundation 01 - Foundation/0$i - Ch$i.mp3" 3 "" "Isaac Asimov" "Ch$i" $i; done
  for i in 1 2; do mk "$SEED/Foundation/Foundation 02 - Foundation and Empire/0$i - Ch$i.mp3" 3 "" "Isaac Asimov" "Ch$i" $i; done

  a shell rm -rf "$REMOTE_LIB"
  a shell mkdir -p "$REMOTE_LIB"
  a push "$(cygpath -w "$SEED")/." "$REMOTE_LIB/" | tail -1
  echo "on device: $(a shell "find $REMOTE_LIB -name '*.mp3' | wc -l" | tr -d '\r') files"
  echo
  echo "Expected after a scan: 11 books, 1 series"
  echo "  Mistborn 1 (4 files) · Dune 1 · Neuromancer 1 · Vol7/8/9 3 · SingleBookParts 1 · TheHobbit 1 · LongBook 1"
  echo "  Foundation series: Foundation 1, Foundation and Empire 1"
}

launch() {
  a logcat -c
  a shell am start -W -n $PKG/.MainActivity 2>&1 | grep -E "Status|TotalTime|LaunchState"
}

shot() {
  mkdir -p "$SHOTS"
  local name="${1:-shot}"
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$(cygpath -w "$SHOTS/$name.png")" >/dev/null 2>&1
  echo "$SHOTS/$name.png"
}

ui() {
  a shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
  a shell cat /sdcard/_ui.xml 2>/dev/null | sed 's/>/>\n/g' \
    | grep -oE 'text="[^"]+"[^/]*bounds="[^"]+"' \
    | sed -E 's/ resource-id=.*bounds=/  |  /'
}

# Tap the center of the first element whose content-desc or text matches exactly. More
# resilient to layout/coordinate drift across app versions than hardcoded pixel taps.
tap_cd() {
  a shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
  local dump; dump="$(a shell cat /sdcard/_ui.xml 2>/dev/null | sed 's/>/>\n/g')"
  local line; line="$(echo "$dump" | grep -F "content-desc=\"$1\"" | head -1)"
  [ -z "$line" ] && line="$(echo "$dump" | grep -F "text=\"$1\"" | head -1)"
  if [ -z "$line" ]; then echo "tap_cd: '$1' not found on screen" >&2; return 1; fi
  local nums; nums="$(echo "$line" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')"
  local x1 y1 x2 y2
  x1=$(echo "$nums" | sed -n 1p); y1=$(echo "$nums" | sed -n 2p)
  x2=$(echo "$nums" | sed -n 3p); y2=$(echo "$nums" | sed -n 4p)
  a shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
}

logs() { a logcat -d -v time -s "${@:-Scan:* Player:* Service:* Widget:* DB:* Nav:* App:*}"; }

dump_ui() { a shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1; a shell cat /sdcard/_ui.xml 2>/dev/null; }

foreground_pkg() {
  a shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' | cut -d/ -f1 | tr -d '\r'
}

# A mistimed tap during a sheet-close animation can land on whatever's behind it (seen once:
# a stray tap during the series-overlay close animation opened the launcher's Messages icon).
# Verify we're still in the app before continuing rather than let taps cascade into another app.
ensure_app() {
  if [ "$(foreground_pkg)" = "$PKG" ]; then return 0; fi
  echo "ensure_app: left $PKG (now in $(foreground_pkg)) — relaunching" >&2
  a shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1
  sleep 2
  return 1
}

# The Home view-cycle button (Books -> Series -> Authors -> Books) renders with no
# content-desc/text at runtime despite the source passing one (PillSlot's semantics don't
# surface it to uiautomator) — screen-center, ~93.6% down, is where it sits regardless of
# resolution since the nav pill is bottom-anchored.
tap_viewcycle() {
  local wh; wh="$(a shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr -d '\r')"
  local sw="${wh%x*}" sh="${wh#*x}"
  a shell input tap $(( sw/2 )) $(( sh*9356/10000 ))
}

# The system Back button reaching the Series overlay's nested NavHost was observed to finish
# MainActivity outright (revealing whatever task was underneath in recents) instead of just
# closing the overlay — use each screen's own chevron-back button instead, never system back.
tap_backchevron() {
  local wh; wh="$(a shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr -d '\r')"
  local sw="${wh%x*}" sh="${wh#*x}"
  a shell input tap $(( sw*85/1000 )) $(( sh*483/10000 ))
}

home_mode() {
  local d; d="$(dump_ui)"
  # Check titles/authors FIRST: Series/Authors mode cards carry their own "N books" label
  # (e.g. "9 books · Audiobooks"), which would otherwise be mistaken for the Books-mode header.
  if echo "$d" | grep -qE 'text="[0-9]+ titles"'; then echo series
  elif echo "$d" | grep -qE 'text="[0-9]+ authors"'; then echo authors
  elif echo "$d" | grep -qE 'text="[0-9]+ books"'; then echo books
  else echo unknown; fi
}

# Cycle the view-cycle button (at most 2 taps) until Home reports the target mode.
goto_mode() {
  local target="$1" tries=0
  while [ "$(home_mode)" != "$target" ] && [ "$tries" -lt 3 ]; do
    ensure_app || return 1
    tap_viewcycle; sleep 2; tries=$((tries+1))
  done
  ensure_app || return 1
  [ "$(home_mode)" = "$target" ]
}

# Freeze a behavioural baseline from the seeded library: books/series/audio_files/
# playback_progress dumped to text (read-only, done FIRST so screenshot navigation below
# never perturbs what's diffed), plus Home/Series/Book Info/Player screenshots.
# Precondition: library already scanned once (folder configured, onboarding complete) and
# at least one book already played once (so a mini-player exists to reach the Player
# screen without this command itself starting new playback and moving position/progress).
baseline() {
  local dir="$PROJ/scripts/baseline"
  mkdir -p "$dir"

  for t in books series audio_files; do
    a shell "run-as $PKG sqlite3 $DB \"SELECT * FROM $t ORDER BY id\"" > "$dir/$t.txt"
  done
  a shell "run-as $PKG sqlite3 $DB \"SELECT * FROM playback_progress ORDER BY bookId\"" > "$dir/playback_progress.txt"
  echo "dumped: $(ls "$dir"/*.txt | wc -l) tables -> $dir"

  # Relaunch explicitly rather than pressing back — back on an already-Home screen with an
  # empty back-stack exits to the launcher instead of "collapsing", stranding every tap below.
  a shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1
  sleep 2
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$(cygpath -w "$dir/home.png")" >/dev/null 2>&1

  goto_mode series || { echo "baseline: could not reach Series view — aborting" >&2; return 1; }
  tap_cd "Foundation" || return 1              # open the series detail overlay
  sleep 3                                      # cover-morph open animation needs more than 1s
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$(cygpath -w "$dir/series.png")" >/dev/null 2>&1
  tap_backchevron                              # close series overlay, back to Series grid
  sleep 2
  ensure_app || { echo "baseline: lost the app after closing the series overlay — aborting" >&2; return 1; }

  goto_mode books || { echo "baseline: could not reach Books view — aborting" >&2; return 1; }
  tap_cd "Dune" || return 1                    # any seeded book card -> Book Info
  sleep 2
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$(cygpath -w "$dir/book_info.png")" >/dev/null 2>&1
  tap_backchevron
  sleep 2
  ensure_app || { echo "baseline: lost the app after closing Book Info — aborting" >&2; return 1; }

  # Expand the mini bar (does not mutate playback state) by tapping its title-text zone —
  # resolution-independent fraction of screen size, since the bar has no content-desc of its
  # own and its title text also appears (ambiguously) on the library grid card above it.
  local wh; wh="$(a shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr -d '\r')"
  local sw="${wh%x*}" sh="${wh#*x}"
  a shell input tap $(( sw*35/100 )) $(( sh*867/1000 ))
  sleep 2
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$(cygpath -w "$dir/player.png")" >/dev/null 2>&1
  tap_backchevron
  sleep 1
  ensure_app || echo "baseline: left the app collapsing the player (screenshots already saved)" >&2

  echo "screenshots -> $dir/{home,series,book_info,player}.png"
}

sql() { a shell "run-as $PKG sqlite3 $DB \"$1\""; }

case "${1:-all}" in
  boot) boot ;;
  build) build ;;
  install) install ;;
  seedlib) seedlib ;;
  launch) launch ;;
  shot) shot "${2:-shot}" ;;
  ui) ui ;;
  log|logs) shift; logs "$@" ;;
  sql) sql "${2:?usage: emu.sh sql \"SELECT ...\"}" ;;
  baseline) baseline ;;
  upgrade) upgrade "${2:-}" ;;
  all) boot && build && install && seedlib && launch ;;
  *) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \?//' ;;
esac
