package ru.ruscrafting.ecia;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateProtectionListenerTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");

    @TempDir
    Path directory;

    @Test
    void managedMainHandEntityCallbackOwnsAndCancelsExactFurnitureAnchor() throws Exception {
        Fixture fixture = fixture();
        List<String> calls = new ArrayList<>();
        CrateProtectionListener listener = fixture.listener();
        listener.setManagedOpenHandler((player, crate) -> {
            calls.add(crate);
            return true;
        });

        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.HAND);
        listener.onFurnitureInteract(event);

        assertTrue(event.isCancelled());
        assertEquals(List.of("case_daily"), calls);
    }

    @Test
    void cancelledEntityInteractionDoesNotReachKeyDebitOwner() throws Exception {
        Fixture fixture = fixture(GameMode.SURVIVAL);
        List<String> calls = new ArrayList<>();
        fixture.listener().setManagedOpenHandler((player, crate) -> { calls.add(crate); return true; });
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.HAND);
        event.setCancelled(true);
        fixture.listener().onFurnitureInteract(event);
        assertTrue(event.isCancelled());
        assertTrue(calls.isEmpty());
    }

    @Test
    void managedOpeningIsAvailableToSurvivalAndAdventurePlayers() throws Exception {
        for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.ADVENTURE)) {
            Fixture fixture = fixture(mode);
            List<String> calls = new ArrayList<>();
            CrateProtectionListener listener = fixture.listener();
            listener.setManagedOpenHandler((player, crate) -> {
                calls.add(crate);
                return true;
            });

            PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
                    fixture.player(), fixture.entity(), EquipmentSlot.HAND);
            listener.onFurnitureInteract(event);

            assertTrue(event.isCancelled(), mode.name());
            assertEquals(List.of("case_daily"), calls, mode.name());
        }
    }

    @Test
    void offHandAndEditModeKeepNativeEntityInteractionUntouched() throws Exception {
        Fixture fixture = fixture();
        List<String> calls = new ArrayList<>();
        CrateProtectionListener listener = fixture.listener();
        listener.setManagedOpenHandler((player, crate) -> {
            calls.add(crate);
            return true;
        });

        PlayerInteractEntityEvent offHand = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.OFF_HAND);
        listener.onFurnitureInteract(offHand);
        listener.setEditMode(fixture.player(), true);
        PlayerInteractEntityEvent editMode = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.HAND);
        listener.onFurnitureInteract(editMode);

        assertFalse(offHand.isCancelled());
        assertFalse(editMode.isCancelled());
        assertTrue(calls.isEmpty());
    }

    @Test
    void unmanagedOrUnownedCallbackPreservesNativeBehavior() throws Exception {
        Fixture fixture = fixture();
        List<String> calls = new ArrayList<>();
        CrateProtectionListener listener = fixture.listener();
        listener.setManagedOpenHandler((player, crate) -> {
            calls.add(crate);
            return false;
        });

        PlayerInteractEntityEvent ownedByNative = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.HAND);
        listener.onFurnitureInteract(ownedByNative);
        Entity ordinary = entity(fixture.world(), fixture.location(), fixture.persistentDataContainer(),
                EntityType.PLAYER, UUID.randomUUID());
        PlayerInteractEntityEvent unmanaged = new PlayerInteractEntityEvent(
                fixture.player(), ordinary, EquipmentSlot.HAND);
        listener.onFurnitureInteract(unmanaged);
        Entity unregisteredFurniture = entity(fixture.world(), new Location(fixture.world(), 11.0, 64.0, 10.0),
                fixture.persistentDataContainer(), EntityType.ITEM_DISPLAY, UUID.randomUUID());
        PlayerInteractEntityEvent unregistered = new PlayerInteractEntityEvent(
                fixture.player(), unregisteredFurniture, EquipmentSlot.HAND);
        listener.onFurnitureInteract(unregistered);

        assertFalse(ownedByNative.isCancelled());
        assertFalse(unmanaged.isCancelled());
        assertFalse(unregistered.isCancelled());
        assertEquals(List.of("case_daily"), calls);
    }

    @Test
    void baseAndHitVectorEntityPathsInvokeOwnerOnlyOnceAndCancelBothEvents() throws Exception {
        Fixture fixture = fixture();
        List<String> calls = new ArrayList<>();
        CrateProtectionListener listener = fixture.listener();
        listener.setManagedOpenHandler((player, crate) -> {
            calls.add(crate);
            return true;
        });

        PlayerInteractEntityEvent base = new PlayerInteractEntityEvent(
                fixture.player(), fixture.entity(), EquipmentSlot.HAND);
        PlayerInteractAtEntityEvent atEntity = new PlayerInteractAtEntityEvent(
                fixture.player(), fixture.entity(), new org.bukkit.util.Vector(0.5, 0.5, 0.5), EquipmentSlot.HAND);
        listener.onFurnitureInteract(base);
        listener.onFurnitureInteractAtEntity(atEntity);

        assertEquals(List.of("case_daily"), calls);
        assertTrue(base.isCancelled());
        assertTrue(atEntity.isCancelled());
    }

    private Fixture fixture() throws Exception {
        return fixture(GameMode.CREATIVE);
    }

    private Fixture fixture(GameMode gameMode) throws Exception {
        World world = proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "world";
            default -> defaultValue(method.getReturnType());
        });
        Location location = new Location(world, 10.0, 64.0, 10.0);
        PersistentDataContainer pdc = proxy(PersistentDataContainer.class,
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Entity entity = entity(world, location, pdc, EntityType.ITEM_DISPLAY, ENTITY_ID);
        Player player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER_ID;
            case "getGameMode" -> gameMode;
            default -> defaultValue(method.getReturnType());
        });
        Files.writeString(directory.resolve("case_daily.yml"), """
                Block:
                  Positions:
                    - '10,64,10,world'
                """);
        CrateRegistry registry = new CrateRegistry(directory, ignored -> { });
        registry.reload();
        return new Fixture(world, location, pdc, entity, player,
                new CrateProtectionListener(registry, null, Component.text("protected"), ""));
    }

    private static Entity entity(World world, Location location, PersistentDataContainer pdc,
                                 EntityType type, UUID id) {
        return proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> type;
            case "getWorld" -> world;
            case "getLocation" -> location;
            case "getPersistentDataContainer" -> pdc;
            case "getUniqueId" -> id;
            default -> defaultValue(method.getReturnType());
        });
    }

    private record Fixture(World world, Location location, PersistentDataContainer persistentDataContainer,
                           Entity entity, Player player, CrateProtectionListener listener) {
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> handler.invoke(proxy, method, args);
                };
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        return null;
    }
}
