package ru.ruscrafting.ecia;

import org.junit.jupiter.api.Test;
import ru.ruscrafting.ecia.api.CrateAcquisitionReward;
import ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot;
import ru.ruscrafting.ecia.api.CrateLocationService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateAcquisitionApiTest {
    @Test
    void legacyLocationProvidersRemainFunctionalAndReportUnavailableAcquisition() {
        CrateLocationService provider = location -> true;

        assertTrue(provider.isCrateLocation(null));
        assertTrue(provider.acquisitionCrateIds().isEmpty());
        var snapshot = provider.acquisitionSnapshotAsync("case_daily").join();
        assertEquals("case_daily", snapshot.crateId());
        assertFalse(snapshot.available());
        assertTrue(snapshot.rewards().isEmpty());
    }

    @Test
    void snapshotsOwnAnImmutableCopyOfTheirRewards() {
        var reward = new CrateAcquisitionReward("ae", "AE box", 20.0, 0.2, "daily", "ae_box");
        var input = new ArrayList<>(List.of(reward));
        var snapshot = new CrateAcquisitionSnapshot("daily", "Daily crate", true, input);
        input.clear();

        assertEquals(List.of(reward), snapshot.rewards());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.rewards().clear());
    }
}
