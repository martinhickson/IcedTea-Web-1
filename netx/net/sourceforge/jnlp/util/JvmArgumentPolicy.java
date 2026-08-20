package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Policy for {@code java-vm-args}: hardcoded allowlists, optional CSV deployment
 * whitelist, IP stack preference, and user-facing whitelist hints.
 */
public final class JvmArgumentPolicy {

    public static final String IP_TYPE_IPV4 = "ipv4";
    public static final String IP_TYPE_IPV6 = "ipv6";
    public static final String IP_TYPE_AUTO = "auto";

    public static final String PROP_PREFER_IPV4_STACK = "java.net.preferIPv4Stack";
    public static final String PROP_PREFER_IPV6_ADDRESSES = "java.net.preferIPv6Addresses";

    private static final String D_PREFER_IPV4 = "-D" + PROP_PREFER_IPV4_STACK;
    private static final String D_PREFER_IPV6 = "-D" + PROP_PREFER_IPV6_ADDRESSES;

    /** Hardcoded {@code -D} property names allowed in {@code java-vm-args}. */
    private static final Set<String> HARDCODED_SECURE_PROPERTIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    PROP_PREFER_IPV4_STACK,
                    PROP_PREFER_IPV6_ADDRESSES
            )));

    private JvmArgumentPolicy() {
    }

    public static boolean isAllowed(String argument, Set<String> exactHardcoded,
            List<String> prefixHardcoded) {
        if (argument == null || argument.isBlank()) {
            return false;
        }
        if (exactHardcoded != null && exactHardcoded.contains(argument)) {
            return true;
        }
        if (prefixHardcoded != null) {
            for (String prefix : prefixHardcoded) {
                if (argument.startsWith(prefix)) {
                    return true;
                }
            }
        }
        if (isHardcodedSecurePropertyArg(argument)) {
            return true;
        }
        return isWhitelistedConfigArgument(argument);
    }

    public static boolean isHardcodedSecurePropertyArg(String argument) {
        if (argument == null || !argument.startsWith("-D") || argument.length() <= 2) {
            return false;
        }
        int eq = argument.indexOf('=');
        String name = eq < 0 ? argument.substring(2) : argument.substring(2, eq);
        return HARDCODED_SECURE_PROPERTIES.contains(name);
    }

    public static boolean isWhitelistedConfigArgument(String argument) {
        if (argument == null || argument.isBlank()) {
            return false;
        }
        String key = whitelistKeyForArgument(argument);
        return getConfigWhitelist().contains(key);
    }

    /**
     * Token matched against the whitelist: everything before the first {@code =},
     * or the full argument when there is no equals.
     */
    public static String whitelistKeyForArgument(String argument) {
        if (argument == null) {
            return "";
        }
        int eq = argument.indexOf('=');
        return eq < 0 ? argument : argument.substring(0, eq);
    }

    /**
     * Escape a whitelist token for a {@code deployment.properties} value
     * ({@code \}, {@code =}, {@code :}, leading/significant whitespace, {@code #}, {@code !}).
     */
    public static String escapeForDeploymentProperties(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(token.length() + 8);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\\' || c == '=' || c == ':' || c == '#' || c == '!'
                    || c == ' ' || c == '\t' || c == '\f') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String formatWhitelistPropertyHint(String argument) {
        String key = whitelistKeyForArgument(argument);
        return DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST + "="
                + escapeForDeploymentProperties(key);
    }

    public static void logUnsupportedVmArg(String argument) {
        String hint = formatWhitelistPropertyHint(argument);
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "Ignoring unsupported java-vm-args entry: " + argument
                        + System.lineSeparator()
                        + "To allow it, add this token to the CSV property "
                        + DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST
                        + " in deployment.properties (escape \\, =, and : as shown):"
                        + System.lineSeparator()
                        + hint);
    }

    public static Set<String> getConfigWhitelist() {
        String raw = null;
        try {
            raw = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_JVM_ARGS_WHITELIST);
        } catch (RuntimeException ex) {
            // configuration may be unavailable in some unit-test contexts
            return Collections.emptySet();
        }
        return parseCsvWhitelist(raw);
    }

    public static Set<String> parseCsvWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public static String getConfiguredIpType() {
        String raw = null;
        try {
            raw = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_JVM_IP_TYPE);
        } catch (RuntimeException ex) {
            return IP_TYPE_IPV4;
        }
        return normalizeIpType(raw);
    }

    public static String normalizeIpType(String raw) {
        if (raw == null || raw.isBlank()) {
            return IP_TYPE_IPV4;
        }
        String v = raw.trim().toLowerCase(Locale.ENGLISH);
        if (IP_TYPE_IPV6.equals(v) || IP_TYPE_AUTO.equals(v) || IP_TYPE_IPV4.equals(v)) {
            return v;
        }
        return IP_TYPE_IPV4;
    }

    /**
     * Apply deployment IP stack preference to a mutable JVM-arg list.
     * Removes any user {@code -Djava.net.preferIPv*} entries, then injects the
     * configured values unless type is {@code auto}.
     *
     * @return the same list instance
     */
    public static List<String> applyConfiguredIpStack(List<String> vmArgs) {
        return applyIpStack(vmArgs, getConfiguredIpType());
    }

    public static List<String> applyIpStack(List<String> vmArgs, String ipType) {
        List<String> args = vmArgs != null ? vmArgs : new ArrayList<>();
        removePreferIpArgs(args);
        String type = normalizeIpType(ipType);
        if (IP_TYPE_AUTO.equals(type)) {
            return args;
        }
        if (IP_TYPE_IPV6.equals(type)) {
            args.add(D_PREFER_IPV4 + "=false");
            args.add(D_PREFER_IPV6 + "=true");
        } else {
            // ipv4 (default)
            args.add(D_PREFER_IPV4 + "=true");
        }
        return args;
    }

    /**
     * Apply deployment IP stack preference as system properties for in-process runs.
     * Takes precedence over any previously set prefer-IP system properties.
     */
    public static void applyConfiguredIpStackToSystemProperties() {
        applyIpStackToSystemProperties(getConfiguredIpType());
    }

    public static void applyIpStackToSystemProperties(String ipType) {
        String type = normalizeIpType(ipType);
        if (IP_TYPE_AUTO.equals(type)) {
            return;
        }
        if (IP_TYPE_IPV6.equals(type)) {
            System.setProperty(PROP_PREFER_IPV4_STACK, "false");
            System.setProperty(PROP_PREFER_IPV6_ADDRESSES, "true");
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "Applied deployment.jvm.ip.type=ipv6 ("
                            + PROP_PREFER_IPV4_STACK + "=false, "
                            + PROP_PREFER_IPV6_ADDRESSES + "=true)");
        } else {
            System.setProperty(PROP_PREFER_IPV4_STACK, "true");
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "Applied deployment.jvm.ip.type=ipv4 ("
                            + PROP_PREFER_IPV4_STACK + "=true)");
        }
    }

    private static void removePreferIpArgs(List<String> args) {
        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            if (arg == null) {
                continue;
            }
            if (arg.startsWith(D_PREFER_IPV4 + "=") || arg.equals(D_PREFER_IPV4)
                    || arg.startsWith(D_PREFER_IPV6 + "=") || arg.equals(D_PREFER_IPV6)) {
                it.remove();
            }
        }
    }
}
