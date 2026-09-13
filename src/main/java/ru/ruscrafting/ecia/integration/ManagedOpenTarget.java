package ru.ruscrafting.ecia.integration;

import org.bukkit.Location;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.Objects;

/** Physical crate context retained until the player chooses a reward. */
public record ManagedOpenTarget(Crate crate, Location anchor) {
    public ManagedOpenTarget {
        Objects.requireNonNull(crate);
        anchor = Objects.requireNonNull(anchor).clone();
    }

    @Override public Location anchor() { return anchor.clone(); }
}
