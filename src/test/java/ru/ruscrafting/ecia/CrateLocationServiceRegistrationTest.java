package ru.ruscrafting.ecia;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import ru.arc.paper.testing.MockBukkitTestRuntime;
import ru.ruscrafting.ecia.api.CrateLocationService;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CrateLocationServiceRegistrationTest {
    @Test
    void registersTypedProviderAndUnregistersItAtLifecycleClose() {
        try (MockBukkitTestRuntime paper = MockBukkitTestRuntime.open()) {
            Plugin owner = paper.createSimplePlugin("CrateLocationOwner");
            CrateLocationService provider = location -> true;
            CrateLocationServiceRegistration registration =
                    new CrateLocationServiceRegistration((org.bukkit.plugin.java.JavaPlugin) owner, provider);

            assertSame(provider, paper.getServer().getServicesManager().load(CrateLocationService.class));

            registration.close();
            registration.close();

            assertNull(paper.getServer().getServicesManager().load(CrateLocationService.class));
        }
    }
}
