package ru.endlesscode.rpginventory.compat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Covers both Paper's historical and 26.x release naming, including multi-digit patch numbers. */
public class ModernVersionTest {
    @Test public void parsesPaperVersionSchemes() {
        assertEquals(260100, VersionHandler.parseVersionCode("26.1"));
        assertEquals(260102, VersionHandler.parseVersionCode("26.1.2.build.74-stable"));
        assertEquals(260200, VersionHandler.parseVersionCode("26.2-R0.1-SNAPSHOT"));
        assertEquals(12111, VersionHandler.parseVersionCode("1.21.11-R0.1-SNAPSHOT"));
        assertEquals(0, VersionHandler.parseVersionCode("unknown"));
    }
}
