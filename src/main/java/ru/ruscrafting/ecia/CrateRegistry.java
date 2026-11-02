package ru.ruscrafting.ecia;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

class CrateRegistry {
    private final Path crateDirectory;
    private final Consumer<String> warningSink;
    private final AtomicReference<Map<CratePosition, String>> crates = new AtomicReference<>(Map.of());
    private Map<Path, CachedFile> cachedFiles = Map.of();

    CrateRegistry(Path crateDirectory, Consumer<String> warningSink) {
        this.crateDirectory = crateDirectory;
        this.warningSink = warningSink;
    }

    /** Worker-only: periodic and manual refreshes may arrive on different executors. */
    synchronized int reload() {
        Map<CratePosition, String> discovered = new HashMap<>();
        Map<Path, CachedFile> refreshedFiles = new HashMap<>();
        if (!Files.isDirectory(crateDirectory)) {
            warningSink.accept("ExcellentCrates directory does not exist: " + crateDirectory);
            cachedFiles = Map.of();
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
                    .forEach(path -> loadFile(path, discovered, refreshedFiles));
        } catch (IOException | UncheckedIOException exception) {
            warningSink.accept("Could not scan ExcellentCrates directory " + crateDirectory + ": " + exception.getMessage());
        }
        cachedFiles = refreshedFiles;
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

    private void loadFile(Path path, Map<CratePosition, String> target, Map<Path, CachedFile> refreshedFiles) {
        CachedFile cached;
        try {
            byte[] content = readFile(path);
            cached = cachedFiles.get(path);
            if (cached == null || !Arrays.equals(cached.content(), content)) {
                List<ParsedPosition> positions = parseYaml(content).getStringList("Block.Positions").stream()
                        .map(raw -> new ParsedPosition(raw, CratePosition.parse(raw).orElse(null)))
                        .toList();
                cached = new CachedFile(content, positions);
            }
            refreshedFiles.put(path, cached);
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
        for (ParsedPosition entry : cached.positions()) {
            CratePosition position = entry.position();
            if (position == null) {
                warningSink.accept("Ignored malformed crate position '" + entry.raw() + "' in " + path);
                continue;
            }
            String previous = target.putIfAbsent(position, crateId);
            if (previous != null && !previous.equals(crateId)) {
                warningSink.accept("Ignored duplicate crate position " + position + " for " + crateId
                        + "; already owned by " + previous);
            }
        }
    }

    byte[] readFile(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    YamlConfiguration parseYaml(byte[] content) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
        return yaml;
    }

    private record CachedFile(byte[] content, List<ParsedPosition> positions) { }
    private record ParsedPosition(String raw, CratePosition position) { }
}
