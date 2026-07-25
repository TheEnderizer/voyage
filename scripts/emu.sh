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
#   ./scripts/emu.sh all          # boot + build + install + seedlib + launch
#
# Notes
#  - Run from Git Bash. MSYS_NO_PATHCONV=1 is required or /sdcard/... is
#    rewritten into a Windows path; every adb call here sets it.
#  - Avoid spaces in on-device paths: `adb push` mangles them under MSYS.
#  - The APK ships arm64-v8a only (Vosk), so speech alignment cannot run on an
#    x86_64 emulator. Everything else works. To test alignment, add x86_64 to
#    `ndk { abiFilters }` in app/build.gradle.kts for local builds.

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
  ( cd "$PROJ" && JAVA_HOME="$JAVA_HOME" "$JAVA_HOME/bin/java.exe" \
      -Dorg.gradle.appname=gradlew \
      -classpath "$PROJ/gradle/wrapper/gradle-wrapper.jar" \
      org.gradle.wrapper.GradleWrapperMain \
      :app:assembleDebug :app:testDebugUnitTest --console=plain ) | tail -20
}

install() {
  a install -r -t "$PROJ/app/build/outputs/apk/debug/app-debug.apk" | tail -2
  a shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
  a shell pm grant $PKG android.permission.READ_MEDIA_AUDIO 2>/dev/null
  a shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
  echo "installed + permissions granted"
}

# Synthetic library covering each scanner heuristic in AudioFileScanner.
mk() { "$FFMPEG" -f lavfi -i "sine=frequency=220:duration=$2" \
        -metadata album="$3" -metadata artist="$4" -metadata title="$5" \
        -metadata track="$6" -b:a 32k -y "$1" >/dev/null 2>&1; }

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

  a shell mkdir -p "$REMOTE_LIB"
  a push "$SEED/." "$REMOTE_LIB/" | tail -1
  echo "on device: $(a shell "find $REMOTE_LIB -name '*.mp3' | wc -l" | tr -d '\r') files"
  echo
  echo "Expected after a scan: 9 books"
  echo "  Mistborn 1 (4 files) · Dune 1 · Neuromancer 1 · Vol7/8/9 3 · SingleBookParts 1 · TheHobbit 1 · LongBook 1"
}

launch() {
  a logcat -c
  a shell am start -W -n $PKG/.MainActivity 2>&1 | grep -E "Status|TotalTime|LaunchState"
}

shot() {
  mkdir -p "$SHOTS"
  local name="${1:-shot}"
  a shell screencap -p /sdcard/_s.png
  a pull /sdcard/_s.png "$SHOTS/$name.png" >/dev/null 2>&1
  echo "$SHOTS/$name.png"
}

ui() {
  a shell uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1
  a shell cat /sdcard/_ui.xml 2>/dev/null | tr '>' '>\n' \
    | grep -oE 'text="[^"]+"[^/]*bounds="[^"]+"' \
    | sed -E 's/ resource-id=.*bounds=/  |  /'
}

logs() { a logcat -d -v time -s "${@:-Scan:* Player:* Service:* Widget:* DB:* Nav:* App:*}"; }

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
  all) boot && build && install && seedlib && launch ;;
  *) sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \?//' ;;
esac
