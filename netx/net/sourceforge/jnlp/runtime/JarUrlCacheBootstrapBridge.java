/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Bootstrap-visible indirection for {@code URLJarFile.retrieve} advice on JDK 24+.
 * The handler is registered from the application class loader during {@link JNLPRuntime} init.
 */
public final class JarUrlCacheBootstrapBridge {

    public interface Handler {
        JarFile retrieve(URL url) throws IOException;
    }

    private static volatile Handler handler;

    private JarUrlCacheBootstrapBridge() {
    }

    public static void setHandler(Handler handler) {
        JarUrlCacheBootstrapBridge.handler = handler;
    }

    public static JarFile retrieve(URL url) throws IOException {
        Handler delegate = handler;
        if (delegate == null) {
            throw new IOException("ITW jar URL cache handler not registered");
        }
        return delegate.retrieve(url);
    }
}
