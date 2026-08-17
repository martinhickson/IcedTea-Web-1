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
import java.net.URL;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.cache.download.JarSlot;
import net.sourceforge.jnlp.cache.download.JarState;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.WeakList;

/**
 * <p>
 * Information about a single resource to download.
 * This class tracks the downloading of various resources of a
 * JNLP file to local files.  It can be used to download icons,
 * jnlp and extension files, jars, and jardiff files using the
 * version based protocol or any file using the basic download
 * protocol.
 * </p>
 * <p>
 * Resources can be put into download groups by specifying a part
 * name for the resource.  The resource tracker can also be
 * configured to prefetch resources, which are downloaded in the
 * order added to the media tracker.
 * </p>
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.9 $
 */
public class Resource {
    // todo: fix resources to handle different versions

    // todo: IIRC, any resource is checked for being up-to-date
    // only once, regardless of UpdatePolicy.  verify and fix.

    public enum Status {
        PRECONNECT,
        CONNECTING,
        CONNECTED,
        PREDOWNLOAD,
        DOWNLOADING,
        DOWNLOADED,
        ERROR,
        PROCESSING // in queue or being worked on
    }

    /** list of weak references of resources currently in use */
    private static final WeakList<Resource> resources = new WeakList<>();

    /** weak list of trackers monitoring this resource */
    private final WeakList<ResourceTracker> trackers = new WeakList<>();

    /** the remote location of the resource */
    private final URL location;

    /** the location to use when downloading */
    private volatile URL downloadLocation;

    /** the local file downloaded to */
    private volatile File localFile;

    /** the requested version */
    private final Version requestVersion;

    /** the version downloaded from server */
    private volatile Version downloadVersion;

    /** amount in bytes transferred */
    private volatile long transferred = 0;

    /** total size of the resource, or -1 if unknown */
    private volatile long size = -1;

    /**
     * HTTP body length (HEAD/GET Content-Length). Never the unpacked jar.
     * {@link #size} is overwritten with on-disk length after Pack200.
     */
    private volatile long wireSize = -1;

    /** GET body bytes only. Never Pack200 output. */
    private volatile long wireTransferred = 0;

    /** lock-free slot for timing/metrics/settle (set when a JarGroupState is created for the group) */
    private volatile JarSlot jarSlot;

    /** terminal state for resources with no wait-group slot yet (cache-hit / prefetch):
     *  null = not terminal, GOOD = usable, SETTLED_BAD = failed. Once a JarSlot
     *  exists it is the single source of truth; this is only read pre-slot. */
    private volatile JarState terminalState;
    /** dedup guard: a download for this resource is enqueued/active (legacy PROCESSING flag).
     *  AtomicBoolean so the claim is a lock-free compareAndSet. */
    private final java.util.concurrent.atomic.AtomicBoolean enqueued = new java.util.concurrent.atomic.AtomicBoolean(false);
    
    /** Update policy for this resource */
    private final UpdatePolicy updatePolicy;

    /** Download options for this resource */
    private DownloadOptions downloadOptions;

    /**
     * Whether we already cleared a terminal DOWNLOADED/ERROR that had no usable
     * local file and re-queued a download. One automatic recovery attempt only —
     * avoids hang loops when the server truly cannot supply the jar.
     */
    private final java.util.concurrent.atomic.AtomicBoolean unusableTerminalRetried = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Create a resource.
     */
    private Resource(URL location, Version requestVersion, UpdatePolicy updatePolicy) {
        this.location = location;
        this.downloadLocation = location;
        this.requestVersion = requestVersion;
        this.updatePolicy = updatePolicy;
    }

    /**
     * Return a shared Resource object representing the given
     * location and version.
     * @param location final location of resource
     * @param requestVersion final version of resource
     * @param updatePolicy final policy for updating
     * @return new resource, which is already added in resources list
     */
    public static Resource getResource(URL location, Version requestVersion, UpdatePolicy updatePolicy) {
        //TODO -rename to create resource?
        synchronized (resources) {
            Resource resource = new Resource(location, requestVersion, updatePolicy);

            //FIXME - url ignores port during its comparison
            //this may affect test-suites
            int index = resources.indexOf(resource);
            if (index >= 0) { // return existing object
                Resource result = resources.get(index);
                if (result != null) {
                    return result;
                }
            }

            resources.add(resource);
            resources.trimToSize();

            return resource;
        }
    }

    /**
     * Returns the remote location of the resource.
     * @return the same location as the one with which this resource was created
     */
    public URL getLocation() {
        return location;
    }

    /**
     * Returns the URL to use for downloading the resource. This can be
     * different from the original location since it may use a different
     * file name to support versioning and compression
     * @return the url to use when downloading
     */
    public URL getDownloadLocation() {
        return downloadLocation;
    }

