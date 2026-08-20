package net.sourceforge.jnlp.runtime;

import java.lang.reflect.Method;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InetAddressNameLookupSkipTest {

    @Test
    void parseEnabledIsOptIn() {
        assertFalse(InetAddressNameLookupSkip.parseEnabled(null));
        assertFalse(InetAddressNameLookupSkip.parseEnabled(""));
        assertFalse(InetAddressNameLookupSkip.parseEnabled("  "));
        assertFalse(InetAddressNameLookupSkip.parseEnabled("false"));
        assertFalse(InetAddressNameLookupSkip.parseEnabled("FALSE"));
        assertFalse(InetAddressNameLookupSkip.parseEnabled("0"));
        assertFalse(InetAddressNameLookupSkip.parseEnabled("off"));
        assertTrue(InetAddressNameLookupSkip.parseEnabled("true"));
        assertTrue(InetAddressNameLookupSkip.parseEnabled("TRUE"));
        assertTrue(InetAddressNameLookupSkip.parseEnabled("on"));
        assertTrue(InetAddressNameLookupSkip.parseEnabled("1"));
        assertTrue(InetAddressNameLookupSkip.parseEnabled(" yes "));
    }

    /**
     * SocketPermission.getCanonName() calls package-private
     * {@code getHostName(boolean)}. Matching only the no-arg public method
     * misses that path.
     */
    @Test
    void inetAddressHasHostNameBooleanOverload() throws Exception {
        Method method = InetAddress.class.getDeclaredMethod("getHostName", boolean.class);
        assertEquals(String.class, method.getReturnType());
    }
}
