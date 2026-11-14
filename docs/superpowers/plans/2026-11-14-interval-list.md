# Interval List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the pinned workout page, the page scrolls to a NEXT list (upcoming intervals: target, length, starts in) and a DONE list (finished intervals), with the plan read offline from the Karoo's own workout library.

**Architecture:** A pure plan layer (`data/WorkoutPlan.kt`, `PlanMatcher`, `PlanTracker`) turns library workouts into expanded intervals, matches one to the running workout and builds the NEXT rows; an isolated Android reader (`data/KarooLibrary.kt` + reflective `LibraryMapper`) fetches the library through the Karoo sync service; `WorkoutExtension` joins them onto `WorkoutUiState.Shown.next`; `WorkoutOverlayView` draws and scrolls the list.

**Tech Stack:** Kotlin, Android 8.1 (minSdk 26), Canvas view, JUnit 4 + Robolectric (`RenderTest`), karoo-ext 1.1.9.

**Spec:** `docs/superpowers/specs/2026-11-14-interval-list-design.md` — read it first; its "What the spike verified" section has the binder protocol.

## Global Constraints

- Read-only: the code contains only transactions **9** (`getDocumentTypeList`) and **10** (`getDocumentTypeCount`) of `io.hammerhead.datasyncservice.v2.DatabaseOperationsAIDL`; never 5–8 or any other.
- No compile-time dependency on Hammerhead classes; map by reflection on field names (`id`, `name`, `plannedDate`, `updatedAt`, `structure`, `repeat`, `steps`, `_class`, `length`, `lengthOpen`, `lengthType`, `primaryTarget`, `type`, `value`, `minValue`, `maxValue`, `rampType`).
- Any library failure degrades silently to today's page (fallback row), one log line under tag `WorkoutExtension`.
- Scrolling only when `machine.pinned(settings.pageMode)` and `machine.size == Size.FULL` and `settings.intervalList`.
- Render loop stays ≤ 4 Hz; no new timers except the existing `nextChangeAtMs` re-render.
- Tolerances: first length ± 2 s, total ± 5 s, live check ± 1 unit; auto-return 10 s after last touch.
- Persisted names are forever: setting key `interval_list`, field `intervalList`.
- Commands need `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; tests: `./gradlew testDebugUnitTest`.
- Work on branch `interval-list`; each task commits with the `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` trailer.

## Review Focus

- **Service restarted mid-workout** (late join): the list must still appear via the late-join path → test `late join matches from the current index` in Task 2.
- **Skip forward two intervals**: NEXT rows and "starts in" re-anchor on the new index at once → test `skip re-anchors rows` in Task 3.
- **Library unreachable or a field renamed by an OS update**: mapper returns null, page shows the fallback row, nothing crashes → test `missing field maps to null` in Task 4.
- **An open (lap-button) step mid-workout**: rows after it show "after lap", not a wrong time → test `open step makes later starts unknown` in Task 3.
- **Ride paused while scrolled**: "starts in" freezes with the countdown, and the page still returns home on the next interval change → test `interval change sends the page home` in Task 6.

---

### Task 1: Plan model and repeat expansion

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutPlan.kt`
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/WorkoutPlanTest.kt`

**Interfaces:**
- Produces:
  - `data class LibraryTarget(val kind: TargetKind, val value: Double?, val min: Double?, val max: Double?, val ramp: Boolean)`
  - `sealed interface LibraryEntry { data class Step(val name: String?, val lengthMs: Long?, val primary: LibraryTarget?) : LibraryEntry; data class Repeat(val times: Int, val steps: List<Step>) : LibraryEntry }` — `lengthMs` null when `lengthOpen` or `lengthType != SECONDS`.
  - `data class LibraryWorkout(val id: String, val name: String, val plannedDateMs: Long?, val updatedAtMs: Long?, val structure: List<LibraryEntry>)`
  - `data class PlannedInterval(val index: Int, val name: String?, val lengthMs: Long?, val target: LibraryTarget?)`
  - `fun LibraryWorkout.expand(): List<PlannedInterval>` (repeats unrolled in order, `index` 0-based)
  - `val List<PlannedInterval>.totalMs: Long?` (null if any length is null)

- [ ] **Step 1: Write the failing tests** — use today's verified workout as the fixture (`todayWorkout()` helper in the test: Warm up 1 200 000 ms 151–159 W; Repeat 3 × [Climb 900 000 ms 220–232 W, Easy 300 000 ms 146–154 W]; Endurance 3 900 000 ms 150–175 W; Skills 1 200 000 ms 133–141 W):
  - `repeats expand to the ride's nine intervals`: `expand().size == 9`; names `[Warm up, Climb, Easy, Climb, Easy, Climb, Easy, Endurance, Skills]`; indices 0..8.
  - `total is the planned time`: `expand().totalMs == 9_900_000L` (2:45:00).
  - `open step has no total`: a workout with one `Step(lengthMs = null)` → `totalMs == null`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests "com.angkyria.karooworkout.WorkoutPlanTest"` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the types and `expand()` in `WorkoutPlan.kt` (pure Kotlin, no Android imports).
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** spec + plan + task: `git switch -c interval-list && git add docs/superpowers app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutPlan.kt app/src/test/kotlin/com/angkyria/karooworkout/WorkoutPlanTest.kt && git commit -m "Interval list: design, plan, and the workout plan model"`

### Task 2: PlanMatcher

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/PlanMatcher.kt`
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/PlanMatcherTest.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `data class RunObservation(val stepCount: Int, val stepIndex: Int, val stepRemainingMs: Long?, val totalRemainingMs: Long?, val elapsedMs: Long, val watchedStart: Boolean, val targetMin: Double?, val targetMax: Double?, val targetImplied: Boolean, val scalePercent: Int?)`
  - `object PlanMatcher { fun match(library: List<LibraryWorkout>, run: RunObservation, todayStartMs: Long): LibraryWorkout?; fun rangeAgrees(planned: LibraryTarget?, min: Double?, max: Double?, implied: Boolean, scalePercent: Int?): Boolean? }` — `rangeAgrees` returns null when there is nothing to compare (implied band, no planned min/max, planned value-only), true/false otherwise; power is scaled by `scalePercent / 100.0`, others unscaled; tolerance ± 1.0.

