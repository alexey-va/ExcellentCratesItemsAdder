package ru.ruscrafting.ecia;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class CrateRegistry {
    private final Path crateDirectory;
    private final Consumer<String> warningSink;
    private final AtomicReference<Map<CratePosition, String>> crates = new AtomicReference<>(Map.of());

    CrateRegistry(Path crateDirectory, Consumer<String> warningSink) {
        this.crateDirectory = crateDirectory;
        this.warningSink = warningSink;
    }

    int reload() {
        Map<CratePosition, String> discovered = new HashMap<>();
        if (!Files.isDirectory(crateDirectory)) {
            warningSink.accept("ExcellentCrates directory does not exist: " + crateDirectory);
            crates.set(Map.of());
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
        crates.set(Map.copyOf(discovered));
        return discovered.size();
    }

    boolean contains(CratePosition position) {
        return crates.get().containsKey(position);
    }

    Optional<String> crateId(CratePosition position) {
        return Optional.ofNullable(crates.get().get(position));
    }

    int size() {
        return crates.get().size();
    }

    private void loadFile(Path path, Map<CratePosition, String> target) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            warningSink.accept("Could not read crate config " + path + ": " + exception.getMessage());
            return;
        }
        String fileName = path.getFileName().toString();
        String crateId = fileName.substring(0, fileName.lastIndexOf('.'));
        if (!crateId.matches("[A-Za-z0-9_-]+")) {
            warningSink.accept("Ignored unsafe crate id from file " + path);
            return;
        }
        for (String raw : yaml.getStringList("Block.Positions")) {
            CratePosition.parse(raw).ifPresentOrElse(
                    position -> {
                        String previous = target.putIfAbsent(position, crateId);
                        if (previous != null && !previous.equals(crateId)) {
                            warningSink.accept("Ignored duplicate crate position " + position + " for " + crateId
                                    + "; already owned by " + previous);
                        }
                    },
                    () -> warningSink.accept("Ignored malformed crate position '" + raw + "' in " + path)
            );
        }
    }
}
