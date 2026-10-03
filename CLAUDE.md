# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

"Workout+" (`com.angkyria.karooworkout`) — a karoo-ext extension that brings the
Karoo 3 workout-drawer layout to the **Karoo 2** (primary target; Karoo 3 untested)
and **replaces the ride app's workout page** with it while a workout runs (other
pages stay usable; "cover every page" is a setting). Single Android app module
(`app/`), Kotlin, minSdk 26 (Karoo 2 runs Android 8.1 / API 27, 480x800, 300 dpi).
Modeled on Climber+ (github.com/hazzus/karoo-climber-plus). Status: **1.0, in use
on a Karoo 2** (past testing). Website: angkyria.github.io/karoo-workout-plus.

## Commands

Java is not on PATH — every gradle invocation needs:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

- Build:      `./gradlew assembleDebug` → `app/build/outputs/apk/debug/karoo-workout-plus-debug.apk`
- Tests:      `./gradlew testDebugUnitTest` (JVM-only; all logic is tested here)
- Single test: `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.WorkoutEngineTest"`
- Install:    `adb install -r app/build/outputs/apk/debug/karoo-workout-plus-debug.apk`
  then `adb shell appops set com.angkyria.karooworkout SYSTEM_ALERT_WINDOW allow`
- Release:    tag `v<versionName>` (plain vX.Y.Z) → `.github/workflows/release.yml`
  (needs the KEYSTORE_* secrets), or run that workflow by hand on main: it releases
  `v<versionName>` and creates the tag (cloud sessions can't push tags)
- CI:         `build.yml` on every branch push (tests incl. `RenderTest`, debug APK +
  screenshots as artifacts); `pages.yml` on main → `site/` + the renders deployed
  straight to GitHub Pages (Source "GitHub Actions", no site branch). `RenderTest` draws the real `WorkoutOverlayView` (Robolectric,
  native graphics) to `app/build/screenshots`; CI puts IBM Plex Sans Condensed at
  `/system/fonts` so the renders use the Karoo's font. Prefer checking layouts there.

## Device workflow

karoo-ext binds to Karoo OS services, so integration only runs on a real device
(Karoo OS binds the service from `io.hammerhead.appstore` right after install).
Demo mode (settings) feeds `debug/DemoWorkout.kt` through the real parse + engine path.

- Logs: `adb logcat -s WorkoutExtension` — page changes (`page workout=…
  elements=[…]`) and raw workout streams every 10 s during real workouts.
- The rider is often using the device while you work: check `dumpsys window
  windows | grep mCurrentFocus` and screenshot before injecting taps/keys, and put
  settings (demo mode) back the way you found them.
- Crashes: `adb logcat -d | grep -A 30 "FATAL EXCEPTION"`
- Screenshots: `adb exec-out screencap -p > file.png`
- If `adb shell` hangs while the device is listed: `adb kill-server` and retry.

## Architecture

Data flows one way: karoo-ext streams → `StreamHub` → `WorkoutStreams.snapshot` →
`WorkoutEngine` (pure) → `OverlayController` → `WorkoutOverlayView` (Canvas).

- **`WorkoutExtension`** (KarooExtension service): owns the KarooSystemService,
  consumers for RideState / UserProfile / ActiveRidePage, and the render loop
  (conflated, ≤4 Hz). Stream ownership: the gate stream
  (`WORKOUT_INTERVAL_COUNT`) runs while a ride is active; detail streams (plus the
  sensor stream of each targeted kind) only while a workout is loaded; page-field
  streams only while the full page is visible.
- **`data/`** — `WorkoutStreams` folds raw fields into `WorkoutSnapshot`;
  `WorkoutEngine` resolves targets (implied ±5 % power / ±7.5 % HR, status on
  rounded numbers, kind via the per-kind target streams + learned type ids) and
  builds the interval history from countdown deltas (pauses don't count; natural
  end vs skip; rewind / restart / new-workout detection). `WorkoutPage` recognizes
  the workout page from page elements.
- **`overlay/`** — `PanelMachine` (CHIP / DRAWER / FULL + takeover rules per
  `PageMode`: EVERY_PAGE re-covers on any real page change; minimize always goes
  straight to the chip; tapping the chip restores FULL), `OverlayController`
  (window, foreground service, key passthrough, full page = screen minus the
  taller of status bar / 32 dp ride header), `WorkoutOverlayView` (all drawing and
  gestures: top handle tap, fling or slow drag down = minimize, horizontal swipe =
  ride page change), `WorkoutColors` (native palette sampled from Hammerhead's
  drawer), `Format`. The page stacks PRIMARY / SECONDARY / TIMING / CORE_HEAT /
  FIELDS; TIMING is interval + workout in one block (the rider wanted them as one):
  header `INTERVAL n OF m` + workout time left, countdown, interval bar, then the
  graph (page) or a thin workout bar (drawer). The CORE row and the fields share
  `drawCell` (label top left, value bottom right, shrunk only to fit) and get the
  most height — the rider couldn't read them at the first sizes. Header text is
  capped by screen width and shrunk to fit its row: section heights change with the
  optional parts, and uncapped headers collided.
- Data fields: four slots; `WorkoutField.NONE` empties one. The grid shows the rest
  two a row (an odd one spans the row) and drops its section when all are NONE.
- **`WorkoutPageField`** — the "Workout+ page" graphical data type; a page marker
  (and a countdown under the overlay).
- **`data/CoreHeat`** — CORE sensor + CORE Heat extension (`TYPE_EXT::coreheat::*`,
  github.com/angkyria/karoo-core) ids, zones on the printed tenth, CORE colors. The
  CORE_TEMP / SKIN_TEMP streams also carry a data-quality flag: always read the
  named field (`WorkoutField.valueField`), never the first value.
- The workout page is **pinned** (`PanelMachine.pinned`): no handle, no minimize
  there — the rider asked for it; minimizing only exists on other pages.
- **`settings/`** — DataStore `SettingsRepo`. Every edit stores the whole settings,
  so changing the default page mode needs a `settings_version` bump (stored modes
  from older versions are dropped once; now 3, default REPLACE_WORKOUT_PAGE).
  Compose only in `SettingsActivity`.
- Ramps: Karoo streams the ramp's current point as the target value and its ends
  as min/max (137 in 137–187). `WorkoutEngine` flags a ramp per interval (value on
  an end, or moving between fixed ends) and holds the rider to value ±band.

## Facts verified on a Karoo 2 (ride app 4.197)

- The native workout page arrives via `ActiveRidePage` as one element: `TYPE_WORKOUT_ID`
  — but only for manual swipes. When a ride starts with a workout loaded, the ride
  app jumps to that page **without** an event; `PanelMachine.onWorkoutStarted(justStarted)`
  covers it, armed only when the extension witnessed the start (not after a restart).
- No ActiveRidePage event fires when leaving the ride app — that's why the
  overlay is gated on RideState Recording/Paused.
- With the power meter off, `WORKOUT_PRIMARY_TARGET_OUTPUT_VALUE` stays SEARCHING and
  `WORKOUT_PRIMARY_TARGET` streams nothing either; only `WORKOUT_POWER_TARGET`
  carries the target, so `WorkoutStreams` falls back to the per-kind streams.
- The workout output stream was never verified with the power meter **on**, and the
  rider reported no power on the page: a missing output falls back to the sensor
  streams (`WorkoutStreams.liveStreams`: 3 s or instant power, HR, cadence — one per
  targeted kind). The 10 s `streams` log and the diagnostics panel show both.
- Hardware keys (`/system/usr/keylayout/gpio-keys.kl`, `qpnp_pon.kl`): top left/right
  = NAVIGATE_PREVIOUS / NAVIGATE_NEXT, bottom left = BACK, bottom right = NAVIGATE_IN.
- `PerformHardwareAction` injects its key into the **focused window**: a focusable
  overlay received its own replayed page presses (device -1) and pages never
  changed. So the overlay is always FLAG_NOT_FOCUSABLE — hardware buttons stay
  native, and a swipe's replayed press reaches the ride app. (Climber+'s focusable
  panel + key replay pattern does not work for paging on a Karoo 2.)
