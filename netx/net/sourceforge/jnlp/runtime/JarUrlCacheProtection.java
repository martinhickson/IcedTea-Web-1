/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 *
 * ByteBuddy hook replacing URLJarFileCallBack on JDK 24+ (JEP / JDK-8323645).
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import net.sourceforge.jnlp.util.JavaVersionUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * On JDK 24+, {@code URLJarFileCallBack} was removed. This installs a ByteBuddy advice on
 * {@code URLJarFile.retrieve} so all jar: URL opens still go through ITW's
 * {@link CachedJarFileCallback#retrieve(java.net.URL)} (including {@code cacheJarFile} downloads).
 */
public final class JarUrlCacheProtection {

    private static final OutputController LOGGER = OutputController.getLogger();

    private static volatile boolean installAttempted;
    private static volatile boolean installed;

    private JarUrlCacheProtection() {
    }

    public static synchronized boolean install() {
        if (installAttempted) {
            return installed;
        }
        installAttempted = true;

        if (JavaVersionUtils.getRunningMajorVersion() < JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            return false;
        }

        try {
            Class.forName("net.bytebuddy.ByteBuddy");
            Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
            installByteBuddyInterceptor();
            installed = true;
            LOGGER.log(OutputController.Level.MESSAGE_ALL,
                    "[ITW] Jar URL cache protection installed via ByteBuddy on JDK "
                            + JavaVersionUtils.getRunningMajorVersion());
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                    "[ITW] ByteBuddy not available; jar: URLs on JDK 24+ may bypass ITW cache");
            return false;
        } catch (Exception e) {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                    "[ITW] Failed to install jar URL cache protection: " + e);
            LOGGER.log(OutputController.Level.ERROR_DEBUG, e);
            return false;
        }
    }

    public static boolean isActive() {
        return installed;
    }

    private static void installByteBuddyInterceptor() throws Exception {
        ByteBuddyAgent.install();
        BootstrapAdviceSupport.injectIntoBootstrap(
                JarUrlCacheBootstrapBridge.class,
                BootstrapUrlJarRetrieveAdvice.class);

        Class<?> urlJarFileClass = Class.forName("sun.net.www.protocol.jar.URLJarFile");
        new ByteBuddy()
                .redefine(urlJarFileClass)
                .visit(Advice.to(BootstrapUrlJarRetrieveAdvice.class)
                        .on(ElementMatchers.named("retrieve")))
                .make()
                .load(urlJarFileClass.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());

        JarUrlCacheBootstrapBridge.setHandler(
                url -> CachedJarFileCallback.getInstance().retrieve(url));
    }
}
