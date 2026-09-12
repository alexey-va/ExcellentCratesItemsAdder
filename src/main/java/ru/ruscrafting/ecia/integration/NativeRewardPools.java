package ru.ruscrafting.ecia.integration;

import org.bukkit.inventory.ItemStack;
import ru.ruscrafting.ecia.inventory.NativeItemPayload;
import ru.ruscrafting.ecia.roll.PoolSnapshot;
import ru.ruscrafting.ecia.roll.RewardDefinition;
import ru.ruscrafting.ecia.season.SeasonPoolStore;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.reward.impl.CommandReward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Captures native EC weights and exact ARC item recipes once per season. */
public final class NativeRewardPools {
    private final SeasonPoolStore seasons;
    private final NativeSeasonKeys keys;
    private final CatalogRewardBridge provider;
    private final NativeItemPayload payload;

    public NativeRewardPools(SeasonPoolStore seasons, NativeSeasonKeys keys,
            CatalogRewardBridge provider, NativeItemPayload payload) {
        this.seasons = seasons;
        this.keys = keys;
        this.provider = provider;
        this.payload = payload;
    }

    public Map<String, PoolSnapshot> install(ManagedCratesSettings settings) {
        if (Config.CRATE_REVERSE_CLICK_ACTIONS.get()) throw new IllegalStateException("Managed crates require native right-click opening");
        Map<String, String> keySeasons = new HashMap<>();
        for (var configured : settings.cases().values()) {
            Crate crate = requireCrate(configured.crateId());
            validate(crate);
            String keyId = keys.cost(crate).keyId();
            String prior = keySeasons.putIfAbsent(keyId, configured.seasonId());
            if (prior != null && !prior.equals(configured.seasonId())) throw new IllegalStateException("Shared key has conflicting seasons: " + keyId);
        }
        Map<String, PoolSnapshot> installed = new HashMap<>();
        for (var configured : settings.cases().values()) {
            Crate crate = requireCrate(configured.crateId());
            var nativeRewards = crate.getRewards().stream().sorted(Comparator.comparing(Reward::getId)).toList();
            for (String id : configured.qualifyingRewards()) {
                if (nativeRewards.stream().noneMatch(reward -> reward.getId().equals(id))) {
                    throw new IllegalStateException("Unknown guarantee reward: " + crate.getId() + "/" + id);
                }
            }
            var old = seasons.find(crate.getId(), configured.seasonId());
            if (old.isPresent()) {
                verifyUnchanged(old.get(), configured, nativeRewards);
                installed.put(crate.getId(), old.get());
                continue;
            }
            List<RewardDefinition> rewards = new ArrayList<>();
            for (Reward reward : nativeRewards) {
                String delivery = freeze(reward, keySeasons);
                rewards.add(new RewardDefinition(reward.getId(), reward.getWeight(),
                        configured.qualifyingRewards().contains(reward.getId()), delivery,
                        payload.items(new ItemStack[]{reward.getPreviewItem()})));
            }
            PoolSnapshot pool = new PoolSnapshot(crate.getId(), configured.seasonId(), rewards,
                    configured.pityThreshold(), configured.choiceCount(), configured.maxRerolls());
            installed.put(crate.getId(), seasons.register(pool));
        }
        return Map.copyOf(installed);
    }

    private void validate(Crate crate) {
        keys.cost(crate);
        if (crate.isOpeningCooldownEnabled() || crate.hasMilestones() || !crate.getPostOpenCommands().isEmpty()) {
            throw new IllegalStateException("Managed crate has unsupported native cooldown, milestone or post-open side effects: " + crate.getId());
        }
        for (Reward reward : crate.getRewards()) {
            if (!(reward instanceof CommandReward command) || command.getCommands().size() != 1
                    || reward.getLimits().isEnabled() || !reward.getRequiredPermissions().isEmpty()
                    || !reward.getIgnoredPermissions().isEmpty()) {
                throw new IllegalStateException("Unsupported managed reward rules: " + crate.getId() + "/" + reward.getId());
            }
        }
    }

    private String freeze(Reward reward, Map<String, String> keySeasons) {
        String command = ((CommandReward) reward).getCommands().getFirst();
        String[] words = command.trim().split("\\s+");
        String fingerprint = sourceFingerprint(reward);
        if (isArcRewardIssue(words)) {
            return provider.freeze(words[2], words[3], fingerprint);
        }
        if (words.length == 6 && words[0].equals("excellentcrates") && words[1].equals("key")
                && words[2].equals("give") && (words[3].equals("%player_name%") || words[3].equals("%player%"))) {
            String season = keySeasons.get(words[4]);
            if (season == null) throw new IllegalStateException("Key reward targets an unmanaged season: " + words[4]);
            return provider.freezeNative(keys.create(words[4], season, Integer.parseInt(words[5])), fingerprint);
        }
        throw new IllegalStateException("Reward has no typed materializer: " + reward.getCrate().getId() + "/" + reward.getId());
    }

    static boolean isArcRewardIssue(String[] words) {
        return words.length == 4 && words[0].equals("arc-reward-issue")
                && (words[1].equals("%player%") || words[1].equals("%player_name%"));
    }

    private void verifyUnchanged(PoolSnapshot pool, ManagedCratesSettings.CaseSettings config, List<Reward> nativeRewards) {
        if (pool.pityThreshold() != config.pityThreshold() || pool.choiceCount() != config.choiceCount()
                || pool.maxRerolls() != config.maxRerolls() || pool.rewards().size() != nativeRewards.size()) {
            throw new IllegalStateException("Season rules changed; create a new season: " + pool.crateId());
        }
        for (Reward reward : nativeRewards) {
            RewardDefinition frozen = pool.rewards().stream().filter(item -> item.id().equals(reward.getId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Season rewards changed: " + pool.crateId()));
            if (frozen.guaranteeEligible() != config.qualifyingRewards().contains(reward.getId())
                    || !provider.sourceFingerprint(frozen).equals(sourceFingerprint(reward))) {
                throw new IllegalStateException("Season reward changed; create a new season: " + pool.crateId() + "/" + reward.getId());
            }
        }
    }

    private String sourceFingerprint(Reward reward) {
        String definition = payload.write(List.of(reward.getId(), reward.getWeight(), reward.getName(),
                reward.getDescription(), ((CommandReward) reward).getCommands(),
                payload.items(new ItemStack[]{reward.getPreviewItem()})));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(definition.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Crate requireCrate(String id) {
        Crate crate = CratesAPI.getCrateManager().getCrateById(id);
        if (crate == null) throw new IllegalStateException("Missing native crate " + id);
        return crate;
    }
}
