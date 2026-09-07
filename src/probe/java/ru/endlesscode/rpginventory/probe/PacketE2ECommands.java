package ru.endlesscode.rpginventory.probe;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.craft.CraftExtension;
import ru.endlesscode.rpginventory.inventory.craft.CraftManager;
import ru.endlesscode.rpginventory.misc.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow test-player setup/readback for real PacketEvents traffic; it never mutates production configuration. */
public final class PacketE2ECommands {
    private static final Map<UUID, PermissionAttachment> ATTACHMENTS = new LinkedHashMap<>();
    private static final Map<UUID, String> RUNS = new LinkedHashMap<>();

    private PacketE2ECommands() { }

    /** Caller already restricts authority and account name; only per-player permissions/inventory are changed here. */
    public static Map<String, Object> handle(Player player, String operation, String run) throws Exception {
        if (!"craft-snapshot".equals(operation) && !"craft-release".equals(operation)
                && !InventoryManager.playerIsLoaded(player)) throw new IllegalStateException("player storage not ready");
        if ("craft-prepare".equals(operation)) {
            if (!Config.getConfig().getBoolean("craft.enabled") || !Config.getConfig().getBoolean("craft.workbench")) {
                throw new IllegalStateException("craft.enabled and craft.workbench must already be enabled by test host");
            }
            if (ATTACHMENTS.containsKey(player.getUniqueId())) throw new IllegalStateException("craft fixture already prepared");
            int empty = -1;
            for (int slot = 9; slot < 36; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        && item.getItemMeta().getDisplayName().startsWith("SXCRAFT:")) {
                    throw new IllegalStateException("old craft fixture must be inspected before another prepare");
                }
                if (empty < 0 && ItemCompatibility.isEmpty(item)) empty = slot;
            }
            if (empty < 0) throw new IllegalStateException("ordinary inventory space required");
            Plugin probe = Bukkit.getPluginManager().getPlugin("SX-RPGInventory-Probe");
            PermissionAttachment attachment = player.addAttachment(probe, 12000);
            // OP is intentionally preserved; exact negative children override its default unlock permissions.
            ConfigurationSection extensions = Config.getConfig().getConfigurationSection("craft.extensions");
            if (extensions == null) throw new IllegalStateException("craft extensions missing");
            for (String key : extensions.getKeys(false)) attachment.setPermission("rpginventory.craft." + key, false);
            player.recalculatePermissions();
            ATTACHMENTS.put(player.getUniqueId(), attachment);
            RUNS.put(player.getUniqueId(), run);
            PacketSlotProbe.track(player);
            ItemStack ingredient = new ItemStack(ItemCompatibility.material("OAK_PLANKS"), 16);
            org.bukkit.inventory.meta.ItemMeta meta = ingredient.getItemMeta();
            meta.setDisplayName("SXCRAFT:" + run);
            ingredient.setItemMeta(meta);
            player.getInventory().setItem(empty, ingredient);
            // Publish a valid recipe display ID to modern clients, rather than testing rejection of an unknown recipe.
            player.discoverRecipe(new NamespacedKey("minecraft", "crafting_table"));
            player.updateInventory();
        } else if ("craft-open".equals(operation)) {
            requireRun(player, run);
            player.openWorkbench(null, true);
        } else if ("craft-sync".equals(operation)) {
            requireRun(player, run);
            player.updateInventory();
        } else if ("craft-slot-sync".equals(operation)) {
            requireRun(player, run);
            // A rejected grid click may use only WINDOW_ITEMS on modern servers; cover SET_SLOT separately.
            PacketSlotProbe.sendEmptyLockedSlot(player);
        } else if ("craft-reload".equals(operation)) {
            requireRun(player, run);
            player.closeInventory();
            // Exercise the public production command and its real cleanup/re-registration lifecycle.
            Bukkit.dispatchCommand(player, "rpginventory reload");
        } else if ("craft-release".equals(operation)) {
            release(player);
        } else if ("craft-disable".equals(operation)) {
            requireRun(player, run);
            release(player);
            Bukkit.getPluginManager().disablePlugin(RPGInventory.getInstance());
        } else if (!"craft-snapshot".equals(operation)) {
            throw new IllegalArgumentException("Unknown craft probe operation");
        }
        return snapshot(player, run);
    }

    private static void requireRun(Player player, String run) {
        if (!run.equals(RUNS.get(player.getUniqueId()))) throw new IllegalStateException("craft-prepare required for this run/player");
    }

    private static void release(Player player) {
        player.closeInventory();
        PermissionAttachment attachment = ATTACHMENTS.remove(player.getUniqueId());
        RUNS.remove(player.getUniqueId());
        PacketSlotProbe.release(player);
        if (attachment != null) player.removeAttachment(attachment);
        player.recalculatePermissions();
    }

    /** Server contents must stay real/empty while packet masks exist only in the client; counters prove each code path ran. */
    private static Map<String, Object> snapshot(Player player, String run) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pluginEnabled", RPGInventory.getInstance().isEnabled());
        result.put("loaded", InventoryManager.playerIsLoaded(player));
        result.put("attachmentActive", ATTACHMENTS.containsKey(player.getUniqueId()));
        Inventory top = InventoryViewCompatibility.top(player.getOpenInventory());
        result.put("windowType", top.getType().name());
        List<Integer> locked = new ArrayList<>();
        for (CraftExtension extension : CraftManager.getExtensions(player)) locked.addAll(extension.getSlots());
        result.put("lockedSlots", locked);
        List<Map<String, Object>> contents = new ArrayList<>();
        for (int slot = 0; slot < Math.min(top.getSize(), 10); slot++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            ItemStack item = top.getItem(slot);
            entry.put("slot", slot); entry.put("empty", ItemCompatibility.isEmpty(item));
            entry.put("type", ItemCompatibility.isEmpty(item) ? "AIR" : item.getType().name());
            entry.put("amount", ItemCompatibility.isEmpty(item) ? 0 : item.getAmount());
            contents.add(entry);
        }
        result.put("serverGrid", contents);
        List<Map<String, Object>> ingredients = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isIngredient(item, run)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("slot", slot); entry.put("amount", item.getAmount()); ingredients.add(entry);
            }
        }
        result.put("inventoryIngredients", ingredients);
        result.put("cursorIngredients", isIngredient(player.getItemOnCursor(), run) ? player.getItemOnCursor().getAmount() : 0);
        Class<?> adapter = Class.forName("ru.endlesscode.rpginventory.compat.packetevents.PacketEventsCraftIntegration", false,
                RPGInventory.getInstance().getClass().getClassLoader());
        result.put("packetCounters", adapter.getMethod("diagnostics").invoke(null));
        return result;
    }

    private static boolean isIngredient(ItemStack item, String run) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && ("SXCRAFT:" + run).equals(item.getItemMeta().getDisplayName());
    }
}