    /**
     * Set the url to use for downloading the resource
     * @param downloadLocation url to be donloaded
     */
    public void setDownloadLocation(URL downloadLocation) {
        this.downloadLocation = downloadLocation;
    }

    /**
     * Returns the tracker that first created or monitored the
     * resource, or null if no trackers are monitoring the resource.
     */
    ResourceTracker getTracker() {
        synchronized (trackers) {
            List<ResourceTracker> t = trackers.hardList();
            if (t.size() > 0) {
                return t.get(0);
            }

            return null;
        }
    }
    
    /**
     * @return the local file currently being downloaded
     */
    public File getLocalFile() {
    	return localFile;
    }
    
    /**
     * Sets the local file to be downloaded
     * @param localFile location of stored resource
     */
    public void setLocalFile(File localFile) {
    	this.localFile = localFile;
    }
    
    /**
     * @return the requested version
     */
    public Version getRequestVersion() {
    	return requestVersion;
    }
    
    /**
     * @return the version downloaded from server
     */
    public Version getDownloadVersion() {
    	return downloadVersion;
    }
    
    /**
     * Sets the version downloaded from server
     * @param downloadVersion version of downloaded resource
     */
    public void setDownloadVersion(Version downloadVersion) {
    	this.downloadVersion = downloadVersion;
    }
    
    /**
     * @return the amount in bytes transferred
     */
    public long getTransferred() {
    	return transferred;
    }
    
    /**
     * Sets the amount transferred
     * @param transferred set the whole transfered amount to this value
     */
    public void setTransferred(long transferred) {
    	this.transferred = transferred;
    }
    
    /**
     * Increments the amount transferred (in bytes)
     * @param incTrans transfered amount in last transfer
     */
    public void incrementTransferred(long incTrans) {
    	transferred += incTrans;
        if (incTrans > 0L) {
            wireTransferred += incTrans;
        }
    }

    /**
     * Returns the size of the resource
     * @return size of resource (-1 if unknown)
     */
    public long getSize() {
    	return size;
    }

    /**
     * Sets the size of the resource
     * @param size desired size of resource
     */
    public void setSize(long size) {
        this.size = size;
    }

    /** HEAD/GET Content-Length. Ignored when {@code n <= 0}. */
    public void setWireSize(long n) {
        if (n > 0L) {
            this.wireSize = n;
        }
    }

    public long getWireSize() {
        return wireSize;
    }

    public long getWireTransferred() {
        return wireTransferred;
    }

    public JarSlot getJarSlot() {
        return jarSlot;
    }

    public void setJarSlot(JarSlot jarSlot) {
        this.jarSlot = jarSlot;
    }

    /**
     * @return the status of the resource — derived from the JarSlot state machine
     */
    public Set<Status> getCopyOfStatus() {
        if (isSet(Status.DOWNLOADED)) {
            return EnumSet.of(Status.DOWNLOADED);
        }
        if (isSet(Status.ERROR)) {
            return EnumSet.of(Status.ERROR);
        }
        return EnumSet.noneOf(Status.class);
    }

    /**
     * Check if the specified flag is set. Delegates to the JarSlot state machine
     * (legacy EnumSet retired); falls back to {@link #terminalState} when no
     * JarSlot exists yet.
     */
    public boolean isSet(Status flag) {
        JarSlot s = jarSlot;
        if (s != null) {
            switch (flag) {
                case DOWNLOADED: return s.state() == JarState.GOOD;
                case ERROR: return s.state() == JarState.SETTLED_BAD;
                default: return false; // phase flags retired
            }
        }
        switch (flag) {
            case DOWNLOADED: return terminalState == JarState.GOOD;
            case ERROR: return terminalState == JarState.SETTLED_BAD;
            default: return false;
        }
    }

