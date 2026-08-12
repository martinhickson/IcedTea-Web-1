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
    public void close() {
        // PropertiesFile holds no long-lived JDBC resources.
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
}