- Workout fields observed: interval countdown 720000 = 12:00 (ms), workout remaining
  in ms, `WORKOUT_STATE` 2 while waiting at the start, 1 while running,
  `WORKOUT_DIFFICULTY` = 1.0 at 100 %.
- At a step change the step index moves a tick before the target streams: the new
  step's first sample carries the previous target. `Rec.observe` re-baselines when
  the target's min/max change inside a step (a real ramp keeps its ends).
- The ride app's header row (ride time, battery, clock) is 60 px = 32 dp; the system
  status bar is 45 px. The full page starts below the taller of the two.
- `/system/fonts/IBMPlexSansCondensed-Medium.otf` exists.

## Conventions / gotchas

- karoo-ext workout fields: times are milliseconds; `WORKOUT_CURRENT_STEP` is 0-based;
  target-type / workout-state codes are undocumented (see the diagnostics panel).
- karoo-ext has no skip/scale/workout-pause effects — only `PauseRide` /
  `ResumeRide`, which pause the whole ride. So there's no pause button (the rider
  asked for it gone); `PAUSED` shows while the ride is paused.
- `ViewEmitter.updateView` drops calls < 900 ms apart — `WorkoutPageField` paces itself.
- `OverlayController.show()` must not relayout before `addView` (relayout hooks are
  attached after the window exists). WindowManager calls are main-thread only.
- `WorkoutField` / enum names are persisted — never rename entries.
