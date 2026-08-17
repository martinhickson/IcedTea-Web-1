// Copyright (C) 2001-2003 Jon A. Maxwell (JAM)
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.jnlp.DownloadServiceListener;

import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.controlpanel.CachePane;
import net.sourceforge.jnlp.runtime.ApplicationInstance;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.Translator;
import static net.sourceforge.jnlp.runtime.Translator.R;

import net.sourceforge.jnlp.security.ConnectionFactory;
import net.sourceforge.jnlp.util.FileUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Provides static methods to interact with the cache, download
 * indicator, and other utility methods.
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.17 $
 */
public class CacheUtil {

    public static boolean USE_LEGACY_FILE_URL_CACHE_BYPASS = false;



    /**
     * Caches a resource and returns a URL for it in the cache;
     * blocks until resource is cached. If the resource location is
     * not cacheable then the original URL is returned.
     *
     * @param location location of the resource
     * @param version the version, or {@code null}
     * @param policy how to handle update
     * @return either the location in the cache or the original location
     */
    public static URL getCachedResourceURL(URL location, Version version, UpdatePolicy policy) {
        try {
            File f = getCachedResourceFile(location, version, policy);
            //url was ponting to nowhere eg 404
            if (f == null){
                //originally  f.toUrl was throwing NPE
                return null;
                //returning null seems to be better
            }
            // TODO: Should be toURI().toURL()
            return f.toURL();
        } catch (MalformedURLException ex) {
            return location;
        }
    }

    /**
     * This is returning File object of cached resource originally from URL
     * @param location original location of blob
     * @param version version of resource
     * @param policy update policy of resource
     * @return location in ITW cache on filesystem
     */
    public static File  getCachedResourceFile(URL location, Version version, UpdatePolicy policy) {
        ResourceTracker rt = new ResourceTracker();
        rt.addResource(location, version, null, policy);
        File f = rt.getCacheFile(location);
        return f;
    }

    /**
     * Returns the Permission object necessary to access the
     * resource, or {@code null} if no permission is needed.
     * @param location location of the resource
     * @param version the version, or {@code null}
     * @return permissions of the location
     */
    public static Permission getReadPermission(URL location, Version version) {
        Permission result = null;
        if (CacheUtil.isCacheable(location, version)) {
            File file = CacheUtil.getCacheFile(location, version);
            result = new FilePermission(file.getPath(), "read");
        } else {
            try {
                // this is what URLClassLoader does
                URLConnection conn = ConnectionFactory.getConnectionFactory().openConnection(location);
                result = conn.getPermission();
                 ConnectionFactory.getConnectionFactory().disconnect(conn);
            } catch (java.io.IOException ioe) {
                // should try to figure out the permission
                OutputController.getLogger().log(ioe);
            }
        }

        return result;
    }

    /**
     * Clears the cache by deleting all the Netx cache files
     *
     * Note: Because of how our caching system works, deleting jars of another javaws
     * process is using them can be quite disasterous. Hence why Launcher creates lock files
     * and we check for those by calling {@link #okToClearCache()}
     * @return true if the cache could be cleared and was cleared
     */
    public static boolean clearCache() {
        // clear all cache
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        File cacheDir = lruHandler.getCacheDir().getFile();

        if (!checkToClearCache()) {
            return false;
        }

        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Clearing cache directory: " + cacheDir);
        synchronized (lruHandler) {
        lruHandler.lock();
        try {
            cacheDir = cacheDir.getCanonicalFile();
            // remove windows shortcuts before cache dir is gone
            if (JNLPRuntime.isWindows()) {
                removeWindowsShortcuts("ALL");
            }
            // Release sqlite WAL/SHM handles before deleting catalog files (Windows).
            lruHandler.close();
            deleteCacheContentsKeepingNative(cacheDir);
            if (!cacheDir.isDirectory() && !cacheDir.mkdir()) {
                throw new IOException("Unable to recreate cache directory: " + cacheDir);
            }
            lruHandler.clearLRUSortedEntries();
            lruHandler.store();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lruHandler.unlock();
        }
        }
        return true;
    }

