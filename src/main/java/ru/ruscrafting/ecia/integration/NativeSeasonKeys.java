package ru.ruscrafting.ecia.integration;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.key.CrateKey;
import su.nightexpress.excellentcrates.util.ItemHelper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Native key identity stays intact; managed openings match the native key id. */
public final class NativeSeasonKeys implements AutoCloseable {
    public static final String LEGACY_SEASON = "launch";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final NamespacedKey SEASON = Objects.requireNonNull(NamespacedKey.fromString("ecia:season"));
    private final Map<CrateKey, AdaptedItem> originals = new HashMap<>();

    public record KeyCost(String keyId, int amount) { }
    public record KeyIdentity(String keyId, String season, String crateId) {
        public KeyIdentity(String keyId, String season) {
            this(keyId, season, null);
        }
    }

    public KeyCost cost(Crate crate) {
        var costs = crate.getCosts();
        if (costs.size() != 1) throw new IllegalStateException("Managed crate requires exactly one physical key cost: " + crate.getId());
        var cost = costs.iterator().next();
        if (!cost.isEnabled() || cost.getEntries().size() != 1
                || !(cost.getEntries().getFirst() instanceof KeyCostEntry keyEntry)) {
            throw new IllegalStateException("Managed crate has unsupported cost: " + crate.getId());
        }
        CrateKey key = key(keyEntry.getKeyId());
        return new KeyCost(key.getId(), keyEntry.getAmount());
    }

    /** Apply after native EC load so its normal give-key commands issue current-season keys. */
    public void stampTemplates(Map<String, String> seasonsByCrate) {
        Map<String, String> seasonsByKey = new HashMap<>();
        for (var entry : seasonsByCrate.entrySet()) {
            Crate crate = CratesAPI.getCrateManager().getCrateById(entry.getKey());
            if (crate == null) throw new IllegalStateException("Missing native crate " + entry.getKey());
            String keyId = cost(crate).keyId();
            String existing = seasonsByKey.putIfAbsent(keyId, entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalStateException("A shared native key cannot issue different current seasons: " + keyId);
            }
        }
        for (var entry : seasonsByKey.entrySet()) {
            CrateKey key = key(entry.getKey());
            originals.putIfAbsent(key, key.getItem());
            ItemStack item = key.getRawItem().clone();
            applyConfiguredName(item, key.getName());
            item.editMeta(meta -> meta.getPersistentDataContainer().set(SEASON, PersistentDataType.STRING, entry.getValue()));
            key.setItem(ItemHelper.vanilla(item));
        }
    }

    public Predicate<ItemStack> matches(String keyId, String season) {
        return item -> {
            if (item == null || item.isEmpty() || PeriodicPhysicalKey.isMarked(item) || !CratesAPI.isLoaded()) return false;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            return nativeKey != null && nativeKey.getId().equals(keyId) && season(item).equals(season);
        };
    }

    /** Current managed cases accept every physical key for the native key id. */
    public Predicate<ItemStack> matches(String keyId) {
        return item -> {
            if (item == null || item.isEmpty() || PeriodicPhysicalKey.isMarked(item) || !CratesAPI.isLoaded()) return false;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            return nativeKey != null && !nativeKey.isVirtual() && nativeKey.getId().equals(keyId);
        };
    }

    /** Identifies one physical native key without scanning the rest of the inventory. */
    public Optional<KeyIdentity> identify(ItemStack item) {
        if (item == null || item.isEmpty() || PeriodicPhysicalKey.isMarked(item) || !CratesAPI.isLoaded()) {
            return Optional.empty();
        }
        CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
        return nativeKey == null || nativeKey.isVirtual()
                ? Optional.empty()
                : Optional.of(new KeyIdentity(nativeKey.getId(), season(item), null));
    }

    /** Identifies an ordinary key or this player's valid automatic key for a configured managed case. */
    public Optional<KeyIdentity> identifyForPlayer(ItemStack item, UUID playerId, ZoneId zone,
            Map<String, ManagedCratesSettings.CaseSettings> configuredCases) {
        return identifyForPlayer(item, playerId, zone, configuredCases, Instant.now());
    }

