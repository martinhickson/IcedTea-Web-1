package net.sourceforge.jnlp.runtime;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.sourceforge.jnlp.config.Defaults;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.IpClassification;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InetAddressNameLookupSkipTest {

    @Test
    void reverseDnsSkipDefaultsOn() {
        assertEquals("true", Defaults.getDefaults()
                .get(DeploymentConfiguration.KEY_INETADDRESS_SKIP_REVERSE_DNS)
                .getDefaultValue());
    }

    @Test
    void parseEnabledRecognizesTruthyValues() {
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

    @Test
    void bootstrapInjectIncludesIpClassificationKind() {
        List<Class<?>> types = BootstrapAdviceSupport.expandWithNestedClasses(IpClassification.class);
        assertTrue(types.contains(IpClassification.class));
        assertTrue(types.contains(IpClassification.Kind.class),
                "Kind must be bootstrap-injected or InetAddress.getHostName NCDFE");
    }

    @Test
    void kindIsVisibleFromBootstrapAfterInject() throws Exception {
        try {
            ByteBuddyAgent.install();
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "ByteBuddy agent attach unavailable: " + t);
        }
        BootstrapAdviceSupport.injectIntoBootstrap(IpClassification.class);
        Class<?> fromBootstrap = Class.forName(
                "net.sourceforge.jnlp.util.IpClassification$Kind", false, null);
        assertEquals("Kind", fromBootstrap.getSimpleName());
    }
}
