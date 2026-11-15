# Workout complete page + chip with navigation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "WORKOUT COMPLETE" page (target blocks + ridden line, totals, interval table) when a workout ends, and a slim bottom chip that, like the drawer and full page, stays above the ride app's navigation pill while a route is navigated — in Workout+ and the Climber+ fork.

**Architecture:** A pure `WorkoutTrace` beside `WorkoutEngine` records the primary output per interval (5 s buckets) and NP; the engine hands it out in `IntervalRecord.trace` / `Shown.normalizedPower`. `WorkoutOverlayView.drawPage` gets a complete-only layout. A pure `BottomEdge` in the shared `Neighbor.kt` decides chip height and bottom offsets from a `navigating` flag that each app reads from `OnNavigationState`.

**Tech Stack:** Kotlin, Android Canvas, karoo-ext 1.1.9, JUnit + Robolectric (`RenderTest`, native graphics).

**Spec:** `docs/superpowers/specs/2026-11-15-workout-complete-design.md`, `docs/superpowers/specs/2026-11-15-navigation-chip-design.md`

## Global Constraints

- Every gradle call: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- `WorkoutField` / enum names are persisted — never rename entries.
- WindowManager calls main-thread only; `OverlayController.show()` must not relayout before `addView`.
- Overlay stays `FLAG_NOT_FOCUSABLE`.
- Copy: headline `WORKOUT` (lilac) + ` COMPLETE` (white); totals `AVG <n> W` · `IN RANGE <n>%` · `NP <n>`; unit per kind via existing `unitOf` (` W`, ` BPM`, ` RPM`).
- Colors: target blocks `WorkoutColors.BAND`, ridden line `WorkoutColors.ENDING`, graph background `WorkoutColors.TRACK`, in-range share `WorkoutColors.inRange(pct)`.
- `Neighbor.kt` is shared with `~/github/karoo-climber-plus` (package `com.hazzus.karooclimber.overlay`): any change lands in both, identical apart from the package line.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Branch `workout-complete-and-nav` (Workout+); Climber+ on its own branch of the same name.

## Review Focus

- A rewind (step index goes back) or a restarted interval: the trace for those steps is dropped with their records, never drawn twice — test in Task 2.
- Extension restarted mid-workout: early intervals have no trace; each such block is tinted by its in-range share while later ones show the line — test in Task 3 (render with a mixed history).
- Heart-rate workout: NP hidden, totals unit ` BPM`, line drawn on the HR scale — test in Task 2 (NP null) and Task 3 (render).
- Ride paused: no samples while paused (credit sees delta 0) and NP doesn't count paused time — test in Task 1.
- Navigation ends mid-ride (`Idle` after a route): chip and panels drop back to the normal bottom offsets without a page change — test in Task 5 (`OverlayController` relayout via `setNavigating(false)` is covered by `BottomEdge` tests plus a manual device check in Task 7).

---

### Task 1: `WorkoutTrace` — per-interval ridden line and NP

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutTrace.kt`
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/WorkoutTraceTest.kt`

**Interfaces:**
- Produces:
  - `class WorkoutTrace` with
    - `fun add(index: Int, ms: Long, output: Double?)` — `ms` of workout time at `output` (null = no reading: the bucket gets time but no value).
    - `fun line(index: Int): List<Float>` — bucket means for interval `index`, oldest first; `Float.NaN` for a bucket with no reading; empty if never fed.
    - `fun dropFrom(index: Int)` — forget intervals `>= index` (rewind / restart).
    - `fun normalizedPower(): Double?` — null until 30 s of readings.
    - `fun reset()`
    - `companion object { const val BUCKET_MS = 5_000L; const val MAX_BUCKETS = 720 }`

- [ ] **Step 1: Write the failing tests**

