package ru.ruscrafting.ecia;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateRegistryTest {
    @TempDir
    Path directory;

    @Test
    void loadsAllPhysicalPositionsAndIgnoresMalformedOnes() throws IOException {
        Files.writeString(directory.resolve("daily.yml"), """
                Block:
                  Positions:
                    - '-8,71,7,rc_origin_spawn'
                    - 'bad-position'
                """);
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("nested/weekly.yaml"), """
                Block:
                  Positions:
                    - '-10,71,0,rc_origin_spawn'
                """);

        List<String> warnings = new ArrayList<>();
        CountingRegistry registry = new CountingRegistry(directory, warnings::add);

        assertEquals(2, registry.reload());
        assertTrue(registry.contains(new CratePosition("rc_origin_spawn", -8, 71, 7)));
        assertTrue(registry.contains(new CratePosition("rc_origin_spawn", -10, 71, 0)));
        assertEquals("daily", registry.crateId(new CratePosition("rc_origin_spawn", -8, 71, 7)).orElseThrow());
        assertEquals("weekly", registry.crateId(new CratePosition("rc_origin_spawn", -10, 71, 0)).orElseThrow());
        assertEquals(1, warnings.size());
        assertEquals(2, registry.parseCount);

        List<String> firstWarnings = List.copyOf(warnings);
        warnings.clear();
        assertEquals(2, registry.reload());
        assertEquals(2, registry.parseCount);
        assertEquals(firstWarnings, warnings);
    }

    @Test
    void detectsContentChangesWithUnchangedFileSizeAndTimestamp() throws IOException {
        Path file = directory.resolve("daily.yml");
        Files.writeString(file, config("-8,71,7,rc_origin_spawn"));
        long originalSize = Files.size(file);
        FileTime originalTime = Files.getLastModifiedTime(file);
        CountingRegistry registry = new CountingRegistry(directory, ignored -> { });
        assertEquals(1, registry.reload());

        Files.writeString(file, config("-9,71,7,rc_origin_spawn"));
        Files.setLastModifiedTime(file, originalTime);
        assertEquals(originalSize, Files.size(file));
        assertEquals(originalTime, Files.getLastModifiedTime(file));

        assertEquals(1, registry.reload());
        assertEquals(2, registry.parseCount);
        assertTrue(registry.crateId(new CratePosition("rc_origin_spawn", -8, 71, 7)).isEmpty());
        assertEquals("daily", registry.crateId(new CratePosition("rc_origin_spawn", -9, 71, 7)).orElseThrow());
    }

    @Test
    void reconcilesAddedDeletedAndRenamedFilesInSortedOrder() throws IOException {
        String content = config("-8,71,7,rc_origin_spawn");
        CratePosition position = new CratePosition("rc_origin_spawn", -8, 71, 7);
        Path zeta = directory.resolve("zeta.yml");
        Path alpha = directory.resolve("alpha.yml");
        Path beta = directory.resolve("beta.yml");
        Files.writeString(zeta, content);
        List<String> warnings = new ArrayList<>();
        CountingRegistry registry = new CountingRegistry(directory, warnings::add);
        assertEquals(1, registry.reload());
        assertEquals("zeta", registry.crateId(position).orElseThrow());

        Files.writeString(alpha, content);
        assertEquals(1, registry.reload());
        assertEquals("alpha", registry.crateId(position).orElseThrow());
        assertEquals(2, registry.parseCount);
        assertEquals(List.of("Ignored duplicate crate position " + position
                + " for zeta; already owned by alpha"), warnings);

        List<String> firstWarnings = List.copyOf(warnings);
        warnings.clear();
        assertEquals(1, registry.reload());
        assertEquals(2, registry.parseCount);
        assertEquals(firstWarnings, warnings);

        Files.delete(alpha);
        warnings.clear();
        assertEquals(1, registry.reload());
        assertEquals("zeta", registry.crateId(position).orElseThrow());
        assertEquals(2, registry.parseCount);
        assertTrue(warnings.isEmpty());

        Files.move(zeta, beta);
        assertEquals(1, registry.reload());
        assertEquals("beta", registry.crateId(position).orElseThrow());
        assertEquals(3, registry.parseCount);

        Files.delete(beta);
        assertEquals(0, registry.reload());
        Files.writeString(beta, content);
        assertEquals(1, registry.reload());
        assertEquals(4, registry.parseCount);
    }

    @Test
    void readFailureDropsCachedPositionsAndRetriesTheFile() throws IOException {
        Path file = directory.resolve("daily.yml");
        Files.writeString(file, config("-8,71,7,rc_origin_spawn"));
        List<String> warnings = new ArrayList<>();
        CountingRegistry registry = new CountingRegistry(directory, warnings::add);
        assertEquals(1, registry.reload());

        registry.failReads = true;
        assertEquals(0, registry.reload());
        assertEquals(0, registry.size());
        assertEquals(List.of("Could not read crate config " + file + ": simulated read failure"), warnings);

        registry.failReads = false;
        assertEquals(1, registry.reload());
        assertEquals(2, registry.parseCount);
    }

    @Test
    void invalidYamlDropsCachedPositionsAndIsReportedOnEachRefresh() throws IOException {
        Path file = directory.resolve("daily.yml");
        String content = config("-8,71,7,rc_origin_spawn");
        Files.writeString(file, content);
        List<String> warnings = new ArrayList<>();
        CountingRegistry registry = new CountingRegistry(directory, warnings::add);
        assertEquals(1, registry.reload());

        Files.writeString(file, "Block: [unterminated");
        assertEquals(0, registry.reload());
        assertEquals(0, registry.reload());
        assertEquals(3, registry.parseCount);
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(warning -> warning.startsWith("Could not read crate config " + file)));

        Files.writeString(file, content);
        assertEquals(1, registry.reload());
        assertEquals(4, registry.parseCount);
    }

    @Test
    void missingDirectoryClearsPositionsAndCachedFiles() throws IOException {
        Path file = directory.resolve("daily.yml");
        String content = config("-8,71,7,rc_origin_spawn");
        Files.writeString(file, content);
        List<String> warnings = new ArrayList<>();
        CountingRegistry registry = new CountingRegistry(directory, warnings::add);
        assertEquals(1, registry.reload());

        Files.delete(file);
        Files.delete(directory);
        assertEquals(0, registry.reload());
        assertEquals(List.of("ExcellentCrates directory does not exist: " + directory), warnings);

        Files.createDirectory(directory);
        Files.writeString(file, content);
        assertEquals(1, registry.reload());
        assertEquals(2, registry.parseCount);
    }

    private static String config(String position) {
        return "Block:\n  Positions:\n    - '" + position + "'\n";
    }

    private static final class CountingRegistry extends CrateRegistry {
        private int parseCount;
        private boolean failReads;

        private CountingRegistry(Path directory, Consumer<String> warningSink) {
            super(directory, warningSink);
        }

        @Override
        byte[] readFile(Path path) throws IOException {
            if (failReads) throw new IOException("simulated read failure");
            return super.readFile(path);
        }

        @Override
        YamlConfiguration parseYaml(byte[] content) throws IOException, InvalidConfigurationException {
            parseCount++;
            return super.parseYaml(content);
        }
    }
}
