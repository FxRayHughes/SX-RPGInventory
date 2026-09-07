package ru.endlesscode.rpginventory.compat;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.lang.reflect.Method;
import java.util.Locale;

/** Resolve API names at runtime where Bukkit changed enum names, class kinds or Paper-only capabilities. */
public final class ServerCompatibility {
    private ServerCompatibility() { }

    /** Fields also cover registry-backed interfaces; bytecode must not assume every Bukkit value is an enum. */
    public static <T> T namedConstant(Class<T> type, String... names) {
        for (String name : names) {
            String normalized = name.toUpperCase(Locale.ROOT);
            try { return type.cast(type.getField(normalized).get(null)); }
            catch (ReflectiveOperationException ignored) { }
            try { return type.cast(type.getMethod("valueOf", String.class).invoke(null, normalized)); }
            catch (ReflectiveOperationException ignored) { }
        }
        throw new IllegalArgumentException("Unsupported " + type.getName() + " value: " + java.util.Arrays.toString(names));
    }

    /** Attribute field names lost GENERIC_ in newer APIs; the parameter type itself remains binary-compatible. */
    public static Attribute attribute(String name) { return namedConstant(Attribute.class, name, "GENERIC_" + name); }

    /** Cats were ocelots in 1.12; newer cat variants may be registry objects instead of enums. */
    public static boolean configureCat(LivingEntity cat, String variant, org.bukkit.DyeColor collar) {
        try {
            Method setter = null;
            for (Method method : cat.getClass().getMethods()) {
                if (method.getName().equals("setCatType") && method.getParameterTypes().length == 1) setter = method;
            }
            if (setter == null) return false;
            setter.invoke(cat, namedConstant(setter.getParameterTypes()[0], variant, "RED_CAT"));
            if (collar != null) {
                try { cat.getClass().getMethod("setCollarColor", org.bukkit.DyeColor.class).invoke(cat, collar); }
                catch (NoSuchMethodException ignored) { /* Ocelots have no visible collar. */ }
            }
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException failure) {
            ru.endlesscode.rpginventory.utils.Log.w(failure, "Cannot configure cat variant");
            return false;
        }
    }

    /** Prefer Paper navigation; Spigot and 1.12 use their native navigation object without linking Paper classes. */
    public static void navigate(LivingEntity entity, Location target, double speed) {
        try {
            try {
                Object pathfinder = entity.getClass().getMethod("getPathfinder").invoke(entity);
                pathfinder.getClass().getMethod("moveTo", Location.class, double.class).invoke(pathfinder, target, speed);
                return;
            } catch (NoSuchMethodException ignored) { }
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            Object navigation = handle.getClass().getMethod("getNavigation").invoke(handle);
            for (String name : new String[] {"moveTo", "a"}) {
                try {
                    navigation.getClass().getMethod(name, double.class, double.class, double.class, double.class)
                            .invoke(navigation, target.getX(), target.getY(), target.getZ(), speed);
                    return;
                } catch (NoSuchMethodException ignored) { }
            }
            throw new NoSuchMethodException("Navigation move method");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pet navigation is unavailable on this server", failure);
        }
    }

    /** UUID requests isolate packs on modern servers; old servers expose only the URL/hash request. */
    public static void resourcePack(Player player, java.util.UUID id, String url, byte[] hash) {
        try {
            Method modern = Player.class.getMethod("setResourcePack", java.util.UUID.class, String.class, byte[].class, String.class, boolean.class);
            modern.invoke(player, id, url, hash, null, true);
        } catch (NoSuchMethodException legacy) {
            player.setResourcePack(url, hash);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Resource pack request failed", failure);
        }
    }

    /** Legacy status events carry no request UUID; callers additionally check their own pending-player set. */
    public static boolean isResourcePack(Object event, java.util.UUID id) {
        try { return id.equals(event.getClass().getMethod("getID").invoke(event)); }
        catch (NoSuchMethodException legacy) { return true; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