```kotlin
class WorkoutTraceTest {
    @Test fun bucketsAverageFiveSeconds() {
        val t = WorkoutTrace()
        repeat(5) { t.add(0, 1000, 100.0) }
        repeat(5) { t.add(0, 1000, 200.0) }
        assertEquals(listOf(100f, 200f), t.line(0))
    }
    @Test fun aPartialBucketShowsItsMeanSoFar() {
        val t = WorkoutTrace(); t.add(0, 2000, 150.0)
        assertEquals(listOf(150f), t.line(0))
    }
    @Test fun noReadingIsAGap() {
        val t = WorkoutTrace(); repeat(5) { t.add(0, 1000, null) }
        assertTrue(t.line(0).single().isNaN())
    }
    @Test fun intervalsAreSeparate() {
        val t = WorkoutTrace(); t.add(0, 5000, 100.0); t.add(1, 5000, 300.0)
        assertEquals(listOf(100f), t.line(0)); assertEquals(listOf(300f), t.line(1))
    }
    @Test fun dropFromForgetsLaterIntervals() {
        val t = WorkoutTrace(); t.add(0, 5000, 100.0); t.add(1, 5000, 300.0)
        t.dropFrom(1)
        assertEquals(listOf(100f), t.line(0)); assertEquals(emptyList<Float>(), t.line(1))
    }
    @Test fun aLongIntervalStaysBounded() {
        val t = WorkoutTrace(); repeat(4 * 3600) { t.add(0, 1000, 200.0) }
        assertTrue(t.line(0).size <= WorkoutTrace.MAX_BUCKETS)
        assertEquals(200f, t.line(0).first())
    }
    @Test fun npOfASteadyRideIsItsPower() {
        val t = WorkoutTrace(); repeat(600) { t.add(0, 1000, 200.0) }
        assertEquals(200.0, t.normalizedPower()!!, 0.5)
    }
    @Test fun npWeighsHardBlocksAboveTheAverage() {
        val t = WorkoutTrace()
        repeat(10) { repeat(60) { t.add(0, 1000, 100.0) }; repeat(60) { t.add(0, 1000, 300.0) } }
        assertTrue(t.normalizedPower()!! in 235.0..255.0) // average is 200
    }
    @Test fun npNeedsThirtySeconds() {
        val t = WorkoutTrace(); repeat(29) { t.add(0, 1000, 200.0) }
        assertNull(t.normalizedPower())
    }
    @Test fun noTimeNoSamples() {
        val t = WorkoutTrace(); t.add(0, 0, 500.0) // a paused frame credits 0 ms
        assertEquals(emptyList<Float>(), t.line(0)); assertNull(t.normalizedPower())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.WorkoutTraceTest"`
Expected: FAIL, unresolved reference `WorkoutTrace`.

- [ ] **Step 3: Implement `WorkoutTrace`**

Per interval: a list of buckets (`sum`, `valueMs`, `ms`); a bucket closes at `BUCKET_MS` of time (split an `add` across buckets). When an interval would exceed `MAX_BUCKETS`, merge neighbours pairwise and double that interval's bucket length (time-weighted). NP: resample to 1 s (carry leftover ms; each whole second takes the output of the `add` covering it, gaps skipped), keep a 30-slot ring of 1 s values; once full, every new second adds `mean(ring)^4` to a running sum; NP = `(sum / count)^0.25`. `dropFrom` does not rewind NP (documented in KDoc: NP is over everything ridden).

- [ ] **Step 4: Run to verify it passes** — same command, PASS.

- [ ] **Step 5: Commit** — `git add` both files; message `WorkoutTrace: ridden line per interval and NP`.

---

### Task 2: Engine hands out the trace

**Files:**
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutEngine.kt` (`credit`, `reset`, rewind branch in `update`, `Rec.restart` call site, `Rec.snapshot`, both `Shown` constructions incl. `holdComplete`)
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutModels.kt` (`IntervalRecord`, `WorkoutUiState.Shown`)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/WorkoutEngineTest.kt`

**Interfaces:**
- Consumes: `WorkoutTrace` (Task 1).
- Produces:
  - `IntervalRecord.trace: List<Float> = emptyList()` (last constructor param).
  - `Shown.normalizedPower: Int? = null` — set only when the primary kind is `TargetKind.POWER`.
  - `Shown.workoutAvgOutput: Double?` (computed property) — `avgOutput` weighted by `actualMs` over records with an `avgOutput`; null if none.

- [ ] **Step 1: Write the failing tests** (in `WorkoutEngineTest`, using its existing snapshot helpers)
  - `traceFollowsTheOutput`: ride step 0 for 10 s at output 200 → `history[0].trace == listOf(200f, 200f)`.
  - `rewindDropsTheTrace`: ride steps 0, 1, then back to 0 → `history` has one record and its `trace` holds only the new samples.
  - `restartedIntervalStartsANewTrace`: countdown jumps up inside step 0 → trace restarts (only post-restart buckets).
  - `heldCompleteKeepsTheTrace`: finish the last step, then `loaded = false` → `Shown.complete` and the last record's `trace` non-empty.
  - `npOnlyForPower`: 60 s power workout → `normalizedPower != null`; same on a heart-rate target → `null`.
  - `newEpochClearsTheTrace`: step count changes → every record's trace empty at first frame.

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.WorkoutEngineTest"` → the new tests FAIL.

