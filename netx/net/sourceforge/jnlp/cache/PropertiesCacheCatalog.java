/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.sourceforge.jnlp.config.InfrastructureFileDescriptor;
import net.sourceforge.jnlp.util.FileUtils;
import net.sourceforge.jnlp.util.PropertiesFile;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Legacy {@code recently_used} properties catalog.
 */
final class PropertiesCacheCatalog implements CacheCatalog {

    private final InfrastructureFileDescriptor recentlyUsedFile;
    private PropertiesFile propertiesFile;
    /** lib_name -> extract_path (last writer). jar_path kept for remove. */
    private final ConcurrentHashMap<String, String> nativeByName = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> nativeNameByJar = new ConcurrentHashMap<String, String>();

    PropertiesCacheCatalog(InfrastructureFileDescriptor recentlyUsedFile) {
        this.recentlyUsedFile = recentlyUsedFile;
        if (!recentlyUsedFile.getFile().exists()) {
            try {
                FileUtils.createParentDir(recentlyUsedFile.getFile());
                FileUtils.createRestrictedFile(recentlyUsedFile.getFile(), true);
            } catch (IOException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            }
        }
    }

    private PropertiesFile props() {
        if (propertiesFile == null) {
            propertiesFile = new PropertiesFile(recentlyUsedFile.getFile());
            return propertiesFile;
        }
        if (recentlyUsedFile.getFile().equals(propertiesFile.getStoreFile())) {
            return propertiesFile;
        }
        if (propertiesFile.tryLock()) {
            propertiesFile.store();
            propertiesFile.unlock();
        }
        propertiesFile = new PropertiesFile(recentlyUsedFile.getFile());
        return propertiesFile;
    }

    @Override
    public void lock() {
        props().lock();
    }

    @Override
    public void unlock() {
        props().unlock();
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return props().isHeldByCurrentThread();
    }

    @Override
    public boolean tryLock() {
        return props().tryLock();
    }

