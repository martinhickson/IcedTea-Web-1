package net.sourceforge.jnlp.jdk89acesses;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedList;
import java.util.jar.JarFile;

import net.sourceforge.jnlp.util.logging.OutputController;

public class JarIndexAccess {

    private static final boolean AVAILABLE;
    private static Class<?> jarIndexClass;
    private static MethodHandle getJarIndexHandle;
    private static MethodHandle getHandle;

    private final Object parent;

    static {
        boolean available = false;
        Class<?> indexClass = null;
        MethodHandle getIndex = null;
        MethodHandle get = null;
        try {
            try {
                indexClass = Class.forName("jdk.internal.util.jar.JarIndex");
            } catch (ClassNotFoundException ex) {
                indexClass = Class.forName("sun.misc.JarIndex");
            }

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getIndex = lookup.findStatic(
                indexClass,
                "getJarIndex",
                MethodType.methodType(indexClass, JarFile.class)
            );

            get = lookup.findVirtual(
                indexClass,
                "get",
                MethodType.methodType(LinkedList.class, String.class)
            );
            available = true;
        } catch (Throwable t) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                "JarIndex support unavailable on this JRE: " + t.getMessage());
            OutputController.getLogger().log(t);
        }
        AVAILABLE = available;
        jarIndexClass = indexClass;
        getJarIndexHandle = getIndex;
        getHandle = get;
    }

    private JarIndexAccess(Object parent) {
        if (parent == null) {
            throw new RuntimeException("JarIndex parent object cannot be null!");
        }
        this.parent = parent;
    }

    public static JarIndexAccess getJarIndex(JarFile jarFile) throws IOException {
        if (!AVAILABLE) {
            return null;
        }
        try {
            // Use invoke() instead of invokeExact() to allow type conversion
            // invokeExact() requires exact type match, but we're assigning to Object
            Object result = getJarIndexHandle.invoke(jarFile);
            if (result == null) {
                return null;
            }
            return new JarIndexAccess(result);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke getJarIndex", t);
        }
    }

    public LinkedList<String> get(String key) {
        try {
            // Use invoke() instead of invokeExact() to allow type conversion
            return (LinkedList<String>) getHandle.invoke(parent, key);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke get()", t);
        }
    }
}
