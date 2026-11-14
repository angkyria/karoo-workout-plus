# Interval list on the workout page — design

Date: 2026-11-14 · Status: reviewed, for the rider's approval

## Intent

While a workout runs, the rider wants to **know what's coming next and prepare
for it**, and to look back at how the finished intervals went. On the Workout+
workout page they scroll down past the current layout to a list: upcoming
intervals first (target, length, time until it starts), then the finished ones.

Karoo's extension API (karoo-ext 1.1.9) only streams the *current* interval.
The plan comes from the **Karoo's own workout library**, read on the device:
offline, no account, whatever app the workout came from.

## Goals

- On the workout page only, the page scrolls: the first screen is the page as it
  is today; below it a **NEXT** list and a **DONE** list.
- NEXT row (the rider's choice): **target, length, time until it starts.** Plus
  (our call) the interval number and a zone-colored dot.
- DONE row (our call, the rider asked for "how it was"): length, average
  output, % in range, colored like the recap.
- Works offline: the plan is read from the Karoo itself.

## Non-goals

- No intervals.icu (or any network) source — considered and dropped once the
  library turned out readable (see Approaches).
- No editing, skipping, or reordering intervals (karoo-ext has no such effects).
- No list on other ride pages, the drawer or the chip.
- The secondary target isn't shown in the rows (our call; the row has to read
  at a glance — revisit if the rider asks).

## Anti-goals (a failure even if it "works")

- Losing the countdown when it matters: the rider must never miss an interval
  start because the page was scrolled away.
- Breaking what exists: horizontal page swipes, the target tap, beeps / flash /
  recap, the pinned (no-minimize) workout page, the Climber+ screen sharing.
- **Ever writing to the Karoo database.** The service we use also deletes and
  writes documents; Workout+ calls only the two read transactions below.
- A wrong plan shown as if right. If the plan doesn't match what Karoo runs,
  show the fallback, not stale rows.
- Battery: no extra wakeups; the render loop stays ≤ 4 Hz.

## Constraints

- Karoo 2, Android 8.1, 480 × 800, touch often with gloves (slow drags, rarely
  a real fling — the existing code already treats slow drags as swipes).
- **Unofficial interface.** Reading the library relies on an exported, unguarded
  Karoo OS service and Hammerhead's own (unobfuscated) data classes. A Karoo OS
  update may change or close it without notice. Everything must degrade to
  today's behavior silently. Karoo 3: untested.
- Verified on the rider's Karoo 2 (ride app / workouts app 4.197.1), see below.

## Approaches

1. **intervals.icu download** — fetch planned workouts while online, cache,
   match offline. Real data from the first ride, but needs an API key, only
   knows intervals.icu workouts, and a network round trip. *Was the pick until
   the library proved readable.*
2. **Learn from earlier rides** — remember a workout's structure after riding
   it. No setup, but nothing on the first ride — the one you most need to
   prepare for. Dropped.
3. **Read the Karoo workout library (chosen by the rider).** The plan is
   already on the device; a spike read it offline with targets already in
   watts. Trade-off: unofficial, may break with an OS update → must fall back
   cleanly.

