package ru.endlesscode.rpginventory.compat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.Test;
import org.mockito.MockedStatic;
import ru.endlesscode.rpginventory.compat.ItemCompatibility;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Holder identity must survive metadata projection while legacy matching retains all nonvisual item constraints. */
public class InventoryPlaceholderTest {
    @Test public void fillerAndPurchaseRowsRemainDistinctDespiteIdenticalAppearance() {
        try (Tags tags = new Tags()) {
            InventoryPlaceholder fill = new InventoryPlaceholder("fill", new Stack());
            InventoryPlaceholder rowOne = new InventoryPlaceholder("buyable:1", new Stack());
            InventoryPlaceholder rowTwo = new InventoryPlaceholder("buyable:2", new Stack());
            InventoryPlaceholder namedLikeRole = new InventoryPlaceholder("slot:fill", new Stack());
            Stack projected = (Stack) rowOne.copy();
            projected.flags.clear();
            assertTrue(rowOne.matches(projected));
            assertFalse(rowTwo.matches(projected));
            assertFalse(fill.matches(projected));
            assertFalse(namedLikeRole.matches(fill.copy()));
            assertFalse(fill.matches(namedLikeRole.copy()));
            Stack projectedFill = (Stack) fill.copy();
            projectedFill.flags.clear();
            assertTrue(fill.matches(projectedFill));
        }
    }

    @Test public void oldFillerAndPurchaseTemplatesKeepFullMetadataConstraints() {
        try (Tags tags = new Tags()) {
            Stack old = new Stack();
            old.tags.put("locked", "0");
            InventoryPlaceholder purchase = new InventoryPlaceholder("buyable:1", old);
            Stack projected = old.clone();
            projected.flags.remove(ItemFlag.HIDE_ATTRIBUTES);
            assertTrue(purchase.matches(projected));
            projected.tags.remove("locked");
            assertFalse(purchase.matches(projected));
            InventoryPlaceholder fill = new InventoryPlaceholder("fill", new Stack());
            assertTrue(fill.matches(projected));
            projected.damage = 1;
            assertFalse(fill.matches(projected));
        }
    }

    @Test public void markedHolderSurvivesDifferentNativeMetadataRepresentation() {
        try (Tags tags = new Tags()) {
            InventoryPlaceholder holder = new InventoryPlaceholder("slot:ring", new Stack());
            Stack projected = (Stack) holder.copy();
            projected.flags.clear();
            assertNotEquals(holder.copy(), projected);
            assertTrue(holder.matches(projected));
            assertEquals("v1:slot:ring", projected.tags.get(InventoryPlaceholder.KEY));
        }
    }

    @Test public void markerFromAnotherSlotOrProtocolNeverFallsBackToVisualEquality() {
        try (Tags tags = new Tags()) {
            InventoryPlaceholder ring = new InventoryPlaceholder("slot:ring", new Stack());
            InventoryPlaceholder necklace = new InventoryPlaceholder("slot:necklace", new Stack());
            assertFalse(ring.matches(necklace.copy()));
            Stack unknown = (Stack) ring.copy();
            unknown.tags.put(InventoryPlaceholder.KEY, "v2:slot:ring");
            assertFalse(ring.matches(unknown));
        }
    }

    @Test public void matchingNameAndLoreDoNotTurnOrdinaryEquipmentIntoAHolder() {
        try (Tags tags = new Tags()) {
            InventoryPlaceholder holder = new InventoryPlaceholder("slot:ring", new Stack());
            Stack equipment = new Stack();
            equipment.tags.put("equipment-id", "real-ring");
            assertFalse(holder.matches(equipment));
            equipment.tags.clear();
            equipment.damage = 7;
            assertFalse(holder.matches(equipment));
            equipment.damage = 0;
            equipment.flags.remove(ItemFlag.HIDE_ENCHANTS);
            assertFalse(holder.matches(equipment));
        }
    }

    @Test public void legacyFallbackIgnoresOnlyHideAttributesWithoutMutatingCaller() {
        try (Tags tags = new Tags()) {
            Stack oldHolder = new Stack();
            InventoryPlaceholder holder = new InventoryPlaceholder("slot:ring", oldHolder);
            Stack projected = oldHolder.clone();
            projected.flags.remove(ItemFlag.HIDE_ATTRIBUTES);
            assertTrue(holder.matches(oldHolder));
            assertTrue(holder.matches(projected));
            assertTrue(oldHolder.flags.contains(ItemFlag.HIDE_ATTRIBUTES));
            assertFalse(projected.flags.contains(ItemFlag.HIDE_ATTRIBUTES));
            assertTrue(oldHolder.tags.isEmpty());
            assertTrue(projected.tags.isEmpty());
            assertFalse(holder.matches(null));
        }
    }

    /** Substitute persistent-key transport only; production holder policy and cloning execute unchanged. */
    private static final class Tags implements AutoCloseable {
        final MockedStatic<ItemCompatibility> bridge = mockStatic(ItemCompatibility.class, CALLS_REAL_METHODS);
        Tags() {
            bridge.when(() -> ItemCompatibility.setString(any(), eq(InventoryPlaceholder.KEY), anyString()))
                    .thenAnswer(call -> { ((Stack) call.getArgument(0)).tags.put(call.getArgument(1), call.getArgument(2)); return null; });
            bridge.when(() -> ItemCompatibility.getString(any(), eq(InventoryPlaceholder.KEY), isNull()))
                    .thenAnswer(call -> ((Stack) call.getArgument(0)).tags.get(InventoryPlaceholder.KEY));
        }
        @Override public void close() { bridge.close(); }
    }

    /** A detached Bukkit item contract without bootstrapping Paper's global registry; equality includes private data. */
    private static final class Stack extends ItemStack {
        final Map<String, String> tags = new HashMap<>();
        Set<ItemFlag> flags = EnumSet.of(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        final String name = "Ring slot";
        final String lore = "Place a ring here";
        int damage;

        Stack() { super(); }
        @Override public Material getType() { return Material.PAPER; }
        @Override public Stack clone() {
            Stack copy = new Stack();
            copy.tags.putAll(tags);
            copy.flags = EnumSet.noneOf(ItemFlag.class);
            copy.flags.addAll(flags);
            copy.damage = damage;
            return copy;
        }
        @Override public ItemMeta getItemMeta() {
            ItemMeta meta = mock(ItemMeta.class);
            Set<ItemFlag> metaFlags = EnumSet.noneOf(ItemFlag.class);
            metaFlags.addAll(flags);
            when(meta.hasItemFlag(any())).thenAnswer(call -> metaFlags.contains(call.getArgument(0)));
            when(meta.getItemFlags()).thenReturn(metaFlags);
            doAnswer(call -> { for (Object flag : call.getArguments()) metaFlags.remove(flag); return null; })
                    .when(meta).removeItemFlags(any(ItemFlag[].class));
            return meta;
        }
        @Override public boolean setItemMeta(ItemMeta meta) {
            flags = EnumSet.noneOf(ItemFlag.class);
            flags.addAll(meta.getItemFlags());
            return true;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Stack)) return false;
            Stack item = (Stack) other;
            return damage == item.damage && name.equals(item.name) && lore.equals(item.lore)
                    && flags.equals(item.flags) && tags.equals(item.tags);
        }
        @Override public int hashCode() { return Objects.hash(tags, flags, name, lore, damage); }
    }
}
