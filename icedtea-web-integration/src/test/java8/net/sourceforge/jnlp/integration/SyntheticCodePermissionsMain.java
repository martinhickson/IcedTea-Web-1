package net.sourceforge.jnlp.integration;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permission;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/**
 * HARD getClassLoader cases under ITW SecurityManager for fully signed
 * {@code <all-permissions/>} apps (not partial signing).
 * <p>
 * The decisive case defines a class with a <em>static empty</em> ProtectionDomain
 * (Policy is never consulted). {@code AccessController.checkPermission(getClassLoader)}
 * from that frame must still succeed via JNLPSecurityManager trust bypass — the GTT
 * shape that Policy-only fixes miss.
 */
public final class SyntheticCodePermissionsMain {

    private static final Permission GET_CLASS_LOADER = new RuntimePermission("getClassLoader");
    private static final String EMPTY_PD_CLASS =
            "net.sourceforge.jnlp.integration.bb.EmptyStaticPdGetClassLoaderProbe";

    public interface Probe {
        ClassLoader probe();
    }

    public static final class HardGetClassLoaderInterceptor {
        @RuntimeType
        public static ClassLoader intercept() {
            AccessController.checkPermission(GET_CLASS_LOADER);
            return ClassLoader.getSystemClassLoader();
        }
    }

    /** Loads bytecode with an explicitly empty static ProtectionDomain. */
    private static final class EmptyStaticPdClassLoader extends ClassLoader {
        EmptyStaticPdClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineWithEmptyStaticPd(String name, byte[] bytecode) {
            Permissions empty = new Permissions();
            // Two-arg ProtectionDomain constructor => static permissions, no Policy.
            ProtectionDomain pd = new ProtectionDomain(
                    new CodeSource(null, (java.security.cert.Certificate[]) null), empty);
            return defineClass(name, bytecode, 0, bytecode.length, pd);
        }
    }

    public static void main(String[] args) throws Exception {
        if (System.getSecurityManager() == null) {
            fail("SecurityManager must be installed");
        }
        System.out.println("ITW_SECURITY_MANAGER=" + System.getSecurityManager().getClass().getName());

        runHardEmptyStaticPdPath();
        System.out.println("ITW_EMPTY_STATIC_PD_GETCLASSLOADER_OK");

        runProxyPath();
        System.out.println("ITW_PROXY_GETCLASSLOADER_OK");

        runByteBuddyPath();
        System.out.println("ITW_BYTEBUDDY_GETCLASSLOADER_OK");

        runJarFilePath();
        System.out.println("ITW_JARFILE_BYTEBUDDY_PATH_OK");

        String payload = "ok hard-empty-pd+proxy+bytebuddy jdk=" + System.getProperty("java.version");
        System.out.println("ITW_INTEGRATION_SUCCESS " + payload);
        writeMarker(payload);
        System.out.println("ITW JNLP APP: about to exit main method JNLP app");
        System.out.flush();
    }

    /**
     * HARD CASE (required): class with a static empty ProtectionDomain (Policy never
     * consulted) calls {@link ClassLoader#getSystemClassLoader()} which checks
     * {@code getClassLoader} via SecurityManager. Without SM trust bypass this fails
     * even when JNLPClassLoader.getPermissions would elevate other CodeSources.
     */
    private static void runHardEmptyStaticPdPath() throws Exception {
        // Inline MethodCall only — no MethodDelegation to another package (that needs
        // accessClassInPackage and obscures the getClassLoader hard case).
        byte[] bytecode = new ByteBuddy()
                .subclass(Object.class)
                .name(EMPTY_PD_CLASS)
                .defineMethod("probe", ClassLoader.class, Modifier.PUBLIC | Modifier.STATIC)
                .intercept(MethodCall.invoke(
                        ClassLoader.class.getMethod("getSystemClassLoader")))
                .make()
                .getBytes();

        EmptyStaticPdClassLoader loader =
                new EmptyStaticPdClassLoader(SyntheticCodePermissionsMain.class.getClassLoader());
        Class<?> generated = loader.defineWithEmptyStaticPd(EMPTY_PD_CLASS, bytecode);

        assertNoCodeSigners(generated, "empty-static-pd");
        ProtectionDomain pd = generated.getProtectionDomain();
        boolean pdImplies = pd != null && pd.implies(GET_CLASS_LOADER);
        System.out.println("ITW_EMPTY_STATIC_PD_CLASS=" + generated.getName());
        System.out.println("ITW_EMPTY_STATIC_PD_IMPLIES_GETCLASSLOADER=" + pdImplies);
        if (pdImplies) {
            fail("HARD CASE required: empty static ProtectionDomain must NOT imply getClassLoader");
        }
        System.out.println("ITW_EMPTY_STATIC_PD_HARD_OK");

        ClassLoader loaded = (ClassLoader) generated.getMethod("probe").invoke(null);
        if (loaded == null) {
            fail("empty-static-pd probe returned null ClassLoader");
        }
        System.out.println("ITW_EMPTY_STATIC_PD_SM_BYPASS_OK");
    }

    private static void runProxyPath() throws Exception {
        final ClassLoader[] holder = new ClassLoader[1];
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("probe".equals(method.getName())) {
                    AccessController.checkPermission(GET_CLASS_LOADER);
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
        ProtectionDomain pd = proxyClass.getProtectionDomain();
        System.out.println("ITW_PROXY_CLASS=" + proxyName);
        System.out.println("ITW_PROXY_PD_IMPLIES_GETCLASSLOADER="
                + (pd != null && pd.implies(GET_CLASS_LOADER)));

        ClassLoader loaded = probe.probe();
        if (loaded == null) {
            fail("proxy probe returned null ClassLoader");
        }
        if (holder[0] == null) {
            fail("proxy handler did not run getSystemClassLoader");
        }
        System.out.println("ITW_PROXY_SM_BYPASS_OK");
    }

    private static void runByteBuddyPath() throws Exception {
        Class<?> generated = new ByteBuddy()
                .subclass(Object.class)
                .name("net.sourceforge.jnlp.integration.bb.GeneratedHardGetClassLoaderProbe")
                .defineMethod("probe", ClassLoader.class, Modifier.PUBLIC)
                .intercept(MethodDelegation.to(HardGetClassLoaderInterceptor.class))
                .make()
                .load(SyntheticCodePermissionsMain.class.getClassLoader(),
                        ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        assertNoCodeSigners(generated, "bytebuddy");
        ProtectionDomain pd = generated.getProtectionDomain();
        System.out.println("ITW_BYTEBUDDY_CLASS=" + generated.getName());
        System.out.println("ITW_BYTEBUDDY_PD_IMPLIES_GETCLASSLOADER="
                + (pd != null && pd.implies(GET_CLASS_LOADER)));

        Object instance = generated.getDeclaredConstructor().newInstance();
        ClassLoader loaded = (ClassLoader) generated.getMethod("probe").invoke(instance);
        if (loaded == null) {
            fail("bytebuddy probe returned null ClassLoader");
        }
        System.out.println("ITW_BYTEBUDDY_SM_BYPASS_OK");
    }

    private static void runJarFilePath() throws Exception {
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
