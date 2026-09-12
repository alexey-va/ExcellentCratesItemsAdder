# Verification: addon expansion, 2026-09-12

## Verified locally

Command: `./gradlew --no-daemon -ParcCoreDir=/absolute/path/to/arc-core test shadowJar`.
Result: **71 tests, 0 failures, 0 errors, 0 skipped**. The composite uses
arc-core source commit `5881ef254a668c576ed560b230cae3d0aa91b515` (2.7.8).
The consumer architecture verifier passed. The packaged addon contains its
menu/locale/features resources and excludes the shared `ru.arc.paper.api` classes.

The regression suite covers:

- Exact-season key debit, repeated application, full storage with no partial
  payout or ground drop, changed inventory and uncertain player-save outcomes.
- Persisted choices across coordinator reconstruction; stale click rejection;
  bounded reroll; one prepared prize and one delivery; player ownership checks.
- Mail remaining available after full inventory, durable-write failure and
  readback ambiguity; no blind replay of an unresolved delivery.
- Immutable season rules and failed journal writes; pity based on the selected
  reward, with separate season progress and a preserved guarantee on reroll.
- Exclusion of unpaid attempts from analytics and separation of final offers
  from actual player selections.
- Six-row menu definitions, configured paging/background icons, native reward
  names, disabled review entries and the global ban on model 11001.
- Creative furniture protection, managed entity interaction, prior cancellation
  and paired entity-event deduplication.
- Native pre-open veto, self-permit scope, cancelled interaction and exact EC
  handler rebinding after disable/enable.
- Admin repair refusal for missing/stale geometry, altered support, floating
  transforms and unverified custom-parent models.

These are component and MockBukkit tests. They do not prove rendered client
pixels or server crash behavior under a real storage failure.

## Dependency and activation boundary

The arc-core source is public. Maven 2.7.8 publication is pending a separately
requested authorization; its full release dry run passed 655 tests with no
failures/errors/skips. No Maven artifact was uploaded by that dry run.

The last runtime readback still had ARC 1.4.63 and addon 0.3.0. The new 0.4.0
managed features have not been enabled on the server. The ARC materializer
bridge and its archived collection-seal path have a separate verification gate.

After dependency publication and coordinated delivery, live acceptance must
exercise all seven native key commands and furniture anchors, one actual debit
and claim, selection/reroll/resume, full-inventory mail and rejoin, old-season
keys, creative left-click protection, menus without window reopening, operator
inspection, and unchanged behavior after native ExcellentCrates reload.

## Repair limitation

Repair is deliberately unavailable without a retained exact geometry report.
The inspected bear chest has current visible residual -0.06364925 blocks while
its analyzer recommends a corrected position. A recommended zero residual does
not validate the current native spawn transform. The addon refuses that report;
it has not moved the existing chest.