- [ ] **Step 3: Implement**
  - Engine owns `private val trace = WorkoutTrace()`.
  - `credit(...)`: `trace.add(rec.index, ms, output)`.
  - `reset()`: `trace.reset()`. Rewind branch: `trace.dropFrom(index)`. Before `rec.restart(rem)`: `trace.dropFrom(index)`.
  - `snapshot()` takes the line: change to `fun snapshot(line: List<Float>)`, callers pass `trace.line(index)`.
  - `normalizedPower = trace.normalizedPower()?.roundToInt()?.takeIf { primaryKind == TargetKind.POWER }`; `holdComplete` keeps the `lastShown` value via `copy`.

- [ ] **Step 4: Run** the full `./gradlew testDebugUnitTest` → PASS (existing tests untouched).

- [ ] **Step 5: Commit** — `Engine: trace and NP in the workout state`.

---

### Task 3: The WORKOUT COMPLETE page

**Files:**
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/WorkoutOverlayView.kt` (`drawPage`, new `drawComplete`, `drawCompleteGraph`, `drawTotals`; `renderSignature` gets the last record's `trace.size`)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/RenderTest.kt`

**Interfaces:**
- Consumes: `IntervalRecord.trace`, `Shown.normalizedPower`, `Shown.workoutAvgOutput` (Task 2); existing `drawSummary(canvas, area, s)`, `unitOf`, `Format.elapsed`, `layout(area, gap, sections)`.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing render tests**
  - `workoutComplete`: `render("workout-complete", ridden(520).copy(complete = true, stepRemainingMs = 0, totalRemainingMs = 0))`; assert drawn, and that a pixel column across the graph area contains `WorkoutColors.ENDING` (scan rows 15–45 % of height at x = width/2 ± 40 for an ENDING pixel) and `WorkoutColors.BAND`.
  - `workoutCompleteWithoutTrace`: same with `history.map { it.copy(trace = emptyList()) }`, `normalizedPower = null` → no ENDING pixel in that region; render `workout-complete-no-trace`.
  - `workoutCompleteHidesTheFields`: with `Settings()` (four fields) the complete page's bottom 15 % holds no field-cell label: assert the existing primary bar color (`WorkoutColors.status(IN_RANGE)`) is absent in the top 25 % (no POWER row).
  - `workoutCompleteHeartRate`: history and state switched to `TargetKind.HEART_RATE` → draws (render `workout-complete-hr`).
  - Existing `workoutDone`, `workoutSummary*` keep passing (they now draw the new page).

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.RenderTest"` → new tests FAIL.

- [ ] **Step 3: Implement**
  - `drawPage`: when `s.complete`, skip PRIMARY / SECONDARY / CORE_HEAT / FIELDS and call `drawComplete(canvas, area, s)`; the pinned/handle logic stays.
  - `drawComplete` sections (via `layout`): headline 8f, graph 35f, totals 6f, table 51f (table = existing `drawSummary`).
  - Headline: `WORKOUT` in `labelPaint` LILAC + ` COMPLETE` white, left; `Format.elapsed(s.elapsedMs)` right in LILAC; shrink to fit like `drawTiming`'s header.
  - `drawCompleteGraph`: x over `history.sumOf { actualMs }`; y from 0 to `1.1 × max(targetMax, startLevel, endLevel, trace max)`. Per record: ramp (`startLevel`/`endLevel` differ ≥ 0.5) → parallelogram start→end ± 5 % of the level; ranged → rect `targetMin..targetMax`; no target → low neutral block as `drawGraph` does. Fill `BAND` when the record has a trace, else `WorkoutColors.inRange(rec.inRangePercent)`. Then the line: point i of a record at `x0 + (i + 0.5) / n × width`, `ENDING`, stroke 2 dp, round joins; NaN breaks the path (moveTo next).
  - `drawTotals`: three cells spread across the row: `AVG ${avg.roundToInt()}${unitOf(kind)}`, `IN RANGE ${pct}%` (value colored `inRange`), `NP $np` (omitted when null); `—` for unknown values. Kind = `history.firstOrNull { it.kind != null }?.kind`.