    /**
     * Delete cache contents but keep {@code native/}. xerial's JNI stays mapped
     * in this JVM; deleting {@code sqlitejdbc.dll} (Windows) would fail clear-cache.
     */
    static void deleteCacheContentsKeepingNative(File cacheDir) throws IOException {
        File[] children = cacheDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && "native".equals(child.getName())) {
                continue;
            }
            FileUtils.recursiveDelete(child, cacheDir);
        }
    }

    /** True for per-jar {@code .info} files; skips sqlite catalog and JNI extract. */
    static boolean isCacheJarInfoFile(Path t) {
        if (t == null || !Files.isRegularFile(t)) {
            return false;
        }
        if (!t.getFileName().toString().endsWith(CacheDirectory.INFO_SUFFIX)) {
            return false;
        }
        for (Path p = t; p != null; p = p.getParent()) {
            String n = p.getFileName() != null ? p.getFileName().toString() : "";
            if ("native".equals(n) || n.startsWith(SqliteCacheCatalog.DB_FILE_NAME)) {
                return false;
            }
        }
        return true;
    }

    public static boolean clearCache(final String application, boolean jnlpPath, boolean domain) {
        // Block when this clear would delete a running app's files. JAR hrefs
        // from -Xcacheids never appear on the process command line, so matching
        // only the id string used to skip the busy guard and yank a live jar.
        if (!canClearApplicationCache(application)) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, R("CCannotClearCache"));
            return false;
        }

        final CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        final int[] markedForDeletion = {0};
        synchronized (lruHandler) {
        lruHandler.lock();
        try {
            lruHandler.load();
            final List<CacheEntryMeta> rows = lruHandler.listAllMeta();
            // Exact catalog match only. Unknown URLs must not domain-walk another
            // app and print "Clearing… Alerting: N".
            final List<CacheEntryMeta> exact = new ArrayList<CacheEntryMeta>();
            for (CacheEntryMeta row : rows) {
                if (catalogRowExactMatch(row, application, jnlpPath, domain)) {
                    exact.add(row);
                }
            }
            if (exact.isEmpty()) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, Translator.R("BXSingleCacheClearNotFound", application));
                return false;
            }
            final Set<String> exactPaths = new HashSet<String>();
            final Set<String> siblingJnlp = new HashSet<String>();
            final Set<String> siblingDirs = new HashSet<String>();
            for (CacheEntryMeta row : exact) {
                if (row.path != null) {
                    exactPaths.add(row.path);
                }
                if (jnlpPath) {
                    if (row.jnlpPath != null && !row.jnlpPath.isEmpty()) {
                        siblingJnlp.add(row.jnlpPath.toLowerCase(java.util.Locale.ROOT));
                    }
                    String dir = parentCacheRelativeDir(row.resourceUrl != null
                            ? normalizeCacheRelativePath(row.resourceUrl) : null);
                    if (dir != null) {
                        siblingDirs.add(dir.toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            for (CacheEntryMeta row : rows) {
                boolean mark = row.path != null && exactPaths.contains(row.path);
                if (!mark && row.jnlpPath != null
                        && siblingJnlp.contains(row.jnlpPath.toLowerCase(java.util.Locale.ROOT))) {
                    mark = true;
                }
                if (!mark && !siblingDirs.isEmpty()) {
                    String dir = parentCacheRelativeDir(row.resourceUrl != null
                            ? normalizeCacheRelativePath(row.resourceUrl) : null);
                    mark = dir != null && siblingDirs.contains(dir.toLowerCase(java.util.Locale.ROOT));
                }
                if (mark) {
                    row.markedDelete = true;
                    lruHandler.putMeta(row);
                    markedForDeletion[0]++;
                    OutputController.getLogger().log("marked for deletion: " + row.path);
                }
            }
            if (markedForDeletion[0] == 0) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, Translator.R("BXSingleCacheClearNotFound", application));
                return false;
            }
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, Translator.R("BXSingleCacheCleared", application));
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, Translator.R("BXSingleCacheFileCount", markedForDeletion[0]));
            if (JNLPRuntime.isWindows()) {
                try {
                    removeWindowsShortcuts(application.toLowerCase());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            // Remove entries marked for deletion even when unrelated JNLP apps hold MAIN_LOCK
            cleanCache();

        } finally {
            lruHandler.unlock();
        }
        }
        return true;
    }

    public static boolean checkToClearCache() {
        if (net.sourceforge.jnlp.util.JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(null)
                || !okToClearCache()) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, R("CCannotClearCache"));
            return false;
        }
        return CacheLRUWrapper.getInstance().getCacheDir().getFile().isDirectory();
    }

    /**
     * @return true when {@code application} may be cleared without deleting
     *         resources a running JNLP process still needs (JNLP URL or JAR href)
     */
    /** JNLP URL or resource href used to decide if a viewer/clear id is busy. */
    public static String clearIdForMeta(CacheEntryMeta meta) {
        if (meta == null) {
            return null;
        }
        if (meta.jnlpPath != null && !meta.jnlpPath.trim().isEmpty()) {
            return meta.jnlpPath.trim();
        }
        return hrefFromCacheRelativePath(meta.resourceUrl);
    }

    public static boolean canClearApplicationCache(String application) {
        if (application == null || application.trim().isEmpty()) {
            return false;
        }
        if (net.sourceforge.jnlp.util.JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(application)) {
            return false;
        }
        if (catalogRowsForClearIdBlocked(application)) {
            return false;
        }
        return CacheLRUWrapper.getInstance().getCacheDir().getFile().isDirectory();
    }

    /**
     * Jar / domain ids never appear on the process command line. If a catalog
     * row for this id belongs to a running JNLP (stored {@code jnlp-path} or
     * href), treat the id as busy.
     */
    static boolean catalogRowsForClearIdBlocked(String application) {
        try {
            for (CacheEntryMeta row : CacheLRUWrapper.getInstance().listAllMeta()) {
                if (!catalogRowExactMatch(row, application, true, true)) {
                    continue;
                }
                if (row.jnlpPath != null && !row.jnlpPath.trim().isEmpty()
                        && net.sourceforge.jnlp.util.JnlpRunningProcessSupport
                                .cacheClearBlockedByRunningApps(row.jnlpPath.trim())) {
                    return true;
                }
                String href = hrefFromCacheRelativePath(row.resourceUrl);
                if (href != null
                        && net.sourceforge.jnlp.util.JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(href)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * True when {@code cacheId} (JNLP or JAR href) lives in the same cache
     * directory as {@code runningJnlpPath}, so clearing it would remove the
     * running application's files.
     */
    /**
     * True when {@code cacheId} is a hostname and equals the host of
     * {@code runningJnlpPath}. {@code example.com} must not match
     * {@code evil.example.com} (substring matching did).
     */
    public static boolean cacheIdIsRunningJnlpHost(String cacheId, String runningJnlpPath) {
        if (!looksLikeCacheDomainId(cacheId) || runningJnlpPath == null) {
            return false;
        }
        String host = hostFromCacheRelativePath(applicationToCacheRelativePath(runningJnlpPath));
        return cacheId.equalsIgnoreCase(host);
    }

    public static boolean cacheIdSharesDirectoryWithJnlp(String cacheId, String runningJnlpPath) {
        if (cacheId == null || runningJnlpPath == null) {
            return false;
        }
        String idRel = applicationToCacheRelativePath(cacheId);
        String jnlpRel = applicationToCacheRelativePath(runningJnlpPath);
        if (idRel == null || jnlpRel == null) {
            return false;
        }
        if (idRel.equalsIgnoreCase(jnlpRel)) {
            return true;
        }
        int idSlash = idRel.lastIndexOf('/');
        int jnlpSlash = jnlpRel.lastIndexOf('/');
        if (idSlash <= 0 || jnlpSlash <= 0) {
            return false;
        }
        String idDir = idRel.substring(0, idSlash + 1);
        String jnlpDir = jnlpRel.substring(0, jnlpSlash + 1);
        return idDir.equalsIgnoreCase(jnlpDir);
    }

    /**
     * @return true when another javaws/JNLP instance holds {@link PathsAndFiles#MAIN_LOCK}
     */
    public static boolean isCacheLockedByOtherInstance() {
        return !okToClearCache();
    }

    public static void removeWindowsShortcuts(String jnlpApp)
            throws IOException {
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Clearing Windows shortcuts");
        if (CacheLRUWrapper.getInstance().getWindowsShortcutList().exists()) {
            List<String> lines = Files.readAllLines(CacheLRUWrapper.getInstance().getWindowsShortcutList().toPath(), Charset.forName("UTF-8"));
            Iterator it = lines.iterator();
            Boolean fDelete;
            while (it.hasNext()) {
                String sItem = it.next().toString();
                String[] sArray = sItem.split(",");
                String application = sArray[0];
                String sPath = sArray[1];
                // if application is codebase then delete files
                if (application.equalsIgnoreCase(jnlpApp)) {
                    fDelete = true;
                    it.remove();
                } else {
                    fDelete = false;
                }
                if (jnlpApp.equals("ALL")) {
                    fDelete = true;
                }
                if (fDelete) {
                    OutputController.getLogger().log("Deleting item = " + sPath);
                    File scList = new File(sPath);
                    try {
                        FileUtils.recursiveDelete(scList, scList);
                    } catch (Exception e) {
                        OutputController.getLogger().log(e);
                    }
                }
            }
            if (jnlpApp.equals("ALL")) {
                //delete shortcut list file
                Files.deleteIfExists(CacheLRUWrapper.getInstance().getWindowsShortcutList().toPath());
            } else {
                //write file after application scuts have been removed
                Files.write(CacheLRUWrapper.getInstance().getWindowsShortcutList().toPath(), lines, Charset.forName("UTF-8"));
            }
        }

    }

     public static void listCacheIds(String filter, boolean jnlpPath, boolean domain) {
         List<CacheId> items = getCacheIds(filter, jnlpPath, domain);
         if (JNLPRuntime.isDebug()) {
             for (CacheId id : items) {
                 OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, id.getId()+" ("+id.getType()+") ["+id.files.size()+"]");
                 for(Object[] o: id.getFiles()){
                     StringBuilder sb = new StringBuilder();
                     for (int i = 0; i < o.length; i++) {
                         Object object = o[i];
                         if (object == null) {
                             object = "??";
                         }
                         sb.append(object.toString()).append(" ;  ");
                     }
                     OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, "  * " + sb);
                 }
             }
         } else {
             for (CacheId id : items) {
                 OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, id.getId());
             }
         }
     }

     /**
      * This method load all known IDs of applications and  will gather all members, which share the id
     * @param filter - regex to filter keys
      * @return
      */
      public static List<CacheId> getCacheIds(final String filter, final boolean jnlpPath, final boolean domain) {
        final List<CacheId> r = new ArrayList<>();
        final CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        synchronized (lruHandler) {
        lruHandler.lock();
        try {
            lruHandler.load();
            for (CacheEntryMeta row : lruHandler.listAllMeta()) {
                if (jnlpPath) {
                    addCacheJnlpId(r, row.jnlpPath, filter);
                    addCacheJnlpId(r, hrefFromCacheRelativePath(row.resourceUrl), filter);
                }
                if (domain) {
                    String host = hostFromCacheRelativePath(row.resourceUrl);
                    if (host != null && host.matches(filter)) {
                        CacheId doaminId = new CacheDomainId(host);
                        if (!r.contains(doaminId)) {
                            r.add(doaminId);
                            doaminId.populate();
                        }
                    }
                }
            }
        } finally {
            lruHandler.unlock();
        }
        }
        return r;
    }

    /**
     * Returns a boolean indicating if it ok to clear the netx application cache at this point
     * @return true if the cache can be cleared at this time without problems
     */
    private static boolean okToClearCache() {
        File otherJavawsRunning = PathsAndFiles.MAIN_LOCK.getFile();
        FileLock locking = null;
        try {
            if (otherJavawsRunning.isFile()) {
                FileOutputStream fis = new FileOutputStream(otherJavawsRunning);

                FileChannel channel = fis.getChannel();
                locking  = channel.tryLock();
                if (locking == null) {
                    OutputController.getLogger().log("Other instances of javaws are running");
                    return false;
                }
                OutputController.getLogger().log("No other instances of javaws are running");
                return true;

            } else {
                OutputController.getLogger().log("No instance file found");
                return true;
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (locking != null) {
                try {
                    locking.release();
                } catch (IOException ex) {
                    OutputController.getLogger().log(ex);
                }
            }
        }
    }

    /**
     * Returns whether there is a version of the URL contents in the
     * cache and it is up to date.  This method may not return
     * immediately.
     *
     * @param source the source {@link URL}
     * @param version the versions to check for
     * @param lastModifed time in milis since epoch of last modfication
     * @return whether the cache contains the version
     * @throws IllegalArgumentException if the source is not cacheable
     */
    public static boolean isCurrent(URL source, Version version, long lastModifed, CacheEntry entry, File cachedFile) {

        if (!isCacheable(source, version))
            throw new IllegalArgumentException(R("CNotCacheable", source));

        try {
            boolean result = entry.isCurrent(lastModifed, cachedFile);

            OutputController.getLogger().log("isCurrent: " + source + " = " + result);

            return result;
        } catch (Exception ex) {
            OutputController.getLogger().log(ex);
            return isCached(source, version); // if can't connect return whether already in cache
        }
    }

    /**
     * Returns true if the cache has a local copy of the contents of
     * the URL matching the specified version string.
     *
     * @param source the source URL
     * @param version the versions to check for
     * @return true if the source is in the cache
     * @throws IllegalArgumentException if the source is not cacheable
     */
    public static boolean isCached(URL source, Version version) {
        if (!isCacheable(source, version))
            throw new IllegalArgumentException(R("CNotCacheable", source));

        CacheEntry entry = new CacheEntry(source, version); // could pool this
        boolean result = entry.isCached();

        OutputController.getLogger().log("isCached: " + source + " = " + result);

        return result;
    }

    /**
     * Returns whether the resource can be cached as a local file;
     * if not, then URLConnection.openStream can be used to obtain
     * the contents.
     * @param source the url of resource
     * @param version version of resource
     * @return whether this resource can be cached
     */
    public static boolean isCacheable(URL source, Version version) {
        if (source == null)
            return false;

        if (USE_LEGACY_FILE_URL_CACHE_BYPASS && source.getProtocol().equals("file")){
            return false;
        }
        if (source.getProtocol().equals("jar")){
            return false;
        }
        return true;
    }

    /**
     * Returns the file for the locally cached contents of the
     * source.  This method returns the file location only and does
     * not download the resource.  The latest version of the
     * resource that matches the specified version will be returned.
     *
     * @param source the source {@link URL}
     * @param version the version id of the local file
     * @return the file location in the cache, or {@code null} if no versions cached
     * @throws IllegalArgumentException if the source is not cacheable
     */
    public static File getCacheFile(URL source, Version version) {
        // ensure that version is an version id not version string

        if (!isCacheable(source, version))
            throw new IllegalArgumentException(R("CNotCacheable", source));

        File cacheFile = null;
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        synchronized (lruHandler) {
            try {
                lruHandler.lock();

                // We need to reload the cacheOrder file each time
                // since another plugin/javaws instance may have updated it.
                lruHandler.load();
                cacheFile = getCacheFileIfExist(urlToPath(source, ""));
                if (cacheFile == null) { // We did not find a copy of it.
                    cacheFile = makeNewCacheFile(source, version);
                } else
                    lruHandler.store();
            } finally {
                lruHandler.unlock();
            }
        }
        return cacheFile;
    }

    /**
     * This will return a File pointing to the location of cache item.
     *
     * @param urlPath Path of cache item within cache directory.
     * @return File if we have searched before, {@code null} otherwise.
     */
    private static File getCacheFileIfExist(File urlPath) {
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        synchronized (lruHandler) {
            File cacheFile = null;
            List<Entry<String, String>> entries = lruHandler.findEntriesByUrlPath(urlPath.getPath());
            // Newest first so an intentional makeNewCacheFile reserved slot wins for writes.
            for (Entry<String, String> e : entries) {
                final String key = e.getKey();
                final String path = e.getValue();
                try {
                    File candidate = new File(path);
                    // Catalog row is the reservation. Do not require a sidecar .info file.
                    if (!candidate.isFile() && lruHandler.getMetaByPath(path) == null
                            && !lruHandler.containsValue(path)) {
                        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                                "Ignoring orphaned cache path listed in catalog: " + path);
                        continue;
                    }
                    cacheFile = candidate;
                    lruHandler.updateEntry(key);
                    break;
                } catch (Exception e2) {
                    //Fuzzy logic to prevent catastrophic startup failure by effectively downloading
                    //the jar again if there is a problem obtaining the cached jar
                    OutputController.getLogger().log(e2);
                }
            }
            return cacheFile;
        }
    }

    /**
     * Find an on-disk cached copy of {@code source} even when the newest LRU slot is a
     * ghost (catalog row / deleted folder). Used to recover launches that would otherwise
     * open a missing path in JarCertVerifier while a good jar still exists elsewhere.
     * For {@code .jar} URLs, candidates must pass {@link #isValidJarFile(File)} so a
     * sticky JNLP version-protocol error body (e.g. {@code 11 Could not locate requested
     * version}) cannot win over a real jar in another LRU folder.
     *
     * @return an existing non-empty (and, for jars, zip-magic) cache file, or {@code null}
     */
    public static File findExistingCacheFile(URL source, Version version) {
        if (!isCacheable(source, version)) {
            return null;
        }
        final boolean requireJarMagic = isJarResourceUrl(source);
        File urlPath = urlToPath(source, "");
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        synchronized (lruHandler) {
            try {
                lruHandler.lock();
                lruHandler.load();
                for (Entry<String, String> e : lruHandler.findEntriesByUrlPath(urlPath.getPath())) {
                    final String path = e.getValue();
                    try {
                        File candidate = new File(path);
                        if (candidate.isFile() && candidate.length() > 0
                                && (!requireJarMagic || isValidJarFile(candidate))) {
                            lruHandler.updateEntry(e.getKey());
                            lruHandler.store();
                            return candidate;
                        }
                    } catch (Exception ex) {
                        OutputController.getLogger().log(ex);
                    }
                }
            } finally {
                lruHandler.unlock();
            }
        }
        return null;
    }

    /**
     * Whether {@code location} names a jar resource (path ends with {@code .jar}, ignoring
     * query/fragment). Used to decide when zip-magic validation applies.
     */
    public static boolean isJarResourceUrl(URL location) {
        if (location == null) {
            return false;
        }
        String path = location.getPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        // Version-encoded downloads use name__V1.2.jar
        return name.toLowerCase().endsWith(".jar");
    }

    /**
     * True when {@code file} exists, is non-empty, and begins with a ZIP local-file or
     * empty-archive header ({@code PK\x03\x04} / {@code PK\x05\x06}). Rejects JNLP download
     * servlet error bodies and raw {@code .pack.gz} bytes mistakenly stored as {@code .jar}.
     */
    public static boolean isValidJarFile(File file) {
        if (file == null || !file.isFile() || file.length() < 4) {
            return false;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            byte[] header = new byte[4];
            int n = in.read(header);
            if (n < 4) {
                return false;
            }
            return header[0] == 'P' && header[1] == 'K'
                    && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6));
        } catch (IOException ex) {
            OutputController.getLogger().log(ex);
            return false;
        }
    }

    /**
     * Open the jar with signature/digest verification enabled and drain every entry.
     * Throws when the ZIP is truncated or unreadable — call this
     * <em>before</em> marking a download GOOD so corrupt payloads never settle as cached.
     * Signature/digest trust is {@code JarCertVerifier}'s job (do not verify twice).
     * Does not use {@code JarFileCache} (must not pin a bad file).
     *
     * @return brief result text suitable for logging when verification succeeds
     */
    public static String verifyJarIntegrity(File file) throws IOException {
        if (!isValidJarFile(file)) {
            throw new IOException("not a valid JAR (missing/empty/non-ZIP): " + file);
        }
        byte[] buffer = new byte[8192];
        int entries = 0;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file, false)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                java.util.jar.JarEntry je = en.nextElement();
                entries++;
                try (InputStream is = jar.getInputStream(je)) {
                    while (is.read(buffer) != -1) {
                        // drain — ZipException if the central directory / payload is truncated
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("JAR integrity check failed for " + file + ": " + e.getMessage(), e);
        }
        return "file has integrity (ZIP OK, " + entries + " entries) path="
                + file.getAbsolutePath();
    }

    /**
     * Short printable preview of a corrupt payload for logs / exception messages.
     */
    public static String previewFileHead(File file, int maxChars) {
        if (file == null || !file.isFile() || maxChars <= 0) {
            return null;
        }
        try {
            byte[] all = Files.readAllBytes(file.toPath());
            int n = Math.min(all.length, maxChars);
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                char c = (char) (all[i] & 0xff);
                sb.append(c < 32 || c == 127 ? ' ' : c);
            }
            return sb.toString().trim();
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Get the path to file minus the cache directory and indexed folder.
     */
    public static String pathToURLPath(String path) {
        return pathToURLPath(path, CacheLRUWrapper.getInstance().getCacheDir().getFullPath());
    }

    /**
     * Get the path to file minus the cache directory and indexed folder.
     * This string is the SQLite {@code resource_url} / properties lookup key.
     *
     * @param path absolute cache file path
     * @param cacheDir effective cache root (legacy {@code cachedir} or sqlite {@code cache/db})
     */
    public static String pathToURLPath(String path, String cacheDir) {
        // Normalize paths: convert backslashes to forward slashes for consistent comparison
        // and handle both trailing separator and no trailing separator cases
        String normalizedPath = path.replace('\\', '/');
        String normalizedCacheDir = cacheDir.replace('\\', '/');
        
        // Remove trailing separator from cache directory if present
        if (normalizedCacheDir.endsWith("/")) {
            normalizedCacheDir = normalizedCacheDir.substring(0, normalizedCacheDir.length() - 1);
        }
        
        // Check if path starts with cache directory (case-insensitive on Windows)
        if (!normalizedPath.toLowerCase().startsWith(normalizedCacheDir.toLowerCase())) {
            // Path doesn't start with cache directory, return as-is
            return path;
        }
        
        // Find the separator after the cache directory
        int cacheDirLen = normalizedCacheDir.length();
        if (normalizedPath.length() <= cacheDirLen) {
            // Path is exactly the cache directory or shorter, return as-is
            return path;
        }
        
        // Check if there's a separator right after the cache directory
        int index = -1;
        if (normalizedPath.charAt(cacheDirLen) == '/') {
            // Separator is at cacheDirLen, look for next separator after that
            index = normalizedPath.indexOf('/', cacheDirLen + 1);
        } else {
            // No separator immediately after cache dir, look for first separator
            index = normalizedPath.indexOf('/', cacheDirLen);
        }
        
        if (index == -1) {
            // No separator found after cache directory - this means the path is just cacheDir/1144
            // Return the part after cache directory (e.g., "/1144" or "1144")
            if (normalizedPath.length() > cacheDirLen) {
                String remainder = normalizedPath.substring(cacheDirLen);
                // Convert back to original separator format
                return remainder.replace('/', File.separatorChar);
            }
            return path;
        }
        
        // Return the path starting from the separator after the indexed folder
        String result = normalizedPath.substring(index);
        // Convert back to original separator format
        return result.replace('/', File.separatorChar);
    }

    /**
     * Returns the parent directory of the cached resource.
     * @param filePath The path of the cached resource directory.
     * @return parent dir of cache
     */
    public static String getCacheParentDirectory(String filePath) {
        String path = filePath;
        String tempPath;
        String cacheDir = CacheLRUWrapper.getInstance().getCacheDir().getFullPath();

        while(path.startsWith(cacheDir) && !path.equals(cacheDir)){
                tempPath = new File(path).getParent();

                if (tempPath.equals(cacheDir))
                    break;

                path = tempPath;
        }
        return path;
    }

    /**
     * This will create a new entry for the cache item. It is however not
     * initialized but any future calls to getCacheFile with the source and
     * version given to here, will cause it to return this item.
     *
     * @param source the source URL
     * @param version the version id of the local file
     * @return the file location in the cache.
     */
    public static File makeNewCacheFile(URL source, Version version) {
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        synchronized (lruHandler) {
            File cacheFile = null;
            try {
                lruHandler.lock();
                lruHandler.load();
                int folderId = lruHandler.nextFolderId();
                String path = lruHandler.getCacheDir().getFullPath() + File.separator + folderId;
                try {
                    cacheFile = urlToPath(source, path);
                    FileUtils.createParentDir(cacheFile);
                    // Metadata is on the catalog row. Do not create a sidecar .info file.
                    lruHandler.addEntry(lruHandler.generateKey(cacheFile.getPath()), cacheFile.getPath());
                } catch (IOException ioe) {
                    OutputController.getLogger().log(ioe);
                }

                lruHandler.store();
            } finally {
                lruHandler.unlock();
            }
            return cacheFile;
        }
    }

    /**
     * Returns a buffered output stream open for writing to the
     * cache file.
     *
     * @param source the remote location
     * @param version the file version to write to
     * @return the stream to write to resource
     * @throws java.io.IOException if IO breaks
     */
    public static OutputStream getOutputStream(URL source, Version version) throws IOException {
        File localFile = getCacheFile(source, version);
        OutputStream out = new FileOutputStream(localFile);
        return new BufferedOutputStream(out);
    }

    public static InputStream ensureInputBuffered(InputStream in) throws IOException {
        if (!(in instanceof BufferedInputStream)) {
            in = new BufferedInputStream(in);
        }
        return in;
    }

    public static OutputStream ensureOutputBuffered(OutputStream out) throws IOException {
        if (!(out instanceof BufferedOutputStream)) {
            out = new BufferedOutputStream(out);
        }
        return out;
    }

    /**
     * Copies from an input stream to an output stream.  On
     * completion, both streams will be closed.  Streams are
     * buffered automatically.
     * @param is stream to read from
     * @param os stream to write to
     * @throws java.io.IOException if copy fails
     */
    public static void streamCopy(InputStream is, OutputStream os) throws IOException {
        if (!(is instanceof BufferedInputStream))
            is = new BufferedInputStream(is);

        if (!(os instanceof BufferedOutputStream))
            os = new BufferedOutputStream(os);

        try {
            byte b[] = new byte[4096];
            while (true) {
                int c = is.read(b, 0, b.length);
                if (c == -1)
                    break;

                os.write(b, 0, c);
            }
        } finally {
            is.close();
            os.close();
        }
    }

    /**
     * Converts a URL into a local path string within the given directory. For
     * example a url with subdirectory /tmp/ will
     * result in a File that is located somewhere within /tmp/
     *
     * @param location the url
     * @param subdir the subdirectory
     * @return the file
     */
    public static File urlToPath(URL location, String subdir) {
        location = CacheEntry.removePackGzSuffix(location);
        if (subdir == null) {
            throw new NullPointerException();
        }

        StringBuilder path = new StringBuilder();

        path.append(subdir);
        path.append(File.separatorChar);

        path.append(location.getProtocol());
        path.append(File.separatorChar);
        path.append(location.getHost());
        path.append(File.separatorChar);
        /**
         * This is a bit of imprecise. The usage of default port would be
         * better, but it would cause terrible backward incompatibility.
         */
        if (location.getPort() > 0) {
            path.append(location.getPort());
            path.append(File.separatorChar);
        }
        String locationPath = location.getPath();
        String query = "";
        if (location.getQuery() != null) {
            query = location.getQuery();
        }
        if (locationPath.contains("..") || query.contains("..")){
            try {
                /**
                 * if path contains .. then it can harm lcoal system
                 * So without mercy, hash it
                 */
                String hexed = hex(new File(locationPath).getName(), locationPath);
                return new File(path.toString(), hexed.toString());
            } catch (NoSuchAlgorithmException ex) {
                // should not occur, cite from javadoc:
                // every java implementation should support
                // MD5 SHA-1 SHA-256
                throw new RuntimeException(ex);
            }
        } else {
            path.append(locationPath.replace('/', File.separatorChar));
            if (location.getQuery() != null && !location.getQuery().trim().isEmpty()) {
                path.append(".").append(location.getQuery());
            }
            File candidate = new File(FileUtils.sanitizePath(path.toString()));
            try {
                if (candidate.getName().length() > 255) {
                    /**
                     * When filename is longer then 255 chars, then then various
                     * filesystems have issues to save it. By saving the file by its
                     * sum, we are trying to prevent collision of two files differs in
                     * suffixes (general suffix of name, not only 'filetype suffix')
                     * only. It is also preventing bug when truncate (files with 1000
                     * chars hash in query) cuts to much.
                     */
                    String hexed = hex(candidate.getName(), candidate.getName());
                    candidate = new File(candidate.getParentFile(), hexed.toString());
                }
            } catch (NoSuchAlgorithmException ex) {
                // should not occur, cite from javadoc:
                // every java implementation should support
                // MD5 SHA-1 SHA-256
                throw new RuntimeException(ex);
            }
            return candidate;
        }
    }

    public static String hex(String origName, String candidate) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] sum = md.digest(candidate.getBytes(StandardCharsets.UTF_8));
        //convert the byte to hex format method 2
        StringBuilder hexString = new StringBuilder();
        for (int i = 0; i < sum.length; i++) {
            hexString.append(Integer.toHexString(0xFF & sum[i]));
        }
        String extension = "";
        int i = origName.lastIndexOf('.');
        if (i > 0) {
            extension = origName.substring(i);//contains dot
        }
        if (extension.length() < 10 && extension.length() > 1) {
            hexString.append(extension);
        }
        return hexString.toString();
    }

    /**
     * Waits until the resources are downloaded, while showing a
     * progress indicator.
     *
     * @param app application instance with context for this resource
     * @param tracker the resource tracker
     * @param resources the resources to wait for
     * @param title name of the download
     */
    public static void waitForResources(ApplicationInstance app, ResourceTracker tracker, URL resources[], String title) {
        DownloadIndicator indicator = JNLPRuntime.getDefaultDownloadIndicator();
        DownloadServiceListener listener = null;

        try {
            if (DownloadProgress.isEnabled()) {
                if (resources == null || resources.length < 2) {
                    tracker.waitForResources(resources, 0);
                    return;
                }
                long known = 0L;
                for (int i = 0; i < resources.length; i++) {
                    long s = tracker.getWireSize(resources[i]);
                    if (s <= 0L) {
                        s = tracker.getTotalSize(resources[i]);
                    }
                    if (s > 0) {
                        known += s;
                    }
                }
                DownloadProgress.begin(title, tracker, resources, downloadProgressSlots(), known);
                tracker.waitForResources(resources, 0);
                return;
            }
            if (indicator == null) {
                tracker.waitForResources(resources, 0);
                return;
            }

            // see if resources can be downloaded very quickly; avoids
            // overhead of creating display components for the resources
            if (tracker.waitForResources(resources, indicator.getInitialDelay()))
                return;

            // only resources not starting out downloaded are displayed
            List<URL> urlList = new ArrayList<>();
            for (URL url : resources) {
                if (!tracker.checkResource(url))
                    urlList.add(url);
            }
            URL undownloaded[] = urlList.toArray(new URL[urlList.size()]);

            final int maxUrls = Integer.parseInt(JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_MAX_URLS_DOWNLOAD_INDICATOR));

            listener = indicator.getListener(app, title, undownloaded);

            do {
                long read = 0;
                long total = 0;

                for (URL url : undownloaded) {
                    // add in any -1's; they're insignificant
                    total += tracker.getTotalSize(url);
                    read += tracker.getAmountRead(url);
                }

                int percent = (int) ((100 * read) / Math.max(1, total));

                int urlCounter = 0;
                for (URL url : undownloaded) {
                    if (urlCounter > maxUrls) {
                        break;
                    }
                    listener.progress(url, "version",
                                      tracker.getAmountRead(url),
                                      tracker.getTotalSize(url),
                                      percent);
                    urlCounter += 1;
                }
            } while (!tracker.waitForResources(resources, indicator.getUpdateRate()));

            // make sure they read 100% until indicator closes
            int urlCounter = 0;
            for (URL url : undownloaded) {
                if (urlCounter > maxUrls) {
                    break;
                }
                listener.progress(url, "version",
                                  tracker.getTotalSize(url),
                                  tracker.getTotalSize(url),
                                  100);
                urlCounter += 1;
            }
        } catch (InterruptedException ex) {
            OutputController.getLogger().log(ex);
        } finally {
            if (listener != null)
                indicator.disposeListener(listener);
        }
    }

    private static int downloadProgressSlots() {
        int n = 6;
        boolean adaptive = true;
        try {
            n = Integer.parseInt(JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_BACKGROUND_THREADS_COUNT));
        } catch (Exception ignored) {
        }
        try {
            String a = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_BACKGROUND_THREADS_ADAPTIVE);
            if (a != null && !a.trim().isEmpty()) {
                adaptive = Boolean.parseBoolean(a.trim());
            }
        } catch (Exception ignored) {
        }
        return AdaptiveBackgroundThreads.connectionSlots(n, adaptive);
    }

    /**
     * This will remove all old cache items.
     */
    public static void cleanCache() {
        processCacheCleanup(okToClearCache());
    }

    /**
     * Shutdown sweep: apply {@code delete=true} marks only. Never LRU-evict,
     * never treat a missing jar as a ghost slot, and never wipe unmarked
     * siblings (GitHub #16 — a relaunch parent must not delete the child's
     * Pack200 sidecars).
     */
    public static void cleanCacheOnShutdown() {
        processCacheCleanup(false);
    }

    /**
     * True when a unique Pack200 drain file sits beside {@code cacheFile}.
     * The LRU row already names {@code foo.jar} before that file exists.
     */
    static boolean hasInFlightPack200Sidecar(File cacheFile) {
        if (cacheFile == null) {
            return false;
        }
        File parent = cacheFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return false;
        }
        String prefix = cacheFile.getName() + ".pack.gz.download.";
        File[] children = parent.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().startsWith(prefix) && child.length() > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes cache entries marked for deletion always; LRU size enforcement only when allowed.
     */
    private static void processCacheCleanup(boolean enforceLruLimit) {
        CacheLRUWrapper lruHandler = CacheLRUWrapper.getInstance();
        HashSet<String> keep = new HashSet<>();
        HashSet<String> remove = new HashSet<>();
        synchronized (lruHandler) {
        try {
            lruHandler.lock();
            lruHandler.load();

            long maxSize = -1; // Default
            try {
                maxSize = Long.parseLong(JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_CACHE_MAX_SIZE));
            } catch (NumberFormatException nfe) {
            }

            maxSize = maxSize << 20; // Convert from megabyte to byte (Negative values will be considered unlimited.)
            long curSize = 0;

            for (Entry<String, String> e : lruHandler.getLRUSortedEntries()) {
                // Check if the item is contained in cacheOrder.
                final String key = e.getKey();
                final String path = e.getValue();

                File file = new File(path);
                CacheEntryMeta rowMeta = lruHandler.getMetaByPath(path);
                boolean delete = rowMeta != null && rowMeta.markedDelete;

            /*
             * This will get me the root directory specific to this cache item.
             * Example:
             *  cacheDir = /home/user1/.icedtea/cache
             *  file.getPath() = /home/user1/.icedtea/cache/0/http/www.example.com/subdir/a.jar
             *  rStr first becomes: /0/http/www.example.com/subdir/a.jar
             *  then rstr becomes: /home/user1/.icedtea/cache/0
             */
                String rStr = file.getPath().substring(lruHandler.getCacheDir().getFullPath().length());
                rStr = lruHandler.getCacheDir().getFullPath()+ rStr.substring(0, rStr.indexOf(File.separatorChar, 1));
                long len = file.length();

                if (keep.contains(path)) {
                    lruHandler.removeEntry(key);
                    continue;
                }

            /*
             * Remove only when marked delete, or (last-instance LRU) a true ghost /
             * over-max slot. A missing jar during an in-flight Pack200 GET is not
             * a ghost — the sidecar is the live download (GitHub #16 follow-up).
             */
                boolean inFlight = hasInFlightPack200Sidecar(file);
                boolean overMax = enforceLruLimit && maxSize >= 0 && curSize + len > maxSize;
                if (delete) {
                    lruHandler.removeEntry(key);
                    remove.add(rStr);
                    continue;
                }
                if (inFlight) {
                    keep.add(path);
                    continue;
                }
                if (enforceLruLimit && (!file.isFile() || overMax)) {
                    lruHandler.removeEntry(key);
                    remove.add(rStr);
                    continue;
                }

                if (file.isFile()) {
                    curSize += len;
                }
                keep.add(path);
            }
            lruHandler.store();
        } finally {
            lruHandler.unlock();
        }
        }
        removeSetOfDirectories(remove);
    }

    private static void removeSetOfDirectories(Set<String> remove) {
        for (String s : remove) {
            File f = new File(s);
            try {
                FileUtils.recursiveDelete(f, f);
            } catch (IOException e) {
            }
        }
    }

    static class CacheJnlpId extends CacheId {

        public CacheJnlpId(String id) {
            super(id);
        }

        @Override
        public void populate() {
            ArrayList<Object[]> all = CachePane.generateData();
            for (Object[] object : all) {
                if (id.equals(object[6])) {
                    this.files.add(object);
                }
            }
        }

        @Override
        String getType() {
            return "JNLP-PATH";
        }

        @Override
        //hascode in super is ok
        public boolean equals(Object obj) {
            if (obj instanceof CacheJnlpId) {
                return super.equals(obj);
            } else {
                return false;
            }
        }

    }

    static class CacheDomainId extends CacheId {

        public CacheDomainId(String id) {
            super(id);
        }

        @Override
        public void populate() {
            ArrayList<Object[]> all = CachePane.generateData();
            for (Object[] object : all) {
                if (id.equals(object[3].toString())) {
                    this.files.add(object);
                }
            }
        }

        @Override
        String getType() {
            return "DOMAIN";
        }

        @Override
        //hascode in super is ok
        public boolean equals(Object obj) {
            if (obj instanceof CacheDomainId) {
                return super.equals(obj);
            } else {
                return false;
            }
        }

    }

    public abstract static class CacheId {

        //last century array of objects instead of some nice class inherited from previous century
        protected final List<Object[]> files = new ArrayList<>();

        abstract void populate();

        abstract String getType();

        protected final String id;

        public CacheId(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return id;
        }

        public List<Object[]> getFiles() {
            return files;
        }

        public String getId() {
            return id;
        }




        @Override
        public boolean equals(Object obj) {
            if (obj instanceof CacheId) {
                CacheId c = (CacheId) obj;
                if (c.id == null && this.id == null) {
                    return true;
                }
                if (c.id == null) {
                    return false;
                }
                return c.id.equals(this.id);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.id);
        }

    }

    private static void addCacheJnlpId(List<CacheId> ids, String id, String filter) {
        if (id == null || id.isEmpty() || !id.matches(filter)) {
            return;
        }
        CacheId jnlpPathId = new CacheJnlpId(id);
        if (!ids.contains(jnlpPathId)) {
            ids.add(jnlpPathId);
            jnlpPathId.populate();
        }
    }

    /**
     * True when this catalog row is the requested cache id itself:
     * stored {@code jnlp-path}, exact resource URL, or a hostname
     * domain id. URLs never match via domain.
     */
    static boolean catalogRowExactMatch(CacheEntryMeta row, String application,
            boolean matchJnlpPath, boolean matchDomain) {
        if (row == null || application == null) {
            return false;
        }
        if (matchJnlpPath) {
            if (application.equalsIgnoreCase(row.jnlpPath)) {
                return true;
            }
            String wantedRel = applicationToCacheRelativePath(application);
            String cachedRel = row.resourceUrl != null
                    ? normalizeCacheRelativePath(row.resourceUrl) : null;
            if (wantedRel != null && cachedRel != null && cachedRel.equalsIgnoreCase(wantedRel)) {
                return true;
            }
        }
        return matchDomain && looksLikeCacheDomainId(application)
                && application.equalsIgnoreCase(hostFromCacheRelativePath(row.resourceUrl));
    }

    static boolean looksLikeCacheDomainId(String application) {
        if (application == null) {
            return false;
        }
        String t = application.trim();
        return !t.isEmpty() && !t.contains("://") && t.indexOf('/') < 0 && t.indexOf('\\') < 0;
    }

    static String parentCacheRelativeDir(String rel) {
        if (rel == null) {
            return null;
        }
        int last = rel.lastIndexOf('/');
        if (last <= 0) {
            return null;
        }
        return rel.substring(0, last + 1);
    }

    static String applicationToCacheRelativePath(String application) {
        if (application == null || application.trim().isEmpty()) {
            return null;
        }
        try {
            String trimmed = application.trim();
            URL url;
            if (trimmed.contains("://") || trimmed.regionMatches(true, 0, "file:", 0, 5)) {
                url = new URL(trimmed);
            } else {
                File local = new File(trimmed);
                if (!local.isFile()) {
                    return null;
                }
                url = local.toURI().toURL();
            }
            return normalizeCacheRelativePath(urlToPath(url, "").getPath());
        } catch (Exception e) {
            return null;
        }
    }

    static String hrefFromCacheRelativePath(String rel) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        String s = rel;
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int slash1 = s.indexOf('/');
        if (slash1 <= 0) {
            return null;
        }
        String protocol = s.substring(0, slash1);
        String rest = s.substring(slash1 + 1);
        if ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol)) {
            int slash2 = rest.indexOf('/');
            if (slash2 < 0) {
                return protocol + "://" + rest + "/";
            }
            String host = rest.substring(0, slash2);
            String afterHost = rest.substring(slash2 + 1);
            int slash3 = afterHost.indexOf('/');
            String maybePort = slash3 < 0 ? afterHost : afterHost.substring(0, slash3);
            if (maybePort.matches("\\d{1,5}")) {
                String path = slash3 < 0 ? "/" : afterHost.substring(slash3);
                return protocol + "://" + host + ":" + maybePort + path;
            }
            return protocol + "://" + host + "/" + afterHost;
        }
        if ("file".equalsIgnoreCase(protocol)) {
            return "file:/" + rest;
        }
        return null;
    }

    private static String normalizeCacheRelativePath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String s = path.replace('\\', '/');
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    /** First path segment ({@code http}, {@code https}, {@code file}). */
    static String protocolFromCacheRelativePath(String rel) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        String s = rel.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return null;
        }
        int slash = s.indexOf('/');
        return slash <= 0 ? s : s.substring(0, slash);
    }

    static String hostFromCacheRelativePath(String rel) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        String href = hrefFromCacheRelativePath(rel.replace('\\', '/'));
        if (href != null) {
            try {
                String host = new URL(href).getHost();
                if (host != null && !host.isEmpty()) {
                    return host;
                }
            } catch (MalformedURLException ignored) {
            }
        }
        String s = rel.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        int slash1 = s.indexOf('/');
        if (slash1 <= 0) {
            return null;
        }
        String rest = s.substring(slash1 + 1);
        int slash2 = rest.indexOf('/');
        String host = slash2 < 0 ? rest : rest.substring(0, slash2);
        return host.isEmpty() ? null : host;
    }
}
