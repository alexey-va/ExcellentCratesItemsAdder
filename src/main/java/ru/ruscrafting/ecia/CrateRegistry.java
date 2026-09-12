package ru.ruscrafting.ecia;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class CrateRegistry {
    private final Path crateDirectory;
    private final Consumer<String> warningSink;
    private final AtomicReference<Set<CratePosition>> positions = new AtomicReference<>(Set.of());

    CrateRegistry(Path crateDirectory, Consumer<String> warningSink) {
        this.crateDirectory = crateDirectory;
        this.warningSink = warningSink;
    }

    int reload() {
        Set<CratePosition> discovered = new HashSet<>();
        if (!Files.isDirectory(crateDirectory)) {
            warningSink.accept("ExcellentCrates directory does not exist: " + crateDirectory);
            positions.set(Set.of());
            return 0;
        }

        try (Stream<Path> files = Files.walk(crateDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(path -> loadFile(path, discovered));
        } catch (IOException exception) {
            warningSink.accept("Could not scan ExcellentCrates directory " + crateDirectory + ": " + exception.getMessage());
        }
        positions.set(Set.copyOf(discovered));
        return discovered.size();
    }

    boolean contains(CratePosition position) {
        return positions.get().contains(position);
    }

    int size() {
        return positions.get().size();
    }

    private void loadFile(Path path, Set<CratePosition> target) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            warningSink.accept("Could not read crate config " + path + ": " + exception.getMessage());
            return;
        }
        for (String raw : yaml.getStringList("Block.Positions")) {
            CratePosition.parse(raw).ifPresentOrElse(
                    target::add,
                    () -> warningSink.accept("Ignored malformed crate position '" + raw + "' in " + path)
            );
        }
    }
}
