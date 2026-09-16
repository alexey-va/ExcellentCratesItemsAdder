# ArcExcellentCrates

Paper addon for [ExcellentCrates](https://github.com/nulli0n/ExcellentCrates-spigot)
and ItemsAdder. It protects furniture used as crate anchors, including creative
left clicks, and provides animated weighted rolls with durable recovery.

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
crate preview. Shift + left-click opens the per-anchor visual editor for an
administrator. The old `/ecia` command is not registered.

The addon does not intercept or alias ExcellentCrates `/case`. Its standalone
`/arc-crate` administrator center opens placement and key-delivery flows. New
placement chooses any loaded reward pool, then a vanilla chest, trapped chest,
barrel, Ender Chest, or an ItemsAdder furniture model whose id identifies an
actual chest. The per-anchor editor can remove one installed chest after an
explicit confirmation without deleting its reward pool or keys. The target is
the empty block adjacent to the face under the crosshair. The native namespaced
ExcellentCrates command remains available without forking ExcellentCrates.

`features.yml` ships disabled. Enable managed openings only after configuring
each case and its frozen reward pool. `menus.yml` contains six-row history and
pool screens. Names and presentation are configurable;
model 11001 is rejected in every menu template. Russian and English chat text
is in `lang/`; existing protection messages in `config.yml` remain authoritative.
Managed openings perform one durable weighted roll and immediately start a
player-only display-entity reel above the physical crate. The rolled item stops
beneath its pointer before delivery. A paired ItemsAdder model such as
`akira_chest` / `akira_chest_opening` switches while the reel runs; vanilla
chests use their native lid. World-visible sound and particle bursts accompany
the transition. The roulette can be private or visible to nearby players; every
viewer receives a personal display plane that continuously turns toward them.
Concurrent reels at the same crate reserve separate vertical rows, and every
reward step plays a short mechanical click before the distinct winner sound.
While the crate is idle, real items from its reward pool use one of 23 presets.
Alongside the original circular scenes, non-orbit layouts include a single-item
showcase, three slot-machine reels, a reward wall, conveyor, falling reward rain,
vertical trophy tower, opening card fan, and two-sided balance. Count, radius,
height, scale, speed, and range are adjustable per placed crate.
Pool previews use five full reward rows and
a fixed bottom navigation row, without an item frame around the pool.

Placed cases use a native billboard `TextDisplay` above each anchor. It contains
only the case name, sits close to the block, and turns toward each viewer;
height, scale, yaw offset, and view range are configurable under `case-holograms`.
The shipped hologram and idle-animation defaults match the tuned daily cache in
the Origin world.

With configured cases, `enabled: false` pauses their openings while retaining
interception and current-season key stamps. It does not return old seasonal
keys to native opening rules. The empty default `cases: {}` provides protection
only. Removing a previously managed case requires an explicit key migration.

## Managed openings

One physical key performs one weighted roll and buys one configured reward
bundle. The reel only visualizes the result already written to the durable
journal; it never rolls again while moving or when the item is delivered.

Players open and preview a crate directly at its pedestal; `ecia.use` is granted
by default and crate-specific native permissions still apply. Rewards enter the inventory as real items, including
fresh redeemable ARC vouchers. No reward command is interpreted as proof of
delivery. A full inventory preserves the pending opening; free slots and
right-click a crate again to retry delivery.

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
Use the placed furniture to open these cases. Native free/forced
open commands and portable crate items are rejected because their additional
cost semantics are outside the journal contract.

## Administration

`/arc-crate` and Shift + left-click settings require `ecia.admin` (operator by
default). The command opens a native administrator center for placing a case or
granting a physical key. The key flow selects a loaded key, player, amount from
1 to 64, and exactly one backend from `key-delivery.backends`. ARC `/x` waits
briefly for that player on the selected backend. The source serializes the exact
physical key item, including its current managed season, so the lightweight
receiver can deliver it on a backend that does not run ExcellentCrates. Requests
are never broadcast or retried automatically. The direct form is also available
as `/arc-crate key player key [amount] [server]`. Amount defaults to `1`, and
omitting the server grants the key on the backend where the command is run.

Shift + left-click changes the stationary animation, roulette audience and
geometry, hologram, or physical vanilla/ItemsAdder shell without moving the
crate anchor. Twenty-three idle presets range from horizontal orbits and fountains
to a continuously spinning fortune wheel, rocking wheel, figure eight, tilted
rings, carousel, comet trail, flower, double helix, wave, and counter-rotating
clockwork rings, plus a showcase, reels, wall, conveyor, rain, tower, fan, and
balance scene. Using a key stops the idle scene before the opening animation.

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

Output: `build/libs/ArcExcellentCrates-0.13.0.jar`.
For coordinated ARC development, use an explicit local composite:

```bash
./gradlew -ParcCoreDir=/absolute/path/to/arc-core test shadowJar
```

Published builds use the pinned public Maven dependencies. Local composite
builds do not prove that those dependencies have been published or activated.

## License

MIT
