package ru.ruscrafting.ecia.integration;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.arc.paper.playerstate.PaperPlayerDataPersistence;
import ru.arc.paper.testing.MockBukkitTestRuntime;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.inventory.OpeningInventoryTransactions;
import ru.ruscrafting.ecia.journal.DurableOpeningStore;
import ru.ruscrafting.ecia.journal.OpeningLedger;
import ru.ruscrafting.ecia.journal.PeriodicKeyGrant;
import ru.ruscrafting.ecia.journal.PeriodicKeyLedger;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PeriodicKeyIssuerTest {
    private static final long SESSION = 41L;
    private static final String CRATE = "case_daily";
    private static final String OTHER_CRATE = "case_weekly";
    private static final String KEY_ID = "daily_key";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final Executor DIRECT = Runnable::run;

    @TempDir
    Path directory;

    @Test
    void fullInventoryCanBeClearedBeforeExactlyOneCurrentKeyIsGranted() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            AtomicInteger nativeSaves = new AtomicInteger();
            IssuerFixture fixture = fixture(grants, openings, clock, p -> nativeSaves.incrementAndGet());
            fixture.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window window = window(clock, PeriodicVirtualOpening.Period.DAILY);

            for (int slot = 0; slot < 36; slot++) {
                player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            }
            ItemStack offhand = new ItemStack(Material.DIRT);
            player.getInventory().setItem(40, offhand);
            UUID id = PeriodicVirtualOpening.openingId(player.getUniqueId(), CRATE, window.period(), window);

            assertEquals(PeriodicKeyIssuer.Result.FULL, grant(fixture.issuer(), player, window));
            assertTrue(grants.get(id).isEmpty(), "A full inventory must not persist a grant plan");
            assertTrue(periodicKeys(player).isEmpty(), "A full inventory must not receive a dropped or partial key");
            assertEquals(offhand, player.getInventory().getItem(40), "An unrelated offhand item is not an overflow slot");

            player.getInventory().clear();
            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, window));

            List<PeriodicPhysicalKey.Identity> keys = periodicKeys(player);
            assertEquals(1, keys.size());
            assertEquals(new PeriodicPhysicalKey.Identity(
                    id, player.getUniqueId(), CRATE, KEY_ID, PeriodicVirtualOpening.Period.DAILY,
                    window.start(), window.nextReset()), keys.getFirst());
            assertEquals(PeriodicKeyGrant.State.DELIVERED, grants.get(id).orElseThrow().state());
            assertEquals(1, nativeSaves.get());
        }
    }

    @Test
    void fullInventoryRenewsByReplacingOnlyTheOwnersExpiredKeyForTheSameCaseAndCadence() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            AtomicInteger nativeSaves = new AtomicInteger();
            IssuerFixture fixture = fixture(grants, openings, clock, p -> nativeSaves.incrementAndGet());
            fixture.issuer().playerDataLoaded(player);

            PeriodicVirtualOpening.Window oldDaily = window(
                    Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC")),
                    PeriodicVirtualOpening.Period.DAILY);
            PeriodicVirtualOpening.Window currentDaily = window(clock, PeriodicVirtualOpening.Period.DAILY);
            PeriodicVirtualOpening.Window currentWeekly = window(clock, PeriodicVirtualOpening.Period.WEEKLY);
            UUID foreignOwner = UUID.randomUUID();
            ItemStack replaceable = PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK),
                    player.getUniqueId(), CRATE, KEY_ID, oldDaily);
            ItemStack foreign = PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK),
                    foreignOwner, CRATE, KEY_ID, oldDaily);
            ItemStack otherCase = PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK),
                    player.getUniqueId(), OTHER_CRATE, KEY_ID, oldDaily);
            ItemStack weekly = PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK),
                    player.getUniqueId(), CRATE, KEY_ID, currentWeekly);
            UUID foreignId = PeriodicPhysicalKey.identify(foreign).orElseThrow().id();

            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            player.getInventory().setItem(4, replaceable);
            player.getInventory().setItem(5, foreign);
            player.getInventory().setItem(6, otherCase);
            player.getInventory().setItem(7, weekly);

            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, currentDaily));

            List<PeriodicPhysicalKey.Identity> keys = periodicKeys(player);
            assertEquals(4, keys.size(), "Only the exact expired owner/case/daily key should be replaced");
            assertTrue(keys.stream().anyMatch(key -> key.id().equals(foreignId)));
            assertTrue(keys.stream().anyMatch(key -> key.crateId().equals(OTHER_CRATE)));
            assertTrue(keys.stream().anyMatch(key -> key.period() == PeriodicVirtualOpening.Period.WEEKLY));
            assertTrue(keys.stream().anyMatch(key -> key.id().equals(PeriodicVirtualOpening.openingId(
                    player.getUniqueId(), CRATE, currentDaily.period(), currentDaily))));
            assertFalse(keys.stream().anyMatch(key -> key.id().equals(PeriodicVirtualOpening.openingId(
                    player.getUniqueId(), CRATE, oldDaily.period(), oldDaily))));

            assertEquals(PeriodicKeyIssuer.Result.ALREADY_DELIVERED, grant(fixture.issuer(), player, currentDaily));
            assertEquals(1, nativeSaves.get(), "The periodic receipt makes renewal idempotent");
        }
    }

    @Test
    void fullInventoryCanRenewAnExpiredMatchingKeyInOffhandWithoutTouchingArmor() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            IssuerFixture fixture = fixture(grants, openings, clock, p -> { });
            fixture.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window oldDaily = window(
                    Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC")),
                    PeriodicVirtualOpening.Period.DAILY);
            PeriodicVirtualOpening.Window currentDaily = window(clock, PeriodicVirtualOpening.Period.DAILY);
            ItemStack[] armor = {
                    new ItemStack(Material.LEATHER_BOOTS),
                    new ItemStack(Material.LEATHER_LEGGINGS),
                    new ItemStack(Material.LEATHER_CHESTPLATE),
                    new ItemStack(Material.LEATHER_HELMET)
            };
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            for (int i = 0; i < armor.length; i++) player.getInventory().setItem(36 + i, armor[i]);
            ItemStack expired = PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK),
                    player.getUniqueId(), CRATE, KEY_ID, oldDaily);
            player.getInventory().setItem(40, expired);

            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, currentDaily));

            assertTrue(PeriodicPhysicalKey.valid(player.getInventory().getItem(40), player.getUniqueId(), CRATE,
                    PeriodicVirtualOpening.Period.DAILY, MOSCOW, clock.instant()),
                    "When storage is full, the current key replaces the reclaimed offhand key in place");
            assertArrayEquals(armor, Arrays.copyOfRange(player.getInventory().getContents(), 36, 40),
                    "Armor slots are not part of periodic-key delivery");
            assertEquals(1, periodicKeys(player).size());
        }
    }

    @Test
    void reconnectAndJournalRestartDoNotGrantTheSameWindowTwice() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            AtomicInteger nativeSaves = new AtomicInteger();
            IssuerFixture first = fixture(grants, openings, clock, p -> nativeSaves.incrementAndGet());
            first.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window window = window(clock, PeriodicVirtualOpening.Period.DAILY);

            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(first.issuer(), player, window));
            first.issuer().playerLeft(player.getUniqueId());
            first.issuer().playerDataLoaded(player);
            assertEquals(PeriodicKeyIssuer.Result.ALREADY_DELIVERED, grant(first.issuer(), player, window));
            assertEquals(1, periodicKeys(player).size());

            PeriodicKeyLedger reopenedGrants = new PeriodicKeyLedger(directory);
            OpeningLedger reopenedOpenings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            IssuerFixture restarted = fixture(reopenedGrants, reopenedOpenings, clock, p -> nativeSaves.incrementAndGet());
            restarted.issuer().playerDataLoaded(player);
            assertEquals(PeriodicKeyIssuer.Result.ALREADY_DELIVERED, grant(restarted.issuer(), player, window));

            assertEquals(1, periodicKeys(player).size());
            assertEquals(1, reopenedGrants.deliveredIds().size());
            assertEquals(1, nativeSaves.get(), "A duplicate grant must not persist a second inventory mutation");
        }
    }

    @Test
    void anExistingVirtualOpeningIdSuppressesThePhysicalGrant() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            PeriodicVirtualOpening.Window window = window(clock, PeriodicVirtualOpening.Period.DAILY);
            UUID id = PeriodicVirtualOpening.openingId(player.getUniqueId(), CRATE, window.period(), window);
            openings.reserveVirtual(id, player.getUniqueId(), pool(), pool()::rewards,
                    PeriodicVirtualOpening.witness(CRATE, window));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            IssuerFixture fixture = fixture(grants, openings, clock, p -> { });
            fixture.issuer().playerDataLoaded(player);

            assertEquals(PeriodicKeyIssuer.Result.SKIPPED, grant(fixture.issuer(), player, window));

            assertTrue(openings.contains(id));
            assertTrue(grants.get(id).isEmpty());
            assertTrue(periodicKeys(player).isEmpty());
        }
    }

    @Test
    void dailyGrantRefreshesNextDayAndMissedDaysDoNotAccumulate() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            IssuerFixture fixture = fixture(grants, openings, clock, p -> { });
            fixture.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window first = window(clock, PeriodicVirtualOpening.Period.DAILY);

            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, first));
            clock.setInstant(Instant.parse("2026-09-26T12:00:00Z"));
            PeriodicVirtualOpening.Window nextDay = window(clock, PeriodicVirtualOpening.Period.DAILY);
            assertNotEquals(first.start(), nextDay.start());
            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, nextDay));

            // Three calendar windows pass without a login; only the current day is granted on return.
            clock.setInstant(Instant.parse("2026-09-30T12:00:00Z"));
            PeriodicVirtualOpening.Window afterAbsence = window(clock, PeriodicVirtualOpening.Period.DAILY);
            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, afterAbsence));

            List<PeriodicPhysicalKey.Identity> keys = periodicKeys(player);
            assertEquals(1, keys.size(), "Only the current key should remain after three missed windows");
            assertEquals(afterAbsence.start(), keys.getFirst().start());
            assertEquals(3, grants.deliveredIds().size());
        }
    }

    @Test
    void unfinishedOpeningPreservesExpiredKeysUntilItsInventoryPreimageIsRecovered() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);

            PeriodicVirtualOpening.Window expired = window(
                    Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneId.of("UTC")),
                    PeriodicVirtualOpening.Period.DAILY);
            ItemStack oldKey = PeriodicPhysicalKey.stamp(
                    new ItemStack(Material.TRIPWIRE_HOOK), player.getUniqueId(), CRATE, KEY_ID, expired);
            player.getInventory().setItem(0, oldKey);

            PeriodicVirtualOpening.Window unresolvedWindow = window(clock, PeriodicVirtualOpening.Period.WEEKLY);
            String unresolvedWitness = PeriodicVirtualOpening.witness(OTHER_CRATE, unresolvedWindow);
            openings.reserveVirtual(
                    PeriodicVirtualOpening.openingId(
                            player.getUniqueId(), OTHER_CRATE, unresolvedWindow.period(), unresolvedWindow),
                    player.getUniqueId(), pool(OTHER_CRATE), pool(OTHER_CRATE)::rewards, unresolvedWitness);

            IssuerFixture fixture = fixture(grants, openings, clock, p -> { });
            fixture.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window current = window(clock, PeriodicVirtualOpening.Period.DAILY);

            assertEquals(PeriodicKeyIssuer.Result.RETRY, grant(fixture.issuer(), player, current));

            List<PeriodicPhysicalKey.Identity> remaining = periodicKeys(player);
            assertEquals(1, remaining.size());
            assertEquals(expired.start(), remaining.getFirst().start());
            assertTrue(grants.get(PeriodicVirtualOpening.openingId(
                    player.getUniqueId(), CRATE, current.period(), current)).isEmpty());
            assertTrue(openings.pending(player.getUniqueId()).isPresent());
        }
    }

    @Test
    void unknownNativeSaveStaysInReviewUntilFreshPlayerDataConfirmsTheReceipt() {
        try (var runtime = MockBukkitTestRuntime.Companion.open()) {
            Player player = runtime.addPlayer("PeriodicKeys");
            MutableClock clock = new MutableClock(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
            PeriodicKeyLedger grants = new PeriodicKeyLedger(directory);
            OpeningLedger openings = new OpeningLedger(new DurableOpeningStore(directory), clock);
            AtomicInteger nativeSaves = new AtomicInteger();
            PaperPlayerDataPersistence uncertainSave = p -> {
                nativeSaves.incrementAndGet();
                throw new IllegalStateException("Injected unknown native save outcome");
            };
            IssuerFixture fixture = fixture(grants, openings, clock, uncertainSave);
            fixture.issuer().playerDataLoaded(player);
            PeriodicVirtualOpening.Window window = window(clock, PeriodicVirtualOpening.Period.DAILY);
            UUID id = PeriodicVirtualOpening.openingId(player.getUniqueId(), CRATE, window.period(), window);

            assertEquals(PeriodicKeyIssuer.Result.REVIEW, grant(fixture.issuer(), player, window));
            assertEquals(PeriodicKeyGrant.State.REVIEW, grants.get(id).orElseThrow().state());
            assertEquals(1, periodicKeys(player).size(), "The native write outcome is uncertain after the inventory changed");

            assertEquals(PeriodicKeyIssuer.Result.REVIEW, grant(fixture.issuer(), player, window));
            fixture.issuer().playerLeft(player.getUniqueId());
            assertEquals(PeriodicKeyIssuer.Result.REVIEW, grant(fixture.issuer(), player, window),
                    "A receipt must not be trusted before a fresh player-data load");
            assertEquals(1, periodicKeys(player).size());
            assertEquals(1, nativeSaves.get());

            fixture.issuer().playerDataLoaded(player);
            assertEquals(PeriodicKeyIssuer.Result.DELIVERED, grant(fixture.issuer(), player, window));
            assertEquals(PeriodicKeyGrant.State.DELIVERED, grants.get(id).orElseThrow().state());
            assertEquals(1, periodicKeys(player).size(), "Receipt recovery must confirm, not repeat, the delivery");
            assertEquals(1, nativeSaves.get());
        }
    }

    @Test
    void claimNoticePersistsOnePerPlayerPerDateAcrossInstancesAndCadences() {
        UUID playerId = UUID.fromString("4d10813d-7a0f-49c4-b82c-bd916da3fcab");
        LocalDate today = LocalDate.of(2026, 9, 25);
        PeriodicKeyLedger first = new PeriodicKeyLedger(directory);

        assertTrue(first.claimNotice(playerId, today), "The first daily or weekly notice may be sent");
        assertFalse(first.claimNotice(playerId, today), "The same daily gate suppresses the other cadence notice");

        PeriodicKeyLedger reopened = new PeriodicKeyLedger(directory);
        assertFalse(reopened.claimNotice(playerId, today), "The claimed date survives a ledger restart");
        assertTrue(reopened.claimNotice(playerId, today.plusDays(1)), "A new date opens one notice claim");

        PeriodicKeyLedger restartedAgain = new PeriodicKeyLedger(directory);
        assertFalse(restartedAgain.claimNotice(playerId, today.plusDays(1)));
        assertFalse(restartedAgain.claimNotice(playerId, today), "Older dates cannot reopen the gate");
    }

    private IssuerFixture fixture(PeriodicKeyLedger grants, OpeningLedger openings, Clock clock,
            PaperPlayerDataPersistence persistence) {
        NativeItemPayload payload = new NativeItemPayload();
        OpeningInventoryTransactions inventory = new OpeningInventoryTransactions(payload, persistence, () -> true);
        PeriodicKeyIssuer issuer = new PeriodicKeyIssuer(grants, openings, payload, inventory, clock,
                DIRECT, DIRECT, (player, session) -> player.isOnline() && session == SESSION);
        return new IssuerFixture(issuer);
    }

    private static PeriodicKeyIssuer.Result grant(PeriodicKeyIssuer issuer, Player player,
            PeriodicVirtualOpening.Window window) {
        return issuer.grant(player, SESSION, CRATE, window,
                () -> PeriodicPhysicalKey.stamp(new ItemStack(Material.TRIPWIRE_HOOK), player.getUniqueId(),
                        CRATE, KEY_ID, window), () -> true).join();
    }

    private static PeriodicVirtualOpening.Window window(Clock clock, PeriodicVirtualOpening.Period period) {
        return PeriodicVirtualOpening.window(period, clock.instant(), MOSCOW);
    }

    private static List<PeriodicPhysicalKey.Identity> periodicKeys(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .map(PeriodicPhysicalKey::identify)
                .flatMap(Optional::stream)
                .toList();
    }

    private static PoolSnapshot pool() {
        return pool(CRATE);
    }

    private static PoolSnapshot pool(String crateId) {
        var reward = new RewardDefinition("prize", 1, "payload", "preview");
        return new PoolSnapshot(crateId, "launch", List.of(reward), 1, 0, 1);
    }

    private record IssuerFixture(PeriodicKeyIssuer issuer) { }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant next) { instant = next; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId nextZone) { return new MutableClock(instant, nextZone); }
        @Override public Instant instant() { return instant; }
    }
}