    @Override
    public boolean load() {
        boolean loaded = props().load();
        if (!loaded) {
            return false;
        }
        boolean modified = false;
        Set<Entry<Object, Object>> q = props().entrySet();
        for (Iterator<Entry<Object, Object>> it = q.iterator(); it.hasNext();) {
            Entry<Object, Object> currentEntry = it.next();
            final String key = (String) currentEntry.getKey();
            final String path = (String) currentEntry.getValue();
            try {
                String sa[] = key.split(",");
                Long.parseLong(sa[0]);
                Long.parseLong(sa[1]);
            } catch (Exception ex) {
                it.remove();
                modified = true;
                continue;
            }
            if (path == null) {
                it.remove();
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean store() {
        if (props().isHeldByCurrentThread()) {
            props().store();
            return true;
        }
        return false;
    }

    @Override
    public boolean addEntry(String key, String path) {
        PropertiesFile p = props();
        if (p.containsKey(key)) {
            return false;
        }
        p.setProperty(key, path);
        return true;
    }

    @Override
    public boolean removeEntry(String key) {
        PropertiesFile p = props();
        if (!p.containsKey(key)) {
            return false;
        }
        p.remove(key);
        return true;
    }

    @Override
    public boolean removeByPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        removeNativeLibsByJarPath(path);
        boolean removed = false;
        List<Entry<String, String>> snapshot = new ArrayList<>(getLRUSortedEntries());
        for (Entry<String, String> e : snapshot) {
            if (path.equals(e.getValue())) {
                removeEntry(e.getKey());
                removed = true;
            }
        }
        PropertiesFile meta = metaProps();
        meta.lock();
        try {
            meta.load();
            String[] suffixes = {
                "|path", "|jnlp_path", "|resource_url", "|content_length",
                "|wire_length", "|last_modified", "|last_updated", "|marked_delete"
            };
            for (String suffix : suffixes) {
                if (meta.remove(path + suffix) != null) {
                    removed = true;
                }
            }
            meta.store();
        } finally {
            meta.unlock();
        }
        return removed;
    }

    @Override
    public boolean updateEntry(String oldKey, String cacheDirPath) {
        PropertiesFile p = props();
        if (!p.containsKey(oldKey)) {
            return false;
        }
        String value = p.getProperty(oldKey);
        String folder = folderIdFromPath(value, cacheDirPath);
        p.remove(oldKey);
        p.setProperty(Long.toString(System.currentTimeMillis()) + "," + folder, value);
        return true;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Entry<String, String>> getLRUSortedEntries() {
        List<Entry<String, String>> entries = new ArrayList<>();
        for (Entry e : props().entrySet()) {
            entries.add(new AbstractMap.SimpleImmutableEntry(e));
        }
        Collections.sort(entries, new Comparator<Entry<String, String>>() {
            @Override
            public int compare(Entry<String, String> e1, Entry<String, String> e2) {
                Long t1 = Long.parseLong(e1.getKey().split(",")[0]);
                Long t2 = Long.parseLong(e2.getKey().split(",")[0]);
                int c = t1.compareTo(t2);
                return c < 0 ? 1 : (c > 0 ? -1 : 0);
            }
        });
        return entries;
    }

    @Override
    public List<Entry<String, String>> findEntriesByUrlPath(String urlPath, String cacheDirPath) {
        List<Entry<String, String>> all = getLRUSortedEntries();
        List<Entry<String, String>> matched = new ArrayList<>();
        for (Entry<String, String> e : all) {
            if (CacheUtil.pathToURLPath(e.getValue(), cacheDirPath).equals(urlPath)) {
                matched.add(e);
            }
        }
        return matched;
    }

    @Override
    public String getValue(String key) {
        return props().getProperty(key);
    }

    @Override
    public boolean containsKey(String key) {
        return props().containsKey(key);
    }

    @Override
    public boolean containsValue(String value) {
        return props().containsValue(value);
    }

    @Override
    public void clear() {
        props().clear();
    }

    @Override
    public String generateKey(String path, String cacheDirPath) {
        return System.currentTimeMillis() + "," + folderIdFromPath(path, cacheDirPath);
    }

    @Override
    public int nextFolderId(File cacheDir) {
        return CacheCatalog.claimFolderId(cacheDir, 0);
    }

    @Override
    public void close() {
        // PropertiesFile holds no long-lived JDBC resources.
    }

    /**
     * Legacy backend: metadata in {@code recently_used.entry-meta} (one catalog
     * file), not a {@code .info} sidecar next to each jar.
     */
    private PropertiesFile metaProps() {
        File metaFile = new File(recentlyUsedFile.getFile().getAbsolutePath() + ".entry-meta");
        return new PropertiesFile(metaFile);
    }

    @Override
    public CacheEntryMeta getMetaByPath(String path) {
        if (path == null) {
            return null;
        }
        PropertiesFile p = metaProps();
        if (!p.containsKey(path + "|path") && p.getProperty(path + "|jnlp_path") == null
                && p.getProperty(path + "|marked_delete") == null) {
            // still return a shell so callers can attach fields
            CacheEntryMeta empty = new CacheEntryMeta();
            empty.path = path;
            return empty;
        }
        CacheEntryMeta m = new CacheEntryMeta();
        m.path = path;
        m.jnlpPath = p.getProperty(path + "|jnlp_path");
        m.resourceUrl = p.getProperty(path + "|resource_url");
        m.contentLength = parseLongOrNull(p.getProperty(path + "|content_length"));
        m.wireLength = parseLongOrNull(p.getProperty(path + "|wire_length"));
        m.lastModified = parseLongOrNull(p.getProperty(path + "|last_modified"));
        m.lastUpdated = parseLongOrNull(p.getProperty(path + "|last_updated"));
        m.markedDelete = Boolean.parseBoolean(p.getProperty(path + "|marked_delete"));
        return m;
    }

    @Override
    public void putMeta(CacheEntryMeta meta) {
        if (meta == null || meta.path == null) {
            return;
        }
        PropertiesFile p = metaProps();
        p.lock();
        try {
            p.setProperty(meta.path + "|path", meta.path);
            setOrClear(p, meta.path + "|jnlp_path", meta.jnlpPath);
            setOrClear(p, meta.path + "|resource_url", meta.resourceUrl);
            setOrClear(p, meta.path + "|content_length",
                    meta.contentLength == null ? null : Long.toString(meta.contentLength));
            setOrClear(p, meta.path + "|wire_length",
                    meta.wireLength == null ? null : Long.toString(meta.wireLength));
            setOrClear(p, meta.path + "|last_modified",
                    meta.lastModified == null ? null : Long.toString(meta.lastModified));
            setOrClear(p, meta.path + "|last_updated",
                    meta.lastUpdated == null ? null : Long.toString(meta.lastUpdated));
            p.setProperty(meta.path + "|marked_delete", Boolean.toString(meta.markedDelete));
            p.store();
        } finally {
            p.unlock();
        }
    }

    @Override
    public void registerRunningApp(int pid, String jnlpPath, String processStart) {
        if (pid <= 0) {
            return;
        }
        List<CacheRunningApp> rows = listRunningApps();
        List<CacheRunningApp> updated = new ArrayList<CacheRunningApp>();
        boolean replaced = false;
        for (CacheRunningApp row : rows) {
            if (row.pid == pid) {
                updated.add(new CacheRunningApp(pid, jnlpPath, processStart));
                replaced = true;
            } else {
                updated.add(row);
            }
        }
        if (!replaced) {
            updated.add(new CacheRunningApp(pid, jnlpPath, processStart));
        }
        writeRunningApps(updated);
    }

    @Override
    public void unregisterRunningApp(int pid) {
        if (pid <= 0) {
            return;
        }
        List<CacheRunningApp> rows = listRunningApps();
        List<CacheRunningApp> updated = new ArrayList<CacheRunningApp>();
        for (CacheRunningApp row : rows) {
            if (row.pid != pid) {
                updated.add(row);
            }
        }
        writeRunningApps(updated);
    }

    @Override
    public List<CacheRunningApp> listRunningApps() {
        List<CacheRunningApp> rows = new ArrayList<CacheRunningApp>();
        File file = runningAppsFile();
        if (file == null || !file.isFile()) {
            return rows;
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 1) {
                    continue;
                }
                try {
                    int pid = Integer.parseInt(parts[0].trim());
                    String jnlp = parts.length > 1 ? emptyToNull(parts[1]) : null;
                    String start = parts.length > 2 ? emptyToNull(parts[2]) : null;
                    rows.add(new CacheRunningApp(pid, jnlp, start));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            OutputController.getLogger().log(e);
            throw new IllegalStateException("Could not list running_app", e);
        }
        return rows;
    }

    private File runningAppsFile() {
        File recentlyUsed = recentlyUsedFile.getFile();
        File parent = recentlyUsed.getParentFile();
        if (parent == null) {
            return new File("running_apps");
        }
        return new File(parent, "running_apps");
    }

    private void writeRunningApps(List<CacheRunningApp> rows) {
        File file = runningAppsFile();
        try {
            FileUtils.createParentDir(file);
            StringBuilder sb = new StringBuilder();
            for (CacheRunningApp row : rows) {
                sb.append(row.pid).append('\t')
                        .append(row.jnlpPath == null ? "" : row.jnlpPath).append('\t')
                        .append(row.processStart == null ? "" : row.processStart).append('\n');
            }
            java.nio.file.Files.write(file.toPath(),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            OutputController.getLogger().log(e);
        }
    }

    private static String emptyToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    @Override
    public List<CacheCleanupRow> listMarkedForDelete() {
        List<CacheCleanupRow> rows = new ArrayList<>();
        for (Entry<String, String> e : getLRUSortedEntries()) {
            CacheEntryMeta meta = getMetaByPath(e.getValue());
            if (meta != null && meta.markedDelete) {
                rows.add(new CacheCleanupRow(e.getKey(), e.getValue(), meta.contentLength));
            }
        }
        return rows;
    }

    @Override
    public List<CacheCleanupRow> listUnmarkedLruNewestFirst() {
        List<CacheCleanupRow> rows = new ArrayList<>();
        for (Entry<String, String> e : getLRUSortedEntries()) {
            CacheEntryMeta meta = getMetaByPath(e.getValue());
            if (meta == null || !meta.markedDelete) {
                Long len = meta == null ? null : meta.contentLength;
                rows.add(new CacheCleanupRow(e.getKey(), e.getValue(), len));
            }
        }
        return rows;
    }

    @Override
    public List<CacheEntryMeta> listAllMeta() {
        List<CacheEntryMeta> rows = new ArrayList<>();
        for (Entry<String, String> e : getLRUSortedEntries()) {
            CacheEntryMeta m = getMetaByPath(e.getValue());
            if (m == null) {
                m = new CacheEntryMeta();
                m.path = e.getValue();
            }
            rows.add(m);
        }
        return rows;
    }

    private static void setOrClear(PropertiesFile p, String key, String value) {
        if (value == null) {
            p.remove(key);
        } else {
            p.setProperty(key, value);
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String folderIdFromPath(String path, String cacheDirPath) {
        String normalizedPath = path.replace('/', File.separatorChar);
        String normalizedRoot = cacheDirPath.replace('/', File.separatorChar);
        int len = normalizedRoot.length();
        int index = normalizedPath.indexOf(File.separatorChar, len + 1);
        if (index < 0) {
            throw new IllegalArgumentException("Cannot derive folder id from path: " + path);
        }
        return normalizedPath.substring(len + 1, index);
    }

    @Override
    public void putNativeLib(String libName, String jarPath, String extractPath) {
        if (libName == null || libName.isEmpty() || extractPath == null) {
            return;
        }
        nativeByName.put(libName, extractPath);
        if (jarPath != null) {
            nativeNameByJar.put(jarPath + '\0' + libName, libName);
        }
    }

    @Override
    public String findNativeLib(String libName) {
        return libName == null ? null : nativeByName.get(libName);
    }

    @Override
    public void removeNativeLibsByJarPath(String jarPath) {
        if (jarPath == null) {
            return;
        }
        String prefix = jarPath + '\0';
        for (String key : nativeNameByJar.keySet()) {
            if (key.startsWith(prefix)) {
                String lib = nativeNameByJar.remove(key);
                if (lib != null) {
                    nativeByName.remove(lib);
                }
            }
        }
    }
}
