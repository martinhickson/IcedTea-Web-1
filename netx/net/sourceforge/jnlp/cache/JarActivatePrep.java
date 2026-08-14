package net.sourceforge.jnlp.cache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;

import net.sourceforge.jnlp.runtime.JarFileCache;
import net.sourceforge.jnlp.util.JarFile;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Walks a settled jar on the download worker: records entry names and
 * extracts nested jars so {@code activateJars} does not re-scan the ZIP.
 */
public final class JarActivatePrep {

    public static final class Nested {
        public final String innerName;
        public final String extractedPath;

        Nested(String innerName, String extractedPath) {
            this.innerName = innerName;
            this.extractedPath = extractedPath;
        }
    }

    public static final class Scan {
        public final List<String> entryNames;
        public final List<Nested> nested;

        Scan(List<String> entryNames, List<Nested> nested) {
            this.entryNames = entryNames;
            this.nested = nested;
        }
    }

    private static final ConcurrentHashMap<String, Scan> SCANS = new ConcurrentHashMap<String, Scan>();

    private JarActivatePrep() {
    }

    static void prepare(File jar) {
        if (jar == null || !jar.isFile()) {
            return;
        }
        String path = jar.getAbsolutePath();
        if (SCANS.containsKey(path)) {
            return;
        }
        try {
            SCANS.putIfAbsent(path, scan(jar));
        } catch (Exception e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_DEBUG, e);
        }
    }

    public static Scan take(String path) {
        return path == null ? null : SCANS.remove(path);
    }

    static Scan peek(String path) {
        return path == null ? null : SCANS.get(path);
    }

    private static Scan scan(File jar) throws Exception {
        List<String> names = new ArrayList<String>();
        List<Nested> nested = new ArrayList<Nested>();
        JarFile jarFile = JarFileCache.getInstance().getJarFile(jar.getAbsolutePath());
        for (JarEntry je : Collections.list(jarFile.entries())) {
            names.add(je.getName());
            if (!je.getName().endsWith(".jar")) {
                continue;
            }
            String name = je.getName();
            if (name.contains("..")) {
                name = CacheUtil.hex(name, name);
            }
            String extractedJarLocation = jar.getAbsolutePath() + ".nested/" + name;
            File parentDir = new File(extractedJarLocation).getParentFile();
            if (parentDir != null && !parentDir.isDirectory() && !parentDir.mkdirs()) {
                continue;
            }
            FileOutputStream extractedJar = new FileOutputStream(extractedJarLocation);
            InputStream is = jarFile.getInputStream(je);
            try {
                byte[] bytes = new byte[1024];
                int read = is.read(bytes);
                int fileSize = read;
                while (read > 0) {
                    extractedJar.write(bytes, 0, read);
                    read = is.read(bytes);
                    fileSize += read;
                }
                if (fileSize > 0) {
                    nested.add(new Nested(je.getName(), extractedJarLocation));
                }
            } finally {
                is.close();
                extractedJar.close();
            }
        }
        return new Scan(names, nested);
    }
}
