# ExcellentCratesItemsAdder

Paper addon for [ExcellentCrates](https://github.com/nulli0n/ExcellentCrates-spigot)
and ItemsAdder. It protects furniture used as crate anchors, including creative
left clicks, and provides optional reward choices with durable mail.

## Requirements

- Paper/Purpur 1.21.11 and Java 25
- ExcellentCrates **6.6.1**, NightCore **2.16.4**
- ItemsAdder 4.x (deployment target: 4.0.18)
- ARC 1.4.64 or later with `ArcItemMaterializer` for managed reward pools

ExcellentCrates and ItemsAdder are not bundled. The managed interaction adapter
targets the verified ExcellentCrates 6.6.1 listener contract and rejects an
unexpected listener layout. The build verifies the official EC binary SHA-256.

## Protection and installation

Place the plugin JAR in `plugins/` and restart. All ExcellentCrates
`Block.Positions` entries are reread every five seconds. Matching furniture
entities are protected; ordinary furniture is unaffected. Left click opens the
crate preview. `/ecia edit on` temporarily permits deliberate furniture removal
for that administrator; `/ecia edit off` restores protection. Edit mode ends on
disconnect or plugin shutdown. `/ecia reload` rereads configuration.

`features.yml` ships disabled. Enable managed openings only after configuring
each case and its immutable season. `menus.yml` contains six-row
choice, mail, history and pool screens. Names and presentation are configurable;
model 11001 is rejected in every menu template. Russian and English chat text
is in `lang/`; existing protection messages in `config.yml` remain authoritative.
Managed openings first play a short, cancellable sealed-offer reveal and then
enable the three durable choices. Pool previews use five full reward rows and
a fixed bottom navigation row, without an item frame around the pool.

With configured cases, `enabled: false` pauses their openings while retaining
interception and current-season key stamps. It does not return old seasonal
keys to native opening rules. The empty default `cases: {}` provides protection
only. Removing a previously managed case requires an explicit key migration.

## Managed openings

One physical key buys one bundle. A configured number of distinct headline
offers (up to three) is shown; the selected headline reward is delivered with
weighted, distinct extras up to `bundle-size`. A reroll keeps two offers and
replaces only the highest-weight visible option instead of redrawing all three.

- `/ecia open [crate]` — use a matching physical key.
- `/ecia preview [crate]` — show the full frozen reward pool.
- `/ecia resume` — continue an unfinished selection.
- `/ecia mail` — claim saved physical rewards when inventory space is available.
- `/ecia history` — read personal opening history.

Player commands require `ecia.use` (default true); crate-specific native
permissions still apply. Rewards enter the inventory as real items, including
fresh redeemable ARC vouchers. No reward command is interpreted as proof of
delivery. A full inventory leaves the reward in mail without dropping it.

## Seasons and recovery

The first successful load freezes a case's reward definitions and selection
rules under its season ID. Change the season ID before changing a managed pool.
Native key-give commands stamp new physical keys with the current season;
existing unstamped keys belong to `launch`. Old keys use their archived pool.
Archived ARC vouchers preserve supported currency, command, furniture and
weighted Treasure recipes. Provider-backed IDs such as mounts must remain
available in their provider; absence keeps the reward unavailable rather than
silently substituting another reward.

The addon durably records an opening before key debit and records the exact
reward payload before delivery. Exact inventory preimages and saved player
receipts distinguish committed actions from actions proven not to have happened.
An uncertain disk/player-save result blocks automatic replay and enters review.
Rejoining allows reconciliation against freshly loaded player data. Keep both
the addon data directory and ARC's `data/reward-physical-archive` in backups.

Managed cases support exactly one enabled physical key cost and one supported
ARC reward or native key-give command per prize. Native cooldowns, milestones,
extra post-open commands and restricted rewards are rejected, not ignored.
Use `/ecia open` or the placed furniture to open these cases. Native free/forced
open commands and portable crate items are rejected because their additional
cost semantics are outside the journal contract.

## Administration

Administrative operations require `ecia.admin` (operator by default).

- `/ecia reconcile [online-player]` — reconcile saved evidence; it does not
  force a second payout when the outcome remains uncertain.
- `/ecia stats [crate] [page]` — compare final offers and selections
  with base draw weights, grouped by ordinary and rerolled openings. Base weight is
  not the final probability after player choice. Failed debit attempts are
  excluded. Statistics retain final offers, not every intermediate reroll set.
- `/ecia inspect [crate]` — inspect native anchors, model, key and pending mail.
- `/ecia repair [crate]` — repair only verified empty anchors through ItemsAdder;
  foreign blocks, ambiguous carriers or missing grounding evidence block repair.

Repair requires retained reports under `grounding/<namespace>/<item>.json`
with exact config/model hashes, the native spawn transform and collision-surface
evidence. Missing or stale reports refuse repair. Models with a custom parent
are currently refused because a complete parent-provenance manifest is not yet
supported. Ordinary protection and openings do not require grounding reports.

Never delete journals to clear an uncertain transaction. Inspect the record and
player receipt first. Journal corruption or an immutable-season conflict stops
managed opening until repaired; protection remains an independent listener.

## Building

```bash
./gradlew test shadowJar
```

Output: `build/libs/ExcellentCratesItemsAdder-0.7.0.jar`.
For coordinated ARC development, use an explicit local composite:

```bash
./gradlew -ParcCoreDir=/absolute/path/to/arc-core test shadowJar
```

Published builds use the pinned public Maven dependencies. Local composite
builds do not prove that those dependencies have been published or activated.

## License

MIT
