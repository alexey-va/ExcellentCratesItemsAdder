package ru.ruscrafting.ecia.integration;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owner- and calendar-window-bound identity for addon-issued physical case keys. */
public final class PeriodicPhysicalKey {
    private static final byte VERSION = 1;
    private static final String DATA_PREFIX = "periodic_key_";
    private static final NamespacedKey VERSION_KEY = key(DATA_PREFIX + "version");
    private static final NamespacedKey OWNER_KEY = key(DATA_PREFIX + "owner");
    private static final NamespacedKey CRATE_KEY = key(DATA_PREFIX + "crate");
    private static final NamespacedKey KEY_ID_KEY = key(DATA_PREFIX + "key_id");
    private static final NamespacedKey PERIOD_KEY = key(DATA_PREFIX + "period");
    private static final NamespacedKey START_KEY = key(DATA_PREFIX + "start");
    private static final NamespacedKey EXPIRY_KEY = key(DATA_PREFIX + "expiry");
    private static final NamespacedKey ID_KEY = key(DATA_PREFIX + "id");
    private static final NamespacedKey EXCELLENT_CRATES_KEY_ID = key("excellentcrates:key_id");

    private PeriodicPhysicalKey() {
    }

    public record Identity(UUID id, UUID playerId, String crateId, String keyId,
            PeriodicVirtualOpening.Period period, Instant start, Instant expiry) {
        public Identity {
            Objects.requireNonNull(id);
            Objects.requireNonNull(playerId);
            requireText(crateId, "crate id");
            requireText(keyId, "key id");
            Objects.requireNonNull(period);
            Objects.requireNonNull(start);
            Objects.requireNonNull(expiry);
            if (period == PeriodicVirtualOpening.Period.NONE || !start.isBefore(expiry)) {
                throw new IllegalArgumentException("Periodic key needs a positive daily or weekly window");
            }
        }
    }

    /**
     * Clone a configured native key item, keep its display/model metadata, and
     * replace the native EC recognition marker with the bounded addon identity.
     */
    public static ItemStack stamp(ItemStack nativeClone, UUID playerId, String crateId, String keyId,
            PeriodicVirtualOpening.Window window) {
        if (nativeClone == null || nativeClone.isEmpty()) throw new IllegalArgumentException("Physical key item required");
        Objects.requireNonNull(playerId);
        requireText(crateId, "crate id");
        requireText(keyId, "key id");
        Objects.requireNonNull(window);
        if (window.period() == PeriodicVirtualOpening.Period.NONE) {
            throw new IllegalArgumentException("Periodic key needs a daily or weekly window");
        }
        UUID entitlementId = PeriodicVirtualOpening.openingId(playerId, crateId, window.period(), window);
        long start = window.start().toEpochMilli();
        long expiry = window.nextReset().toEpochMilli();
        if (start >= expiry) throw new IllegalArgumentException("Periodic key window is empty");

        ItemStack stamped = nativeClone.clone();
        stamped.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.remove(EXCELLENT_CRATES_KEY_ID);
            data.set(VERSION_KEY, PersistentDataType.BYTE, VERSION);
            data.set(OWNER_KEY, PersistentDataType.STRING, playerId.toString());
            data.set(CRATE_KEY, PersistentDataType.STRING, crateId);
            data.set(KEY_ID_KEY, PersistentDataType.STRING, keyId);
            data.set(PERIOD_KEY, PersistentDataType.STRING, window.period().name());
            data.set(START_KEY, PersistentDataType.LONG, start);
            data.set(EXPIRY_KEY, PersistentDataType.LONG, expiry);
            data.set(ID_KEY, PersistentDataType.STRING, entitlementId.toString());
        });
        return stamped;
    }

    /** True even for partial or malformed periodic-key data, so it fails closed. */
    public static boolean isMarked(ItemStack item) {
        ItemMeta meta = item == null || item.isEmpty() ? null : item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> key.getNamespace().equals("ecia") && key.getKey().startsWith(DATA_PREFIX));
    }

    /** Empty means either no periodic-key marker exists or the marker is malformed. */
    public static Optional<Identity> identify(ItemStack item) {
        if (!isMarked(item)) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        try {
            Byte version = data.get(VERSION_KEY, PersistentDataType.BYTE);
            String owner = data.get(OWNER_KEY, PersistentDataType.STRING);
            String crate = data.get(CRATE_KEY, PersistentDataType.STRING);
            String keyId = data.get(KEY_ID_KEY, PersistentDataType.STRING);
            String periodName = data.get(PERIOD_KEY, PersistentDataType.STRING);
            Long startValue = data.get(START_KEY, PersistentDataType.LONG);
            Long expiryValue = data.get(EXPIRY_KEY, PersistentDataType.LONG);
            String idValue = data.get(ID_KEY, PersistentDataType.STRING);
            if (version == null || version != VERSION || owner == null || crate == null || keyId == null
                    || periodName == null || startValue == null || expiryValue == null || idValue == null) {
                return Optional.empty();
            }
            UUID playerId = UUID.fromString(owner);
            UUID id = UUID.fromString(idValue);
            PeriodicVirtualOpening.Period period = PeriodicVirtualOpening.Period.valueOf(periodName);
            Instant start = Instant.ofEpochMilli(startValue);
            Instant expiry = Instant.ofEpochMilli(expiryValue);
            Identity identity = new Identity(id, playerId, crate, keyId, period, start, expiry);
            PeriodicVirtualOpening.Window window = new PeriodicVirtualOpening.Window(period, start, expiry);
            UUID expectedId = PeriodicVirtualOpening.openingId(playerId, crate, period, window);
            return expectedId.equals(id) ? Optional.of(identity) : Optional.empty();
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    /** Validate the exact configured owner, case, cadence, and current local calendar window. */
    public static boolean valid(ItemStack item, UUID playerId, String crateId,
            PeriodicVirtualOpening.Period period, ZoneId zone, Instant now) {
        if (playerId == null || crateId == null || period == null || period == PeriodicVirtualOpening.Period.NONE
                || zone == null || now == null) return false;
        Identity identity = identify(item).orElse(null);
        if (identity == null || !identity.playerId().equals(playerId) || !identity.crateId().equals(crateId)
                || identity.period() != period || now.isBefore(identity.start()) || !now.isBefore(identity.expiry())) {
            return false;
        }
        PeriodicVirtualOpening.Window current = PeriodicVirtualOpening.window(period, now, zone);
        return identity.start().equals(current.start())
                && identity.expiry().equals(current.nextReset())
                && identity.id().equals(PeriodicVirtualOpening.openingId(playerId, crateId, period, current));
    }

    private static NamespacedKey key(String value) {
        // fromString without a namespace defaults to minecraft. Keep addon-owned
        // fields in the ecia namespace so the marker check can identify them and
        // avoid colliding with unrelated vanilla PDC data.
        String namespaced = value.indexOf(':') >= 0 ? value : "ecia:" + value;
        return Objects.requireNonNull(NamespacedKey.fromString(namespaced));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid periodic key " + field);
        }
    }
}
