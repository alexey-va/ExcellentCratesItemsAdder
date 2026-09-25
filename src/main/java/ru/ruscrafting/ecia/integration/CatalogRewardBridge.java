package ru.ruscrafting.ecia.integration;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import ru.arc.paper.api.ArcItemMaterializationReference;
import ru.arc.paper.api.ArcItemMaterializationRequest;
import ru.arc.paper.api.ArcItemMaterializer;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.roll.RewardDefinition;

import java.util.List;
import java.util.Objects;

/** Typed ARC provider integration; a handled console command is never a receipt. */
public final class CatalogRewardBridge {
    // The two legacy providers remain readable for openings already in the journal.
    public enum Provider { FROZEN_ITEMS, ARC_VOUCHER, ARC_CURRENT, NATIVE_ITEMS }
    public record Recipe(int schema, Provider provider, String categoryId, String entryId,
            String providerFingerprint, String sourceKey, List<String> nativeItems, String sourceFingerprint) {
        public Recipe {
            if (schema != 1) throw new IllegalArgumentException("Unsupported reward recipe schema");
            Objects.requireNonNull(provider);
            Objects.requireNonNull(categoryId);
            Objects.requireNonNull(entryId);
            Objects.requireNonNull(providerFingerprint);
            Objects.requireNonNull(sourceKey);
            Objects.requireNonNull(sourceFingerprint);
            nativeItems = List.copyOf(nativeItems);
            if ((provider == Provider.FROZEN_ITEMS || provider == Provider.NATIVE_ITEMS) && nativeItems.isEmpty()
                    || provider == Provider.ARC_VOUCHER && (sourceKey.isBlank() || providerFingerprint.isBlank())
                    || provider == Provider.ARC_CURRENT && (categoryId.isBlank() || entryId.isBlank())) {
                throw new IllegalArgumentException("Incomplete reward recipe");
            }
        }
    }

    private final NativeItemPayload payload;

    public CatalogRewardBridge(NativeItemPayload payload) { this.payload = payload; }

    /** Records the catalog identity without creating items or archiving the catalog at startup. */
    public String catalogReward(String category, String entry, String sourceFingerprint) {
        return payload.write(new Recipe(1, Provider.ARC_CURRENT, category, entry,
                "", "", List.of(), sourceFingerprint));
    }

    public String nativeReward(ItemStack item, String sourceFingerprint) {
        if (item == null || item.isEmpty()) throw new IllegalArgumentException("Empty native reward");
        return payload.write(new Recipe(1, Provider.NATIVE_ITEMS, "", "", "", "",
                payload.capture(new ItemStack[]{item}), sourceFingerprint));
    }

    public String sourceFingerprint(RewardDefinition reward) {
        return payload.read(reward.deliveryPayload(), Recipe.class).sourceFingerprint();
    }

    public ItemStack[] materialize(RewardDefinition reward) {
        Recipe recipe = payload.read(reward.deliveryPayload(), Recipe.class);
        if (recipe.provider() == Provider.FROZEN_ITEMS || recipe.provider() == Provider.NATIVE_ITEMS) {
            return payload.restore(recipe.nativeItems());
        }
        ArcItemMaterializer provider = provider();
        if (provider == null) return null;
        var request = new ArcItemMaterializationRequest(recipe.categoryId(), recipe.entryId());
        ArcItemMaterializationReference reference;
        if (recipe.provider() == Provider.ARC_CURRENT) {
            if (!provider.capability().getAvailable()) return null;
            reference = provider.prepare(request);
            if (reference == null) return null;
        } else {
            reference = new ArcItemMaterializationReference.FreshVoucher(
                    request, recipe.providerFingerprint(), recipe.sourceKey());
        }
        var items = provider.materialize(reference);
        return items == null ? null : items.toArray(ItemStack[]::new);
    }

    private ArcItemMaterializer provider() {
        return Bukkit.getServicesManager().load(ArcItemMaterializer.class);
    }
}
