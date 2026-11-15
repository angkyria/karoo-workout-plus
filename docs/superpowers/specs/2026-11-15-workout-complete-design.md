# Workout complete page — design

Date: 2026-11-15 · Status: for the rider's approval · Size: small project

## Intent

When the workout ends, the native Karoo 2 shows "Training Complete" over a graph
of the power ridden (DC Rainmaker's TrainerRoad review, Apr 2024). The rider wants
the Workout+ workout page to say the same, clearly: **how the workout was supposed
to go, how it went, and a small interval summary.** Today the TIMING block turns
into a per-interval table and the rest of the page stays as during the workout.

## Goals (the rider's, from the v2 mockup)

- Once `WorkoutUiState.Shown.complete`, the workout page shows only:
  1. headline `WORKOUT COMPLETE` + the workout time (right);
  2. a tall graph: the **target as purple blocks** (each interval's range, a ramp
     as its slope) and the **actual output as a yellow line** through them;
  3. a totals line: `AVG <n> W` · `IN RANGE <n>%` (colored like the recap) · `NP <n>`
     (NP is our call, power workouts only);
  4. the interval table as today (# / TARGET / AVG / IN), every interval, older
     ones collapse to `+n EARLIER` only when they don't fit.
- The POWER row, SECONDARY, the CORE row (our call) and the data fields are
  hidden on the finished page. The DONE list below (scroll) stays as it is.

## Non-goals

- No live line during the workout (the trace makes it possible later).
- No change to the chip / drawer finished state, or to other pages.

## Approaches

- **A. Record the trace in a pure class next to the engine (picked).**
  `data/WorkoutTrace`: fed each frame with the targeted value (the primary
  output the engine already resolves: power, HR on an HR workout) and workout
  elapsed time (pauses don't count). Averaged into ~300 fixed buckets, so memory
  is constant. Reset on a new `WorkoutEngine.epoch`; kept while
  `holdComplete` holds. Handed to the view with the completed state. Unit-tested.
- B. Record in `WorkoutOverlayView` — lost whenever the overlay is recreated,
  not unit-testable. Dropped.
- C. Read the ride file afterwards — karoo-ext gives no access. Out.

## Details

- Target blocks come from `IntervalRecord` (targetMin/Max, startLevel/endLevel,
  actualMs for the width), so no library plan is needed.
- Fallback: no trace (extension restarted mid-workout, no sensor) → blocks only,
  each tinted by its in-range share; totals line shows `—` where unknown.
- Y scale: 0 to max(target max, trace max) + a little headroom.
- NP: 30 s rolling average to the 4th power over the trace's 1 Hz input
  (computed while recording, not from buckets). Hidden when not power.
- Demo mode: `DemoWorkout` drives a trace through the real path; `RenderTest`
  adds a `workout-complete.png` render (with and without trace).

## Left to the builder

Exact section heights (graph ≈ 35 % of the page, table the rest), bucket count,
line smoothing (light, so the line doesn't spike like the native one).
