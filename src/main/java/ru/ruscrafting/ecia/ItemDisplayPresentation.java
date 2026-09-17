package ru.ruscrafting.ecia;

import org.bukkit.configuration.Configuration;
import org.bukkit.entity.ItemDisplay;
import org.joml.Vector3f;

/** Selects between a thin camera-facing preview and the previous full-depth GUI model. */
public enum ItemDisplayPresentation {
    FLAT(ItemDisplay.ItemDisplayTransform.FIXED, .06F),
    THREE_DIMENSIONAL(ItemDisplay.ItemDisplayTransform.GUI, 1.0F);

    private final ItemDisplay.ItemDisplayTransform transform;
    private final float depthRatio;

    ItemDisplayPresentation(ItemDisplay.ItemDisplayTransform transform, float depthRatio) {
        this.transform = transform;
        this.depthRatio = depthRatio;
    }

    public static ItemDisplayPresentation from(Configuration config, String path) {
        return config.getBoolean(path, true) ? FLAT : THREE_DIMENSIONAL;
    }

    public ItemDisplay.ItemDisplayTransform transform() {
        return transform;
    }

    public Vector3f scale(float scale) {
        return new Vector3f(scale, scale, scale * depthRatio);
    }
}
