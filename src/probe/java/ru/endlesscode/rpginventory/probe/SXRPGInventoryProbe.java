package ru.endlesscode.rpginventory.probe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;
import ru.endlesscode.rpginventory.compat.SXItemBridge;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.misc.serialization.InventorySnapshot;
import ru.endlesscode.rpginventory.misc.serialization.ItemPayloadCodec;
import ru.endlesscode.rpginventory.misc.serialization.Serialization;
import ru.endlesscode.rpginventory.storage.PersistenceModule;
import ru.endlesscode.rpginventory.storage.StorageKey;
import ru.endlesscode.rpginventory.storage.StorageService;
import ru.endlesscode.rpginventory.storage.StorageSession;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Console-driven tests against real Bukkit objects; this companion is never included in the production JAR. */
public final class SXRPGInventoryProbe extends JavaPlugin {
    // Probe keys are private and stable so serialization validates all three persistent primitive types.
    private static final String STRING_KEY = "probe-string";
    private static final String LONG_KEY = "probe-long";
    private static final String DOUBLE_KEY = "probe-double";
    private static final String STRING_VALUE = "SX-RPGInventory probe \u80cc\u5305";
    private static final long LONG_VALUE = 9007199254740993L;
    private static final double DOUBLE_VALUE = 1234.125D;
    private final AtomicBoolean codecRunning = new AtomicBoolean();

    /** Tests start only from an explicit console command, after production dependency initialization. */
    @Override
    public void onEnable() {
        getLogger().info("SX_RPG_PROBE READY server=" + Bukkit.getVersion()
                + " commands=codec,player:<SXRPGTestName>,parallel");
    }

