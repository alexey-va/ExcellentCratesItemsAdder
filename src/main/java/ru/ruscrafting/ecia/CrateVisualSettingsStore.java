package ru.ruscrafting.ecia;

import org.bukkit.Location;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Per-placed-crate visual geometry with config-backed defaults. */
public final class CrateVisualSettingsStore {
    private static final String FILE_NAME = "visuals.yml";

    private final Path file;
    private final Configuration config;
    private final Map<Anchor, Visuals> overrides = new HashMap<>();
    private Hologram defaultHologram;
    private Roulette defaultRoulette;

    CrateVisualSettingsStore(ArcExcellentCratesPlugin plugin) {
        this(plugin.getDataFolder().toPath(), plugin.getConfig());
    }

    CrateVisualSettingsStore(Path dataFolder, Configuration config) {
        this.file = dataFolder.resolve(FILE_NAME);
        this.config = config;
        reload();
    }

    public void reload() {
        defaultHologram = new Hologram(
                value("case-holograms.offset-x", 0.0, -8.0, 8.0),
                value("case-holograms.height-above-block", 0.18, -4.0, 8.0),
                value("case-holograms.offset-z", 0.0, -8.0, 8.0),
                (float) value("case-holograms.yaw", 0.0, -180.0, 180.0),
                (float) value("case-holograms.pitch", 0.0, -90.0, 90.0),
                (float) value("case-holograms.scale", 2.0, 0.1, 10.0),
                (float) value("case-holograms.view-range", 1.0, 0.1, 64.0)
        );
        defaultRoulette = new Roulette(
                value("case-roulette.offset-x", 0.0, -16.0, 16.0),
                value("case-roulette.height-above-block", 3.65, -4.0, 16.0),
                value("case-roulette.offset-z", 0.0, -16.0, 16.0),
                value("case-roulette.item-spacing", 0.82, 0.1, 5.0),
                (float) value("case-roulette.item-scale", 0.95, 0.1, 10.0),
                (float) value("case-roulette.winner-scale", 1.32, 0.1, 12.0),
                value("case-roulette.pointer-height", 1.08, -4.0, 8.0),
                (float) value("case-roulette.pointer-scale", 1.55, 0.1, 10.0),
                (float) value("case-roulette.view-range", 24.0, 0.1, 64.0)
        );
        overrides.clear();
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        var anchors = yaml.getConfigurationSection("anchors");
        if (anchors == null) return;
        for (String key : anchors.getKeys(false)) {
            String path = "anchors." + key;
            String world = yaml.getString(path + ".world");
            if (world == null || world.isBlank()) continue;
            Anchor anchor = new Anchor(world, yaml.getInt(path + ".x"), yaml.getInt(path + ".y"), yaml.getInt(path + ".z"));
            Hologram hologram = new Hologram(
                    bounded(yaml.getDouble(path + ".hologram.offset-x", defaultHologram.offsetX()), -8, 8, defaultHologram.offsetX()),
                    bounded(yaml.getDouble(path + ".hologram.offset-y", defaultHologram.offsetY()), -4, 8, defaultHologram.offsetY()),
                    bounded(yaml.getDouble(path + ".hologram.offset-z", defaultHologram.offsetZ()), -8, 8, defaultHologram.offsetZ()),
                    (float) bounded(yaml.getDouble(path + ".hologram.yaw", defaultHologram.yaw()), -180, 180, defaultHologram.yaw()),
                    (float) bounded(yaml.getDouble(path + ".hologram.pitch", defaultHologram.pitch()), -90, 90, defaultHologram.pitch()),
                    (float) bounded(yaml.getDouble(path + ".hologram.scale", defaultHologram.scale()), .1, 10, defaultHologram.scale()),
                    (float) bounded(yaml.getDouble(path + ".hologram.view-range", defaultHologram.viewRange()), .1, 64, defaultHologram.viewRange())
            );
            Roulette roulette = new Roulette(
                    bounded(yaml.getDouble(path + ".roulette.offset-x", defaultRoulette.offsetX()), -16, 16, defaultRoulette.offsetX()),
                    bounded(yaml.getDouble(path + ".roulette.offset-y", defaultRoulette.offsetY()), -4, 16, defaultRoulette.offsetY()),
                    bounded(yaml.getDouble(path + ".roulette.offset-z", defaultRoulette.offsetZ()), -16, 16, defaultRoulette.offsetZ()),
                    bounded(yaml.getDouble(path + ".roulette.item-spacing", defaultRoulette.itemSpacing()), .1, 5, defaultRoulette.itemSpacing()),
                    (float) bounded(yaml.getDouble(path + ".roulette.item-scale", defaultRoulette.itemScale()), .1, 10, defaultRoulette.itemScale()),
                    (float) bounded(yaml.getDouble(path + ".roulette.winner-scale", defaultRoulette.winnerScale()), .1, 12, defaultRoulette.winnerScale()),
                    bounded(yaml.getDouble(path + ".roulette.pointer-height", defaultRoulette.pointerHeight()), -4, 8, defaultRoulette.pointerHeight()),
                    (float) bounded(yaml.getDouble(path + ".roulette.pointer-scale", defaultRoulette.pointerScale()), .1, 10, defaultRoulette.pointerScale()),
                    (float) bounded(yaml.getDouble(path + ".roulette.view-range", defaultRoulette.viewRange()), .1, 64, defaultRoulette.viewRange())
            );
            overrides.put(anchor, new Visuals(hologram, roulette));
        }
    }

