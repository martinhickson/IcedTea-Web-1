/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.asm.Advice;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Bootstrap-visible {@code URLJarFile.retrieve} advice for JDK 24+.
 */
public final class BootstrapUrlJarRetrieveAdvice {

    private BootstrapUrlJarRetrieveAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static JarFile intercept(@Advice.Argument(0) URL url) throws IOException {
        return JarUrlCacheBootstrapBridge.retrieve(url);
    }
}
