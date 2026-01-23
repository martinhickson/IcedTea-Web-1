package net.sourceforge.jnlp.runtime;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.jar.JarFile;

public class JarFileFactoryAccess {

    private static final MethodHandle getJarFileHandle;

    private static final Object factoryInstance;

    static {
        MethodHandle jarFileGetter = null;
        Object factory = null;

        try {
            Class<?> jarFileFactoryClass = Class.forName("sun.net.www.protocol.jar.JarFileFactory");

            Method getInstanceMethod = jarFileFactoryClass.getDeclaredMethod("getInstance");
            getInstanceMethod.setAccessible(true);

            Method getMethod = jarFileFactoryClass.getDeclaredMethod("get", URL.class);
            getMethod.setAccessible(true); 

            factory = getInstanceMethod.invoke(null);

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            jarFileGetter = lookup.unreflect(getMethod);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        getJarFileHandle = jarFileGetter;
        factoryInstance = factory;
    }

    /**
     * Get a cached JarFile from JDK's global cache.
     * 
     * IMPORTANT: The returned JarFile is managed by the JDK's global cache and should NOT be closed
     * by application code. Closing it can cause issues for other parts of the application that
     * might still need the same jar file.
     * 
     * @param url the JAR file URL
     * @return cached JarFile instance from JDK's global cache
     * @throws IOException if the JAR file cannot be accessed
     */
    public static JarFile getCachedJarFile(URL url) throws IOException {
        if (factoryInstance == null || getJarFileHandle == null) {
            throw new IOException("JarFileFactory is not available");
        }

        try {
            return (JarFile) getJarFileHandle.invoke(factoryInstance, url);
        } catch (Throwable t) {
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            throw new IOException("Failed to get cached JarFile", t);
        }
    }

    public static boolean isCached(URL url) {
        try {
            JarFile cached = getCachedJarFile(url);
            return cached != null;
        } catch (IOException e) {
            return false;
        }
    }
}
