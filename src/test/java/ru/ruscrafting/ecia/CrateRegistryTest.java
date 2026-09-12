package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        CrateRegistry registry = new CrateRegistry(directory, warnings::add);

        assertEquals(2, registry.reload());
        assertTrue(registry.contains(new CratePosition("rc_origin_spawn", -8, 71, 7)));
        assertTrue(registry.contains(new CratePosition("rc_origin_spawn", -10, 71, 0)));
        assertEquals("daily", registry.crateId(new CratePosition("rc_origin_spawn", -8, 71, 7)).orElseThrow());
        assertEquals("weekly", registry.crateId(new CratePosition("rc_origin_spawn", -10, 71, 0)).orElseThrow());
        assertEquals(1, warnings.size());
    }
}