Layout (rider's choice, "A" in the mockup): the **whole page scrolls**; the
alternative — only the graph box scrolling — kept the countdown fixed but fit
just ~3 small rows.

## What the spike verified (2026-11-14, rider's Karoo 2)

- `io.hammerhead.datasyncservice/.v2.DataSyncService` is exported with no
  permission and no caller check. Binding with
  `Intent("databaseOperationsController").setComponent(that service)` returns
  a binder for interface descriptor
  `io.hammerhead.datasyncservice.v2.DatabaseOperationsAIDL`.
- Transaction **9 = `getDocumentTypeList`**: request = interface token,
  `writeString(docType)`, `writeInt(skip)`, `writeInt(limit)`, `writeInt(0)`
  (null options Bundle). Reply = `readException()`, then `readInt()` (non-zero
  = a Bundle follows), `Bundle.CREATOR.createFromParcel`. The Bundle's key
  `"value"` holds an `io.hammerhead.aidlrx.ParcelableList` whose list field
  holds `io.hammerhead.datamodels.workouts.Workout` objects.
  Transaction **10 = `getDocumentTypeCount(docType, options)`**. docType is
  `"workout"`.
- Unparceling needs Hammerhead's classes: set the Bundle's class loader to
  `createPackageContext("io.hammerhead.datasyncservice",
  CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY).classLoader`.
- `Workout` fields (read by reflection, by field name): `id`
  (`67685.workout.<uuid>` — the same id the ride app logs), `name`,
  `plannedDate`, `source` (`intervals_icu`), `structure: List`.
  Structure entries: `Step(_class [WARM_UP, ACTIVE, …], length: Double,
  lengthOpen: Boolean, lengthType [SECONDS, …], name, primaryTarget,
  secondaryTarget, type=STEP)` and `RepeatStep(repeat: Int, steps: List<Step>)`.
  `Target(type [POWER, …], value, minValue, maxValue, rampType)` — absolute
  units (watts), already converted from %FTP by Karoo.
- Today's workout read back as: Warm up 20:00 @151–159 W; 3 × (Climb 15:00
  @220–232 W, Easy 5:00 @146–154 W); Endurance 65:00 @150–175 W; Skills 20:00
  @133–141 W → **9 intervals expanded, 2:45:00**, exactly what the ride app ran
  (`WORKOUT_STEP_COUNT` = 9, first countdown 1 200 000 ms, total 2:45:00). The
  library's own `steps.size` (4) counts a repeat block once.
