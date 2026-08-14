package net.sourceforge.jnlp.cache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

import net.sourceforge.jnlp.jdk89acesses.JarIndexAccess;
import net.sourceforge.jnlp.runtime.JarFileCache;
import net.sourceforge.jnlp.util.FileUtils;
import net.sourceforge.jnlp.util.JarFile;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Walks a settled jar on the download worker: entry names, nested-jar extract,
 * native extract into {@code {cache/db}/native/jars/}, and catalog native index.
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
        public final Manifest manifest;
        public final JarIndexAccess jarIndex;
        public final File nativeDir;

        Scan(List<String> entryNames, List<Nested> nested, Manifest manifest,
                JarIndexAccess jarIndex, File nativeDir) {
            this.entryNames = entryNames;
            this.nested = nested;
            this.manifest = manifest;
            this.jarIndex = jarIndex;
            this.nativeDir = nativeDir;
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
        File nativeDir = null;
        JarFile jarFile = JarFileCache.getInstance().getJarFile(jar.getAbsolutePath());
        Manifest manifest = jarFile.getManifest();
        JarIndexAccess jarIndex = null;
        try {
            jarIndex = JarIndexAccess.getJarIndex(jarFile);
        } catch (Exception e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_DEBUG, e);
        }
        String jarPath = jar.getAbsolutePath();
        for (JarEntry je : Collections.list(jarFile.entries())) {
            names.add(je.getName());
            if (je.isDirectory()) {
                continue;
            }
            String leaf = new File(je.getName()).getName();
            if (isNativeLibraryName(leaf)) {
                if (nativeDir == null) {
                    nativeDir = CacheLRUWrapper.getInstance().jarNativeExtractDir(jar);
                    if (nativeDir != null && !nativeDir.isDirectory() && !nativeDir.mkdirs()) {
                        nativeDir = null;
                    }
                }
                if (nativeDir != null) {
                    File out = extractEntry(jarFile, je, new File(nativeDir, leaf));
                    if (out != null) {
                        CacheLRUWrapper.getInstance().putNativeLib(leaf, jarPath, out.getAbsolutePath());
                    }
                }
                continue;
            }
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
            File extracted = extractEntry(jarFile, je, new File(extractedJarLocation));
            if (extracted != null) {
                nested.add(new Nested(je.getName(), extracted.getAbsolutePath()));
            }
        }
        return new Scan(names, nested, manifest, jarIndex, nativeDir);
    }

    static boolean isNativeLibraryName(String leaf) {
        if (leaf == null || leaf.isEmpty()) {
            return false;
        }
        for (String suffix : NativeLibraryStorage.NATIVE_LIBRARY_EXTENSIONS) {
            if (leaf.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static File extractEntry(JarFile jarFile, JarEntry je, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }
        if (!out.isFile()) {
            FileUtils.createRestrictedFile(out, true);
        }
        InputStream is = jarFile.getInputStream(je);
        FileOutputStream os = new FileOutputStream(out);
        try {
            byte[] bytes = new byte[8192];
            int read = is.read(bytes);
            int fileSize = read;
            while (read > 0) {
                os.write(bytes, 0, read);
                read = is.read(bytes);
                fileSize += read;
            }
            return fileSize > 0 ? out : null;
        } finally {
            is.close();
            os.close();
        }
    }
}
