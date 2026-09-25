# ArcExcellentCrates

Paper addon for [ExcellentCrates](https://github.com/nulli0n/ExcellentCrates-spigot)
and ItemsAdder. It protects furniture used as crate anchors, including creative
left clicks, and provides animated weighted rolls with durable recovery.

## Requirements

- Paper/Purpur 1.21.11 and Java 25
- PacketEvents **2.12.1**
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
each case and its native rewards. `menus.yml` contains six-row history and
pool screens. Names and presentation are configurable;
model 11001 is rejected in every menu template. Russian and English chat text
is in `lang/`; existing protection messages in `config.yml` remain authoritative.
The thirteen ordinary player chat notices use a white body with gold crate/key
names, amounts and reset dates. Key receipts omit `notice.heading`; other notices
show it only when the wrapped body leaves room within three rows. Longer custom
values keep every word. The large key remains anchored to the third row.
Self-grants omit the separate request-sent chat confirmation in both commands
and dialogs; the recipient still receives the actual key-delivery notice.
`CrateChatNotice` wraps resolved literal values, places the text column
35 logical pixels from the left edge (8 outer + 27 icon column), and sends the
whole framed notice as one component. The key is `arc:crate_key_large`, U+E531:
54×54 native pixels, provider height 27 / ascent 26 / advance 23. Install and
publish that ItemsAdder glyph before activating this version. Compact protection
actionbars and administrator messages keep their existing presentation.

`CrateChatNoticeTest` exports actual rendered components to
`build/reports/crate-chat-notices.json`. `scripts/preview-crate-chat.py` renders
those fixtures through the ops workspace's shared Minecraft renderer, using
an official 1.21.11 client JAR, the current server pack and matching Faithful32.
It records input hashes and checks every rendered line against chat width.
These images are offline previews; native-client acceptance is a separate check.
Newly issued physical keys use the configured ExcellentCrates key name while
preserving their native key identity and ItemsAdder model metadata. Existing
keys already held by players are not rewritten.
Enabled managed reward pools are checked again four ticks after server-load completion,
after PlayerParticles 8.13's three-tick preset parser. This lifecycle-owned retry
includes late reward providers even when the early enable pass succeeded.
Managed openings perform one durable weighted roll and immediately start a
player-only packet-display reel above the physical crate. The rolled item stops
beneath its pointer before delivery. A paired ItemsAdder model such as
`akira_chest` / `akira_chest_opening` switches while the reel runs; vanilla
chests use their native lid. World-visible sound and particle bursts accompany
the transition. The roulette can be private or visible to nearby players; every
viewer receives a personal display plane that continuously turns toward them.
Concurrent reels at the same crate reserve separate vertical rows, and every
reward step plays a short mechanical click before the distinct winner sound.
While the crate is idle, packet-backed item displays from its reward pool use one of 23 presets.
Alongside the original circular scenes, non-orbit layouts include a single-item
showcase, three slot-machine reels, a reward wall, conveyor, falling reward rain,
vertical trophy tower, opening card fan, and two-sided balance. Count, radius,
height, scale, speed, and range are adjustable per placed crate.
Idle reward displays are capped at 30 blocks by `case-ambient.max-view-distance-blocks`,
including crates with older per-anchor range overrides. The packet renderer removes
the displays from viewers outside that distance and restores them on approach.
Changes to this global `config.yml` limit require a plugin/server restart.
Pool previews use only as many reward rows as the current page needs, with at
most 27 rewards per page. A single-page pool has no navigation row or arrows.
Multi-page pools add one navigation row; previous/next controls stay visible,
disabled at the corresponding edge. Native reward descriptions and usage hints
are preserved, followed by the roll chance computed from the live pool weights.
Rewards are sorted before pagination by descending unrounded chance, then by
their visible name in Russian alphabetical order (ignoring formatting and case),
then by stable reward ID. This presentation order never changes the rolling pool.
Reward descriptions do not contain nested chance placeholders, and rendering
never changes the delivered item's quantity or metadata.

Placed cases use a native billboard `TextDisplay` above each anchor. It contains
only the case name, sits close to the block, and turns toward each viewer;
height, scale, yaw offset, and view range are configurable under `case-holograms`.
The shipped hologram and idle-animation defaults match the tuned daily cache in
the Origin world.

With configured cases, `enabled: false` pauses their openings while retaining
interception. The empty default `cases: {}` provides protection only. The
managed path accepts the native physical key by its key id, so old stamped keys
remain usable after a reload without a separate season migration.

## Managed openings

One physical key performs one weighted roll and buys one configured reward
bundle. The reel only visualizes the result already written to the durable
journal; it never rolls again while moving or when the item is delivered.

An individual case can set `free-open-period: daily` or `weekly` in
`features.yml`. Calendar windows use `free-open-time-zone` (default
`Europe/Moscow`); weeks reset on Monday. One attempt is available per player and
window. At login and calendar rollover the addon issues one physical key when a
storage slot is available; a full inventory leaves the entitlement unclaimed and
retries within five seconds after space appears. Missed periods do not accumulate.
Auto-keys are personal and expire at their period boundary; the next inventory
reconciliation removes expired copies. Their native appearance is preserved, but
the native ExcellentCrates key marker is replaced with the addon's period identity
so another native/unmanaged opener cannot ignore expiry. Ordinary admin/paid keys
keep their existing behavior. Holding an actual valid key highlights its case.

`PeriodicKeysService` owns the online schedule and daily notification gate;
`PeriodicKeyIssuer` persists a capacity-checked inventory witness before granting.
When HuskSync is installed, recovery and grants wait for its native inventory
unlock; sync completion retries join recovery after the provider has applied data.
`periodic-keys/` and `periodic-key-notices/` use arc-core's durable record journal.
One notification per player per local calendar day combines the received daily
and weekly keys. The notice restores the “Сундуки RusCrafting” heading and omits
the final use instruction. Reset/expiry text uses “в полночь” for daily keys and
“в понедельник, в полночь” for weekly keys, without numeric timestamps.
A native save with an unknown outcome requires fresh
player-data reconciliation; it never blindly reissues a key. Previously spent
virtual-opening IDs remain spent for their existing day/week, including after
restart. Spending an auto-key uses that same identity, so a copied item cannot
buy another roll. No reward pool, chance or quantity changes with this migration.
Enable this policy only on the single backend that owns case openings: local
journals are not a distributed quota across independent server directories.
The configured authority is the spawn server; no second issuing backend should
be enabled. Include both periodic directories in the same backup as `openings/`.

Players open and preview a crate directly at its pedestal; `ecia.use` is granted
by default and crate-specific native permissions still apply. Rewards enter the inventory as real items, including
fresh redeemable ARC vouchers. No reward command is interpreted as proof of
delivery. A full inventory preserves the pending opening; free slots and
right-click a crate again to retry delivery.

## Current rewards and recovery

Every reload reads the currently loaded native reward list and rebuilds the
managed pool from it. A bad reward is reported at error level and omitted from
that case; the other rewards remain usable. There is no immutable season pool
and no reward drift gate. Ordinary physical keys are matched by the native key id, so
legacy `ecia:season` metadata is ignored for new openings.

The addon durably records an opening before key debit and records the exact
reward payload before delivery. Exact inventory preimages and saved player
receipts distinguish committed actions from actions proven not to have happened.
An uncertain disk/player-save result blocks automatic replay and enters review.
Rejoining allows reconciliation against freshly loaded player data. Keep both
the addon data directory and ARC's `data/reward-physical-archive` in backups.
Journal I/O failures remain fail-closed across settings reloads; after repairing
storage, restart the plugin runtime to reload and reconcile the journal. Never
delete a journal to reset a player's quota or clear an uncertain delivery.

Removing an anchor uses ExcellentCrates' native position mutators because its
position getter returns a copy. Idle displays and holograms also stop rendering
when the saved point no longer contains a block or ItemsAdder furniture; this
does not destructively remove the saved point.

Managed cases support exactly one enabled physical key cost and one supported
ARC reward or native key-give command per prize. Native cooldowns, milestones,
extra post-open commands and restricted rewards are logged and the affected
case/reward is skipped, while valid rewards remain available.
Use the placed furniture to open these cases. Native free/forced
open commands and portable crate items are rejected because their additional
cost semantics are outside the journal contract.

## Administration

`/arc-crate` and Shift + left-click settings require `ecia.admin` (operator by
default). The command opens a native administrator center for placing a case or
granting a physical key. The key flow selects a loaded key, player, amount from
1 to 64, and exactly one backend from `key-delivery.backends`. ARC `/x` waits
briefly for that player on the selected backend. The source serializes the exact
physical key item, so the lightweight
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
player receipt first. Journal corruption stops managed opening until repaired;
protection remains an independent listener.

## Building

Focused calendar-key and durable-delivery regression checks:

```bash
./gradlew test --tests ru.ruscrafting.ecia.integration.PeriodicKeyIssuerTest --tests ru.ruscrafting.ecia.integration.PeriodicPhysicalKeyTest --tests ru.ruscrafting.ecia.integration.ManagedOpeningEngineTest --tests ru.ruscrafting.ecia.runtime.CrateChatNoticeTest --tests ru.ruscrafting.ecia.runtime.EciaLocaleTest
```

```bash
./gradlew test shadowJar
```

Output: `build/libs/ArcExcellentCrates-<version>.jar`.
For coordinated ARC development, use an explicit local composite:

```bash
./gradlew -ParcCoreDir=/absolute/path/to/arc-core test shadowJar
```

Published builds use the pinned public Maven dependencies. Local composite
builds do not prove that those dependencies have been published or activated.

## License

MIT
