package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmArgumentPolicyTest {

    private String savedWhitelist;
    private String savedIpType;

    @AfterEach
    void restoreConfig() {
        if (savedWhitelist != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST, savedWhitelist);
        }
        if (savedIpType != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_JVM_IP_TYPE, savedIpType);
        }
        System.clearProperty(JvmArgumentPolicy.PROP_PREFER_IPV4_STACK);
        System.clearProperty(JvmArgumentPolicy.PROP_PREFER_IPV6_ADDRESSES);
    }

    private void rememberAndSet(String whitelist, String ipType) {
        savedWhitelist = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST);
        savedIpType = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_JVM_IP_TYPE);
        if (whitelist != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST, whitelist);
        }
        if (ipType != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_JVM_IP_TYPE, ipType);
        }
    }

    @Test
    void hardcodedPreferIpPropertiesAreAllowed() {
        Set<String> exact = Collections.emptySet();
        List<String> prefixes = Collections.emptyList();
        assertTrue(JvmArgumentPolicy.isAllowed("-Djava.net.preferIPv4Stack=true", exact, prefixes));
        assertTrue(JvmArgumentPolicy.isAllowed("-Djava.net.preferIPv6Addresses=false", exact, prefixes));
        assertTrue(JvmArgumentPolicy.isHardcodedSecurePropertyArg("-Djava.net.preferIPv4Stack=true"));
    }

    @Test
    void configWhitelistIsAdditive() {
        rememberAndSet("-Darg1, -Dcustom.prop", null);
        Set<String> exact = new HashSet<>(Collections.singletonList("-client"));
        List<String> prefixes = Collections.singletonList("-Xmx");
        assertTrue(JvmArgumentPolicy.isAllowed("-client", exact, prefixes));
        assertTrue(JvmArgumentPolicy.isAllowed("-Xmx512m", exact, prefixes));
        assertTrue(JvmArgumentPolicy.isAllowed("-Darg1=value", exact, prefixes));
        assertTrue(JvmArgumentPolicy.isAllowed("-Dcustom.prop=x", exact, prefixes));
        assertFalse(JvmArgumentPolicy.isAllowed("-Dunknown=x", exact, prefixes));
    }

    @Test
    void parseCsvWhitelistTrimsEntries() {
        Set<String> parsed = JvmArgumentPolicy.parseCsvWhitelist(" -Dfoo , -Dbar,  ");
        assertEquals(newLinkedHashSetOf("-Dfoo", "-Dbar"), parsed);
    }

    @Test
    void whitelistKeyStopsAtFirstEquals() {
        assertEquals("-Dfoo", JvmArgumentPolicy.whitelistKeyForArgument("-Dfoo=bar=baz"));
        assertEquals("-XX:+Flag", JvmArgumentPolicy.whitelistKeyForArgument("-XX:+Flag"));
        assertEquals("-agentpath:C:\\x\\y.dll",
                JvmArgumentPolicy.whitelistKeyForArgument("-agentpath:C:\\x\\y.dll=delay=1"));
    }

    @Test
    void escapeForDeploymentPropertiesEscapesSpecials() {
        assertEquals("-agentpath\\:C\\:\\\\x\\\\y.dll",
                JvmArgumentPolicy.escapeForDeploymentProperties("-agentpath:C:\\x\\y.dll"));
        assertEquals("-Dfoo", JvmArgumentPolicy.escapeForDeploymentProperties("-Dfoo"));
    }

    @Test
    void formatWhitelistPropertyHintUsesEscapedKey() {
        String hint = JvmArgumentPolicy.formatWhitelistPropertyHint(
                "-agentpath:C:\\tools\\agent.dll=delay=1000");
        assertEquals(
                "deployment.jvm.arguments.whitelist=-agentpath\\:C\\:\\\\tools\\\\agent.dll",
                hint);
    }

    @Test
    void ipStackIpv4OverridesUserPreferFlags() {
        List<String> args = new ArrayList<>(Arrays.asList(
                "-Xmx256m",
                "-Djava.net.preferIPv4Stack=false",
                "-Djava.net.preferIPv6Addresses=true"));
        JvmArgumentPolicy.applyIpStack(args, "ipv4");
        assertEquals(Arrays.asList("-Xmx256m", "-Djava.net.preferIPv4Stack=true"), args);
    }

    @Test
    void ipStackIpv6OverridesUserPreferFlags() {
        List<String> args = new ArrayList<>(Arrays.asList(
                "-Djava.net.preferIPv4Stack=true"));
        JvmArgumentPolicy.applyIpStack(args, "ipv6");
        assertEquals(Arrays.asList(
                "-Djava.net.preferIPv4Stack=false",
                "-Djava.net.preferIPv6Addresses=true"), args);
    }

    @Test
    void ipStackAutoLeavesPreferFlagsUnset() {
        List<String> args = new ArrayList<>(Arrays.asList(
                "-Xmx128m",
                "-Djava.net.preferIPv4Stack=false"));
        JvmArgumentPolicy.applyIpStack(args, "auto");
        // user prefer flags removed; nothing re-injected
        assertEquals(Collections.singletonList("-Xmx128m"), args);
    }

    @Test
    void ipStackSystemPropertiesIpv4TakesPrecedence() {
        System.setProperty(JvmArgumentPolicy.PROP_PREFER_IPV4_STACK, "false");
        JvmArgumentPolicy.applyIpStackToSystemProperties("ipv4");
        assertEquals("true", System.getProperty(JvmArgumentPolicy.PROP_PREFER_IPV4_STACK));
    }

    @Test
    void normalizeIpTypeDefaultsUnknownToAuto() {
        assertEquals("auto", JvmArgumentPolicy.normalizeIpType(null));
        assertEquals("auto", JvmArgumentPolicy.normalizeIpType(""));
        assertEquals("auto", JvmArgumentPolicy.normalizeIpType("bogus"));
        assertEquals("ipv6", JvmArgumentPolicy.normalizeIpType("IPV6"));
        assertEquals("ipv4", JvmArgumentPolicy.normalizeIpType("ipv4"));
        assertEquals("auto", JvmArgumentPolicy.normalizeIpType(" Auto "));
    }

    @Test
    void ipStackAutoDoesNotSetSystemProperties() {
        System.clearProperty(JvmArgumentPolicy.PROP_PREFER_IPV4_STACK);
        System.clearProperty(JvmArgumentPolicy.PROP_PREFER_IPV6_ADDRESSES);
        JvmArgumentPolicy.applyIpStackToSystemProperties("auto");
        assertEquals(null, System.getProperty(JvmArgumentPolicy.PROP_PREFER_IPV4_STACK));
        assertEquals(null, System.getProperty(JvmArgumentPolicy.PROP_PREFER_IPV6_ADDRESSES));
    }

    private static Set<String> newLinkedHashSetOf(String... values) {
        return JvmArgumentPolicy.parseCsvWhitelist(String.join(",", values));
    }
}
