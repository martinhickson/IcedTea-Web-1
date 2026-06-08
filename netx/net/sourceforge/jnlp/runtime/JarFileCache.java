package net.sourceforge.jnlp.runtime;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import net.sourceforge.jnlp.util.JarFile;
import net.sourceforge.jnlp.util.JarFileTempManager;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.logging.OutputController.Level;
public class JarFileCache {

    private static final OutputController LOGGER = OutputController.getLogger();

    private static final JarFileCache INSTANCE = new JarFileCache();

    private ConcurrentHashMap<String, JarFile> jarFileMap
        = new ConcurrentHashMap<String, JarFile>();

    private ConcurrentHashMap<File, JarFile> urlJarFileMap =
            new ConcurrentHashMap<File, JarFile>();

    private JarFileCache() {}

    public static JarFileCache getInstance() {
        return INSTANCE;
    }

    public JarFile getURLJarFile(File tmpFile) throws IOException {
        JarFile jarFile = urlJarFileMap.get(tmpFile);
        if (jarFile == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.log(Level.MESSAGE_DEBUG, String.format("Loading JarFile into cache: %1$s",
                        tmpFile.getAbsoluteFile()));
            }
            // Copy to temp directory first to avoid classloader interference
            File tempFile = JarFileTempManager.getInstance().getTempJarFile(tmpFile);
            jarFile = new JarFile(tempFile.getAbsolutePath());
            urlJarFileMap.put(tmpFile, jarFile);
        }
        return jarFile;
    }

    public JarFile getJarFile(String path) throws IOException {
        JarFile jarFile = jarFileMap.get(path);
        if (jarFile == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.log(Level.MESSAGE_DEBUG, String.format("Loading JarFile into cache: %1$s",
                        path));
            }
            // Copy to temp directory first to avoid classloader interference
            // The JarFile constructor will handle the temp copy automatically
            File originalFile = new File(path);
            File tempFile = JarFileTempManager.getInstance().getTempJarFile(originalFile);
            
            // CRITICAL: Pass verify=true to enable signature verification!
            // Without this, CodeSigners will be null and JARs will appear unsigned.
            jarFile = new JarFile(tempFile, true);  // verify=true enables signatures
            jarFileMap.put(path, jarFile);
        }
        return jarFile;
    }

}
