package ru.ruscrafting.ecia.integration;

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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Native key identity stays intact; managed openings match the native key id. */
public final class NativeSeasonKeys implements AutoCloseable {
    public static final String LEGACY_SEASON = "launch";
    private static final NamespacedKey SEASON = Objects.requireNonNull(NamespacedKey.fromString("ecia:season"));
    private final Map<CrateKey, AdaptedItem> originals = new HashMap<>();

    public record KeyCost(String keyId, int amount) { }
    public record KeyIdentity(String keyId, String season) { }

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
            item.editMeta(meta -> meta.getPersistentDataContainer().set(SEASON, PersistentDataType.STRING, entry.getValue()));
            key.setItem(ItemHelper.vanilla(item));
        }
    }

    public Predicate<ItemStack> matches(String keyId, String season) {
        return item -> {
            if (item == null || item.isEmpty()) return false;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            return nativeKey != null && nativeKey.getId().equals(keyId) && season(item).equals(season);
        };
    }

    /** Current managed cases accept every physical key for the native key id. */
    public Predicate<ItemStack> matches(String keyId) {
        return item -> {
            if (item == null || item.isEmpty()) return false;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            return nativeKey != null && !nativeKey.isVirtual() && nativeKey.getId().equals(keyId);
        };
    }

    /** Identifies one physical native key without scanning the rest of the inventory. */
    public Optional<KeyIdentity> identify(ItemStack item) {
        if (item == null || item.isEmpty()) return Optional.empty();
        CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
        return nativeKey == null || nativeKey.isVirtual()
                ? Optional.empty()
                : Optional.of(new KeyIdentity(nativeKey.getId(), season(item)));
    }

    /** Prefer the held key, then the first matching inventory key; never combine seasons. */
    public Optional<String> selectedSeason(Player player, KeyCost cost) {
        ItemStack held = player.getInventory().getItemInMainHand();
        CrateKey heldKey = CratesAPI.getKeyManager().getKeyByItem(held);
        if (heldKey != null && heldKey.getId().equals(cost.keyId())) return Optional.of(season(held));
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) continue;
            CrateKey nativeKey = CratesAPI.getKeyManager().getKeyByItem(item);
            if (nativeKey != null && nativeKey.getId().equals(cost.keyId())) return Optional.of(season(item));
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        CrateKey offHandKey = CratesAPI.getKeyManager().getKeyByItem(offHand);
        return offHandKey != null && offHandKey.getId().equals(cost.keyId()) ? Optional.of(season(offHand)) : Optional.empty();
    }

    public ItemStack create(String keyId, String season, int amount) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Key stack amount outside 1..64");
        ItemStack item = key(keyId).getItemStack().clone();
        item.editMeta(meta -> meta.getPersistentDataContainer().set(SEASON, PersistentDataType.STRING, season));
        item.setAmount(amount);
        return item;
    }

    /** Creates a plain native key; no managed season metadata is written. */
    public ItemStack create(String keyId, int amount) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Key stack amount outside 1..64");
        ItemStack item = key(keyId).getItemStack().clone();
        item.editMeta(meta -> meta.getPersistentDataContainer().remove(SEASON));
        item.setAmount(amount);
        return item;
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
