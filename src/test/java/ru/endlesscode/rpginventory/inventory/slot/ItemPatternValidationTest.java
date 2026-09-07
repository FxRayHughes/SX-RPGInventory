package ru.endlesscode.rpginventory.inventory.slot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/** Pure syntax regression cases run without a Bukkit server, plugin initialization or an installed SX-Item provider. */
@RunWith(Parameterized.class)
public class ItemPatternValidationTest {
    private final String pattern;
    private final boolean expected;

    /** Each syntax family exercises the same predicate used before a slot is registered. */
    public ItemPatternValidationTest(String pattern, boolean expected) {
        this.pattern = pattern;
        this.expected = expected;
    }

    /** Opaque SX IDs retain namespace separators and case; only the known framework prefix is recognized. */
    @Parameterized.Parameters(name = "{index}: {0} -> {1}")
    public static Collection<Object[]> patterns() {
        return Arrays.asList(new Object[][] {
                {"BOOK:100-200", true}, {"DIAMOND_HOE:23", true}, {"DIAMOND_HOE:1-999", true},
                {"IRON_SWORD", true}, {"ALL", true}, {"BOOK:100-", false}, {"BOOK:-100", false},
                {"BOOK:1-2-3", false}, {"BOOK:text", false}, {"BOOK:1:2", false},
                {"sxitem:SXRPGProbeRing", true}, {"SXITEM:SXRPGProbeRing", true},
                {"sxitem:pack:Ring", true}, {"sxitem:namespace:pack:Ring-v2", true},
                {"sxitem:戒指", true}, {"sxitem:", false}, {"sxitem:   ", false},
                {"sxitem:\u2003", false}, {"mmoitems:SWORD", false}, {"custom:namespace:item", false},
                {"", false}, {null, false}
        });
    }

    /** Assert the production validator directly so a future allow/deny registration change cannot bypass these cases. */
    @Test
    public void validatesBeforeSlotRegistration() {
        assertEquals(expected, SlotManager.isValidItemPattern(pattern));
    }
}
