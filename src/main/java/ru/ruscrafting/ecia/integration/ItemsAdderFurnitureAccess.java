package ru.ruscrafting.ecia.integration;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** One cached, optional ItemsAdder API boundary used by placement and animation. */
public final class ItemsAdderFurnitureAccess {
    private static final String PLUGIN = "ItemsAdder";

    private final Method ids;
    private final Method instance;
    private final Method itemStack;
    private final Method isFurniture;
    private final Method byBlock;
    private final Method spawn;
    private final Method replace;
    private final Method remove;
    private final Method entity;
    private final Method namespacedId;
    private final String bindingFailure;

    private ItemsAdderFurnitureAccess(Method ids, Method instance, Method itemStack, Method isFurniture,
            Method byBlock, Method spawn, Method replace, Method remove, Method entity,
            Method namespacedId, String bindingFailure) {
        this.ids = ids;
        this.instance = instance;
        this.itemStack = itemStack;
        this.isFurniture = isFurniture;
        this.byBlock = byBlock;
        this.spawn = spawn;
        this.replace = replace;
        this.remove = remove;
        this.entity = entity;
        this.namespacedId = namespacedId;
        this.bindingFailure = bindingFailure;
    }

    public static ItemsAdderFurnitureAccess create() {
        try {
            Class<?> stack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Class<?> furniture = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
            return new ItemsAdderFurnitureAccess(
                    stack.getMethod("getNamespacedIdsInRegistry"),
                    stack.getMethod("getInstance", String.class),
                    stack.getMethod("getItemStack"),
                    findMethod(furniture, "isFurniture", ItemStack.class),
                    furniture.getMethod("byAlreadySpawned", Block.class),
                    furniture.getMethod("spawn", String.class, Block.class),
                    furniture.getMethod("replaceFurniture", String.class),
                    furniture.getMethod("remove", boolean.class),
                    furniture.getMethod("getEntity"),
                    furniture.getMethod("getNamespacedID"),
                    "");
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new ItemsAdderFurnitureAccess(null, null, null, null, null, null, null, null, null, null,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        }
    }

    public boolean available() {
        return bindingFailure.isEmpty() && Bukkit.getPluginManager().isPluginEnabled(PLUGIN);
    }

    public String failure() {
        return bindingFailure;
    }

    /** All registered furniture, sorted by namespaced id. */
    public List<Model> models() {
        Set<String> registered = registeredIds();
        if (registered.isEmpty()) return List.of();
        List<Model> result = new ArrayList<>();
        for (String id : registered) {
            try {
                Object custom = instance.invoke(null, id);
                if (custom == null) continue;
                Object rawItem = itemStack.invoke(custom);
                if (!(rawItem instanceof ItemStack icon) || !furniture(icon, id)) continue;
                result.add(new Model(id, icon.clone()));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // One malformed registry entry must not hide every other model.
            }
        }
        result.sort(Comparator.comparing(Model::namespacedId));
        return List.copyOf(result);
    }

    public Set<String> registeredIds() {
        if (!available()) return Set.of();
        try {
            Object value = ids.invoke(null);
            if (!(value instanceof Set<?> registered)) return Set.of();
            Set<String> result = new java.util.HashSet<>();
            for (Object raw : registered) {
                if (raw instanceof String id && !id.isBlank()) result.add(id.trim());
            }
            return Set.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Set.of();
        }
    }

    public Optional<Instance> at(Block block) {
        if (!available()) return Optional.empty();
        try {
            Object furniture = byBlock.invoke(null, block);
            return furniture == null ? Optional.empty() : read(furniture);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public Optional<Instance> spawn(String id, Block block) {
        if (!available()) return Optional.empty();
        try {
            Object furniture = spawn.invoke(null, id, block);
            return furniture == null ? Optional.empty() : read(furniture);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public boolean replace(Block block, String id) {
        if (!available()) return false;
        try {
            Object furniture = byBlock.invoke(null, block);
            if (furniture == null) return false;
            replace.invoke(furniture, id);
            return true;
        } catch (InvocationTargetException failure) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    public boolean remove(Block block) {
        if (!available()) return false;
        try {
            Object furniture = byBlock.invoke(null, block);
            if (furniture == null) return false;
            remove.invoke(furniture, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private boolean furniture(ItemStack icon, String id) {
        if (isFurniture != null) {
            try {
                Object result = isFurniture.invoke(null, icon);
                if (result instanceof Boolean value) return value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Older IA builds do not expose the predicate. Use the registry
                // naming fallback below so the catalog remains useful.
            }
        }
        String value = id.toLowerCase(Locale.ROOT);
        return value.contains("chest") || value.contains("crate") || value.contains("case")
                || value.contains("barrel") || value.contains("box") || value.contains("coffer")
                || value.contains("сундук") || value.contains("ящик") || value.contains("бочк");
    }

    private Optional<Instance> read(Object furniture) throws ReflectiveOperationException {
        Object id = namespacedId.invoke(furniture);
        Object root = entity.invoke(furniture);
        if (!(id instanceof String value) || value.isBlank() || !(root instanceof Entity carrier)) {
            return Optional.empty();
        }
        return Optional.of(new Instance(value.trim(), carrier));
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    public record Model(String namespacedId, ItemStack icon) { }

    public record Instance(String namespacedId, Entity entity) { }
}
