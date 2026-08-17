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

import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTED;
import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTING;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADED;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADING;
import static net.sourceforge.jnlp.cache.Resource.Status.ERROR;
import static net.sourceforge.jnlp.cache.Resource.Status.PRECONNECT;
import static net.sourceforge.jnlp.cache.Resource.Status.PREDOWNLOAD;
import static net.sourceforge.jnlp.cache.Resource.Status.PROCESSING;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.event.DownloadEvent;
import net.sourceforge.jnlp.event.DownloadListener;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * This class tracks the downloading of various resources of a
 * JNLP file to local files in the cache. It can be used to
 * download icons, jnlp and extension files, jars, and jardiff
 * files using the version based protocol or any file using the
 * basic download protocol (jardiff and version not implemented
 * yet).
 * <p>
 * The resource tracker can be configured to prefetch resources,
 * which are downloaded in the order added to the media
 * tracker.
 * </p>
 * <p>
 * Multiple threads are used to download and cache resources that
 * are actively being waited for (blocking a caller) or those that
 * have been started downloading by calling the startDownload
 * method.  Resources that are prefetched are downloaded one at a
 * time and only if no other trackers have requested downloads.
 * This allows the tracker to start downloading many items without
 * using many system resources, but still quickly download items
 * as needed.
 * </p>
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.22 $
 */
public class ResourceTracker {

    // todo: use event listener arrays instead of lists

    // todo: see if there is a way to set the socket options just
    // for use by the tracker so checks for updates don't hang for
    // a long time.

    // todo: ability to restart/retry a hung download

    // todo: move resource downloading/processing code into Resource
    // class, threading stays in ResourceTracker

    // todo: get status method? and some way to convey error status
    // to the caller.

    // todo: might make a tracker be able to download more than one
    // version of a resource, but probably not very useful.

    // defines
    //    ResourceTracker.Downloader (download threads)

    // separately locks on (in order of aquire order, ie, sync on prefetch never syncs on lock):
    //   lock, prefetch, this.resources, each resource, listeners
    public static enum RequestMethods{
        HEAD, GET, TESTING_UNDEF;

    private static final RequestMethods[] requestMethods = {RequestMethods.HEAD, RequestMethods.GET};

        public static RequestMethods[] getValidRequestMethods() {
            return requestMethods;
        }
    }
    
      /** notified on initialization or download of a resource */

    /** the resources known about by this resource tracker (lock-free registry) */
    private final List<Resource> resources = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Resource> resourcesMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** metrics group from the last wait() call, for post-download stats logging */
    private net.sourceforge.jnlp.cache.download.JarGroupState lastMetricsGroup;

    /** download listeners for this tracker (CopyOnWriteArrayList: writes rare, readers lock-free) */
    private final List<DownloadListener> listeners = new CopyOnWriteArrayList<>();

    /** whether to download parts before requested */
    private final boolean prefetch;

    /**
     * Creates a resource tracker that does not prefetch resources.
     */
    public ResourceTracker() {
        this(false);
    }

    /**
     * Creates a resource tracker.
     *
     * @param prefetch whether to download resources before requested.
     */
    public ResourceTracker(boolean prefetch) {
        this.prefetch = prefetch;
    }