- The library held 3 workouts (yesterday's, today's, tomorrow's), all
  `source=intervals_icu`. `Workout.toString` also prints an `updatedAt`
  date (a field to map for ties).
- Karoo streams the library's real range for a library step: at step 0 the
  page showed **151–159 W** from the streams, the library's warm-up range
  exactly — so the live check can compare min/max directly.

## The design

### Data: `data/` (pure where possible)

- **`WorkoutPlan`** (pure model): the expanded list of planned intervals —
  per interval: length (ms, or null when open / not time-based), kind (power /
  HR / cadence / other), min, max, value, ramp (start → end when the plan says
  ramp), step class, name. Built from the library structure by **expanding
  repeats** in order.
- **`KarooLibrary`** (Android, isolated): binds the service, runs transaction 9
  page by page (limit 10, skip += 10, until a short page), unparcels with the
  package class loader, maps each `Workout` to a plain model **by reflection on
  field names** (so nothing compiles against Hammerhead classes). Any failure
  — bind refused, class missing, field renamed, exception — returns "no
  library" and logs one line. Read-only: only transactions 9 and 10 exist in
  the code. Runs off the main thread; unbinds when done.
- **When it reads:** once when a workout becomes loaded (the gate stream shows
  a step count), and again only if nothing matched and the workout changes.
  Never during a running interval otherwise.
- **`PlanMatcher`** (pure): picks the library workout Karoo is running.
  - *Watched the start* (the engine saw step 0 begin, as `freshStart` does
    today): a candidate must match **all** of: expanded interval count =
    Karoo's step count; first interval length = the first countdown seen at
    step 0 (± 2 s); total planned time = elapsed + remaining at the first
    sample (± 5 s); step 0's target range = the plan's (see live check).
  - *Joined late* (service restart, app update mid-ride, skip before the first
    sample) — our call: match on count, the current index's planned length ≥
    its remaining countdown, remaining total ≈ the rest of the plan from that
    index (± 5 s, the current step counted as its remaining time), and the
    current target range. Same live check afterwards.
  - Ties (our call): identical expanded structures are interchangeable — take
    `plannedDate` today, then the latest `updatedAt`. Candidates whose
    structures **differ** and can't be told apart by `plannedDate` today →
    no plan (fallback): a wrong plan is worse than none.
- **Live check** (our call on the details): once per step, after the target
  settles (the new step's first sample can carry the previous target — compare
  as `Rec.observe` re-baselines), Karoo's min/max must equal the plan's for
  that index after difficulty scaling (± 1 W / bpm / rpm). A step with no range
  to compare (value only, implied band, open target) neither passes nor fails.
  Mismatches on **two consecutive checked steps** → drop the plan, fallback for
  the rest of the workout. Step 0 is checked before any NEXT row shows.
- **Difficulty:** `WORKOUT_DIFFICULTY` (1.0 = 100 %) scales the upcoming
  targets the way Karoo scales the current one. *Unverified which kinds Karoo
  scales — check on device; until then scale power only.*

### "Starts in"

For upcoming interval *k*: the current countdown + the planned lengths of the
intervals between now and *k*. It follows skips (the index jumps) and pauses
(the countdown stops). After an open / lap-button or non-time step, later rows
show "after lap" instead of a time.

### Page and scrolling: `overlay/WorkoutOverlayView`

- Only when the page is the pinned workout page (`machine.pinned(mode)` and
  FULL). Everywhere else nothing changes (vertical swipes still minimize).
- The first screen is today's page, unchanged in size. Below it, in one
  scrolling column: **NEXT** header (`starts in` on the right), one row per
  upcoming interval, then **DONE** header (`avg · in range`), finished
  intervals most recent first.
- NEXT row: `n` · zone dot · target (`220–232 W`, ramp `137→187 W`, HR in bpm)
  · length · `in 12:30`. Zone dot from the rider's zones (`RiderProfile`), the
  existing zone palette.
- DONE row: `n` · length · average output · % in range colored green / blue /
  red like the recap. A skipped interval says "skipped" instead of the %.
- Peek (our call): a thin `NEXT ▾` cue at the bottom of the first screen, as in
  the approved mockup — only if the fields stay readable; otherwise a slim
  scroll indicator at the right edge. Check in `RenderTest`.
- **Gestures:** a vertical drag scrolls with the finger (slow drags count);
  a fling coasts and stops at the ends. Horizontal swipe still changes the
  ride page; a tap on the target still toggles visual / numeric.
- **Back to the top by itself** (keeps the countdown in view): 10 s after the
  last touch, at every interval change, and when the 10 s warning beep fires.
- The list rows change every second ("starts in"); the existing render
  signature must include the scroll offset and what's visible. Draw only the
  visible rows.

### No plan

When no library workout matches (or reading failed): NEXT shows one row,
`+8 intervals · 2:24:40` (what the graph knows today). DONE always works — it
comes from the engine's own history.

### Settings and diagnostics

- Setting (our call): **"Interval list on the workout page"**, default on — an
  off switch in case a Karoo update makes the library misbehave. New enum /
  key names are persisted: pick them once.
- The diagnostics panel shows the library read: workouts found, matched id /
  name, or the failure reason. One `WorkoutExtension` log line per read.

### Testing

- JVM: repeat expansion, matching (counts, tolerances, ties, the first-sample
  quirk), live-check drop, "starts in" with skips / pauses / open steps,
  difficulty scaling, row formatting.
- `KarooLibrary` mapping: tests against fake classes with the same field names.
- `RenderTest`: the page scrolled to NEXT and to DONE, the peek, the fallback.
- On device: today's style of workout (repeats), a workout with a ramp, one
  with an HR target, one not from intervals.icu, a skip, and a Karoo with the
  service unreachable (fallback).

## Left to the builder

- Row heights / text sizes within the existing look (rows must read at a
  glance, like the fields); scroll physics; the peek vs. indicator call after
  seeing renders.
- Where exactly `PlanMatcher` hooks into `WorkoutEngine` / `WorkoutStreams`.
- How the zone dot handles cadence or untyped targets (neutral color is fine).

## Unverified (check on device)

- Other `lengthType` values (distance?) and `_class` values; HR / cadence
  target units in `Target`; `rampType` values and how a ramp's min/max map to
  start/end.
- Whether `WORKOUT_DIFFICULTY` scales HR / cadence targets.
- Karoo 3.
