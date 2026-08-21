/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.IpClassification;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.net.InetAddress;

/**
 * Weave of {@link InetAddress} so private literals skip reverse-DNS.
 * <p>
 * Uses the same ByteBuddy javaagent as {@link JarFileCloseProtection}. Does
 * not add a second {@code -javaagent} and does not call attach. On by
 * default ({@link DeploymentConfiguration#KEY_INETADDRESS_SKIP_REVERSE_DNS}).
 * Set that property to {@code false} to leave InetAddress unwoven.
 * <p>
 * Weaves every {@code getHostName} overload, including package-private
 * {@code getHostName(boolean)} used by {@code SocketPermission.getCanonName()},
 * plus {@code getCanonicalHostName()}.
 */
public final class InetAddressNameLookupSkip {

    private static volatile boolean installAttempted;
    private static volatile boolean installed;

    private InetAddressNameLookupSkip() {
    }

    public static void installFromConfiguration(DeploymentConfiguration config) {
        String value = config == null
                ? null
                : config.getProperty(DeploymentConfiguration.KEY_INETADDRESS_SKIP_REVERSE_DNS);
        if (!parseEnabled(value)) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "[ITW] InetAddress reverse-DNS skip off ("
                            + DeploymentConfiguration.KEY_INETADDRESS_SKIP_REVERSE_DNS
                            + "=false)");
            return;
        }
        install();
    }

    static boolean parseEnabled(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return "true".equalsIgnoreCase(trimmed)
                || "1".equals(trimmed)
                || "yes".equalsIgnoreCase(trimmed)
                || "on".equalsIgnoreCase(trimmed);
    }

    public static synchronized boolean install() {
        if (installAttempted) {
            return installed;
        }
        installAttempted = true;

        try {
            Class.forName("net.bytebuddy.ByteBuddy");
            Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
            ByteBuddyAgent.install();
            // Kind is a nested enum; bootstrap InetAddress cannot load it
            // from the application class loader (NoClassDefFoundError).
            BootstrapAdviceSupport.injectIntoBootstrap(
                    IpClassification.class, IpClassification.Kind.class);
            BootstrapAdviceSupport.adviseBootstrapMethods(
                    InetAddress.class,
                    BootstrapInetAddressNameAdvice.class,
                    "getHostName",
                    "getCanonicalHostName");
            installed = true;
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "[ITW] InetAddress reverse-DNS skip installed (private/loopback literals)");
            return true;
        } catch (ClassNotFoundException e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "[ITW] ByteBuddy not available; InetAddress reverse-DNS skip not installed");
            return false;
        } catch (Exception e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "[ITW] Failed to install InetAddress reverse-DNS skip: " + e);
            return false;
        }
    }

    public static boolean isActive() {
        return installed;
    }
}
