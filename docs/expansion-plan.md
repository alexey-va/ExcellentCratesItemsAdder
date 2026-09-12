# ExcellentCrates addon expansion

Owner request: implement the previously proposed features, excluding duplicate
conversion. Existing furniture protection remains part of every release.

## Required outcomes

- Rare-reward guarantee with persisted progress, visible threshold and an
  explicit per-pool qualifying reward set.
- Durable reward mailbox. Full inventory, disconnect, restart and provider
  failure preserve entitlement. An ambiguous delivery must never be replayed
  blindly.
- Choice of three weighted rewards and a bounded reroll. One paid opening
  yields exactly one prize. Escape preserves choices; reconnect resumes them.
- Immutable seasonal pool snapshots. Physical keys identify their season;
  old keys and pending openings retain the original pool and item payload.
- Admin inspection and repair for registered ItemsAdder crate positions,
  including model identity, hitbox, dependency and pending-delivery state.
- Player opening history and operator observed-versus-expected distribution,
  separated by pool version and ordinary versus guaranteed/rerolled selection.
- Public source publication, appropriate regression tests, deployment and
  live verification of every feature. Duplicate conversion is excluded.

## Evidence and integration constraints

At task start the live classic reports ExcellentCrates 6.6.1, ItemsAdder
4.0.18, NightCore 2.16.4, addon 0.3.0 and ARC 1.4.63. The addon checkout is
clean at 3775623. Current crate configuration has seven physical cases.

ARC's existing console reward command returns handled even when the provider
rejects delivery and drops rewards on the ground when the inventory is full.
Command dispatch success is therefore not a delivery receipt. Mail must own
the durable entitlement and use an explicit outcome from the reward provider.

Current rewards share the `common` rarity. Guarantee eligibility must be
configured from the actual reward catalogue; existing labels alone cannot
implement a useful pity system. Balance assessment must record units
separately and distinguish potential key openings from recurring income.

## Delivery sequence

1. Verify the exact native opening, cost, reward and event contracts.
2. Implement immutable pools and deterministic weighted/guaranteed offers.
3. Implement persisted opening transitions, mailbox and recovery boundaries.
4. Connect native EC keys/opening entry and ARC reward materialization.
5. Implement configured player and admin menus, progress and history.
6. Configure seven production cases and assess before/after reward output.
7. Test restart, double-click, full inventory, season rollover, reroll and
   corrupted/unavailable provider cases; review integrated changes.
8. Publish, deploy, run live acceptance and record exact remaining gaps.

## Progress

- [x] Current addon and runtime versions inspected.
- [x] Existing ARC reward command failure semantics traced.
- [x] Exact EC lifecycle adapter verified against source and the runtime JAR.
- [x] Domain and durable storage implemented; inventory coordinator tested.
- [x] Player/admin interfaces implemented; provider bridge integration under verification.
- [x] Balance/configuration migration verified for seven launch cases.
- [ ] Source published and runtime acceptance completed.

## Current verification boundary

The full addon composite build on 2026-09-12 passed 72 tests with zero failures,
errors or skips, and produced the 0.4.0 shaded JAR. The consumer architecture
contract passed for arc-core 2.7.8. The shared paper API is compile-only and is
not present in the addon JAR. See [verification-20260912.md](verification-20260912.md)
for the tested scenarios and remaining delivery gates.

EC's native interaction handler ignores cancellation and rejects a full
inventory before its cancellable opening event. The adapter wraps only its
exact PlayerInteractEvent registration; link tools and unmanaged interactions
are forwarded. Managed openings fire the native cancellable CrateOpenEvent
before their durable debit. Tests cover an external veto, no self-veto,
pre-cancelled interactions and EC disable/enable rebinding. Native command
openings for managed cases are explicitly rejected because their event omits
free/forced/cost options.

Source verification does not establish live acceptance. Dependency publication,
coordinated ARC/addon delivery and player acceptance remain separate steps.