    /**
     * Add a resource identified by the specified location and
     * version.  The tracker only downloads one version of a given
     * resource per instance (ie cannot download both versions 1 and
     * 2 of a resource in the same tracker).
     *
     * @param location the location of the resource
     * @param version the resource version
     * @param options options to control download
     * @param updatePolicy whether to check for updates if already in cache
     */
    public void addResource(URL location, Version version, DownloadOptions options, UpdatePolicy updatePolicy) {
        if (location == null)
            throw new IllegalResourceDescriptorException("location==null");
        try {
            location = UrlUtils.normalizeUrl(location);
        } catch (Exception ex) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "Normalization of " + location.toString() + " have failed");
            OutputController.getLogger().log(ex);
        }
        Resource resource = Resource.getResource(location, version, updatePolicy);

        // lock-free registry: putIfAbsent dedups by URL; the COW list is written once here
        if (resourcesMap.putIfAbsent(location.toString(), resource) != null) {
            return;
        }
        resource.addTracker(this);
        // Shared Resource instances are keyed by URL only; a prior launch in this JVM
        // may have spent the one-shot unusable-terminal retry. New tracking must allow
        // recovery again after a terminal-unusable result.
        resource.clearUnusableTerminalRetry();
        resources.add(resource);

        if (options == null) {
            options = new DownloadOptions(false, false);
        }
        resource.setDownloadOptions(options);

        // checkCache may take a while (loads properties file).  this
        // should really be synchronized on resources, but the worst
        // case should be that the resource will be updated once even
        // if unnecessary.
        boolean downloaded = checkCache(resource, updatePolicy);

        if (!downloaded) {
            if (prefetch) {
                startResource(resource);
            }
        }
    }

    /**
     * Removes a resource from the tracker.  This method is useful
     * to allow memory to be reclaimed, but calling this method is
     * not required as resources are reclaimed when the tracker is
     * collected.
     *
     * @param location location of resource to be removed
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public void removeResource(URL location) {
        Resource resource = getResource(location);

        if (resource != null) {
            resources.remove(resource);
            resourcesMap.remove(location.toString());
            resource.removeTracker(this);
        }

        // should remove from queue? probably doesn't matter
    }

    /**
     * Check the cache for a resource, and initialize the resource
     * as already downloaded if found.
     *
     * @param updatePolicy whether to check for updates if already in cache
     * @return whether the resource are already downloaded
     */
    private boolean checkCache(Resource resource, UpdatePolicy updatePolicy) {
        if (!CacheUtil.isCacheable(resource.getLocation(), resource.getDownloadVersion())) {
            // pretend that they are already downloaded; essentially
            // they will just 'pass through' the tracker as if they were
            // never added (for example, not affecting the total download size).
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.GOOD);
            fireDownloadEvent(resource);
            return true;
        }

        if (updatePolicy != UpdatePolicy.ALWAYS && updatePolicy != UpdatePolicy.FORCE) { // save loading entry props file
            CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getDownloadVersion());

            if (entry.isCached() && !updatePolicy.shouldUpdate(entry)) {
                OutputController.getLogger().log("not updating: " + resource.getLocation());

                resource.setLocalFile(CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion()));
                resource.setSize(resource.getLocalFile().length());
                resource.setTransferred(resource.getLocalFile().length());
                resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.GOOD);
                fireDownloadEvent(resource);
                return true;
            }
        }

        if (updatePolicy == UpdatePolicy.FORCE) { // ALWAYS update
            // When we are "always" updating, we update for each instance. Reset resource status.
            resource.resetStatus();
        }

        // may or may not be cached, but check update when connection
        // is open to possibly save network communication time if it
        // has to be downloaded, and allow this call to return quickly
        return false;
    }

    /**
     * Adds the listener to the list of objects interested in
     * receivind DownloadEvents.
     *
     * @param listener the listener to add.
     */
    public void addDownloadListener(DownloadListener listener) {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    /**
     * Removes a download listener.
     *
     * @param listener the listener to remove.
     */
    public void removeDownloadListener(DownloadListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fires the download event corresponding to the resource's
     * state.  This method is typicall called by the Resource itself
     * on each tracker that is monitoring the resource.  Do not call
     * this method with any locks because the listeners may call
     * back to this ResourceTracker.
     * @param resource resource on which event is fired
     */
    protected void fireDownloadEvent(Resource resource) {
        DownloadListener l[] = listeners.toArray(new DownloadListener[0]);

        // getCopyOfStatus reads the JarSlot/terminalState (volatile) — lock-free
        Collection<Resource.Status> status = resource.getCopyOfStatus();

        DownloadEvent event = new DownloadEvent(this, resource);
        for (DownloadListener dl : l) {
            if (status.contains(ERROR) || status.contains(DOWNLOADED))
                dl.downloadCompleted(event);
            else if (status.contains(DOWNLOADING))
                dl.downloadStarted(event);
            else if (status.contains(CONNECTING))
                dl.updateStarted(event);
        }
    }

    /**
     * Returns a URL pointing to the cached location of the
     * resource, or the resource itself if it is a non-cacheable
     * resource.
     * <p>
     * If the resource has not downloaded yet, the method will block
     * until it has been transferred to the cache.
     * </p>
     *
     * @param location the resource location
     * @return the resource, or null if it could not be downloaded
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     * @see CacheUtil#isCacheable
     */
    public URL getCacheURL(URL location) {
        try {
            File f = getCacheFile(location);
            if (f != null)
                // TODO: Should be toURI().toURL()
                return f.toURL();
        } catch (MalformedURLException ex) {
            OutputController.getLogger().log(ex);
        }

        return location;
    }

    /**
     * Returns a file containing the downloaded resource.  If the
     * resource is non-cacheable then null is returned.
     * <p>
     * If the resource has not downloaded yet, the method will block
     * until it has been transferred to the cache.
     * </p>
     *
     * @param location the resource location
     * @return a local file containing the resource, or null
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     * @see CacheUtil#isCacheable
     */
    public File getCacheFile(URL location) {
        try {
            Resource resource = getResource(location);
            // At most two passes: initial wait, then one recovery re-download when the
            // terminal state had no usable local jar (terminal-unusable result).
            for (int pass = 0; pass < 2; pass++) {
                if (!(resource.isSet(DOWNLOADED) || resource.isSet(ERROR))) {
                    waitForResource(location, 0);
                }

                File usable = resolveUsableLocalFile(resource, location);
                if (usable != null) {
                    return usable;
                }

                if (pass == 0 && requeueUnusableTerminal(resource)) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                            "Cache file missing/unusable for " + location
                                    + " after terminal download state; retrying download once");
                    continue;
                }
                break;
            }

            if (CacheUtil.USE_LEGACY_FILE_URL_CACHE_BYPASS && location.getProtocol().equalsIgnoreCase("file")) {
                File file = UrlUtils.decodeUrlAsFile(location);
                if (file.exists()) {
                    return file;
                }
                // try plain, not decoded file now
                // sometimes the jnlp app developers are encoding for us
                // so we end up encoding already encoded file. See RH1154177
                file = new File(location.getPath());
                if (file.exists()) {
                    return file;
                }
                // have it sense to try also filename with whole query here?
                // => location.getFile() ?
            }

            return null;
        } catch (InterruptedException ex) {
            OutputController.getLogger().log(ex);
            return null; // need an error exception to throw
        }
    }

    /**
     * True when the resource's localFile is present and, for jar URLs, a real zip.
     */
    static boolean hasUsableLocalFile(Resource resource) {
        if (resource == null) {
            return false;
        }
        File local = resource.getLocalFile();
        if (local == null || !local.isFile() || local.length() == 0) {
            return false;
        }
        URL location = resource.getLocation();
        return !CacheUtil.isJarResourceUrl(location) || CacheUtil.isValidJarFile(local);
    }

    /** Visible for Groovy probes / unit tests. */
    protected File resolveUsableLocalFile(Resource resource, URL location) {
        if (resource.getLocalFile() != null) {
            File local = resource.getLocalFile();
            boolean usable = local.isFile() && local.length() > 0
                    && (!CacheUtil.isJarResourceUrl(location) || CacheUtil.isValidJarFile(local));
            if (usable) {
                return local;
            }
        }
        // Ghost / corrupt localFile (reserved catalog slot, cleared folder, or a
        // non-zip payload cached as .jar). Recover an older good copy if present.
        try {
            File recovered = CacheUtil.findExistingCacheFile(location, resource.getDownloadVersion());
            if (recovered != null) {
                resource.setLocalFile(recovered);
                return recovered;
            }
        } catch (Exception ex) {
            OutputController.getLogger().log(ex);
        }
        return null;
    }

    /**
     * Clear a terminal DOWNLOADED/ERROR that left no usable jar and enqueue one more download.
     *
     * @return true if a re-download was started
     */
    /** Visible for Groovy probes / unit tests. */
    protected boolean requeueUnusableTerminal(Resource resource) {
        if (hasUsableLocalFile(resource) && resource.isSet(DOWNLOADED)) {
            return false;
        }
        if (!(resource.isSet(ERROR) || resource.isSet(DOWNLOADED))) {
            return false;
        }
        if (!resource.consumeUnusableTerminalRetry()) {
            return false;
        }
        resource.prepareRedownloadAfterUnusableTerminal();
        startResource(resource);
        return true;
    }

    /**
     * Wait for a group of resources to be downloaded and made
     * available locally.
     *
     * @param urls the resources to wait for
     * @param timeout the time in ms to wait before returning, 0 for no timeout
     * @return whether the resources downloaded before the timeout
     * @throws java.lang.InterruptedException if thread is interrupted
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public boolean waitForResources(URL urls[], long timeout) throws InterruptedException {
        Resource lresources[] = new Resource[urls.length];

        // getResource is lock-free (concurrent registry); no monitor needed
        for (int i = 0; i < urls.length; i++) {
            lresources[i] = getResource(urls[i]);
        }

        if (lresources.length > 0)
            return wait(lresources, timeout);

        return true;
    }

    /**
     * Wait for a particular resource to be downloaded and made
     * available.
     *
     * @param location the resource to wait for
     * @param timeout the timeout, or 0 to wait until completed
     * @return whether the resource downloaded before the timeout
     * @throws InterruptedException if another thread interrupted the wait
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public boolean waitForResource(URL location, long timeout) throws InterruptedException {
        return wait(new Resource[]{getResource(location)}, timeout);
    }

    /**
     * Returns the number of bytes downloaded for a resource.
     *
     * @param location the resource location
     * @return the number of bytes transferred
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public long getAmountRead(URL location) {
        // not atomic b/c transferred is a long, but so what (each
        // byte atomic? so probably won't affect anything...)
        return getResource(location).getTransferred();
    }

    /**
     * Returns whether a resource is available for use (ie, can be
     * accessed with the getCacheFile method).
     *
     * @param location the resource location
     * @return resource availability
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public boolean checkResource(URL location) {
        Resource resource = getResource(location);
        return resource.isSet(DOWNLOADED) || resource.isSet(ERROR);
    }

    /**
     * Starts loading the resource if it is not already being
     * downloaded or already cached.  Resources started downloading
     * using this method may download faster than those prefetched
     * by the tracker because the tracker will only prefetch one
     * resource at a time to conserve system resources.
     *
     * @param location the resource location
     * @return true if the resource is already downloaded (or an error occurred)
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public boolean startResource(URL location) {
        Resource resource = getResource(location);

        return startResource(resource);
    }

    /**
     * Sets the resource status to connect and download, and
     * enqueues the resource if not already started.
     *
     * @return true if the resource is already downloaded (or an error occurred)
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    boolean startResource(Resource resource) {
        // DOWNLOADED/ERROR without a usable jar: clear and enqueue a fresh attempt.
        // Do NOT consume the one-shot retry here — wait/getCacheFile consume it when
        // they observe another terminal-unusable result after this download finishes.
        boolean terminalUnusable = (resource.isSet(ERROR) || resource.isSet(DOWNLOADED))
                && !hasUsableLocalFile(resource);
        if (terminalUnusable) {
            if (resource.isUnusableTerminalRetried()) {
                return true;
            }
            resource.prepareRedownloadAfterUnusableTerminal();
        } else if (resource.isSet(ERROR) || resource.isSet(DOWNLOADED)) {
            return true;
        }

        // A downloader already owns this resource (IN_FLIGHT integrity, or
        // sitting in the size-first queue). Splash wait() ticks must not start
        // a second GET for the same URL.
        if (resource.isEnqueued()) {
            return true;
        }

        // Dedup: never enqueue two downloads for the same resource. Lock-free CAS
        // on the AtomicBoolean enqueued flag; exactly one thread wins.
        boolean enqueue = resource.tryEnqueue();

        if (enqueue)
            startDownloadThread(resource);

        return !enqueue;
    }

    /**
     * Returns the number of total size in bytes of a resource, or
     * -1 it the size is not known.
     *
     * @param location the resource location
     * @return the number of bytes, or -1
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    public long getTotalSize(URL location) {
        return getResource(location).getSize(); // atomic
    }

    /** HTTP Content-Length. Not the unpacked jar. */
    public long getWireSize(URL location) {
        return getResource(location).getWireSize();
    }

    /** GET body bytes. Not Pack200 output. */
    public long getWireTransferred(URL location) {
        return getResource(location).getWireTransferred();
    }

    /**
     * Start a new download thread.
     * <p>
     * Calls to this method should be synchronized on lock.
     * </p>
     * @param resource  resource to be download
     */
    protected void startDownloadThread(Resource resource) {
        if (SizeFirstDownloadQueue.isEnabled()) {
            SizeFirstDownloadQueue.enqueue(resource);
            return;
        }
        CachedDaemonThreadPoolProvider.noteJarDownloadStarting();
        CachedDaemonThreadPoolProvider.getThreadPool().execute(new ResourceDownloader(resource, null));
    }

    static Resource selectByFilter(Collection<Resource> source, Filter<Resource> filter) {
        Resource result = null;

        for (Resource resource : source) {
            boolean selectable;

            selectable = filter.test(resource);   // reads JarSlot state (atomic)

            if (selectable) {
                result = resource;
            }
        }

        return result;
    }

    static Resource selectByStatus(Collection<Resource> source, Resource.Status include, Resource.Status exclude) {
        return selectByStatus(source, EnumSet.of(include), EnumSet.of(exclude));
    }

    /**
     * Selects a resource from the source list that has the
     * specified flag set.
     * <p>
     * Calls to this method should be synchronized on lock and
     * source list.
     * </p>
     */
    static Resource selectByStatus(Collection<Resource> source, final Collection<Resource.Status> included, final Collection<Resource.Status> excluded) {
        return selectByFilter(source, new Filter<Resource>() {
            @Override
            public boolean test(Resource t) {
                boolean hasIncluded = false;
                for (Resource.Status flag : included) {
                    if (t.isSet(flag)) {
                        hasIncluded = true;
                    }
                }
                boolean hasExcluded = false;
                for (Resource.Status flag : excluded) {
                    if (t.isSet(flag)) {
                        hasExcluded = true;
                    }
                }
                return hasIncluded && !hasExcluded;
            }
        });
    }

    /**
     * Return the resource matching the specified URL.
     *
     * @throws IllegalResourceDescriptorException if the resource is not being tracked
     */
    private Resource getResource(URL location) {
        if (null != location) {
            Resource res = resourcesMap.get(location.toString());
            if (null != res && UrlUtils.urlEquals(res.getLocation(), location)) {
                return res;
            }
        }
        for (Resource resource : resources) {
            if (UrlUtils.urlEquals(resource.getLocation(), location))
                return resource;
        }

        throw new IllegalResourceDescriptorException("Location does not specify a resource being tracked.");
    }

    /**
     * Wait for some resources.
     *
     * State model: the lock-free {@link JarSlot} machine is the SINGLE source of
     * truth for download state. The legacy {@link Resource.Status} EnumSet was
     * retired; {@code Resource.isSet}/{@code changeStatus} now delegate to the
     * JarSlot (terminal states) or are no-ops (phase flags). A small volatile
     * {@code enqueued} flag guards double-enqueue, and {@code alreadyDownloaded}
     * marks cache-hit resources before a wait group exists.
     *
     * @param resources the resources to wait for
     * @param timeout the timeout, or {@code 0} to wait until completed
     * @return {@code true} if the resources were downloaded or had errors,
     * {@code false} if the timeout was reached
     * @throws InterruptedException if another thread interrupted the wait
     */
    private boolean wait(Resource[] resources, long timeout) throws InterruptedException {
        long startTime = System.currentTimeMillis();

        // Progress UI calls wait() every ~150ms. Reuse the in-flight JarGroupState so
        // ResourceDownloader keeps writing wire bytes / first-last-byte times onto the
        // same JarSlots. Creating a fresh group each tick was swapping slots mid-download
        // (metrics on the old slot, settle on the new empty one) and the final tick then
        // pre-settled everything as CACHED → Download stats with no transfer time / bytes=0.
        net.sourceforge.jnlp.cache.download.JarGroupState metricsGroup = lastMetricsGroup;
        java.util.List<Resource> needsRestart = new java.util.ArrayList<>();
        if (!canReuseMetricsGroup(resources, metricsGroup)) {
            // Progress UI calls wait() again after the group is done. Do not
            // allocate a fresh group and recount every GET as cached.
            if (isCompletedMetricsGroup(resources, metricsGroup)) {
                return true;
            }
            java.util.List<URL> urls = new java.util.ArrayList<>();
            for (Resource r : resources) {
                urls.add(r.getLocation());
            }
            metricsGroup = net.sourceforge.jnlp.cache.download.JarGroupState.forJars(urls);
            // Pre-settle fresh slots for resources already terminal (repeat wait() after
            // a completed group) so a new IN_FLIGHT slot does not hang the loop.
            preSettleSlots(resources, metricsGroup, needsRestart);
            this.lastMetricsGroup = metricsGroup;
        }

        // start them downloading / connecting in background
        for (Resource resource : resources) {
            startResource(resource);
        }
        for (Resource resource : needsRestart) {
            startResource(resource);
        }

        // HEAD pending jars (12-wide) then submit GETs largest-first. No-op when
        // size-first is off (downloads already started from startDownloadThread).
        SizeFirstDownloadQueue.flush();

        // wait for completion — PURE EVENT BARRIER. The download thread drives its
        // own one-shot retry (see ResourceDownloader.run), so RETRY_PENDING never
        // persists and every slot reaches an absorbing state on its own. The waiter
        // only blocks on the group's done future: no polling, no reclaim loop,
        // no synchronized, no wait(), no notifyAll() anywhere in the download path.
        long remaining = timeout > 0 ? timeout - (System.currentTimeMillis() - startTime) : Long.MAX_VALUE;
        if (timeout > 0 && remaining <= 0) {
            return false;
        }
        try {
            if (timeout > 0) {
                metricsGroup.done().get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                metricsGroup.done().join();
            }
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            // done completes with null — never expected; treat as completed
        }
        logDownloadStats();
        return true;
    }

    /**
     * True when {@code group} is the still-in-flight metrics group for exactly these
     * resources (same URLs, same JarSlot instances still bound). Used so progress-tick
     * wait() calls do not replace slots underneath an active download.
     */
    static boolean canReuseMetricsGroup(Resource[] resources,
            net.sourceforge.jnlp.cache.download.JarGroupState group) {
        if (group == null || group.done().isDone() || group.size() != resources.length) {
            return false;
        }
        for (int i = 0; i < resources.length; i++) {
            net.sourceforge.jnlp.cache.download.JarSlot slot = group.slot(i);
            if (slot == null || resources[i].getJarSlot() != slot) {
                return false;
            }
            if (!resources[i].getLocation().equals(slot.location())) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code group} already finished for exactly these resource URLs.
     * A second wait() must not rebuild slots and reprint Download stats as cached.
     */
    static boolean isCompletedMetricsGroup(Resource[] resources,
            net.sourceforge.jnlp.cache.download.JarGroupState group) {
        if (group == null || !group.done().isDone() || group.size() != resources.length) {
            return false;
        }
        for (int i = 0; i < resources.length; i++) {
            net.sourceforge.jnlp.cache.download.JarSlot slot = group.slot(i);
            if (slot == null || resources[i].getLocation() == null
                    || !resources[i].getLocation().equals(slot.location())) {
                return false;
            }
        }
        return true;
    }

    /** Visible for Groovy probes / unit tests. */
    protected void logDownloadStats() {
        if (lastMetricsGroup == null || !lastMetricsGroup.markStatsLogged()) {
            return;
        }
        try {
            net.sourceforge.jnlp.cache.download.GroupStats stats = lastMetricsGroup.stats();
            net.sourceforge.jnlp.util.logging.OutputController.getLogger()
                    .log(net.sourceforge.jnlp.util.logging.OutputController.Level.MESSAGE_ALL,
                            "Download stats: " + stats.summaryLine());
            // Always surface per-jar lines when anything failed; otherwise DEBUG.
            net.sourceforge.jnlp.util.logging.OutputController.Level jarLevel =
                    stats.failedCount > 0
                            ? net.sourceforge.jnlp.util.logging.OutputController.Level.MESSAGE_ALL
                            : net.sourceforge.jnlp.util.logging.OutputController.Level.MESSAGE_DEBUG;
            for (String line : stats.jarLines()) {
                net.sourceforge.jnlp.util.logging.OutputController.getLogger().log(jarLevel, line);
            }
            if (stats.failedCount > 0) {
                net.sourceforge.jnlp.util.logging.OutputController.getLogger()
                        .log(net.sourceforge.jnlp.util.logging.OutputController.Level.ERROR_ALL,
                                "Download group finished with " + stats.failedCount
                                        + " failed jar(s) — see per-jar lines above");
            }
        } catch (Exception e) {
            // stats are diagnostic — never fail the launch on a stats error
        }
    }

    /**
     * Pre-settle fresh JarSlots for resources already in a terminal state
     * (repeat wait() calls for already-downloaded jars), so a fresh IN_FLIGHT
     * slot does not hang the loop. Terminal-unusable resources with a retry
     * available are consumed and added to {@code needsRestart}.
     */
    static void preSettleSlots(Resource[] resources, net.sourceforge.jnlp.cache.download.JarGroupState group,
            List<Resource> needsRestart) {
        for (int i = 0; i < resources.length; i++) {
            Resource r = resources[i];
            net.sourceforge.jnlp.cache.download.JarSlot s = group.slot(i);
            // read terminalState BEFORE assigning the slot, otherwise isSet() would
            // delegate to the fresh IN_FLIGHT slot and never see the prior outcome
            net.sourceforge.jnlp.cache.download.JarState terminal = r.getTerminalState();
            r.setJarSlot(s);
            if (terminal == net.sourceforge.jnlp.cache.download.JarState.GOOD && hasUsableLocalFile(r)) {
                s.settleGood(System.currentTimeMillis(), true);
            } else if (terminal == net.sourceforge.jnlp.cache.download.JarState.GOOD) {
                // Ghost: prior wait marked GOOD but local file is missing/corrupt.
                // Leaving the fresh slot IN_FLIGHT would hang done() forever.
                r.setTerminalState(null);
                prepareRestartKeepingSlot(r, s, needsRestart);
            } else if (terminal == net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD
                    && r.isUnusableTerminalRetried()) {
                s.settleBadFinal(System.currentTimeMillis()); // retry already spent → terminal FAILED
            } else if (terminal == net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD) {
                // premature ERROR with the one-shot retry still available — consume and
                // re-enqueue so a fresh download settles the slot (old wait() requeued here)
                if (r.consumeUnusableTerminalRetry()) {
                    prepareRestartKeepingSlot(r, s, needsRestart);
                }
            }
        }
    }

    /**
     * {@link Resource#prepareRedownloadAfterUnusableTerminal()} clears the
     * JarSlot binding. Re-bind the wait() group's slot afterwards so
     * ResourceDownloader settles that slot; otherwise {@code group.done()}
     * stays IN_FLIGHT and wait() times out (or hangs forever when timeout is 0).
     */
    private static void prepareRestartKeepingSlot(Resource r,
            net.sourceforge.jnlp.cache.download.JarSlot s, List<Resource> needsRestart) {
        r.prepareRedownloadAfterUnusableTerminal();
        r.setJarSlot(s);
        needsRestart.add(r);
    }

    interface Filter<T> {
        public boolean test(T t);
    }
}