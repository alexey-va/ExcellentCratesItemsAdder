package ru.ruscrafting.ecia.integration;

import org.bukkit.inventory.ItemStack;
import ru.ruscrafting.ecia.api.CrateAcquisitionReward;
import ru.ruscrafting.ecia.api.CrateAcquisitionSnapshot;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import ru.arc.core.BudgetedTaskQueue;

/** Builds the managed pool directly from the currently loaded native rewards. */
public final class NativeRewardPools {
    private static final String CURRENT_POOL = "current";

    /** The opening pool and read-only evidence captured from the same native rewards. */
    public record LoadedPool(PoolSnapshot pool, CrateAcquisitionSnapshot acquisition) { }

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

    /** Starts on Paper; each native reward capture shares the runtime tick budget. */
    public CompletableFuture<Map<String, PoolSnapshot>> installAsync(ManagedCratesSettings settings,
            BudgetedTaskQueue work, Executor worker) {
        var configured = settings.cases().values().iterator();
        List<CompletableFuture<PoolSnapshot>> pending = new ArrayList<>();
        return work.submit(() -> true, () -> {
            if (configured.hasNext()) pending.add(loadCurrentAsync(configured.next(), work, worker));
            return !configured.hasNext();
        }).thenCompose(ignored -> CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)))
                .thenApplyAsync(ignored -> {
                    Map<String, PoolSnapshot> result = new java.util.HashMap<>();
                    for (var completed : pending) {
                        PoolSnapshot pool = completed.getNow(null);
                        if (pool != null) result.put(pool.crateId(), pool);
                    }
                    return Map.copyOf(result);
                }, worker);
    }

    public CompletableFuture<PoolSnapshot> loadCurrentAsync(ManagedCratesSettings.CaseSettings configured,
            BudgetedTaskQueue work, Executor worker) {
        return loadCurrentWithAcquisitionAsync(configured, work, worker)
                .thenApply(loaded -> loaded == null ? null : loaded.pool());
    }

    public CompletableFuture<LoadedPool> loadCurrentWithAcquisitionAsync(ManagedCratesSettings.CaseSettings configured,
            BudgetedTaskQueue work, Executor worker) {
        final Crate crate;
        try {
            if (Config.CRATE_REVERSE_CLICK_ACTIONS.get()) throw new IllegalStateException("Managed crates require native right-click opening");
            crate = requireCrate(configured.crateId());
            validateCrate(crate);
        } catch (RuntimeException failure) {
            issueSink.accept("crate=" + configured.crateId() + " reason=" + failure.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        String displayName = displayName(crate.getName(), configured.crateId());
        var nativeRewards = List.copyOf(crate.getRewards()).iterator();
        List<CapturedReward> captured = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        return work.submit(() -> CratesAPI.getCrateManager().getCrateById(crate.getId()) == crate, () -> {
            if (nativeRewards.hasNext()) {
                Reward reward = nativeRewards.next();
                if (!ids.add(reward.getId())) issueSink.accept(issue(crate, reward.getId(), "duplicate native reward"));
                else try { captured.add(captureReward(reward)); }
                catch (RuntimeException failure) { issueSink.accept(issue(crate, reward.getId(), failure.getMessage())); }
            }
            return !nativeRewards.hasNext();
        }).thenApplyAsync(ignored -> {
            if (captured.isEmpty()) {
                issueSink.accept("crate=" + configured.crateId() + " reason=no valid rewards remain; managed case disabled");
                return null;
            }
            List<RewardDefinition> definitions = captured.stream().map(this::encodeReward)
                    .sorted(Comparator.comparing(RewardDefinition::id)).toList();
            int bundle = Math.min(configured.bundleSize(), definitions.size());
            if (bundle != configured.bundleSize()) issueSink.accept("crate=" + configured.crateId()
                    + " reason=bundle size " + configured.bundleSize() + " reduced to " + bundle + " for current rewards");
            PoolSnapshot pool = currentPool(configured.crateId(), definitions,
                    configured.choiceCount(), configured.maxRerolls(), bundle);
            double maximum = definitions.stream().mapToDouble(RewardDefinition::weight).max().orElseThrow();
            double total = 0.0d;
            for (RewardDefinition definition : definitions) total += definition.weight() / maximum;
            double denominator = total;
            List<CrateAcquisitionReward> acquisition = captured.stream()
                    .sorted(Comparator.comparing(CapturedReward::id))
                    .map(reward -> new CrateAcquisitionReward(reward.id(), reward.displayName(), reward.weight(),
                            (reward.weight() / maximum) / denominator, reward.category(), reward.entry()))
                    .toList();
            return new LoadedPool(pool, new CrateAcquisitionSnapshot(configured.crateId(), displayName, true, acquisition));
        }, worker);
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

    /** Only native bytes and scalar recipe fields cross the worker boundary. */
    private CapturedReward captureReward(Reward reward) {
        if (!(reward instanceof CommandReward command) || command.getCommands().size() != 1
                || reward.getLimits().isEnabled() || !reward.getRequiredPermissions().isEmpty()
                || !reward.getIgnoredPermissions().isEmpty()) throw new IllegalStateException("unsupported reward rules");
        String[] words = command.getCommands().getFirst().trim().split("\\s+");
        String category = "";
        String entry = "";
        byte[] delivery = null;
        if (isArcRewardIssue(words)) {
            category = words[2]; entry = words[3];
        } else if (words.length == 6 && words[0].equals("excellentcrates") && words[1].equals("key")
                && words[2].equals("give") && (words[3].equals("%player_name%") || words[3].equals("%player%"))) {
            ItemStack item = keys.create(words[4], Integer.parseInt(words[5]));
            if (item == null || item.isEmpty()) throw new IllegalStateException("Empty native reward");
            delivery = ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec.INSTANCE.encodeItem(item);
        } else throw new IllegalStateException("reward has no typed materializer");
        ItemStack preview = reward.getPreviewItem();
        byte[] previewBytes = preview == null || preview.isEmpty() ? null
                : ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec.INSTANCE.encodeItem(preview);
        return new CapturedReward(reward.getId(), displayName(reward.getName(), reward.getId()),
                reward.getWeight(), category, entry, delivery, previewBytes);
    }

    private RewardDefinition encodeReward(CapturedReward captured) {
        String delivery = captured.delivery() == null
                ? provider.catalogReward(captured.category(), captured.entry(), captured.id())
                : payload.write(new CatalogRewardBridge.Recipe(1, CatalogRewardBridge.Provider.NATIVE_ITEMS,
                    "", "", "", "", List.of(java.util.Base64.getEncoder().encodeToString(captured.delivery())), captured.id()));
        String preview = payload.write(List.of(captured.preview() == null ? ""
                : java.util.Base64.getEncoder().encodeToString(captured.preview())));
        return new RewardDefinition(captured.id(), captured.weight(), delivery, preview);
    }

    private record CapturedReward(String id, String displayName, double weight,
            String category, String entry, byte[] delivery, byte[] preview) { }

    private static String displayName(String loaded, String fallback) {
        return loaded == null || loaded.isBlank() ? fallback : loaded;
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