    /** Restrict mutable API probes to the console and explicitly named disposable test players. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // The separate E2E handler grants only the named disposable account its narrowly scoped fixture protocol.
        if (E2ECommands.handle(sender, args)) return true;
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("SX_RPG_PROBE FAIL console-only");
            return true;
        }
        if (!Bukkit.isPrimaryThread()) {
            getLogger().severe("SX_RPG_PROBE FAIL command was dispatched off the server thread");
            return true;
        }
        try {
            if (args.length == 1 && "parallel".equalsIgnoreCase(args[0])) {
                // Read-only startup diagnostics remain console-only and never unblock or alter another plugin.
                ParallelTaskProbe.report(sender);
                return true;
            }
            if (args.length == 1 && "codec".equalsIgnoreCase(args[0])) {
                if (!codecRunning.compareAndSet(false, true)) {
                    sender.sendMessage("SX_RPG_PROBE BUSY codec/storage check is still running");
                    return true;
                }
                try {
                    byte[] payload = codec();
                    storage(payload);
                } catch (Throwable failure) {
                    codecRunning.set(false);
                    fail("codec", failure);
                    rethrowFatal(failure);
                }
                return true;
            }
            if (args.length == 2 && "player".equalsIgnoreCase(args[0])) {
                player(args[1]);
                return true;
            }
            return false;
        } catch (Throwable failure) {
            fail("player", failure);
            rethrowFatal(failure);
            return true;
        }
    }

    /** Build metadata on the real server, then exercise both the native and positional production codecs. */
    private byte[] codec() throws Exception {
        ItemStack original = new ItemStack(Material.DIAMOND_SWORD, 1);
        ItemMeta meta = original.getItemMeta();
        check(meta != null, "DIAMOND_SWORD has no ItemMeta");
        meta.setDisplayName("\u00a7bSX native metadata probe");
        meta.setLore(Arrays.asList("\u00a77Persistent lore", "\u00a7aUnicode: \u80cc\u5305"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.setUnbreakable(true);
        original.setItemMeta(meta);
        // Enchantment field names changed; resolve names without linking removed static constants.
        Enchantment enchantment = Enchantment.getByName("UNBREAKING");
        if (enchantment == null) enchantment = Enchantment.getByName("DURABILITY");
        check(enchantment != null, "No unbreaking enchantment exists");
        original.addUnsafeEnchantment(enchantment, 3);
        ItemCompatibility.setDamage(original, 37);
        ItemCompatibility.setString(original, STRING_KEY, STRING_VALUE);
        ItemCompatibility.setLong(original, LONG_KEY, LONG_VALUE);
        ItemCompatibility.setDouble(original, DOUBLE_KEY, DOUBLE_VALUE);
        assertItem(original, original, "before-serialize");

        byte[] nativeBytes = ItemCompatibility.serialize(original);
        check(nativeBytes.length > 0, "Native codec returned no bytes");
        ItemStack nativeCopy = ItemCompatibility.deserialize(nativeBytes, ItemCompatibility.usesModernSerialization());
        assertItem(original, nativeCopy, "native");

        List<ItemStack> layout = Arrays.asList(null, original, new ItemStack(Material.AIR), original.clone(), null);
        List<String> encoded = ItemPayloadCodec.encode(layout);
        check(encoded.size() == 5 && encoded.get(0) == null && encoded.get(2) == null && encoded.get(4) == null,
                "ItemPayloadCodec compacted or changed empty slots");
        List<ItemStack> decoded = ItemPayloadCodec.decode(encoded);
        check(decoded.size() == 5 && decoded.get(0) == null && decoded.get(2) == null && decoded.get(4) == null,
                "Decoded empty slot positions changed");
        assertItem(original, decoded.get(1), "slot-1");
        assertItem(original, decoded.get(3), "slot-3");
        pass("codec", "nativeBytes=" + nativeBytes.length + " damage=37 tags=string,long,double emptySlots=0,2,4"
                + " metadata=name,lore,enchantment,flags,unbreakable storage=PENDING");
        // Store only immutable bytes; the storage callback never receives or touches an ItemStack.
        return Serialization.encode(encoded);
    }

    /** Metadata equality plus explicit native keys catches lossy Bukkit-YAML fallbacks on legacy servers. */
    private static void assertItem(ItemStack expected, ItemStack actual, String stage) {
        try {
        check(actual != null, stage + ": item disappeared");
        check(expected.getType() == actual.getType(), stage + ": material changed");
        check(expected.getAmount() == actual.getAmount(), stage + ": amount changed");
        check(expected.getItemMeta().equals(actual.getItemMeta()), stage + ": complete ItemMeta differs");
        check(expected.getEnchantments().equals(actual.getEnchantments()), stage + ": enchantments differ");
        check(ItemCompatibility.getDamage(actual) == 37, stage + ": damage changed");
        check(STRING_VALUE.equals(ItemCompatibility.getString(actual, STRING_KEY, null)), stage + ": string tag lost");
        check(ItemCompatibility.getLong(actual, LONG_KEY, -1) == LONG_VALUE, stage + ": long tag precision lost");
        check(Double.compare(ItemCompatibility.getDouble(actual, DOUBLE_KEY, -1), DOUBLE_VALUE) == 0,
                stage + ": double tag lost");
        } catch (RuntimeException failure) {
            // This assertion is used only for the synthetic sword above, never for a real player's inventory.
            // Keep the original failure while exposing both native payloads for component/metadata normalization diagnosis.
            java.util.logging.Logger logger = java.util.logging.Logger.getLogger("SX-RPGInventory-Probe");
            logger.severe("SX_RPG_PROBE DIAGNOSTIC " + stage + " expected=" + describeSynthetic(expected));
            logger.severe("SX_RPG_PROBE DIAGNOSTIC " + stage + " actual=" + describeSynthetic(actual));
            throw failure;
        }
    }

    /** Dumps are restricted to generated codec fixtures; callers must never use this helper for player data. */
    private static String describeSynthetic(ItemStack item) {
        if (item == null) return "null";
        StringBuilder detail = new StringBuilder("type=").append(item.getType()).append(" amount=").append(item.getAmount());
        try {
            ItemMeta meta = item.getItemMeta();
            detail.append(" meta.serialize=").append(meta == null ? "null" : meta.serialize());
            detail.append(" meta.toString=").append(meta);
        } catch (RuntimeException failure) {
            detail.append(" metaDiagnosticFailure=").append(failure);
        }
        try {
            byte[] bytes = ItemCompatibility.serialize(item);
            detail.append(" nativeLength=").append(bytes.length);
            detail.append(" nativeBase64=").append(Base64.getEncoder().encodeToString(bytes));
        } catch (RuntimeException failure) {
            detail.append(" nativeDiagnosticFailure=").append(failure);
        }
        return detail.toString();
    }

    /** Use a fresh artificial BACKPACK key, never a PLAYER key or the UUID of an issued backpack item. */
    private void storage(byte[] payload) {
        final StorageService service = PersistenceModule.service();
        final StorageKey key = new StorageKey(StorageKey.Kind.BACKPACK,
                UUID.nameUUIDFromBytes(("SX-RPGInventory-Probe:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
        final AtomicReference<StorageSession> owned = new AtomicReference<>();
        getLogger().info("SX_RPG_PROBE START storage key=" + key.value() + " backend="
                + ru.endlesscode.rpginventory.RPGInventory.getInstance().getConfig().getString("storage.backend", "SQLITE"));
        CompletableFuture<Void> attempt = service.hasData(key).thenCompose(exists -> {
            check(!exists, "Fresh probe key unexpectedly exists; refusing to overwrite it");
            return service.acquire(key);
        }).thenCompose(session -> {
            owned.set(session);
            check(session.initialPayload() == null && session.isActive(), "Fresh probe record is not empty/owned");
            return service.save(session, payload, true);
        }).thenCompose(ignored -> {
            owned.set(null);
            return service.hasData(key);
        }).thenCompose(exists -> {
            check(exists, "Acknowledged probe payload is missing");
            return service.acquire(key);
        }).thenCompose(session -> {
            owned.set(session);
            check(session.isActive(), "Read-back ownership is inactive");
            check(Arrays.equals(payload, session.initialPayload()), "Storage read-back bytes differ");
            return service.release(session);
        }).thenRun(() -> owned.set(null));
        attempt.whenComplete((ignored, failure) -> {
            StorageSession session = owned.getAndSet(null);
            if (failure != null && session != null) {
                service.release(session).whenComplete((released, cleanupFailure) -> {
                    if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
                    finishStorage(key, payload.length, failure);
                });
            } else finishStorage(key, payload.length, failure);
        });
    }

    /** Logging is thread-safe; no future is joined on the Bukkit thread and no sender is used off-thread. */
    private void finishStorage(StorageKey key, int bytes, Throwable failure) {
        codecRunning.set(false);
        if (failure != null) fail("storage key=" + key.value(), failure);
        else pass("codec-storage", "key=" + key.value() + " bytes=" + bytes
                + " save/read/release=verified record=retained-isolated-probe");
    }

    /** Snapshot inspection never installs a replacement wrapper or mutates real players' equipment. */
    private void player(String name) throws Exception {
        check(name.startsWith("SXRPGTest"), "Only SXRPGTest-prefixed disposable players may be probed");
        Player player = Bukkit.getPlayerExact(name);
        check(player != null && player.isOnline(), "Test player is not online: " + name);
        check(InventoryManager.playerIsLoaded(player), "Test player's RPG inventory is not loaded/owned");
        InventorySnapshot snapshot = InventoryManager.get(player).createSnapshot();
        byte[] encoded = Serialization.encode(snapshot);
        InventorySnapshot restored = Serialization.decode(encoded, InventorySnapshot.class);
        check(Objects.equals(canonical(snapshot), canonical(restored)), "Player snapshot content changed on decode");
        pass("player-snapshot", "player=" + name + " bytes=" + encoded.length);

        Plugin itemPlugin = enabled("SX-Item");
        Object itemManager = itemPlugin.getClass().getMethod("getItemManager").invoke(null);
        check(itemManager != null, "SX-Item returned no public item manager");
        ItemStack[] items = InventoryManager.get(player).getInventory().getContents();
        int identities = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) items[i] = items[i].clone();
            if (!ItemCompatibility.isEmpty(items[i]) && SXItemBridge.identity(items[i]) != null) identities++;
        }
        SXItemBridge.update(player, items);
        pass("sx-item-api", "player=" + name + " pluginVersion=" + itemPlugin.getDescription().getVersion()
                + " identifiedRpgItems=" + identities + " update=invoked-on-clones");

        Plugin attributePlugin = enabled("SX-Attribute");
        Object manager = attributePlugin.getClass().getMethod("getAttributeManager").invoke(null);
        check(manager != null, "SX-Attribute returned no public attribute manager");
        manager.getClass().getMethod("loadEntityData", LivingEntity.class).invoke(manager, player);
        manager.getClass().getMethod("attributeUpdateEvent", LivingEntity.class).invoke(manager, player);
        Object data = manager.getClass().getMethod("getEntityData", LivingEntity.class).invoke(manager, player);
        check(data != null, "SX-Attribute returned no loaded player attribute data");
        double[][] attributes = (double[][]) data.getClass().getMethod("getValues").invoke(data);
        check(attributes != null, "SX-Attribute returned no public attribute values");
        int nonzero = 0;
        for (double[] group : attributes) {
            if (group == null) continue;
            for (double value : group) if (value != 0) nonzero++;
        }
        pass("player-api", "player=" + name + " SX-Attribute=" + attributePlugin.getDescription().getVersion()
                + " refresh/read=invoked dataType=" + data.getClass().getName()
                + " attributeGroups=" + attributes.length + " nonzeroValues=" + nonzero
                + " limitation=API-and-snapshot-only;clicks-and-attribute-deltas-not-tested");
    }

    /** Preserve persistent map/list structure while comparing deserialized objects that lack value equality. */
    private static Object canonical(Object value) {
        if (value instanceof ConfigurationSerializable) return canonical(((ConfigurationSerializable) value).serialize());
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) normalized.put(entry.getKey(), canonical(entry.getValue()));
            return normalized;
        }
        if (value instanceof List<?>) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : (List<?>) value) normalized.add(canonical(item));
            return normalized;
        }
        return value;
    }

    private Plugin enabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        check(plugin != null && plugin.isEnabled(), name + " is absent or disabled; integration is not verified");
        return plugin;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void pass(String stage, String detail) { getLogger().info("SX_RPG_PROBE PASS " + stage + " " + detail); }

    private void fail(String stage, Throwable failure) {
        getLogger().log(Level.SEVERE, "SX_RPG_PROBE FAIL " + stage, failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }
}
