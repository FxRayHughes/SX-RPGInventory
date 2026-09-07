package ru.endlesscode.rpginventory.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;

/** Optional SX-Item bridge using its public API through the owning plugin's class loader. */
public final class SXItemBridge {
    private static Plugin boundPlugin;
    private static Object manager;
    private static Method identity;
    private static Method generate;
    private static Method update;
    private SXItemBridge() { }

    private static boolean bind() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SX-Item");
        if (plugin == null || !plugin.isEnabled()) return false;
        if (plugin == boundPlugin) return true;
        try {
            manager = plugin.getClass().getMethod("getItemManager").invoke(null);
            Class<?> type = manager.getClass();
            identity = type.getMethod("getItemKey", ItemStack.class);
            generate = type.getMethod("getItem", String.class, Player.class, Object[].class);
            update = type.getMethod("checkUpdateItem", Player.class, ItemStack[].class);
            boundPlugin = plugin;
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SX-Item public API is incompatible", e);
        }
    }

    /** Resolve persisted SX-Item identity instead of relying on editable display names or lore. */
    public static String identity(ItemStack item) {
        if (ItemCompatibility.isEmpty(item) || !bind()) return null;
        try { return (String) identity.invoke(manager, item); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot read SX-Item identity", e); }
    }

    /** Generate a configured SX-Item template through its normal event pipeline, preserving attribute integrations. */
    public static ItemStack generate(String id, Player player) {
        if (!bind()) throw new IllegalStateException("SX-Item is required for template " + id);
        try {
            ItemStack item = (ItemStack) generate.invoke(manager, id, player, new Object[0]);
            if (ItemCompatibility.isEmpty(item)) throw new IllegalArgumentException("Unknown SX-Item template: " + id);
            return item;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot generate SX-Item " + id, e); }
    }

    /** Refresh only through SX-Item's own version check; never reconstruct foreign items from lore. */
    public static void update(Player player, ItemStack[] items) {
        if (!bind()) return;
        try { update.invoke(manager, player, (Object) items); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot update SX-Item equipment", e); }
    }

    /** Read literal legacy RPGInventory NBT keys through SX-Item's modern NMS adapter during item migration. */
    public static Object legacyTag(ItemStack item, String key) {
        if (!bind()) return null;
        try {
            ClassLoader loader = boundPlugin.getClass().getClassLoader();
            Class<?> nbtType = Class.forName("github.saukiya.tools.nms.NbtUtil", true, loader);
            Object nbt = nbtType.getMethod("getInst").invoke(null);
            Object compound = nbtType.getMethod("getItemTag", ItemStack.class).invoke(nbt, item);
            if (compound == null) return null;
            Map<?, ?> tags = (Map<?, ?>) compound.getClass().getMethod("getValue").invoke(compound);
            Object tag = tags.get(key);
            return tag == null ? null : tag.getClass().getMethod("getValue").invoke(tag);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot migrate RPGInventory item tag " + key, e);
        }
    }
}
