package net.sourceforge.jnlp.integration;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/**
 * Headless JNLP app that reproduces Sonata-style getClassLoader failures under
 * ITW SecurityManager: JDK proxy frames and ByteBuddy-generated frames with no
 * CodeSigners must still be able to call {@link ClassLoader#getSystemClassLoader()}
 * when the application is fully trusted with {@code <all-permissions/>}.
 */
public final class SyntheticCodePermissionsMain {

    public interface Probe {
        ClassLoader probe();
    }

    public static final class GetSystemClassLoaderInterceptor {
        @RuntimeType
        public static ClassLoader intercept() {
            return ClassLoader.getSystemClassLoader();
        }
    }

    public static void main(String[] args) throws Exception {
        if (System.getSecurityManager() == null) {
            fail("SecurityManager must be installed");
        }

        runProxyPath();
        System.out.println("ITW_PROXY_GETCLASSLOADER_OK");

        runByteBuddyPath();
        System.out.println("ITW_BYTEBUDDY_GETCLASSLOADER_OK");

        runJarFilePath();
        System.out.println("ITW_JARFILE_BYTEBUDDY_PATH_OK");

        String payload = "ok proxy+bytebuddy+jarfile jdk=" + System.getProperty("java.version");
        System.out.println("ITW_INTEGRATION_SUCCESS " + payload);
        writeMarker(payload);
        System.out.println("ITW JNLP APP: about to exit main method JNLP app");
        System.out.flush();
    }

    private static void runProxyPath() throws Exception {
        final ClassLoader[] holder = new ClassLoader[1];
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("probe".equals(method.getName())) {
                    // Proxy frame is on the ACC stack; this always checks getClassLoader.
                    holder[0] = ClassLoader.getSystemClassLoader();
                    return holder[0];
                }
                if ("toString".equals(method.getName())) {
                    return "SyntheticProbeProxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                throw new UnsupportedOperationException(method.getName());
            }
        };

        Probe probe = (Probe) Proxy.newProxyInstance(
                SyntheticCodePermissionsMain.class.getClassLoader(),
                new Class<?>[]{Probe.class},
                handler);

        Class<?> proxyClass = probe.getClass();
        String proxyName = proxyClass.getName();
        if (!proxyName.contains("Proxy") && !proxyName.contains("$Proxy")) {
            fail("expected JDK proxy class, got " + proxyName);
        }
        assertNoCodeSigners(proxyClass, "proxy");

        ClassLoader loaded = probe.probe();
        if (loaded == null) {
            fail("proxy probe returned null ClassLoader");
        }
        if (holder[0] == null) {
            fail("proxy handler did not run getSystemClassLoader");
        }
        System.out.println("ITW_PROXY_CLASS=" + proxyName);
    }

    private static void runByteBuddyPath() throws Exception {
        Class<?> generated = new ByteBuddy()
                .subclass(Object.class)
                .name("net.sourceforge.jnlp.integration.bb.GeneratedSystemClassLoaderProbe")
                .defineMethod("probe", ClassLoader.class, Modifier.PUBLIC)
                .intercept(MethodDelegation.to(GetSystemClassLoaderInterceptor.class))
                .make()
                .load(SyntheticCodePermissionsMain.class.getClassLoader(),
                        ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        assertNoCodeSigners(generated, "bytebuddy");

        Object instance = generated.getDeclaredConstructor().newInstance();
        ClassLoader loaded = (ClassLoader) generated.getMethod("probe").invoke(instance);
        if (loaded == null) {
            fail("bytebuddy probe returned null ClassLoader");
        }
        System.out.println("ITW_BYTEBUDDY_CLASS=" + generated.getName());
    }

    private static void runJarFilePath() throws Exception {
        // Under ITW the CodeSource location is often http(s)://... (not a local file).
        // Exercise jar: URL / JarFile handling (ITW ByteBuddy JarFile close protection).
        URL classUrl = SyntheticCodePermissionsMain.class.getResource(
                "/net/sourceforge/jnlp/integration/SyntheticCodePermissionsMain.class");
        if (classUrl == null) {
            fail("missing class resource URL");
        }
        InputStream classStream = classUrl.openStream();
        try {
            byte[] buf = new byte[64];
            if (classStream.read(buf) <= 0) {
                fail("empty class resource via " + classUrl);
            }
        } finally {
            classStream.close();
        }

        if (!"jar".equals(classUrl.getProtocol())) {
            fail("expected jar: resource URL, got " + classUrl);
        }
        java.net.JarURLConnection conn = (java.net.JarURLConnection) classUrl.openConnection();
        JarFile jar = conn.getJarFile();
        if (jar == null) {
            fail("JarURLConnection.getJarFile() returned null for " + classUrl);
        }
        InputStream in = jar.getInputStream(
                jar.getJarEntry("net/sourceforge/jnlp/integration/SyntheticCodePermissionsMain.class"));
        if (in == null) {
            fail("missing class entry in JarFile from " + classUrl);
        }
        try {
            byte[] buf = new byte[64];
            if (in.read(buf) <= 0) {
                fail("empty class entry in JarFile from " + classUrl);
            }
        } finally {
            in.close();
        }
        // Do not force-close: ITW may protect cached JarFiles from close().
        System.out.println("ITW_JARFILE_URL=" + classUrl);
    }

    private static void assertNoCodeSigners(Class<?> type, String label) {
        ProtectionDomain pd = type.getProtectionDomain();
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        if (cs != null && cs.getCodeSigners() != null) {
            fail(label + " CodeSource unexpectedly has CodeSigners: " + type.getName());
        }
    }

    private static void writeMarker(String payload) {
        String marker = System.getProperty("itw.test.success.marker");
        if (marker == null || marker.trim().isEmpty()) {
            marker = System.getenv("ITW_TEST_SUCCESS_MARKER");
        }
        if (marker == null || marker.trim().isEmpty()) {
            return;
        }
        try {
            File markerFile = new File(marker);
            File parent = markerFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(markerFile);
            try {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            System.out.println("ITW_INTEGRATION_MARKER_SKIPPED " + e.getMessage());
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }
}