- [ ] **Step 1: Write the failing tests** (fixture from Task 1's test, copied into a shared `testPlans.kt` under the test dir as `fun todayWorkout(id: String = "today", plannedDateMs: Long? = TODAY)`):
  - `watched start matches today's workout`: run = count 9, index 0, remaining 1_200_000, total 9_900_000, elapsed 0, watched, range 151–159 → returns it.
  - `first length off by more than 2 s rejects`: remaining 1_197_000 at elapsed 0 → null.
  - `wrong count rejects`: count 8 → null.
  - `target range mismatch at step 0 rejects`: range 140–150 → null.
  - `late join matches from the current index`: not watched, index 3 (Climb #2), remaining 400_000, total = 400_000 + sum(lengths 4..8) = 400_000 + 300_000 + 900_000 + 300_000 + 3_900_000 + 1_200_000, range 220–232 → match.
  - `late join rejects a remaining longer than the step`: index 3, remaining 950_000 → null.
  - `identical twins pick today then latest update`: two copies differing only in `plannedDateMs`/`updatedAtMs` → today's one; both today → higher `updatedAtMs`.
  - `different structures that both fit give no plan`: two candidates both passing but with different interval 4 targets, neither or both dated today → null.
  - `difficulty scales power for the range check`: scale 90, range 135.9–143.1 vs planned 151–159 → match (`rangeAgrees` true).
  - `implied band is not compared`: `rangeAgrees(planned, 147.25, 162.75, implied = true, 100) == null`.
- [ ] **Step 2: Run** `--tests "com.angkyria.karooworkout.PlanMatcherTest"` → FAIL.
- [ ] **Step 3: Implement** `PlanMatcher.match`: filter by expanded count; watched path: index 0 and |remaining − first length| ≤ 2 000 and |elapsed + totalRemaining − totalMs| ≤ 5 000; late path: lengthMs[index] ≥ remaining and |totalRemaining − (remaining + Σ lengths after index)| ≤ 5 000; both paths require `rangeAgrees(...) != false` for the current index. Ties per the spec (structure equality = expanded lists equal ignoring `index`/`name`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `"Match a library workout to the running one"`.

### Task 3: PlanTracker — live check and NEXT rows

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/PlanTracker.kt`
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/data/WorkoutModels.kt` (add `next: NextList? = null` as the last property of `WorkoutUiState.Shown`)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/PlanTrackerTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - `data class UpcomingRow(val index: Int, val target: LibraryTarget?, val lengthMs: Long?, val startsInMs: Long?)` — `target` already difficulty-scaled; `startsInMs` null after an open step.
  - `sealed interface NextList { data class Planned(val rows: List<UpcomingRow>) : NextList; data class Unknown(val count: Int, val remainingMs: Long?) : NextList }`
  - `class PlanTracker { fun onLibrary(workouts: List<LibraryWorkout>?); fun update(state: WorkoutUiState.Shown, watchedStart: Boolean, todayStartMs: Long): NextList; fun reset(); val matched: LibraryWorkout? }` — `onLibrary(null)` = read failed.

- [ ] **Step 1: Write the failing tests** (build `Shown` states with a small helper `shown(index, remainingMs, totalRemainingMs, elapsedMs, min, max, implied = false, scale = 100)`):
  - `no library gives the unknown row`: `onLibrary(null)`, state index 0 of 9 → `Unknown(count = 8, remainingMs = totalRemaining − stepRemaining)`.
  - `rows list every upcoming interval`: matched today, index 0, remaining 1_000_000 → 8 rows, first `index 1, lengthMs 900_000, startsInMs 1_000_000`, second `startsInMs 1_900_000`.
  - `skip re-anchors rows`: then index 3, remaining 900_000 → first row index 4, `startsInMs 900_000`.
  - `open step makes later starts unknown`: plan with interval 2 open → row 2 `startsInMs` set, row 3 onward `startsInMs == null`.
  - `two mismatched steps drop the plan`: step 1 and step 2 each seen with range 100–110 after ≥ 3 s in the step → third update returns `Unknown`; one mismatch followed by an agreeing step keeps `Planned`.
  - `first sample of a step is not judged`: step 1 at its first update carrying step 0's 151–159 → still `Planned` (judge only once `lengthMs − remaining ≥ 3 000`).
  - `difficulty scales the rows`: scale 90 → row 1 power min 198.0, max 208.8.
  - `new workout resets`: step count changes → `matched == null` until matched again.
- [ ] **Step 2: Run** `--tests "com.angkyria.karooworkout.PlanTrackerTest"` → FAIL.
- [ ] **Step 3: Implement** `PlanTracker`: match lazily via `PlanMatcher` when unmatched and the library is present; one live check per step index (the first update with ≥ 3 s elapsed in the step); two consecutive `false` → `dropped = true` until `reset()`; `startsInMs` = remaining + Σ lengths of the intervals in between, null once any is null. Add the `next` property to `Shown`.
- [ ] **Step 4: Run** the whole suite (`./gradlew testDebugUnitTest`) → PASS (the new default property must not break existing tests).
- [ ] **Step 5: Commit** `"Track the matched plan and build the NEXT rows"`.

### Task 4: LibraryMapper and KarooLibrary

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/LibraryMapper.kt` (pure JVM reflection)
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/data/KarooLibrary.kt` (Android binder reader)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/LibraryMapperTest.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `object LibraryMapper { fun workout(o: Any): LibraryWorkout? }` — reads fields by name up the class hierarchy; `structure` entries are a repeat when they have a `repeat` field, else a step; `type`/`_class`/`lengthType` enums read via `toString()`; target `type` POWER/HEART_RATE/CADENCE → `TargetKind`, anything else `UNKNOWN`; `ramp = rampType != null`; `Date` → epoch ms. Any missing required field (`id`, `name`, `structure`) → null.
  - `class KarooLibrary(private val context: Context) { suspend fun read(): List<LibraryWorkout>? }` — null on any failure; pages transaction 9 with `limit = 10` until a page shorter than 10; 5 s timeout; unbinds in `finally`. Constants: package `io.hammerhead.datasyncservice`, service `io.hammerhead.datasyncservice.v2.DataSyncService`, action `databaseOperationsController`, descriptor as in Global Constraints, docType `"workout"`, Bundle key `"value"`, class loader from `createPackageContext(pkg, CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY)`.

- [ ] **Step 1: Write the failing tests** with fake classes in the test file mirroring the verified shapes (`class FakeWorkout(val id: String, val name: String, val plannedDate: Date?, val updatedAt: Date?, val structure: List<Any>)`, `class FakeStep(val _class: String, val length: Double, val lengthOpen: Boolean, val lengthType: String, val name: String?, val primaryTarget: FakeTarget?)`, `class FakeRepeat(val repeat: Int, val steps: List<FakeStep>)`, `class FakeTarget(val type: String, val value: Double?, val minValue: Double?, val maxValue: Double?, val rampType: String?)`):
  - `today's workout maps and expands to nine`: build the fake of today's workout → `LibraryMapper.workout(it)!!.expand().size == 9`, first target `LibraryTarget(POWER, 155.0, 151.0, 159.0, ramp = false)`.
  - `open and distance steps have no length`: `lengthOpen = true` → `lengthMs == null`; `lengthType = "DISTANCE"` → `lengthMs == null`.
  - `heart rate target maps its kind`: type `"HEART_RATE"` → `TargetKind.HEART_RATE`.
  - `missing field maps to null`: a class without `structure` → `null`, no exception.
- [ ] **Step 2: Run** `--tests "com.angkyria.karooworkout.LibraryMapperTest"` → FAIL.
- [ ] **Step 3: Implement** `LibraryMapper`, then `KarooLibrary.read()`: bind with `suspendCancellableCoroutine`, transact off the main thread (`Dispatchers.IO`), parcel exactly as the spec's protocol, unwrap `ParcelableList` by its first `List`-typed field, map each item with `LibraryMapper.workout`, drop nulls.
- [ ] **Step 4: Run** the whole suite → PASS; `./gradlew assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `"Read the Karoo workout library"`.

### Task 5: Wiring — extension, setting, demo, diagnostics

**Files:**
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/WorkoutExtension.kt` (render path, near `overlay.show`)
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/settings/Settings.kt`, `SettingsRepo.kt`, `SettingsActivity.kt`
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/debug/DemoWorkout.kt`, `debug/Diagnostics.kt`
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/DemoWorkoutTest.kt` (extend)

**Interfaces:**
- Consumes: `PlanTracker`, `KarooLibrary`, `NextList`.
- Produces:
  - `Settings.intervalList: Boolean = true` (key `interval_list`); settings row "Interval list on the workout page" in the "Workout page" section, hint "Scroll the workout page for the upcoming and finished intervals. Read from the Karoo's workout library."
  - `fun DemoWorkout.libraryWorkout(): LibraryWorkout` — the demo's own steps as a library workout, so demo mode and `RenderTest` show a planned list.
  - `Diagnostics.library: MutableStateFlow<String?>` — e.g. `"3 workouts, matched: IntervalCoach - MTB …"` / `"read failed: <reason>"`; shown as one line in `DiagnosticsPanel`.

- [ ] **Step 1: Write the failing test** `demo library workout matches the demo run`: run the demo 5 s through `WorkoutEngine`, then `PlanMatcher.match(listOf(demo.libraryWorkout()), observation from that state with watchedStart = true, today)` → not null.
- [ ] **Step 2: Run** `--tests "com.angkyria.karooworkout.DemoWorkoutTest"` → FAIL.
- [ ] **Step 3: Implement**: `DemoWorkout.libraryWorkout()`; `Diagnostics.library`; the setting (+ repo key, UI row); in `WorkoutExtension`: a `PlanTracker`, a library read launched when a workout becomes loaded with a new step count (demo mode: `onLibrary(listOf(demo.libraryWorkout()))`, no binder), `tracker.reset()` with `engine.reset()`, and `state.copy(next = tracker.update(state, freshStart-or-watched, startOfToday))` before `overlay.show` when `s.intervalList`. `watchedStart` = the engine saw step 0 begin in this service (same arming as `freshStartArmed`, kept for the workout).
- [ ] **Step 4: Run** the whole suite → PASS; `assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `"Wire the interval list: library read, demo plan, setting"`.

### Task 6: Scrolling page and list drawing

**Files:**
- Create: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/ListScroll.kt` (pure scroll state)
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/WorkoutOverlayView.kt` (`onTouchEvent`, gesture listener, `drawPage`, `renderSignature`, new `drawIntervalList`)
- Modify: `app/src/main/kotlin/com/angkyria/karooworkout/overlay/Format.kt` (row text helpers)
- Test: `app/src/test/kotlin/com/angkyria/karooworkout/ListScrollTest.kt`, `RenderTest.kt` (extend)

**Interfaces:**
- Consumes: `Shown.next`, `Shown.history`, `RiderProfile.zoneOf`, `WorkoutColors`, `WorkoutCues.WARNING_MS` (public, 10 000 ms).
- Produces:
  - `class ListScroll { var offsetPx: Float; fun drag(dy: Float, maxPx: Float); fun fling(velocityY: Float, maxPx: Float, nowMs: Long); fun touched(nowMs: Long); fun step(nowMs: Long, stepIndex: Int, stepRemainingMs: Long?): Boolean }` — `step` returns true when the offset changed (fling coasting, or a return home); home when `nowMs − lastTouch ≥ 10_000`, when `stepIndex` changes, or when the countdown crosses `WARNING_MS`.
  - `fun Format.startsIn(ms: Long?): String` → `"in 12:30"` / `"after lap"`; `fun Format.plannedTarget(t: LibraryTarget?): String` → `"220–232 W"`, ramp `"137→187 W"`, HR `"140–150 bpm"`, cadence `"85–95 rpm"`, null `"—"`.

- [ ] **Step 1: Write the failing tests**:
  - `ListScrollTest`: `drag clamps to the content` (drag −5000 with max 900 → offset 900; +5000 → 0); `returns home 10 s after the last touch` (`touched(0)`, `step(9_999, …)` false, `step(10_000, …)` true and offset 0); `interval change sends the page home` (offset 400, `step(t, stepIndex + 1, …)` → offset 0 even while paused); `warning beep sends the page home` (remaining 10_400 then 9_900 → home).
  - `RenderTest`: `page shows the next peek` (FULL, demo with `next` set → `assertDrawn`, png `page-list-peek`); `page scrolled to next` (offset = first screen height → png `page-list-next`); `page scrolled to done` (offset at max → png `page-list-done`); `list fallback row` (`next = Unknown(8, 8_640_000)` → png `page-list-unknown`); `no list on other pages` (DRAWER_ONLY FULL → scrolling a drag leaves offset 0).
- [ ] **Step 2: Run** `--tests "com.angkyria.karooworkout.ListScrollTest" --tests "com.angkyria.karooworkout.RenderTest"` → FAIL.
- [ ] **Step 3: Implement**: `ListScroll`; in the view, when list-enabled (Global Constraints condition): vertical drags/flings go to `ListScroll` instead of `verticalSwipe` (horizontal swipe and taps unchanged; tap hit areas offset by the scroll); `drawPage` draws the first screen at `canvas.translate(0, −offset)` exactly as now, then `drawIntervalList` below it (NEXT header with "starts in", rows with number · zone dot from `profile.zoneOf(kind, value ?: min)` in the existing zone palette (neutral `ZONE_UNKNOWN` when null) · target · length · starts in, DONE header with "avg · in range", `history` minus the current one, newest first; "skipped" when `!endedNaturally` and not the current); the `NEXT ▾` peek only if the fields' cell height stays unchanged, else a right-edge indicator (decide from the renders, note the choice in CLAUDE.md); `renderSignature` gains `scroll.offsetPx.toInt()` and `next` (rows' `startsInMs / 1000`); call `scroll.step` in `update` and request the existing `nextChangeAtMs`-style re-render while a fling coasts or a return is due.
- [ ] **Step 4: Run** the whole suite → PASS; open `app/build/screenshots/page-list-*.png` and check rows read at a glance and the first screen is pixel-identical to `page` when offset 0 (except the peek).
- [ ] **Step 5: Commit** `"Scroll the workout page to the interval list"`.

### Task 7: Device check, docs, version

**Files:**
- Modify: `CLAUDE.md` (architecture bullet for the plan layer + "Facts verified" for the library service), `README.md` ("What's new"), `app/build.gradle.kts` (versionCode 7, versionName "1.5.0")

- [ ] **Step 1: Install** `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/karoo-workout-plus-debug.apk`; check the device first (`dumpsys window windows | grep mCurrentFocus`, screenshot) — the rider may be using it.
- [ ] **Step 2: Demo mode on device**: the list appears, scrolls with a slow drag, returns home after 10 s; put demo mode back off.
- [ ] **Step 3: Real workout** (with the rider, who starts and discards the ride): `adb logcat -s WorkoutExtension` shows the library line and `matched`; NEXT rows equal the library (today's: 9 intervals); skip one interval → rows re-anchor; diagnostics panel shows the read.
- [ ] **Step 4: Record** what was verified / still unverified (ramp min→max direction, HR difficulty scaling, `lengthType` values) in CLAUDE.md "Facts verified"; README what's new; version bump.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest assembleDebug` → PASS; **commit** `"Interval list on the workout page (1.5.0)"`.
