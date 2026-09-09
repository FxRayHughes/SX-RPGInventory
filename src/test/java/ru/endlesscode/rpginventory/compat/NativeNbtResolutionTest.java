package ru.endlesscode.rpginventory.compat;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

/** Prevents pre-component servers and remapped native methods from taking the component-only path. */
public class NativeNbtResolutionTest {
    /** Class availability is irrelevant: the component contract starts at 1.20.5. */
    @Test public void respectsComponentBoundary() throws Exception {
        Method selector = ItemCompatibility.class.getDeclaredMethod("usesComponentCodec");
        selector.setAccessible(true);
        try (MockedStatic<VersionHandler> versions = mockStatic(VersionHandler.class)) {
            for (int version : new int[] {11200, 11605, 11700, 12001, 12004}) {
                versions.when(VersionHandler::getVersionCode).thenReturn(version);
                assertEquals(false, selector.invoke(null));
            }
            for (int version : new int[] {12005, 12111, 260102}) {
                versions.when(VersionHandler::getVersionCode).thenReturn(version);
                assertEquals(true, selector.invoke(null));
            }
        }
    }

    /** SRG names must not prevent full item saves or DataVersion writes. */
    @Test public void resolvesRemappedDescriptors() throws Exception {
        Compound data = new Compound();
        Method save = ItemCompatibility.uniqueInstanceMethod(NativeItem.class, Compound.class, Compound.class);
        assertSame(data, save.invoke(new NativeItem(), data));
        Method put = ItemCompatibility.uniqueInstanceMethod(Compound.class, void.class, String.class, int.class);
        put.invoke(data, "DataVersion", 3465);
        assertEquals(3465, data.version);
    }

    /** A new native overload must fail closed instead of selecting an arbitrary mutation. */
    @Test(expected = NoSuchMethodException.class) public void rejectsAmbiguousDescriptors() throws Exception {
        ItemCompatibility.uniqueInstanceMethod(AmbiguousItem.class, Compound.class, Compound.class);
    }

    /** Static item factories must not be mistaken for instance save methods. */
    @Test(expected = NoSuchMethodException.class) public void rejectsStaticFactory() throws Exception {
        ItemCompatibility.uniqueInstanceMethod(Factory.class, Compound.class, Compound.class);
    }

    /** Models the SRG tag setter without linking a server implementation into unit tests. */
    public static class Compound {
        int version;
        /** A typed setter distinguishes DataVersion from other numeric NBT tags. */
        public void m_128405_(String key, int value) { version = value; }
    }

    /** Models the native full-compound save descriptor. */
    public static class NativeItem {
        /** Native save returns the compound it populated. */
        public Compound m_41739_(Compound data) { return data; }
    }

    /** Models a future mapping that no longer has a unique descriptor. */
    public static class AmbiguousItem extends NativeItem {
        /** This competing method must make reflection reject the class. */
        public Compound other(Compound data) { return data; }
    }

    /** Static factories have different semantics even with identical return and parameter types. */
    public static class Factory {
        /** A static factory cannot save the instance passed by the codec. */
        public static Compound create(Compound data) { return data; }
    }
}
