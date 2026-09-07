package ru.endlesscode.rpginventory.probe;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.endlesscode.rpginventory.compat.InventoryViewCompatibility;
import ru.endlesscode.rpginventory.compat.ServerCompatibility;
import ru.endlesscode.rpginventory.compat.SXItemBridge;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackHolder;
import ru.endlesscode.rpginventory.inventory.backpack.BackpackManager;
import ru.endlesscode.rpginventory.storage.PlayerStorage;
import ru.endlesscode.rpginventory.utils.ItemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disposable-player fixtures and read-only assertions; inventory transfers still arrive as real client clicks. */
public final class E2ECommands {
    private static final Gson JSON = new Gson();
    private static final String PLAYER = "SXRPGTest";
    // Minecraft limits account names to 16 characters; short suffixes isolate each backend/version run.
    private static final String PLAYER_PATTERN = "^SXRPGTest[A-Za-z0-9_]{0,7}$";
    // Stable private metadata verifies that serialization preserves identity beyond the visible display name.
    private static final String RUN_TAG = "sxrpg-e2e-run";

    private E2ECommands() { }

    /** Accept only the isolated test account with explicit authority, or the owning server console. */
    public static boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0 || !"e2e".equalsIgnoreCase(args[0])) return false;
        boolean console = sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
        if (!console && (!(sender instanceof Player) || !sender.getName().matches(PLAYER_PATTERN)
                || !(sender.isOp() || sender.hasPermission("sxrpgprobe.e2e")))) {
            sender.sendMessage("SXRPG_E2E {\"error\":\"unauthorized test account\"}");
            return true;
        }
        String run = args.length > 2 ? args[2] : "";
        String label = args.length > 3 ? args[3] : args.length > 1 ? args[1] : "unknown";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run", run);
        response.put("label", label);
        try {
            if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("main thread required");
            if (args.length < 3 || !run.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid test run id");
            Player player = sender instanceof Player ? (Player) sender : Bukkit.getPlayerExact(PLAYER);
            if (player == null) throw new IllegalStateException("test player is offline");
            EquipmentEventProbe.start();
            InventoryEventProbe.start();
            String operation = args[1];
            if (operation.startsWith("craft-")) {
                response.putAll(PacketE2ECommands.handle(player, operation, run));
                sender.sendMessage("SXRPG_E2E " + JSON.toJson(response));
                return true;
            }
            if (!InventoryManager.playerIsLoaded(player)) throw new IllegalStateException("player storage not ready");
            if ("seed".equals(operation)) seed(player, run, args.length > 4 ? args[4] : null);
            else if ("arm-kick".equals(operation)) {
                KickAfterDeposit.arm(player, run);
                response.put("kickArmed", true);
            }
            else if ("open".equals(operation)) InventoryManager.get(player).openInventory();
            else if ("backpack".equals(operation)) {
                ItemStack backpack = find(player, run, "backpack");
                if (backpack == null || !BackpackManager.open(player, backpack)) throw new IllegalStateException("backpack open rejected");
            } else if (!"snapshot".equals(operation)) throw new IllegalArgumentException("unknown e2e operation");
            response.putAll(snapshot(player, run));
        } catch (Exception failure) {
            response.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        sender.sendMessage("SXRPG_E2E " + JSON.toJson(response));
        return true;
    }

    /** Seed once per persistent run; retries must never create a second physical copy after reconnect/restart. */
    private static void seed(Player player, String run, String sxItem) throws Exception {
        // Do not hide a failed prior run behind another ID, including a backpack whose payload is currently closed.
        for (Inventory inventory : Arrays.asList(player.getInventory(), InventoryManager.get(player).getInventory(),
                InventoryViewCompatibility.top(player.getOpenInventory()))) {
            for (ItemStack item : inventory.getContents()) {
                if (isFixture(item)) throw new IllegalStateException("existing test fixtures require inspection before reseeding");
            }
        }
        if (isFixture(player.getItemOnCursor())) throw new IllegalStateException("test fixture remains on cursor");
        List<Integer> emptyStorage = new ArrayList<>();
        for (int slot = 9; slot < 36; slot++) {
            if (ItemUtils.isEmpty(player.getInventory().getItem(slot))) emptyStorage.add(slot);
        }
        if (emptyStorage.size() < 3) throw new IllegalStateException("three empty non-hotbar slots required");
        int emptyHotbar = -1;
        for (int slot = 8; slot >= 0; slot--) {
            if (ItemUtils.isEmpty(player.getInventory().getItem(slot))) { emptyHotbar = slot; break; }
        }
        if (emptyHotbar < 0) throw new IllegalStateException("empty hotbar slot required for attribute baseline");
        player.getInventory().setHeldItemSlot(emptyHotbar);
        ItemStack ring = sxItem == null ? new ItemStack(Material.DIAMOND_HOE) : SXItemBridge.generate(sxItem, player);
        // Default rings accept this legacy texture discriminator on every target; it is a PASSIVE slot with no armor mirror.
        ring.setDurability((short) 23);
        ItemStack payload = new ItemStack(Material.DIAMOND, 7);
        ItemStack backpack = BackpackManager.getItem("small");
        if (backpack.getType() == Material.AIR) throw new IllegalStateException("small backpack type missing");
        Path receipt = Bukkit.getPluginManager().getPlugin("SX-RPGInventory-Probe").getDataFolder().toPath()
                .resolve("e2e-seeds").resolve(run + ".txt");
        Files.createDirectories(receipt.getParent());
        Files.write(receipt, player.getUniqueId().toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        int fixtureIndex = 0;
        for (Map.Entry<String, ItemStack> entry : fixtureMap(ring, payload, backpack).entrySet()) {
            ItemStack item = entry.getValue();
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("SXRPG:" + run + ":" + entry.getKey());
            // A deterministic lore attribute exposes missed/double SX-Attribute refresh as 20 -> 27 -> 20.
            if ("ring".equals(entry.getKey())) meta.setLore(Arrays.asList("生命上限: 7"));
            item.setItemMeta(meta);
            ItemUtils.setTag(item, RUN_TAG, run);
            // Never seed into the selected hand: SX-Attribute scans main-hand lore independently of RPG equipment.
            player.getInventory().setItem(emptyStorage.get(fixtureIndex++), item);
        }
        player.updateInventory();
    }

    private static Map<String, ItemStack> fixtureMap(ItemStack ring, ItemStack payload, ItemStack backpack) {
        Map<String, ItemStack> map = new LinkedHashMap<>();
        map.put("ring", ring); map.put("payload", payload); map.put("backpack", backpack);
        return map;
    }

    /** Only passive fixtures are counted, so player armor/active-slot mirrors cannot produce false duplicate alarms. */
    private static Map<String, Object> snapshot(Player player, String run) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String key : Arrays.asList("ring", "payload", "backpack")) totals.put(key, 0);
        out.put("loaded", InventoryManager.playerIsLoaded(player));
        out.put("sessionActive", PlayerStorage.isActive(player.getUniqueId()));
        out.put("equipmentEvents", EquipmentEventProbe.snapshot(player));
        // Read-only evidence separates client prediction errors from missing storage wrappers and cancelled clicks.
        out.put("inventoryDiagnostics", InventoryEventProbe.snapshot(player));
        out.put("selectedHotbar", player.getInventory().getHeldItemSlot());
        out.put("maxHealth", player.getAttribute(ServerCompatibility.attribute("MAX_HEALTH")).getValue());
        out.put("sxAttributeEnabled", Bukkit.getPluginManager().isPluginEnabled("SX-Attribute"));
        out.put("sxItemEnabled", Bukkit.getPluginManager().isPluginEnabled("SX-Item"));
        out.put("playerItems", items(player.getInventory().getStorageContents(), run, totals));
        out.put("rpgItems", items(InventoryManager.get(player).getInventory().getContents(), run, totals));
        out.put("cursor", items(new ItemStack[]{player.getItemOnCursor()}, run, totals));
        Inventory top = InventoryViewCompatibility.top(player.getOpenInventory());
        boolean backpackOpen = top.getHolder() instanceof BackpackHolder;
        out.put("backpackOpen", backpackOpen);
        out.put("openBackpackItems", backpackOpen ? items(top.getContents(), run, totals) : new ArrayList<>());
        out.put("canonicalCounts", totals);
        ItemStack backpack = find(player, run, "backpack");
        out.put("backpackUuid", backpack == null ? "" : ItemUtils.getTag(backpack, ItemUtils.BACKPACK_UID_TAG));
        return out;
    }

    private static List<Map<String, Object>> items(ItemStack[] contents, String run, Map<String, Integer> counts) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            String role = role(item, run);
            if (role == null) continue;
            counts.put(role, counts.getOrDefault(role, 0) + item.getAmount());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", slot); entry.put("role", role); entry.put("amount", item.getAmount());
            entry.put("type", item.getType().name()); entry.put("tagValid", run.equals(ItemUtils.getTag(item, RUN_TAG)));
            entry.put("sxItemIdentity", SXItemBridge.identity(item));
            entries.add(entry);
        }
        return entries;
    }

    private static String role(ItemStack item, String run) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return null;
        String name = item.getItemMeta().getDisplayName();
        String prefix = "SXRPG:" + run + ":";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : null;
    }

    private static boolean isFixture(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().startsWith("SXRPG:");
    }

    private static ItemStack find(Player player, String run, String wanted) {
        for (Inventory inventory : Arrays.asList(player.getInventory(), InventoryManager.get(player).getInventory())) {
            for (ItemStack item : inventory.getContents()) if (wanted.equals(role(item, run))) return item;
        }
        return null;
    }
}
