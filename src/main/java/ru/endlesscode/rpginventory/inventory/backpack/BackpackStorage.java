package ru.endlesscode.rpginventory.inventory.backpack;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.inventory.InventoryManager;
import ru.endlesscode.rpginventory.misc.serialization.Serialization;
import ru.endlesscode.rpginventory.storage.PersistenceModule;
import ru.endlesscode.rpginventory.storage.StorageKey;
import ru.endlesscode.rpginventory.storage.StorageSession;
import ru.endlesscode.rpginventory.storage.StorageService;
import ru.endlesscode.rpginventory.utils.ItemUtils;
import ru.endlesscode.rpginventory.utils.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lazy portable-backpack persistence. One local viewer and one backend lease own each backpack UUID. */
public final class BackpackStorage {
    private static final Map<UUID, StorageSession> sessions = new HashMap<>();
    private static final Map<UUID, Backpack> backpacks = new HashMap<>();
    // Per-open generations stop pre-reload callbacks from installing old type definitions or clearing a newer request.
    private static final Map<UUID, UUID> pending = new HashMap<>();
    private BackpackStorage() { }

    /** Reserve before loading; two physical items with the same UUID cannot open separate writable views. */
    public static boolean open(Player player, BackpackType type, UUID id) {
        if (!InventoryManager.playerIsLoaded(player) || sessions.containsKey(id) || pending.containsKey(id)) {
            player.sendMessage("§cThis backpack is already open or still loading.");
            return false;
        }
        UUID generation = UUID.randomUUID();
        pending.put(id, generation);
        StorageService service = PersistenceModule.service();
        java.nio.file.Path file = RPGInventory.getInstance().getDataPath().resolve("backpacks").resolve(id + ".bp");
        service.acquire(new StorageKey(StorageKey.Kind.BACKPACK, id)).whenComplete((session, failure) -> {
            if (failure != null) { failed(player, id, generation, failure); return; }
            byte[] bytes = session.initialPayload();
            if (bytes == null) {
                service.readLegacy(session, file).whenComplete((legacy, error) -> {
                    if (error != null) { service.release(session); failed(player, id, generation, error); }
                    else PersistenceModule.main(() -> restore(player, type, id, generation, service, session, legacy));
                });
            } else PersistenceModule.main(() -> restore(player, type, id, generation, service, session, bytes));
        });
        return true;
    }

    private static void restore(Player player, BackpackType type, UUID id, UUID generation,
                                StorageService service, StorageSession session, byte[] bytes) {
        try {
            if (!generation.equals(pending.get(id)) || !canOpen(player, id) || !session.isActive()) {
                pending.remove(id, generation);
                service.release(session);
                return;
            }
            Backpack backpack = bytes == null ? type.createBackpack(id) : Serialization.decode(bytes, Backpack.class);
            if (!backpack.getUniqueId().equals(id) || !backpack.getType().getId().equals(type.getId())) {
                throw new IllegalArgumentException("Backpack identity does not match the saved record");
            }
            if (session.initialPayload() == null) {
                byte[] payload = Serialization.encode(backpack);
                service.save(session, payload, false).whenComplete((ignored, failure) -> {
                    if (failure != null) failed(player, id, generation, failure);
                    else PersistenceModule.main(() -> install(player, id, generation, service, session, backpack));
                });
            } else install(player, id, generation, service, session, backpack);
        } catch (Exception failure) {
            service.release(session);
            failed(player, id, generation, failure);
        }
    }

    private static void install(Player player, UUID id, UUID generation, StorageService service,
                                StorageSession session, Backpack backpack) {
        if (!pending.remove(id, generation) || !canOpen(player, id) || !session.isActive()) {
            service.release(session);
            return;
        }
        sessions.put(id, session);
        backpacks.put(id, backpack);
        try {
            if (backpack.open(player)) return;
        } catch (RuntimeException failure) {
            Log.w(failure, "Backpack view could not be opened");
        }
        // A cancelled or throwing open never leaves an invisible, permanently leased local backpack.
        if (sessions.get(id) == session) {
            sessions.remove(id, session);
            backpacks.remove(id, backpack);
            service.release(session);
        }
    }

    /** Cancel unopened requests before reloading type definitions; active views are closed by player unload. */
    public static void cancelPendingLoads() { pending.clear(); }

    /** After player views close, retained backpacks indicate a snapshot failure; their type definitions must survive. */
    public static boolean hasRetainedState() { return !backpacks.isEmpty(); }

    /** Only the current carrier may finish an asynchronous open after the item was moved or traded. */
    private static boolean canOpen(Player player, UUID id) {
        if (!player.isOnline() || !InventoryManager.playerIsLoaded(player)) return false;
        if (InventoryManager.get(player).getBackpack() != null) return false;
        for (ItemStack item : player.getInventory().getContents()) if (matches(item, id)) return true;
        for (ItemStack item : InventoryManager.get(player).getInventory().getContents()) if (matches(item, id)) return true;
        return false;
    }

    private static boolean matches(ItemStack item, UUID id) {
        return ItemUtils.isNotEmpty(item) && id.toString().equals(ItemUtils.getTag(item, ItemUtils.BACKPACK_UID_TAG));
    }

    /** Snapshot close contents synchronously before releasing the lease through an ordered final save. */
    public static void close(Backpack backpack, ItemStack[] contents) {
        backpack.setContents(contents);
        backpack.onUse();
        save(backpack, true);
    }

    /** Autosave only active backpacks; no unbounded startup scan or resident cache of every backpack. */
    public static void saveAll() {
        for (Backpack backpack : backpacks.values()) save(backpack, false);
    }

    private static void save(Backpack backpack, boolean release) {
        UUID id = backpack.getUniqueId();
        StorageSession session = sessions.get(id);
        if (session == null) return;
        try {
            byte[] bytes = Serialization.encode(backpack);
            PersistenceModule.service().save(session, bytes, release).whenComplete((ignored, failure) -> {
                if (failure != null) Log.w(failure, "Backpack save was not acknowledged");
            });
            if (release) { sessions.remove(id); backpacks.remove(id); }
        } catch (Exception failure) {
            Log.w(failure, "Cannot serialize backpack; keeping its live data for recovery");
            PersistenceModule.service().serializationFailed(session, failure);
        }
    }

    /** Event handlers must reject edits after lease expiry, even before the next heartbeat reports it. */
    public static boolean isActive(Backpack backpack) {
        if (backpack == null) return false;
        StorageSession session = sessions.get(backpack.getUniqueId());
        return session != null && session.isActive();
    }

    private static void failed(Player player, UUID id, UUID generation, Throwable failure) {
        Log.w(failure, "Backpack load failed; no replacement inventory was created");
        PersistenceModule.main(() -> {
            if (pending.remove(id, generation) && player.isOnline()) {
                player.sendMessage("§cBackpack unavailable or in use on another server. Please try again later.");
            }
        });
    }
}
