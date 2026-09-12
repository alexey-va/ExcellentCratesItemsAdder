# ExcellentCratesItemsAdder

A small Paper addon that protects ItemsAdder furniture used as physical
[ExcellentCrates](https://github.com/nulli0n/ExcellentCrates-spigot) crates.

ItemsAdder furniture is represented by entities. A creative-mode left click can
therefore remove a crate even when a crate plugin protects the block position.
This addon reads every `Block.Positions` entry from ExcellentCrates and cancels
damage to the matching ItemsAdder furniture entity. Ordinary ItemsAdder
furniture is not affected.

## Requirements

- Paper or Purpur 1.21.11
- Java 21+
- ItemsAdder 4.x
- ExcellentCrates 6.6.x

No ExcellentCrates or ItemsAdder binary is bundled or linked at compile time.
The addon reads the public YAML configuration and the furniture marker stored
in Bukkit persistent data.

## Installation

1. Put the JAR in `plugins/`.
2. Restart the server.
3. Check `/ecia edit status` as an operator.

The registry automatically rereads crate positions every five seconds. You can
also run `/ecia reload` after changing crate configuration.

## Admin editing

`/ecia edit on` disables protection only for the issuing administrator. Use it
to remove or reposition a physical crate, then run `/ecia edit off`. Editing
mode is cleared when the player leaves or the plugin stops.

Permission: `ecia.admin` (operator by default).

## Building

```bash
./gradlew test build
```

The resulting plugin is in `build/libs/`.

## License

MIT
