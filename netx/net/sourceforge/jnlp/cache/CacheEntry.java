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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import java.util.concurrent.locks.ReentrantLock;

import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Describes an entry in the cache.
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.10 $
 */
public class CacheEntry {

    private static final String KEY_CONTENT_LENGTH = "content-length";
    private static final String KEY_WIRE_LENGTH = "wire-length";
    private static final String KEY_LAST_MODIFIED = "last-modified";
    private static final String KEY_LAST_UPDATED = "last-updated";
    public static final String KEY_JNLP_PATH = "jnlp-path";

    /** the remote resource location */
    private final URL location;

    /** the requested version */
    private final Version version;

    /** Catalog-row metadata (not a sidecar {@code .info} file). */
    private final CacheEntryMeta meta = new CacheEntryMeta();
    private final ReentrantLock entryLock = new ReentrantLock();

    private File localFile;

    private boolean directPackGz;

    public CacheEntry(URL location, Version version) {
        this(location, version, false);
    }

    /**
     * Create a CacheEntry for the resources specified as a remote
     * URL.
     *
     * @param location the remote resource location
     * @param version the version of the resource
     */
    public CacheEntry(URL location, Version version, boolean directPackGz) {
        this.location = location;
        this.version = version;
        this.directPackGz = directPackGz;

        this.localFile = directPackGz ? CacheUtil.getCacheFile(removePackGzSuffix(location), version)
                : CacheUtil.getCacheFile(location, version);
        if (this.localFile != null) {
            this.meta.path = this.localFile.getPath();
            CacheEntryMeta stored = CacheLRUWrapper.getInstance().getMetaByPath(this.meta.path);
            if (stored != null) {
                this.meta.jnlpPath = stored.jnlpPath;
                this.meta.contentLength = stored.contentLength;
                this.meta.wireLength = stored.wireLength;
                this.meta.lastModified = stored.lastModified;
                this.meta.lastUpdated = stored.lastUpdated;
                this.meta.markedDelete = stored.markedDelete;
                this.meta.resourceUrl = stored.resourceUrl;
            }
        }
    }

    public static URL removePackGzSuffix(URL url) {
        String urlString = url.toString();
        if (urlString.endsWith(".pack.gz")) {
            String modifiedUrlString = urlString.substring(0, urlString.length() - 8); // Remove the last 8 characters
            try {
                return new URL(modifiedUrlString);
            } catch (MalformedURLException e) {
                System.err.println("Error creating URL: " + e.getMessage());
            }
        }
        return url; // No change needed
    }

    public static File removePackGzSuffixFromFile(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Input file cannot be null.");
        }

