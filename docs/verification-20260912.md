# Verification: addon expansion, 2026-09-12

## Verified locally

Command: `./gradlew --no-daemon -ParcCoreDir=/absolute/path/to/arc-core test shadowJar`.
Result: **72 tests, 0 failures, 0 errors, 0 skipped**. The composite uses
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
- Archived provider delivery after the current catalog becomes unavailable,
  while new snapshot preparation remains refused.
- Immutable season rules and failed journal writes; weighted offers remain
  ordinary across bounded rerolls, with no pity or forced-reward state.
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

The ARC 1.4.64 bridge also passed its final targeted run:
`test --tests 'ru.arc.itemcatalog.*' shadowJar` with the same local composite.
Result: **64 tests, 0 failures, 0 errors, 0 skipped**, source commit `667844a`.
The archived-seal test opens the real MockBukkit menu against an empty current
catalog, chooses and confirms one item, and verifies one seal consumed, one
chosen item granted, and no second item granted. Ordinary command delivery and
addon delivery use the same archived seal creation route.
ARC JAR SHA-256: `ca9990a8a7cfd8e83233488b10efd58746ebcb29494c8197150beb4c08fbe23b`.

## Dependency and activation boundary

The arc-core source is public. Maven 2.7.8 publication is pending a separately
requested authorization; its full release dry run passed 655 tests with no
failures/errors/skips. No Maven artifact was uploaded by that dry run.

The [public CI run for source commit 30d5920](https://github.com/alexey-va/ExcellentCratesItemsAdder/actions/runs/34697681573)
failed during dependency resolution because Maven does not yet contain
`arc-core-paper-api:2.7.8` and the other shared 2.7.8 modules. This is a failed
public build, separate from the successful local composite verification above.

The last runtime readback still had ARC 1.4.63 and addon 0.3.0. The new 0.4.0
managed features have not been enabled on the server. The ARC materializer
bridge and its archived collection-seal path passed local verification above;
they have not passed live server acceptance yet.

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