    /**
     * Check if all the specified flags are set.
     */
    public boolean hasFlags(Collection<Status> flags) {
        if (flags == null) {
            return true;
        }
        for (Status f : flags) {
            if (!isSet(f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true if the resource reached a terminal state (usable file or exhausted retry)
     */
    public boolean isTerminal() {
        JarSlot s = jarSlot;
        return s != null ? s.state().isAbsorbing() : terminalState != null;
    }

    public JarState getTerminalState() {
        return terminalState;
    }

    public void setTerminalState(JarState terminalState) {
        this.terminalState = terminalState;
    }

    /**
     * @return the update policy for this resource
     */
    public UpdatePolicy getUpdatePolicy() {
        return this.updatePolicy;
    }

    /**
     * Returns a human-readable status string.
     */
    private String getStatusString() {
        Set<Status> s = getCopyOfStatus();
        if (s.isEmpty()) {
            return "<>";
        }
        StringBuilder result = new StringBuilder();
        for (Status stat : s) {
            result.append(stat.toString()).append(" ");
        }
        return result.toString().trim();
    }

    /**
     * LEGACY: retired. Terminal state transitions now happen through the
     * {@link JarSlot} machine (settleGood/settleUnusable). No-op.
     */
    public void changeStatus(Collection<Status> clear, Collection<Status> add) {
        // intentionally empty — the JarSlot state machine is the source of truth
    }

    /**
     * LEGACY: retired. No-op (see {@link #changeStatus}).
     */
    public void setStatusFlag(Status flag) {
    }

    /**
     * LEGACY: retired. No-op (see {@link #changeStatus}).
     */
    public void setStatusFlags(Collection<Status> flags) {
    }

    /**
     * LEGACY: retired. No-op (see {@link #changeStatus}).
     */
    public void unsetStatusFlag(Collection<Status> flags) {
    }

    /**
     * LEGACY: retired. No-op (the JarSlot machine owns terminal/reset state).
     */
    public void resetStatus() {
    }

    public boolean isEnqueued() {
        return enqueued.get();
    }

    /** Lock-free claim: true if this thread won the right to enqueue a download. */
    public boolean tryEnqueue() {
        return enqueued.compareAndSet(false, true);
    }

    public void clearEnqueued() {
        enqueued.set(false);
    }

    /**
     * If this resource ended DOWNLOADED/ERROR without a usable cache file, claim the
     * single automatic re-download attempt. Returns {@code false} when that attempt
     * was already used.
     */
    boolean consumeUnusableTerminalRetry() {
        return unusableTerminalRetried.compareAndSet(false, true);
    }

    boolean isUnusableTerminalRetried() {
        return unusableTerminalRetried.get();
    }

    /**
     * Allow one automatic recovery again (new tracker / new launch sharing this URL).
     */
    void clearUnusableTerminalRetry() {
        unusableTerminalRetried.set(false);
    }

    /**
     * Clear terminal status, slot binding, and enqueue flag so
     * {@link ResourceTracker} can start a fresh download.
     */
    void prepareRedownloadAfterUnusableTerminal() {
        localFile = null;
        terminalState = null;
        jarSlot = null;
        clearEnqueued();
    }

    /**
     * Check if this resource has been initialized
     * @return true iff any flags have been set
     */
    public boolean isInitialized() {
        return isTerminal() || getJarSlot() != null;
    }

    /**
     * Removes the tracker to the list of trackers monitoring this
     * resource.
     * 
     * @param tracker tracker to be removed
     */
    public void removeTracker(ResourceTracker tracker) {
        synchronized (trackers) {
            trackers.remove(tracker);
            trackers.trimToSize();
        }
    }

    /**
     * Adds the tracker to the list of trackers monitoring this
     * resource.
     * @param tracker to observing resource
     */
    public void addTracker(ResourceTracker tracker) {
        synchronized (trackers) {
            // prevent GC between contains and add
            List<ResourceTracker> t = trackers.hardList();
            if (!t.contains(tracker))
                trackers.add(tracker);

            trackers.trimToSize();
        }
    }

    /**
     * Instructs the trackers monitoring this resource to fire a
     * download event.
     */
    protected void fireDownloadEvent() {
        List<ResourceTracker> send;

        synchronized (trackers) {
            send = trackers.hardList();
        }

        for (ResourceTracker rt : send) {
            rt.fireDownloadEvent(this);
        }
    }

    public void setDownloadOptions(DownloadOptions downloadOptions) {
        this.downloadOptions = downloadOptions;
    }

    public DownloadOptions getDownloadOptions() {
        return this.downloadOptions;
    }

    public boolean isConnectable() {
        return JNLPRuntime.isConnectable(this.location);
    }

    @Override
    public int hashCode() {
        // FIXME: should probably have a better hashcode than this, but considering
        // #equals(Object) was already defined first (without also overriding hashcode!),
        // this is just being implemented in line with that so we don't break HashMaps,
        // HashSets, etc
        String key = UrlUtils.urlKey(location);
        return key == null ? 0 : key.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Resource) {
            // this prevents the URL handler from looking up the IP
            // address and doing name resolution; much faster so less
            // time spent in synchronized addResource determining if
            // Resource is already in a tracker, and better for offline
            // mode on some OS.
            return UrlUtils.urlEquals(location, ((Resource) other).location);
        }
        return false;
    }

    @Override
    public String toString() {
        return "location=" + location.toString() + " state=" + getStatusString();
    }
}