- [ ] **Step 4: Run** the full `./gradlew testDebugUnitTest` → PASS. Open `app/build/screenshots/workout-complete*.png` and compare with the approved v2 mockup (`.superpowers/brainstorm/79277-1794750496/content/summary-v2.html`): headline, tall graph, totals, every interval in the table.

- [ ] **Step 5: Commit** — `Workout complete page: target blocks, ridden line, totals`.

---

### Task 4: `BottomEdge` — slim chip and the navigation gap (shared rules)

**Files:**
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/Neighbor.kt` (append)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/NeighborRulesTest.kt`

**Interfaces:**
- Produces (in `Neighbor.kt`, so Climber+ gets the identical file):

```kotlin
/** Where bottom-anchored windows sit: a slim chip, lifted above the ride app's navigation pill while navigating. */
object BottomEdge {
    const val CHIP_DP = 15          // ≈ 28 px on a Karoo 2 (density 1.875)
    const val EDGE_OFFSET_FRACTION = 0.03 // clear of the page dots, as before
    const val NAV_GAP_DP = 50       // pill ≈ y 712–775 of 800: bar bottom at ≈ 706
    fun chipHeightPx(density: Float): Int
    fun chipOffsetPx(navigating: Boolean, density: Float, screenH: Int): Int
    /** Bottom offset of a bottom-anchored drawer / full page. */
    fun panelOffsetPx(navigating: Boolean, density: Float): Int
}
```

- [ ] **Step 1: Write the failing tests**
  - `chipIsSlim`: `chipHeightPx(1.875f) == 28`.
  - `chipSitsAtTheBottomWithoutNavigation`: `chipOffsetPx(false, 1.875f, 800) == 24`.
  - `chipClearsThePillWhileNavigating`: `chipOffsetPx(true, 1.875f, 800) == 94`, and `800 - 94 <= 712` (bar bottom above the pill top).
  - `panelsReachTheBottomWithoutNavigation`: `panelOffsetPx(false, 1.875f) == 0`.
  - `panelsStopAboveThePill`: `panelOffsetPx(true, 1.875f) == 94`.

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.NeighborRulesTest"` → FAIL.
- [ ] **Step 3: Implement** — `(dp * density).roundToInt()`; offsets as tested. Update the file's header KDoc with one line on `BottomEdge`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `BottomEdge: slim chip, clear of the navigation pill`.

---

### Task 5: Workout+ follows navigation

