package net.sourceforge.jnlp.jdk89acesses;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedList;
import java.util.jar.JarFile;

import net.sourceforge.jnlp.util.logging.OutputController;

public class JarIndexAccess {

    private static Class<?> jarIndexClass;
    private static MethodHandle getJarIndexHandle;
    private static MethodHandle getHandle;

    private final Object parent;

    static {
        try {
            jarIndexClass = Class.forName("jdk.internal.util.jar.JarIndex");
        } catch (ClassNotFoundException ex) {
            try {
                jarIndexClass = Class.forName("sun.misc.JarIndex");
            } catch (ClassNotFoundException exx) {
                OutputController.getLogger().log(exx);
                throw new RuntimeException("JarIndex class not found!");
            }
        }

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getJarIndexHandle = lookup.findStatic(
                jarIndexClass,
                "getJarIndex",
                MethodType.methodType(jarIndexClass, JarFile.class)
            );

            getHandle = lookup.findVirtual(
                jarIndexClass,
                "get",
                MethodType.methodType(LinkedList.class, String.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize MethodHandles", e);
        }
    }

    private JarIndexAccess(Object parent) {
        if (parent == null) {
            throw new RuntimeException("JarIndex parent object cannot be null!");
        }
        this.parent = parent;
    }

    public static JarIndexAccess getJarIndex(JarFile jarFile) throws IOException {
        try {
            Object result = getJarIndexHandle.invokeExact(jarFile);
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
            return (LinkedList<String>) getHandle.invokeExact(parent, key);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke get()", t);
        }
    }
}
