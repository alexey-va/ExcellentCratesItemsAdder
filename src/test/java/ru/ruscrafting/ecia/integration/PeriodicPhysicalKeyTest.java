package ru.ruscrafting.ecia.integration;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import ru.arc.paper.testing.MockBukkitTestRuntime;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PeriodicPhysicalKeyTest {
    private static final UUID OWNER = UUID.fromString("bc51af10-86e9-48d1-aa68-3bd69f472011");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test void stampedKeyKeepsItsModelButIsOnlyValidForItsOwnerCaseAndCurrentWindow() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Instant now = Instant.parse("2026-09-25T12:00:00Z");
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY, now, MOSCOW);
            var itemsAdderId = new NamespacedKey("itemsadder", "vinland_animated_weapon_key");
            var nativeId = NamespacedKey.fromString("excellentcrates:key_id");
            ItemStack template = new ItemStack(Material.PAPER);
            template.editMeta(meta -> {
                meta.setCustomModelData(12236);
                meta.getPersistentDataContainer().set(itemsAdderId, PersistentDataType.STRING,
                        "elitecreatures:vinland_animated_weapon-key");
                meta.getPersistentDataContainer().set(nativeId, PersistentDataType.STRING, "daily_key");
            });
            byte[] before = template.serializeAsBytes();

            ItemStack key = PeriodicPhysicalKey.stamp(template, OWNER, "case_daily", "daily_key", window);
            var identity = PeriodicPhysicalKey.identify(key).orElseThrow();

            assertTrue(PeriodicPhysicalKey.isMarked(key));
            assertEquals(OWNER, identity.playerId());
            assertEquals("case_daily", identity.crateId());
            assertEquals("daily_key", identity.keyId());
            assertEquals(PeriodicVirtualOpening.Period.DAILY, identity.period());
            assertEquals(PeriodicVirtualOpening.openingId(OWNER, "case_daily", window.period(), window), identity.id());
            assertFalse(key.getItemMeta().getPersistentDataContainer().has(nativeId, PersistentDataType.STRING),
                    "the auto key must not be accepted by ExcellentCrates' native key matcher");
            assertEquals("elitecreatures:vinland_animated_weapon-key",
                    key.getItemMeta().getPersistentDataContainer().get(itemsAdderId, PersistentDataType.STRING));
            assertEquals(12236, key.getItemMeta().getCustomModelData());
            assertArrayEquals(before, template.serializeAsBytes(), "stamping must not mutate the configured template");

            assertTrue(PeriodicPhysicalKey.valid(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, now));
            assertTrue(PeriodicPhysicalKey.valid(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, window.start()));
            assertFalse(PeriodicPhysicalKey.valid(key, UUID.randomUUID(), "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, now));
            assertFalse(PeriodicPhysicalKey.valid(key, OWNER, "case_weekly",
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, now));
            assertFalse(PeriodicPhysicalKey.valid(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.WEEKLY, MOSCOW, now));
            assertFalse(PeriodicPhysicalKey.valid(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, window.nextReset()));
        }
    }

    @Test void malformedOrPartialMarkerRemainsMarkedAndCannotBeIdentifiedOrUsed() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Instant now = Instant.parse("2026-09-25T12:00:00Z");
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.WEEKLY, now, MOSCOW);
            ItemStack key = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), OWNER,
                    "case_weekly", "weekly_key", window);
            var expiry = NamespacedKey.fromString("ecia:periodic_key_expiry");
            key.editMeta(meta -> meta.getPersistentDataContainer().remove(expiry));

            assertTrue(PeriodicPhysicalKey.isMarked(key));
            assertTrue(PeriodicPhysicalKey.identify(key).isEmpty());
            assertFalse(PeriodicPhysicalKey.valid(key, OWNER, "case_weekly",
                    PeriodicVirtualOpening.Period.WEEKLY, MOSCOW, now));
        }
    }

    @Test void weeklyKeyExpiresAtMondayInTheConfiguredCalendarZone() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Instant sunday = Instant.parse("2026-09-27T20:59:59Z");
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.WEEKLY, sunday, MOSCOW);
            ItemStack key = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), OWNER,
                    "case_weekly", "weekly_key", window);

            assertTrue(PeriodicPhysicalKey.valid(key, OWNER, "case_weekly",
                    PeriodicVirtualOpening.Period.WEEKLY, MOSCOW, sunday));
            assertFalse(PeriodicPhysicalKey.valid(key, OWNER, "case_weekly",
                    PeriodicVirtualOpening.Period.WEEKLY, MOSCOW, window.nextReset()));
        }
    }

    @Test void expiredForRequiresTheSameOwnerCaseAndCadenceAndUsesAnExclusiveExpiryBoundary() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Instant now = Instant.parse("2026-09-25T12:00:00Z");
            var window = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY, now, MOSCOW);
            ItemStack key = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), OWNER,
                    "case_daily", "daily_key", window);

            assertFalse(PeriodicPhysicalKey.expiredFor(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, window.nextReset().minusMillis(1)));
            assertTrue(PeriodicPhysicalKey.expiredFor(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, window.nextReset()));
            assertFalse(PeriodicPhysicalKey.expiredFor(key, UUID.randomUUID(), "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, window.nextReset()));
            assertFalse(PeriodicPhysicalKey.expiredFor(key, OWNER, "case_weekly",
                    PeriodicVirtualOpening.Period.DAILY, window.nextReset()));
            assertFalse(PeriodicPhysicalKey.expiredFor(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.WEEKLY, window.nextReset()));
            assertFalse(PeriodicPhysicalKey.expiredFor(key, OWNER, "case_daily",
                    PeriodicVirtualOpening.Period.NONE, window.nextReset()));
        }
    }

    @Test void discardExpiredHeldKeyRemovesOnlyAnExpiredOwnedKeyForTheTargetCaseAndPeriod() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            var player = runtime.addPlayer("PeriodicKeys");
            Instant now = Instant.parse("2026-09-26T12:00:00Z");
            var oldDaily = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY,
                    Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            var currentDaily = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.DAILY, now, ZoneId.of("UTC"));
            var currentWeekly = PeriodicVirtualOpening.window(PeriodicVirtualOpening.Period.WEEKLY, now, ZoneId.of("UTC"));

            ItemStack expired = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), player.getUniqueId(),
                    "case_daily", "daily_key", oldDaily);
            player.getInventory().setItemInMainHand(expired);
            assertTrue(PeriodicPhysicalKey.discardExpiredHeldKey(player, "case_daily",
                    PeriodicVirtualOpening.Period.DAILY, now));
            assertTrue(player.getInventory().getItemInMainHand().isEmpty());

            ItemStack current = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), player.getUniqueId(),
                    "case_daily", "daily_key", currentDaily);
            assertHeldKeyIsUntouched(player, current, "case_daily", PeriodicVirtualOpening.Period.DAILY, now);

            ItemStack foreign = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), UUID.randomUUID(),
                    "case_daily", "daily_key", oldDaily);
            assertHeldKeyIsUntouched(player, foreign, "case_daily", PeriodicVirtualOpening.Period.DAILY, now);

            ItemStack wrongCase = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), player.getUniqueId(),
                    "case_weekly", "weekly_key", oldDaily);
            assertHeldKeyIsUntouched(player, wrongCase, "case_daily", PeriodicVirtualOpening.Period.DAILY, now);

            ItemStack weekly = PeriodicPhysicalKey.stamp(new ItemStack(Material.PAPER), player.getUniqueId(),
                    "case_daily", "daily_key", currentWeekly);
            assertHeldKeyIsUntouched(player, weekly, "case_daily", PeriodicVirtualOpening.Period.DAILY, now);

            var nativeKeyId = NamespacedKey.fromString("excellentcrates:key_id");
            ItemStack paid = new ItemStack(Material.PAPER);
            paid.editMeta(meta -> meta.getPersistentDataContainer().set(nativeKeyId,
                    PersistentDataType.STRING, "paid_key"));
            assertHeldKeyIsUntouched(player, paid, "case_daily", PeriodicVirtualOpening.Period.DAILY, now);
        }
    }

    private static void assertHeldKeyIsUntouched(org.bukkit.entity.Player player, ItemStack key,
            String crateId, PeriodicVirtualOpening.Period period, Instant now) {
        player.getInventory().setItemInMainHand(key);
        assertFalse(PeriodicPhysicalKey.discardExpiredHeldKey(player, crateId, period, now));
        assertEquals(key, player.getInventory().getItemInMainHand());
    }
}
