package ru.ruscrafting.ecia.integration;

import org.bukkit.inventory.ItemStack;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.reward.impl.CommandReward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Builds the managed pool directly from the currently loaded native rewards. */
public final class NativeRewardPools {
    private static final String CURRENT_POOL = "current";

    private final NativeSeasonKeys keys;
    private final CatalogRewardBridge provider;
    private final NativeItemPayload payload;
    private final Consumer<String> issueSink;

    public NativeRewardPools(NativeSeasonKeys keys, CatalogRewardBridge provider,
            NativeItemPayload payload) {
        this(keys, provider, payload, ignored -> { });
    }

    public NativeRewardPools(NativeSeasonKeys keys, CatalogRewardBridge provider,
            NativeItemPayload payload, Consumer<String> issueSink) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.issueSink = Objects.requireNonNull(issueSink, "issueSink");
    }

    public Map<String, PoolSnapshot> install(ManagedCratesSettings settings) {
        if (Config.CRATE_REVERSE_CLICK_ACTIONS.get()) {
            throw new IllegalStateException("Managed crates require native right-click opening");
        }
        Map<String, PoolSnapshot> installed = new java.util.HashMap<>();
        for (var configured : settings.cases().values()) {
            PoolSnapshot pool = loadCurrent(configured);
            if (pool != null) installed.put(configured.crateId(), pool);
        }
        return Map.copyOf(installed);
    }

    /** Re-read the native reward list for each new opening or preview; existing receipts stay intact. */
    public PoolSnapshot loadCurrent(ManagedCratesSettings.CaseSettings configured) {
        if (Config.CRATE_REVERSE_CLICK_ACTIONS.get()) {
            throw new IllegalStateException("Managed crates require native right-click opening");
        }
        Crate crate;
        try {
            crate = requireCrate(configured.crateId());
            validateCrate(crate);
        } catch (RuntimeException failure) {
            issueSink.accept("crate=" + configured.crateId() + " reason=" + failure.getMessage());
            return null;
        }

        List<RewardDefinition> rewards = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Reward reward : crate.getRewards().stream()
                .sorted(Comparator.comparing(Reward::getId)).toList()) {
            if (!ids.add(reward.getId())) {
                issueSink.accept(issue(crate, reward.getId(), "duplicate native reward"));
                continue;
            }
            try {
                rewards.add(compileReward(reward));
            } catch (RuntimeException failure) {
                issueSink.accept(issue(crate, reward.getId(), failure.getMessage()));
            }
        }
        if (rewards.isEmpty()) {
            issueSink.accept("crate=" + crate.getId() + " reason=no valid rewards remain; managed case disabled");
            return null;
        }
        int bundleSize = Math.min(configured.bundleSize(), rewards.size());
        if (bundleSize != configured.bundleSize()) {
            issueSink.accept("crate=" + crate.getId() + " reason=bundle size "
                    + configured.bundleSize() + " reduced to " + bundleSize + " for current rewards");
        }
        return currentPool(crate.getId(), rewards,
                configured.choiceCount(), configured.maxRerolls(), bundleSize);
    }

    static PoolSnapshot currentPool(String crateId, List<RewardDefinition> rewards,
            int choiceCount, int maxRerolls, int bundleSize) {
        return new PoolSnapshot(crateId, CURRENT_POOL, rewards, choiceCount, maxRerolls, bundleSize);
    }

    private void validateCrate(Crate crate) {
        keys.cost(crate);
        if (crate.isOpeningCooldownEnabled() || crate.hasMilestones() || !crate.getPostOpenCommands().isEmpty()) {
            throw new IllegalStateException("unsupported native cooldown, milestone or post-open side effects");
        }
    }

    private RewardDefinition compileReward(Reward reward) {
        if (!(reward instanceof CommandReward command) || command.getCommands().size() != 1
                || reward.getLimits().isEnabled() || !reward.getRequiredPermissions().isEmpty()
                || !reward.getIgnoredPermissions().isEmpty()) {
            throw new IllegalStateException("unsupported reward rules");
        }
        String delivery = deliveryReference(reward, command.getCommands().getFirst());
        return new RewardDefinition(reward.getId(), reward.getWeight(), delivery,
                payload.items(new ItemStack[]{reward.getPreviewItem()}));
    }

    private String deliveryReference(Reward reward, String command) {
        String[] words = command.trim().split("\\s+");
        if (isArcRewardIssue(words)) {
            return provider.catalogReward(words[2], words[3], reward.getId());
        }
        if (words.length == 6 && words[0].equals("excellentcrates") && words[1].equals("key")
                && words[2].equals("give") && (words[3].equals("%player_name%") || words[3].equals("%player%"))) {
            return provider.nativeReward(keys.create(words[4], Integer.parseInt(words[5])), reward.getId());
        }
        throw new IllegalStateException("reward has no typed materializer");
    }

    static boolean isArcRewardIssue(String[] words) {
        return words.length == 4 && words[0].equals("arc-reward-issue")
                && (words[1].equals("%player%") || words[1].equals("%player_name%"));
    }

    private static String issue(Crate crate, String rewardId, String reason) {
        return "crate=" + crate.getId() + " reward=" + rewardId + " reason=" + reason;
    }

    private Crate requireCrate(String id) {
        Crate crate = CratesAPI.getCrateManager().getCrateById(id);
        if (crate == null) throw new IllegalStateException("missing native crate");
        return crate;
    }
}
