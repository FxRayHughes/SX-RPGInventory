package ru.endlesscode.rpginventory.compat;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import ru.endlesscode.rpginventory.RPGInventory;
import ru.endlesscode.rpginventory.utils.Log;

/** Keeps the optional, separately compiled legacy MyPet adapter outside the modern default dependency graph. */
public final class OptionalMyPetBridge {
    private static Class<?> adapter;
    private OptionalMyPetBridge() { }

    /** Older MyPet builds require private package credentials; enable their adapter explicitly at build time. */
    public static boolean init(RPGInventory plugin) {
        try {
            adapter = Class.forName("ru.endlesscode.rpginventory.compat.mypet.MyPetManager");
            return (boolean) adapter.getMethod("init", RPGInventory.class).invoke(null, plugin);
        } catch (ClassNotFoundException missing) {
            Log.w("MyPet adapter not included; build with -PwithMyPet=true to enable the legacy integration.");
            return false;
        } catch (ReflectiveOperationException | LinkageError failure) {
            Log.w(failure, "MyPet adapter is incompatible with the installed version");
            return false;
        }
    }

    /** A missing or failed adapter rejects pet swaps instead of consuming an item with no owner. */
    public static boolean validatePet(Player player, InventoryAction action, ItemStack current, ItemStack cursor) {
        if (adapter == null) return false;
        try {
            return (boolean) adapter.getMethod("validatePet", Player.class, InventoryAction.class, ItemStack.class, ItemStack.class)
                    .invoke(null, player, action, current, cursor);
        } catch (ReflectiveOperationException failure) {
            Log.w(failure, "MyPet swap failed");
            return false;
        }
    }
}
