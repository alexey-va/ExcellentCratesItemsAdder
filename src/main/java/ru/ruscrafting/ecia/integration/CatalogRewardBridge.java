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
    public enum Provider { FROZEN_ITEMS, ARC_VOUCHER }
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
            if (provider == Provider.FROZEN_ITEMS && nativeItems.isEmpty()
                    || provider == Provider.ARC_VOUCHER && (sourceKey.isBlank() || providerFingerprint.isBlank())) {
                throw new IllegalArgumentException("Incomplete reward recipe");
            }
        }
    }

    private final NativeItemPayload payload;

    public CatalogRewardBridge(NativeItemPayload payload) { this.payload = payload; }

    public String freeze(String category, String entry, String sourceFingerprint) {
        ArcItemMaterializer provider = provider();
        if (provider == null || !provider.capability().getAvailable()) throw new IllegalStateException("ARC reward provider is unavailable");
        var reference = provider.prepare(new ArcItemMaterializationRequest(category, entry));
        if (reference instanceof ArcItemMaterializationReference.FrozenItems frozen) {
            return payload.write(new Recipe(1, Provider.FROZEN_ITEMS, category, entry,
                    frozen.getProviderFingerprint(), "", payload.capture(frozen.getTemplates().toArray(ItemStack[]::new)), sourceFingerprint));
        }
        if (reference instanceof ArcItemMaterializationReference.FreshVoucher voucher) {
            return payload.write(new Recipe(1, Provider.ARC_VOUCHER, category, entry,
                    voucher.getProviderFingerprint(), voucher.getSourceKey(), List.of(), sourceFingerprint));
        }
        throw new IllegalStateException("ARC cannot freeze case reward " + category + "/" + entry);
    }

    public String freezeNative(ItemStack item, String sourceFingerprint) {
        if (item == null || item.isEmpty()) throw new IllegalArgumentException("Cannot freeze empty native reward");
        return payload.write(new Recipe(1, Provider.FROZEN_ITEMS, "", "", "", "",
                payload.capture(new ItemStack[]{item}), sourceFingerprint));
    }

    public String sourceFingerprint(RewardDefinition reward) {
        return payload.read(reward.deliveryPayload(), Recipe.class).sourceFingerprint();
    }

    public ItemStack[] materialize(RewardDefinition reward) {
        Recipe recipe = payload.read(reward.deliveryPayload(), Recipe.class);
        if (recipe.provider() == Provider.FROZEN_ITEMS) return payload.restore(recipe.nativeItems());
        ArcItemMaterializer provider = provider();
        if (provider == null) return null;
        var request = new ArcItemMaterializationRequest(recipe.categoryId(), recipe.entryId());
        var items = provider.materialize(new ArcItemMaterializationReference.FreshVoucher(
                request, recipe.providerFingerprint(), recipe.sourceKey()));
        return items == null ? null : items.toArray(ItemStack[]::new);
    }

    private ArcItemMaterializer provider() {
        return Bukkit.getServicesManager().load(ArcItemMaterializer.class);
    }
}