**Files:**
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/WorkoutExtension.kt` (consumer next to `ActiveRidePage`)
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/OverlayController.kt` (`layoutParams`, new `setNavigating`; drop `CHIP_HEIGHT_FRACTION`, `CHIP_EDGE_OFFSET_FRACTION`)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/RenderTest.kt`

**Interfaces:**
- Consumes: `BottomEdge` (Task 4).
- Produces: `OverlayController.setNavigating(navigating: Boolean)` — stores the flag; if it changed, `refresh()`.

- [ ] **Step 1: Write the failing render test** — `slimChip`: `pageView` with `Size.CHIP` at height `BottomEdge.chipHeightPx(1.875f)` (change the `Size.CHIP -> 60` line in `pageView` to that) renders `chip-slim`; assert a pixel at the vertical center is non-transparent and the time text fits (`view.chipContentWidth(28) <= SCREEN_W * 0.9`).
- [ ] **Step 2: Run** `RenderTest` → `slimChip` FAILS (or passes trivially; then go on — the layout change is what matters).
- [ ] **Step 3: Implement**
  - `WorkoutExtension`: `consumerIds += karooSystem.addConsumer<OnNavigationState> { e -> val nav = e.state is OnNavigationState.NavigationState.NavigatingRoute || e.state is OnNavigationState.NavigationState.NavigatingToDestination; Log.d(TAG, "navigating=$nav"); scope.launch { overlay.setNavigating(nav) } }`.
  - `layoutParams`: chip height `BottomEdge.chipHeightPx(density)`; bottom chip `y = BottomEdge.chipOffsetPx(navigating, density, heightPixels)`; bottom-anchored DRAWER and FULL `y = BottomEdge.panelOffsetPx(navigating, density)`; FULL height `heightPixels - topInsetPx - panelOffsetPx(...)`. Top-anchored windows unchanged.
- [ ] **Step 4: Run** full `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` → PASS / BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `Slim chip; chip and panels stay above the navigation pill`.

---

### Task 6: Climber+ does the same

**Files (repo `~/github/karoo-climber-plus`, branch `workout-complete-and-nav`):**
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/overlay/Neighbor.kt` — paste Task 4's `BottomEdge` (and header line) verbatim.
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/overlay/OverlayController.kt` (`layoutParams` lines ~169–232; drop its `CHIP_HEIGHT_FRACTION`, `CHIP_EDGE_OFFSET_FRACTION`)
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt` — feed `navigationRepo.route.map { it != null }.distinctUntilChanged()` into `overlay.setNavigating(...)`.
- Modify: `app/build.gradle.kts` — `versionCode = 10`, `versionName = "0.1.9"`.
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/NeighborRulesTest.kt` — the five `BottomEdge` tests from Task 4.

**Interfaces:**
- Consumes: `NavigationRepo.route: StateFlow<RouteData?>` (null = not navigating).
- Produces: `OverlayController.setNavigating(navigating: Boolean)` as in Task 5.

- [ ] **Step 1:** Add the `BottomEdge` tests; run `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.NeighborRulesTest"` → FAIL.
- [ ] **Step 2:** Paste `BottomEdge`; confirm `diff <(tail -n +2 ~/github/karoo-workout/app/src/main/kotlin/com/angkyria/karooworkout/overlay/Neighbor.kt) <(tail -n +2 app/src/main/kotlin/com/hazzus/karooclimber/overlay/Neighbor.kt)` prints nothing.
- [ ] **Step 3:** `layoutParams`: slim chip height and `chipOffsetPx(navigating, …)` for the bottom chip; bottom-anchored EXPANDED / FULL get `y = panelOffsetPx(navigating, …)` (FULL keeps `FULL_HEIGHT_FRACTION`, capped at `heightPixels - panelOffsetPx`).
- [ ] **Step 4:** `./gradlew testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5: Commit** in the Climber+ repo — `Slim chip, clear of the navigation pill (0.1.9)`.

---

### Task 7: Docs, version, device check

**Files:**
- Modify: `CLAUDE.md` (Architecture: `WorkoutTrace`, complete page, `BottomEdge`; Facts: navigation pill geometry once measured), `README.md` + `site/` what's-new lines, `app/build.gradle.kts` (`versionCode = 8`, `versionName = "1.6.0"`).

- [ ] **Step 1:** Update docs and version; `./gradlew testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 2:** Device (rider's Karoo, check `mCurrentFocus` and ask before installing): `adb install -r app/build/outputs/apk/debug/karoo-workout-plus-debug.apk`, `adb shell appops set com.angkyria.karooworkout SYSTEM_ALERT_WINDOW allow`; same for the Climber+ APK. With a route navigated, screenshot: the bar sits above the pill (`adb exec-out screencap -p`); `adb logcat -s WorkoutExtension | grep navigating`. Stop navigation → bar back at the bottom. If the pill top isn't ≈ 712 px, adjust `NAV_GAP_DP` in both repos and record the measurement in CLAUDE.md Facts.
- [ ] **Step 3:** Demo mode run to the end → the complete page shows; put demo mode back as found.
- [ ] **Step 4: Commit** — `1.6.0: workout complete page, chip with navigation`.
