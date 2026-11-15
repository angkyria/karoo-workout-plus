# Chip and panels with navigation — design

Date: 2026-11-15 · Status: for the rider's approval · Size: small change, two repos

## Intent

While a route is navigated, the ride app shrinks its data pages and shows a red
navigation pill at the bottom (seen on the rider's Karoo 2: fields end ≈ y 700,
pill ≈ y 712–775 of 800). The Workout+ chip (60 px tall, 24 px off the bottom) and
Climber+'s chip sit exactly on that pill. Without navigation the fields run to the
bottom and the chip covers data. The rider wants neither: the chips must not hide
the pill, and should cover as little data as possible.

## Goals

- **Slim bar** (the rider's pick): the bottom chip becomes a thin strip (≈ 28 px,
  one line `--  2:24:35`), so it covers only the lower edge of the last field row.
- **Navigating** (`OnNavigationState` ≠ `Idle`): the bottom chip sits just above
  the pill, over the bottom of the fields; the drawer and the full page stop
  above the pill too.
- Not navigating: the slim bar sits at the bottom, as the chip does today.
- **Climber+** does the same in its half: each app reads `OnNavigationState`
  itself, no new broadcast. Side by side stays as it is (`Neighbor.kt` unchanged).

## Non-goals

- Resizing the ride app's own pages: karoo-ext has no way to.
- Top-anchored chips / drawers: unchanged.

## Approaches

- **Each app reads `OnNavigationState` (picked)** — one consumer, a flag into
  `OverlayController.layoutParams` (bottom offset = pill gap while navigating).
- Broadcast navigation state over `NeighborLink` — needless; both can read it.
- Detect the gap from page elements — `ActiveRidePage` doesn't describe it.

## Details / left to the builder

- The pill gap (≈ 100 px from the bottom on a Karoo 2) is a dp constant,
  measured on the device during navigation; verify whether the pill is always
  there while navigating or only on reroute (unverified — the rider couldn't say).
- Slim bar drawing in `WorkoutOverlayView` (chip text shrunk to the bar; the
  flash still fills the background); `RenderTest` renders it.
- Climber+ fork (`~/github/karoo-climber-plus`): same flag and offset; its chip's
  slim form follows its own content.
