package sun.net.www.protocol.jar;

import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Accessor class to use JDK 17's JarFileFactory for global JAR file caching.
 * This provides the same performance benefits as JDK 17's built-in caching
 * while allowing IcedTea-Web to wrap the result with its custom JarFile.
 */
public class JarFileFactoryAccess {
    private static final JarFileFactory factory = JarFileFactory.getInstance();
    
    /**
     * Get a cached JarFile from JDK 17's global cache.
     * This eliminates the overhead of creating new JarFile instances
     * and provides the same performance as standard JDK 17 ResourceBundle loading.
     * 
     * IMPORTANT: The returned JarFile is managed by the JDK's global cache and should NOT be closed
     * by application code. Closing it can cause issues for other parts of the application that
     * might still need the same jar file.
     * 
     * @param url the JAR file URL
     * @return cached JarFile instance from JDK 17's global cache
     * @throws IOException if the JAR file cannot be accessed
     */
    public static JarFile getCachedJarFile(URL url) throws IOException {
        return factory.get(url);
    }
    
    /**
     * Check if a JarFile is already cached in JDK 17's global cache.
     * This can be used to determine if we should use the cache or create a new JarFile.
     * 
     * @param url the JAR file URL
     * @return true if the JAR file is cached, false otherwise
     */
    public static boolean isCached(URL url) {
        try {
            // Try to get from cache without creating if not present
            JarFile cached = factory.get(url);
            return cached != null;
        } catch (IOException e) {
            return false;
        }
    }
}
