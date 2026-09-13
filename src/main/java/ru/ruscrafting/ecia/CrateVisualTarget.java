package ru.ruscrafting.ecia;

import org.bukkit.Location;

import java.util.Objects;

public record CrateVisualTarget(String crateId, Location anchor) {
    public CrateVisualTarget {
        Objects.requireNonNull(crateId);
        anchor = Objects.requireNonNull(anchor).clone();
    }

    @Override public Location anchor() { return anchor.clone(); }
}
