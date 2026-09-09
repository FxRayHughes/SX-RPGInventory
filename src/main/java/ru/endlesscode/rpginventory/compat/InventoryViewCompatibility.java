package ru.endlesscode.rpginventory.compat;

import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Bridges InventoryView's class-to-interface change without embedding either invocation opcode. */
public final class InventoryViewCompatibility {
    private static final Method TOP = method("getTopInventory");
    private static final Method TYPE = method("getType");
    private static final Method ITEM = method("getItem", int.class);

    private InventoryViewCompatibility() { }

    /** The top inventory defines raw-slot boundaries on both legacy and current servers. */
    public static Inventory top(Object view) { return (Inventory) invoke(TOP, view); }

    /** Resolve the view's type without a direct call to a potentially incompatible interface. */
    public static InventoryType type(Object view) { return (InventoryType) invoke(TYPE, view); }

    /** Raw slots belong to the whole view, so they cannot be read from the top inventory alone. */
    public static ItemStack item(Object view, int rawSlot) { return (ItemStack) invoke(ITEM, view, rawSlot); }

    private static Method method(String name, Class<?>... arguments) {
        try {
            // Use the public Bukkit declaration, not a potentially nonpublic CraftBukkit implementation class.
            return Class.forName("org.bukkit.inventory.InventoryView").getMethod(name, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Bukkit InventoryView API: " + name, failure);
        }
    }

    private static Object invoke(Method method, Object view, Object... arguments) {
        try {
            return method.invoke(view, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("InventoryView operation failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke InventoryView operation: " + method.getName(), failure);
        }
    }
}
