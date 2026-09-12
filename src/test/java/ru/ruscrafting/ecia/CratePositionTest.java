package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CratePositionTest {
    @Test
    void parsesExcellentCratesPositionIncludingNegativeCoordinates() {
        assertEquals(
                new CratePosition("rc_origin_spawn", -8, 71, 7),
                CratePosition.parse(" -8, 71, 7, rc_origin_spawn ").orElseThrow()
        );
    }

    @Test
    void rejectsMalformedPosition() {
        assertTrue(CratePosition.parse("-8,71,rc_origin_spawn").isEmpty());
        assertTrue(CratePosition.parse("x,71,7,rc_origin_spawn").isEmpty());
        assertTrue(CratePosition.parse("-8,71,7,").isEmpty());
    }
}