    public Visuals get(Anchor anchor) {
        return overrides.getOrDefault(anchor, defaults());
    }

    public Visuals defaults() {
        return new Visuals(defaultHologram, defaultRoulette);
    }

    public void save(Anchor anchor, Visuals visuals) {
        overrides.put(anchor, visuals);
        persist();
    }

    public void reset(Anchor anchor) {
        if (overrides.remove(anchor) != null) persist();
    }

    private void persist() {
        YamlConfiguration yaml = new YamlConfiguration();
        int index = 0;
        for (Map.Entry<Anchor, Visuals> entry : overrides.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Anchor anchor = entry.getKey();
            Visuals visuals = entry.getValue();
            String path = "anchors.a" + index++;
            yaml.set(path + ".world", anchor.world());
            yaml.set(path + ".x", anchor.x());
            yaml.set(path + ".y", anchor.y());
            yaml.set(path + ".z", anchor.z());
            write(yaml, path + ".hologram", visuals.hologram());
            write(yaml, path + ".roulette", visuals.roulette());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save per-crate visual settings", failure);
        }
    }

    private static void write(YamlConfiguration yaml, String path, Hologram value) {
        yaml.set(path + ".offset-x", value.offsetX()); yaml.set(path + ".offset-y", value.offsetY());
        yaml.set(path + ".offset-z", value.offsetZ()); yaml.set(path + ".yaw", value.yaw());
        yaml.set(path + ".pitch", value.pitch()); yaml.set(path + ".scale", value.scale());
        yaml.set(path + ".view-range", value.viewRange());
    }

    private static void write(YamlConfiguration yaml, String path, Roulette value) {
        yaml.set(path + ".offset-x", value.offsetX()); yaml.set(path + ".offset-y", value.offsetY());
        yaml.set(path + ".offset-z", value.offsetZ()); yaml.set(path + ".item-spacing", value.itemSpacing());
        yaml.set(path + ".item-scale", value.itemScale()); yaml.set(path + ".winner-scale", value.winnerScale());
        yaml.set(path + ".pointer-height", value.pointerHeight()); yaml.set(path + ".pointer-scale", value.pointerScale());
        yaml.set(path + ".view-range", value.viewRange());
    }

    private double value(String path, double fallback, double minimum, double maximum) {
        return bounded(config.getDouble(path, fallback), minimum, maximum, fallback);
    }

    private static double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }

    public record Anchor(String world, int x, int y, int z) implements Comparable<Anchor> {
        public static Anchor of(Location location) {
            return new Anchor(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        @Override public int compareTo(Anchor other) {
            int worldOrder = world.compareTo(other.world);
            if (worldOrder != 0) return worldOrder;
            int xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) return xOrder;
            int yOrder = Integer.compare(y, other.y);
            return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
        }
    }

    public record Hologram(double offsetX, double offsetY, double offsetZ, float yaw, float pitch,
                            float scale, float viewRange) { }

    public record Roulette(double offsetX, double offsetY, double offsetZ, double itemSpacing,
                           float itemScale, float winnerScale, double pointerHeight, float pointerScale,
                           float viewRange) { }

    public record Visuals(Hologram hologram, Roulette roulette) { }
}
