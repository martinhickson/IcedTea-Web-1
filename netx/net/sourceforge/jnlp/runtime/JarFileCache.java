package net.sourceforge.jnlp.runtime;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import net.sourceforge.jnlp.util.JarFile;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.logging.OutputController.Level;
import sun.net.www.protocol.jar.URLJarFile;

public class JarFileCache {

    private static final OutputController LOGGER = OutputController.getLogger();

    private static final JarFileCache INSTANCE = new JarFileCache();

    private ConcurrentHashMap<String, JarFile> jarFileMap
        = new ConcurrentHashMap<String, JarFile>();

    private ConcurrentHashMap<File, URLJarFile> urlJarFileMap =
            new ConcurrentHashMap<File, URLJarFile>();

    private JarFileCache() {}

    public static JarFileCache getInstance() {
        return INSTANCE;
    }

    public URLJarFile getURLJarFile(File tmpFile) throws IOException {
        URLJarFile jarFile = urlJarFileMap.get(tmpFile);
        if (jarFile == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.log(Level.MESSAGE_DEBUG, String.format("Loading URLJarFile into cache: %1$s",
                        tmpFile.getAbsoluteFile()));
            }
            jarFile = new URLJarFile(tmpFile, null);
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
            jarFile = new JarFile(path);
            jarFileMap.put(path, jarFile);
        }
        return jarFile;
    }

}
