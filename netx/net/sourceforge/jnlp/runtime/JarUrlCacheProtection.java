/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 *
 * ByteBuddy hook replacing URLJarFileCallBack on JDK 24+ (JEP / JDK-8323645).
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.asm.Advice;
import net.sourceforge.jnlp.util.JavaVersionUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * On JDK 24+, {@code URLJarFileCallBack} was removed. This installs a ByteBuddy advice on
 * {@code URLJarFile.retrieve} so all jar: URL opens still go through ITW's
 * {@link CachedJarFileCallback#retrieve(URL)} (including {@code cacheJarFile} downloads).
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
                    "[ITW] Failed to install jar URL cache protection: " + e.getMessage());
            LOGGER.log(OutputController.Level.ERROR_DEBUG, e);
            return false;
        }
    }

    public static boolean isActive() {
        return installed;
    }

    @SuppressWarnings("unchecked")
    private static void installByteBuddyInterceptor() throws Exception {
        Class<?> agentClass = Class.forName("net.bytebuddy.agent.ByteBuddyAgent");
        agentClass.getMethod("install").invoke(null);

        Class<?> urlJarFileClass = Class.forName("sun.net.www.protocol.jar.URLJarFile");

        Class<?> byteBuddyClass = Class.forName("net.bytebuddy.ByteBuddy");
        Object byteBuddy = byteBuddyClass.getDeclaredConstructor().newInstance();

        Class<?> matchersClass = Class.forName("net.bytebuddy.matcher.ElementMatchers");
        Object nameMatcher = matchersClass.getMethod("named", String.class).invoke(null, "retrieve");

        Class<?> adviceClass = Class.forName("net.bytebuddy.asm.Advice");
        Object advice = adviceClass.getMethod("to", Class.class).invoke(null, RetrieveAdvice.class);
        Object adviceOn = advice.getClass().getMethod("on",
                Class.forName("net.bytebuddy.matcher.ElementMatcher")).invoke(advice, nameMatcher);

        Object builder = byteBuddyClass.getMethod("redefine", Class.class).invoke(byteBuddy, urlJarFileClass);
        builder = builder.getClass().getMethod("visit",
                Class.forName("net.bytebuddy.asm.AsmVisitorWrapper")).invoke(builder, adviceOn);

        Object dynamicType = builder.getClass().getMethod("make").invoke(builder);

        Class<?> strategyClass = Class.forName("net.bytebuddy.dynamic.loading.ClassReloadingStrategy");
        Object strategy = strategyClass.getMethod("fromInstalledAgent").invoke(null);

        dynamicType.getClass().getMethod("load", ClassLoader.class,
                Class.forName("net.bytebuddy.dynamic.loading.ClassLoadingStrategy"))
                .invoke(dynamicType, urlJarFileClass.getClassLoader(), strategy);
    }

    /** Advice invoked inside {@code sun.net.www.protocol.jar.URLJarFile.retrieve}. */
    public static class RetrieveAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static JarFile intercept(@Advice.Argument(0) URL url) throws IOException {
            return CachedJarFileCallback.getInstance().retrieve(url);
        }
    }
}
