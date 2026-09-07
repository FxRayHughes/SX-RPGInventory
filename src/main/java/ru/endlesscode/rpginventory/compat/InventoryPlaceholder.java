package ru.endlesscode.rpginventory.compat;

import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Identifies plugin-owned empty-slot items independently of the server's visual ItemMeta projection. */
public final class InventoryPlaceholder {
    // Persistent protocol: namespaced STRING on PDC servers, literal NBT STRING on 1.12.
    // Values are v1:slot:<config name>, v1:fill, or v1:buyable:<line>; kind prefixes prevent cross-role collisions.
    static final String KEY = "rpginventory_placeholder";
    private final String identity;
    private final ItemStack prototype;
    private final ItemStack legacy;

    /** Keep an unmarked full template for upgrades and mark only a detached newly generated placeholder. */
    public InventoryPlaceholder(String role, ItemStack item) {
        identity = "v1:" + role;
        legacy = withoutUnstableVisualFlag(item);
        prototype = item.clone();
        ItemCompatibility.setString(prototype, KEY, identity);
    }

    /** Never expose the prototype to callers that can mutate an inventory item. */
    public ItemStack copy() { return prototype.clone(); }

    /** Explicit role markers take precedence over the conservative fallback for old unmarked placeholders. */
    public boolean matches(ItemStack item) {
        if (ItemCompatibility.isEmpty(item)) return false;
        String marker = ItemCompatibility.getString(item, KEY, null);
        // A holder from another configured slot must never enter the legacy fallback, even if both look identical.
        if (marker != null) return identity.equals(marker);
        // Pre-marker quickbar holders may survive an upgrade; compare the complete template, never just text.
        return legacy.equals(withoutUnstableVisualFlag(item));
    }

    private static ItemStack withoutUnstableVisualFlag(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null && meta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES)) {
            // Paper 1.20.6 can discard only this presentation flag during native projection. All other data stays exact.
            meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            copy.setItemMeta(meta);
        }
        return copy;
    }
}
