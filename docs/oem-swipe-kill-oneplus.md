# Playback stops when the app is swiped from recents (OnePlus / ColorOS)

**Investigated:** 2026-08-15 · **Device:** OnePlus CPH2649 (OP5D55L1), Android 16 / ColorOS "exp rom"
**App build:** Voyage 1.9.19b (versionCode 68), release-signed
**Verdict:** Not a Voyage bug. The ROM SIGKILLs the process. No app-side code change tried so far can prevent it.

## Symptom

Swipe Voyage away from recents while a book is playing → audio stops. Reopening the app shows the
book paused, ~30 s behind where it was.

## Root cause

ColorOS's task-clear path (`AthenaKillerManagerService` → `OplusClearSystemService`) kills the whole
process on swipe-up. Captured with `adb logcat` at the moment of the swipe:

```
Athena: SwipeUpClearAction: stop type is 1
Athena: SwipeUpClearAction: current is exp rom, skip to kill
Athena: SwipeUpClearAction: audio is playing: [10501]com.betteraudio, flags:0, packageType:non-icon(not checked)|non-game(not checked)
Athena: SwipeUpClearAction: remove task, taskId: 20631
Athena: OplusClearSystemService: K [0,22323,10501,com.betteraudio,200, reason: 40 o-kill(40) K|null with swipe up]
ActivityManager: Death received, pid = 22323, processName = com.betteraudio
ActivityManager: Process com.betteraudio (pid 22323) has died: prcp FGS
```

Points worth noting:

- **The ROM explicitly detects the audio** (`audio is playing: [10501]com.betteraudio`) and kills
  anyway. `flags:0` is its protection-flag lookup coming back empty; nothing tried below made it
  non-zero.
- **`prcp FGS`** — at the instant of death the service was a healthy `mediaPlayback` foreground
  service. Verified immediately before the swipe with `dumpsys activity services com.betteraudio`:
  `isForeground=true foregroundId=1001 types=0x00000002`, `startRequested=true`, `stopIfKilled=false`.
  So this is not Media3 failing to promote, and not `ensureStartedService` failing to stick.
- **`onTaskRemoved` never runs.** With `setprop log.tag.PLAYBACK INFO` enabled, no `onTaskRemoved`
  or `onDestroy` line is ever emitted — the process is gone before any lifecycle callback. The
  reasoning documented in `PlaybackService.onTaskRemoved` (deliberately not calling `super`) is
  correct and simply never executes on this device.
- **START_STICKY doesn't rescue it.** `MediaSessionService.onStartCommand` returns START_STICKY and
  the service was started (`startRequested=true`), but no `ServiceRecord` exists afterwards — the
  clear action cancels the pending restart. The process that reappears seconds later is spawned by
  `VoyageWidgetProvider` for a widget render, not by a service restart.

## A/B: it is the ROM, not the app

Same device, same gesture, same session:

| App | Audio playing | Task removed | Process |
|---|---|---|---|
| Voyage | yes | yes | **killed** (`OplusClearSystemService: K`) |
| Smart AudioBook Player (`ak.alizandro.smartaudiobookplayer`) | yes | yes | **killed**, byte-for-byte identical Athena log |
| AliExpress (`com.alibaba.aliexpresshd`) | no | yes | **survived** — no `audio is playing` line, no `K` line at all |

A mature third-party audiobook player fails exactly the same way. An app with no audio survives the
identical gesture. Holding an audio foreground service is what selects an app for the kill.

## Workarounds tested — all ineffective

Each was tested with a full cycle: apply → launch → start playback → confirm `state=PLAYING` in
`dumpsys media_session` → open recents → swipe → check pid and session.

| Attempt | How | Result |
|---|---|---|
| AOSP battery-optimization whitelist | `dumpsys deviceidle whitelist +com.betteraudio` | killed |
| Locked in recents | already locked — card menu reads "Unlock" | killed |
| ColorOS auto-clear off | `settings put system auto_clear_swith 0` | killed |
| OEM "Allow background activity" | App info → Battery usage (was "Smart mode"); needs the confirm dialog, a bare radio tap silently does nothing | killed, still `flags:0` |

### Testing pitfall that produced one false positive

Driving the repro over adb with repeated `am start -n com.betteraudio/.MainActivity` can leave
**two** Voyage tasks in recents. Swiping one away then removes a task while another remains, and
Athena does **not** kill the process — which looks exactly like a successful fix. Always confirm
with `dumpsys activity recents | grep A=10501:com.betteraudio` that exactly one task exists before
swiping, and that zero remain after.

Also: `input keyevent 187` (APP_SWITCH) re-opens the app instead of showing recents when the app is
already foreground. Press HOME first. And `input keyevent 126` (MEDIA_PLAY) can be grabbed by
another app's media session — start playback deterministically with:

```bash
adb shell am start-foreground-service -n com.betteraudio/.playback.PlaybackService -a com.betteraudio.action.WIDGET_PLAY_PAUSE
```

## Knock-on bug: up to 30 s of progress lost per swipe

Because it is a SIGKILL, every flush path is skipped — `onTaskRemoved`, `onDestroy`, and the
pause/stop/media-item-transition hooks all fail to run. The only write that survives is the last
tick of the 30-second `startPositionSaver` loop in `PlaybackService`.

Measured: killed at roughly 15:10 into the chapter, reopened at **14:40**.

This part *is* fixable in app code (shrink the interval, flush on more events) and is independent of
whether playback itself can be kept alive. Not done — deferred by request.

## The one untested app-side lever

Run `PlaybackService` in its own process (`android:process=":playback"`) so the OEM's kill of the
UI process may not reach the player. Unverified, and the evidence is ambiguous: the `K` log line
names a single pid, but `SwipeUpClearAction` appears to enumerate the package's processes, so a
package-wide sweep would kill both.

Cost if attempted:

- `SettingsStore`'s DataStore is **not** multi-process safe — would need `MultiProcessDataStore`.
- Hilt `@Singleton`s duplicate per process (`PlayerController`, `SeriesPlayer`, `WidgetUpdater`,
  `DiskMirror`, `SleepTimerEngine` …) — every piece of shared in-memory state crosses a boundary.
- Room needs `enableMultiInstanceInvalidation()` or the UI process serves stale queries.
- `WidgetUpdater.push` is called from the service but the editor/gallery ViewModels write designs
  from the UI process.

Not a small change, and it may buy nothing. Decide before starting.

## Reproducing / re-verifying

```bash
adb shell setprop log.tag.PLAYBACK INFO
adb logcat -c
# start playback, confirm: dumpsys media_session | grep -A12 'com.betteraudio/androidx'
# swipe the card away, then:
adb logcat -d -v time | grep -E "SwipeUpClear|OplusClearSystem|has died"
```

A surviving fix shows **no** `OplusClearSystemService: K` line and the same pid in `ps -A | grep betteraudio`.

## Device state left changed on the dev phone

Left applied (both harmless, neither helped — revert if you want a clean baseline):

- `com.betteraudio` added to the deviceidle battery whitelist — remove with
  `adb shell dumpsys deviceidle whitelist -com.betteraudio`
- OEM per-app power mode set to **Allow background activity** (was **Smart mode**) — App info →
  Battery usage

Restored already: `settings put system auto_clear_swith 1` (original value). The `log.tag.*`
setprops clear on reboot.