        String originalName = file.getName();
        if (originalName.endsWith(".pack.gz")) {
            String modifiedName = originalName.substring(0, originalName.length() - 8); // Remove the last 8 characters
            String parentPath = file.getParent(); // Get the parent directory path
            return new File(parentPath, modifiedName);
        } else {
            return file; // No change needed
        }
    }

    /**
     * Returns the remote location this entry caches.
     * @return URL same as the one on which this entry was created
     */
    public URL getLocation() {
        return location;
    }

    /**
     * Returns the time in the local system clock that the file was
     * most recently checked for an update.
     * @return when the item was updated (in ms)
     */
    public long getLastUpdated() {
        return getLongKey(KEY_LAST_UPDATED);
    }

    /**
     * Sets the time in the local system clock that the file was
     * most recently checked for an update.
     * @param updatedTime the time (in ms) to be set as last updated time
     */
    public void setLastUpdated(long updatedTime) {
        setLongKey(KEY_LAST_UPDATED, updatedTime);
    }

    public long getRemoteContentLength() {
        return getLongKey(KEY_CONTENT_LENGTH);
    }

    public void setRemoteContentLength(long length) {
        setLongKey(KEY_CONTENT_LENGTH, length);
    }

    public long getRemoteWireLength() {
        return getLongKey(KEY_WIRE_LENGTH);
    }

    public void setRemoteWireLength(long length) {
        setLongKey(KEY_WIRE_LENGTH, length);
    }

    public void setJnlpPath(String jnlpPath) {
        meta.jnlpPath = jnlpPath;
    }

    public long getLastModified() {
        return getLongKey(KEY_LAST_MODIFIED);
    }

    public void setLastModified(long modifyTime) {
        setLongKey(KEY_LAST_MODIFIED, modifyTime);
    }

    private long getLongKey(String key) {
        Long v = longField(key);
        return v == null ? 0L : v;
    }

    private void setLongKey(String key, long value) {
        if (KEY_CONTENT_LENGTH.equals(key)) {
            meta.contentLength = value;
        } else if (KEY_WIRE_LENGTH.equals(key)) {
            meta.wireLength = value;
        } else if (KEY_LAST_MODIFIED.equals(key)) {
            meta.lastModified = value;
        } else if (KEY_LAST_UPDATED.equals(key)) {
            meta.lastUpdated = value;
        }
    }

    private Long longField(String key) {
        if (KEY_CONTENT_LENGTH.equals(key)) {
            return meta.contentLength;
        }
        if (KEY_WIRE_LENGTH.equals(key)) {
            return meta.wireLength;
        }
        if (KEY_LAST_MODIFIED.equals(key)) {
            return meta.lastModified;
        }
        if (KEY_LAST_UPDATED.equals(key)) {
            return meta.lastUpdated;
        }
        return null;
    }

    /**
     * Returns whether there is a version of the URL contents in
     * the cache and it is up to date.
     *
     * @param lastModified - current time as get from server (in ms). Mostly value of "Last-Modified" http header'?
     * @return whether the cache contains the version
     */
    public boolean isCurrent(long lastModified) {
        return isCurrent(lastModified, null);
    }

    public boolean isCurrent(long lastModified, File cachedFile) {
        boolean cached = isCached(cachedFile);
        OutputController.getLogger().log("isCurrent:isCached " + cached);

        if (!cached) {
            return false;
        }
        try {
            long cachedModified = meta.lastModified == null ? 0L : meta.lastModified;
            OutputController.getLogger().log("isCurrent:lastModified cache:" + cachedModified +  " actual:" + lastModified);
            // Servers that omit Last-Modified (common for simple local HTTP such as
            // Undertow test hosts) report 0. Only treat as current when THIS entry's
            // file is present — never when another LRU slot happens to contain a jar.
            if (lastModified <= 0) {
                return true;
            }
            return lastModified <= cachedModified;
        } catch (Exception ex){
            OutputController.getLogger().log(ex);
            return cached;
        }
    }

    /**
     * Returns true if the cache has a local copy of the contents
     * of the URL matching the specified version string.
     *
     * @return true if the resource is in the cache
     */
    public boolean isCached() {
        return isCached(null);
    }

    public boolean isCached(File cachedFile) {
        // Always evaluate THIS entry's file (via getCacheFile()/localFile). Re-querying
        // CacheUtil.getCacheFile() can return a different LRU slot while this entry still
        // points at a newer empty reserved path — which then gets marked DOWNLOADED and
        // fails in JarCertVerifier with NoSuchFileException.
        final File fileToCheck = cachedFile != null ? cachedFile : getCacheFile();
        if (fileToCheck == null || !fileToCheck.isFile() || fileToCheck.length() == 0) {
            return false;
        }
        // JNLP versioning can return a text error body with HTTP 200
        // ("11 Could not locate requested version"). That must never count as cached,
        // especially when lastModified is 0 (isCurrent would otherwise stick forever).
        if (CacheUtil.isJarResourceUrl(location) && !CacheUtil.isValidJarFile(fileToCheck)) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "isCached: rejecting non-jar payload at " + fileToCheck
                            + " preview=" + CacheUtil.previewFileHead(fileToCheck, 80));
            return false;
        }

        try {
            long cachedLength = fileToCheck.length();
            long remoteLength = meta.contentLength == null ? -1L : meta.contentLength;

            OutputController.getLogger().log("isCached: remote:" + remoteLength + " cached:" + cachedLength);

            if (remoteLength >= 0 && cachedLength != remoteLength)
                return false;
            else
                return true;
        } catch (Exception ex) {
            OutputController.getLogger().log(ex);

            return false; // should throw?
        }
    }

    /**
     * HEAD vs catalog: Last-Modified match, or HTTP Content-Length vs stored
     * {@code wire_length} (pack.gz) / on-disk length (plain jar).
     */
    boolean matchesHead(long headLastModified, long headWireLength, File cachedFile) {
        File file = cachedFile != null ? cachedFile : getCacheFile();
        if (file == null || !file.isFile() || file.length() == 0) {
            return false;
        }
        if (headLastModified > 0L && isCurrent(headLastModified, file)) {
            return true;
        }
        if (headWireLength > 0L && isCached(file)) {
            if (meta.wireLength != null && meta.wireLength.longValue() == headWireLength) {
                return true;
            }
            return file.length() == headWireLength;
        }
        return false;
    }

    /**
     * Seam for testing. Production entries stay bound to the path captured at construction
     * so currency checks cannot drift to a different LRU slot mid-flight.
     */
    File getCacheFile() {
        if (localFile != null) {
            return localFile;
        }
        return CacheUtil.getCacheFile(directPackGz ? removePackGzSuffix(location) : location, version);
    }

    /**
     * Save the current information for the cache entry into the catalog.
     *
     * @return True if stored, false otherwise
     */
    protected boolean store() {
        if (!entryLock.isHeldByCurrentThread()) {
            return false;
        }
        if (localFile != null) {
            meta.path = localFile.getPath();
        }
        CacheLRUWrapper.getInstance().putMeta(meta);
        return true;
    }

    /**
     * Mark this entry for deletion at shutdown.
     */
    public void markForDelete() { // once marked it should not be unmarked.
        meta.markedDelete = true;
    }

    /**
     * Lock cache item.
     */
    protected void lock() {
        entryLock.lock();
    }

    /**
     * Unlock cache item. Does not do anything if not holding the lock.
     */
    protected void unlock() {
        if (entryLock.isHeldByCurrentThread()) {
            entryLock.unlock();
        }
    }

    protected boolean tryLock() {
        return entryLock.tryLock();
    }

    protected boolean isHeldByCurrentThread() {
        return entryLock.isHeldByCurrentThread();
    }

    public File getLocalFile() {
        return localFile;
    }
}