    /** Clock-injected form keeps period-bound glow matching deterministic in tests. */
    public Optional<KeyIdentity> identifyForPlayer(ItemStack item, UUID playerId, ZoneId zone,
            Map<String, ManagedCratesSettings.CaseSettings> configuredCases, Instant now) {
        Objects.requireNonNull(configuredCases);
        if (!PeriodicPhysicalKey.isMarked(item)) return identify(item);
        PeriodicPhysicalKey.Identity identity = PeriodicPhysicalKey.identify(item).orElse(null);
        if (identity == null || !CratesAPI.isLoaded()) return Optional.empty();
        ManagedCratesSettings.CaseSettings configured = configuredCases.get(identity.crateId());
        if (configured == null || configured.freeOpenPeriod() != identity.period()
                || !PeriodicPhysicalKey.valid(item, playerId, identity.crateId(), identity.period(), zone, now)) {
            return Optional.empty();
        }
        Crate crate = CratesAPI.getCrateManager().getCrateById(identity.crateId());
        if (crate == null) return Optional.empty();
        KeyCost configuredCost;
        try {
            configuredCost = cost(crate);
        } catch (IllegalStateException unsupported) {
            return Optional.empty();
        }
        return configuredCost.keyId().equals(identity.keyId())
                ? Optional.of(new KeyIdentity(identity.keyId(), season(item), identity.crateId()))
                : Optional.empty();
    }

    /** Prefer the held key, then the first matching inventory key; never combine seasons. */
    public Optional<String> selectedSeason(Player player, KeyCost cost) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!CratesAPI.isLoaded()) return Optional.empty();
        if (!PeriodicPhysicalKey.isMarked(held)) {
            CrateKey heldKey = CratesAPI.getKeyManager().getKeyByItem(held);
            if (heldKey != null && heldKey.getId().equals(cost.keyId())) return Optional.of(season(held));
        }
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty() || PeriodicPhysicalKey.isMarked(item)) continue;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            if (nativeKey != null && nativeKey.getId().equals(cost.keyId())) return Optional.of(season(item));
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (!PeriodicPhysicalKey.isMarked(offHand)) {
            CrateKey offHandKey = CratesAPI.getKeyManager().getKeyByItem(offHand);
            if (offHandKey != null && offHandKey.getId().equals(cost.keyId())) return Optional.of(season(offHand));
        }
        return Optional.empty();
    }

    public ItemStack create(String keyId, String season, int amount) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Key stack amount outside 1..64");
        return createKeyStack(key(keyId), amount, season);
    }

    /** Creates a plain native key; no managed season metadata is written. */
    public ItemStack create(String keyId, int amount) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Key stack amount outside 1..64");
        return createKeyStack(key(keyId), amount, null);
    }

    /** Builds an addon-issued copy without replacing its native metadata or ItemsAdder model data. */
    static ItemStack createKeyStack(CrateKey key, int amount, String season) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Key stack amount outside 1..64");
        ItemStack item = key.getItemStack().clone();
        applyConfiguredName(item, key.getName());
        item.editMeta(meta -> {
            if (season == null) meta.getPersistentDataContainer().remove(SEASON);
            else meta.getPersistentDataContainer().set(SEASON, PersistentDataType.STRING, season);
        });
        item.setAmount(amount);
        return item;
    }

    static void applyConfiguredName(ItemStack item, String configuredName) {
        item.editMeta(meta -> meta.displayName(
                MINI_MESSAGE.deserialize(configuredName).decoration(TextDecoration.ITALIC, false)));
    }

    public String season(ItemStack key) {
        return key.getItemMeta().getPersistentDataContainer().getOrDefault(SEASON, PersistentDataType.STRING, LEGACY_SEASON);
    }

    private CrateKey key(String id) {
        CrateKey key = CratesAPI.getKeyManager().getKeyById(id);
        if (key == null || key.isVirtual()) throw new IllegalStateException("Physical native key required: " + id);
        return key;
    }

    @Override public void close() {
        originals.forEach(CrateKey::setItem);
        originals.clear();
    }
}
