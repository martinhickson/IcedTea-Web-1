package net.sourceforge.jnlp.cache;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.security.HttpClientProvider;
import net.sourceforge.jnlp.security.HttpResponse;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Longest-job-first download order for a shared pipe one TCP flow cannot fill.
 * <p>
 * The first real flood (2+ jars) is HEADed 12-wide to learn
 * {@code Content-Length}, then GETs run on two reserved lanes: 10 workers
 * always take the largest remaining, 2 workers always take the smallest
 * remaining. A finished tiny is replaced by the next-smallest, not a giant.
 * Later single-resource waits must not HEAD again — that stole Apache pool
 * slots from in-flight GETs and logged a bogus “1 at a time” sequence.
 */
public final class SizeFirstDownloadQueue {

    private static final long SWEEP_TIMEOUT_MS = 30_000L;
    static final int LARGE_LANES = 10;
    static final int SMALL_LANES = 2;
    private static final Consumer<Resource> DEFAULT_STARTER = SizeFirstDownloadQueue::startDownload;

    private static final ConcurrentLinkedQueue<Resource> PENDING = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Resource, HeadMeta> HEAD_META = new ConcurrentHashMap<Resource, HeadMeta>();
    private static final Object FLUSH_LOCK = new Object();
    private static final AtomicBoolean MAIN_SWEEP_DONE = new AtomicBoolean();
    private static volatile ExecutorService GET_LANES;

    /** Test seam: replace to capture submit order without starting HTTP GETs. */
    static volatile Consumer<Resource> downloadStarter = DEFAULT_STARTER;
    /** Test seam: replace to inject sizes without a real HEAD. */
    static volatile Consumer<Resource> headProbe = SizeFirstDownloadQueue::headOne;

    private SizeFirstDownloadQueue() {
    }

    static boolean isEnabled() {
        try {
            String v = JNLPRuntime.getConfiguration()
                    .getProperty("deployment.http.sizeFirstDownloads");
            if (v == null || v.trim().isEmpty()) {
                return true;
            }
            return Boolean.parseBoolean(v.trim());
        } catch (Exception e) {
            return true;
        }
    }

    static void enqueue(Resource resource) {
        if (resource != null) {
            PENDING.add(resource);
        }
    }

    /**
     * HEAD a 2+ jar flood (capped at connection-slot width), sort largest-first,
     * then start GETs. A later singleton wait starts GET immediately — no HEAD.
     */
    static void flush() {
        if (!isEnabled()) {
            return;
        }
        synchronized (FLUSH_LOCK) {
            List<Resource> batch = drain();
            if (batch.isEmpty()) {
                return;
            }
            if (batch.size() < 2 || MAIN_SWEEP_DONE.get()) {
                log(OutputController.Level.MESSAGE_DEBUG,
                        "Size-first: skip HEAD for " + batch.size()
                                + " resource(s) (singleton or sweep already done) — GET immediately");
                for (Resource r : batch) {
                    downloadStarter.accept(r);
                }
                return;
            }
            int width = headSlots();
            log(OutputController.Level.MESSAGE_ALL,
                    "Size-first HEAD start: " + batch.size() + " jars, "
                            + width + " in flight");
            DownloadProgress.markPreparing();
            long t0 = System.currentTimeMillis();
            probeHeads(batch, width);
            List<Resource> largestFirst = orderLargestFirst(batch);
            long wall = System.currentTimeMillis() - t0;
            int known = 0;
            long sizeSum = 0L;
            long wireSum = 0L;
            for (Resource r : largestFirst) {
                long size = r.getSize();
                if (size > 0) {
                    known++;
                    sizeSum += size;
                }
                long wire = r.getWireSize();
                if (wire > 0) {
                    wireSum += wire;
                }
            }
            int smallLanes = smallLaneCount(width);
            int largeLanes = width - smallLanes;
            log(OutputController.Level.MESSAGE_ALL,
                    "Size-first HEAD complete: " + known + "/" + largestFirst.size()
                            + " sizes in " + wall + "ms (" + width + " in flight)"
                            + " sizeSum=" + sizeSum + " wireSum=" + wireSum);
            int cacheHits = noteCacheHits(largestFirst);
            log(OutputController.Level.MESSAGE_ALL,
                    "Size-first HEAD cache hits: " + cacheHits + "/" + largestFirst.size()
                            + " (skip GET when Last-Modified or wire length matches)");
            DownloadProgress.clearPreparing();
            logLanePlan(largestFirst, largeLanes, smallLanes);
            MAIN_SWEEP_DONE.set(true);
            startTwoLaneDownloads(largestFirst, largeLanes, smallLanes);
        }
    }

    static List<Resource> orderLargestFirst(List<Resource> batch) {
        final Map<Resource, Integer> original = new HashMap<Resource, Integer>();
        for (int i = 0; i < batch.size(); i++) {
            original.put(batch.get(i), i);
        }
        List<Resource> ordered = new ArrayList<Resource>(batch);
        ordered.sort(new Comparator<Resource>() {
            @Override
            public int compare(Resource a, Resource b) {
                int bySize = Long.compare(rankSize(b), rankSize(a));
                if (bySize != 0) {
                    return bySize;
                }
                return Integer.compare(original.getOrDefault(a, 0), original.getOrDefault(b, 0));
            }
        });
        return ordered;
    }

    /**
     * Start order when the 10 large lanes stay busy on giants and the 2 small
     * lanes immediately refill from the small end (smallest → largest).
     */
    static List<Resource> tinyChurnWhileGiantsHeld(List<Resource> batch) {
        return tinyChurnWhileGiantsHeld(batch, LARGE_LANES, SMALL_LANES);
    }

    static List<Resource> tinyChurnWhileGiantsHeld(List<Resource> batch, int largeLanes, int smallLanes) {
        Deque<Resource> dq = new ArrayDeque<Resource>(orderLargestFirst(batch));
        List<Resource> started = new ArrayList<Resource>();
        int large = Math.max(0, largeLanes);
        int small = Math.max(0, smallLanes);
        for (int i = 0; i < large; i++) {
            Resource r = dq.pollFirst();
            if (r == null) {
                break;
            }
            started.add(r);
        }
        while (!dq.isEmpty() && small > 0) {
            for (int i = 0; i < small && !dq.isEmpty(); i++) {
                started.add(dq.pollLast());
            }
        }
        return started;
    }

    private static int smallLaneCount(int slots) {
        int n = Math.max(2, slots);
        return Math.min(SMALL_LANES, n - 1);
    }

    private static void startTwoLaneDownloads(List<Resource> largestFirst, int largeLanes, int smallLanes) {
        if (downloadStarter != DEFAULT_STARTER) {
            for (Resource r : tinyChurnWhileGiantsHeld(largestFirst, largeLanes, smallLanes)) {
                downloadStarter.accept(r);
            }
            return;
        }
        final Deque<Resource> dq = new ArrayDeque<Resource>(largestFirst);
        ExecutorService lanes = Executors.newFixedThreadPool(largeLanes + smallLanes, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                int i = n.incrementAndGet();
                String kind = i <= largeLanes ? "large" : "small";
                Thread t = new Thread(r, "itw-size-first-" + kind + "-" + i);
                t.setDaemon(true);
                return t;
            }
        });
        GET_LANES = lanes;
        for (int i = 0; i < largeLanes; i++) {
            final int lane = i;
            lanes.execute(new Runnable() {
                @Override
                public void run() {
                    laneLoop(dq, true, lane);
                }
            });
        }
        for (int i = 0; i < smallLanes; i++) {
            final int lane = largeLanes + i;
            lanes.execute(new Runnable() {
                @Override
                public void run() {
                    laneLoop(dq, false, lane);
                }
            });
        }
        lanes.shutdown();
    }

    private static void laneLoop(Deque<Resource> dq, boolean largeLane, int lane) {
        while (true) {
            Resource resource;
            synchronized (dq) {
                resource = largeLane ? dq.pollFirst() : dq.pollLast();
                if (resource == null) {
                    resource = largeLane ? dq.pollLast() : dq.pollFirst();
                }
            }
            if (resource == null) {
                return;
            }
            log(OutputController.Level.MESSAGE_DEBUG,
                    "Size-first GET " + (largeLane ? "large" : "small") + "-lane "
                            + formatSize(resource.getSize()) + " " + resourceName(resource));
            CachedDaemonThreadPoolProvider.noteJarDownloadStarting();
            DownloadProgress.bindLane(lane, resource);
            try {
                new ResourceDownloader(resource, null).run();
            } finally {
                DownloadProgress.unbindLane(lane);
            }
        }
    }

    static URL headWinner(Resource resource) {
        HeadMeta meta = headMeta(resource);
        return meta == null ? null : meta.url;
    }

    static HeadMeta headMeta(Resource resource) {
        return resource == null ? null : HEAD_META.get(resource);
    }

    /** Test seam: plant HEAD headers without HTTP. */
    static void recordHead(Resource resource, URL url, long contentLength, long lastModified) {
        if (resource == null || url == null) {
            return;
        }
        if (contentLength > 0L) {
            resource.setSize(contentLength);
            resource.setWireSize(contentLength);
        }
        resource.setDownloadLocation(url);
        HEAD_META.put(resource, new HeadMeta(url, contentLength, lastModified));
    }

    /**
     * HEAD result reused by the GET lanes. Last-Modified and Content-Length
     * are compared to the catalog so a current cache skips the body.
     */
    static final class HeadMeta {
        final URL url;
        final long contentLength;
        final long lastModified;

        HeadMeta(URL url, long contentLength, long lastModified) {
            this.url = url;
            this.contentLength = contentLength;
            this.lastModified = lastModified;
        }
    }

    private static int noteCacheHits(List<Resource> batch) {
        long cacheKnown = 0L;
        int hits = 0;
        for (Resource r : batch) {
            HeadMeta meta = HEAD_META.get(r);
            if (meta == null || !ResourceDownloader.peekCacheHit(r, meta)) {
                continue;
            }
            hits++;
            java.io.File local = ResourceDownloader.peekCachedFile(r);
            if (local != null) {
                cacheKnown += local.length();
            }
        }
        if (hits > 0) {
            DownloadProgress.setCacheKnown(cacheKnown);
        }
        return hits;
    }

    /**
     * Known length ranks as itself. Unknown ({@code <= 0}) ranks as
     * {@link Long#MAX_VALUE} so a failed HEAD cannot bury a fat jar at the tail.
     */
    static long rankSize(Resource resource) {
        long size = resource.getSize();
        return size > 0 ? size : Long.MAX_VALUE;
    }

    static void resetForTests() {
        PENDING.clear();
        HEAD_META.clear();
        MAIN_SWEEP_DONE.set(false);
        downloadStarter = DEFAULT_STARTER;
        headProbe = SizeFirstDownloadQueue::headOne;
        ResourceUrlCreator.resetPackHostForTests();
        ExecutorService lanes = GET_LANES;
        GET_LANES = null;
        if (lanes != null) {
            lanes.shutdownNow();
        }
    }

    static boolean isEmptyForTests() {
        return PENDING.isEmpty();
    }

    private static List<Resource> drain() {
        List<Resource> batch = new ArrayList<Resource>();
        Resource r;
        while ((r = PENDING.poll()) != null) {
            batch.add(r);
        }
        return batch;
    }

    private static void probeHeads(List<Resource> batch, int width) {
        int slots = Math.max(1, Math.min(width, batch.size()));
        ExecutorService heads = Executors.newFixedThreadPool(slots, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "itw-size-first-head-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        CountDownLatch done = new CountDownLatch(batch.size());
        final AtomicInteger finished = new AtomicInteger();
        try {
            for (final Resource resource : batch) {
                heads.execute(new Runnable() {
                    @Override
                    public void run() {
                        long s = System.currentTimeMillis();
                        try {
                            headProbe.accept(resource);
                        } catch (Throwable t) {
                            OutputController.getLogger().log(t);
                        } finally {
                            int n = finished.incrementAndGet();
                            log(OutputController.Level.MESSAGE_DEBUG,
                                    "Size-first HEAD " + n + "/" + batch.size()
                                            + " " + formatSize(resource.getSize())
                                            + " " + resourceName(resource)
                                            + " " + (System.currentTimeMillis() - s) + "ms");
                            done.countDown();
                        }
                    }
                });
            }
            try {
                if (!done.await(SWEEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    log(OutputController.Level.ERROR_ALL,
                            "Size-first HEAD timed out after " + SWEEP_TIMEOUT_MS
                                    + "ms — " + finished.get() + "/" + batch.size()
                                    + " done, starting GETs with sizes collected so far");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            heads.shutdownNow();
        }
    }

    private static void headOne(Resource resource) {
        if (resource.getSize() > 0) {
            return;
        }
        URL location = resource.getLocation();
        if (location == null) {
            return;
        }
        String protocol = location.getProtocol();
        if (protocol != null && !"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return;
        }
        if (ResourceDownloader.isFavIconUrl(location) || !CacheUtil.isJarResourceUrl(location)) {
            return;
        }
        DownloadOptions options = resource.getDownloadOptions();
        if (options == null) {
            options = new DownloadOptions(false, false);
        }
        List<URL> urls = new ResourceUrlCreator(resource, options).getUrls();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept-Encoding", "pack200-gzip");
        for (URL url : urls) {
            try (HttpResponse response = HttpClientProvider.getDefault().open(url, "HEAD", headers, null)) {
                int code = response.getStatusCode();
                log(OutputController.Level.MESSAGE_DEBUG,
                        "Size-first HEAD " + resourceName(resource) + " -> HTTP " + code
                                + " Content-Length=" + contentLength(response)
                                + " " + url);
                if (code < 200 || code >= 300) {
                    ResourceUrlCreator.notePackHost(url, false);
                    continue;
                }
                long len = contentLength(response);
                long lastModified = response.getLastModified();
                if (len > 0 || lastModified > 0) {
                    if (len > 0) {
                        resource.setSize(len);
                        resource.setWireSize(len);
                    }
                    resource.setDownloadLocation(url);
                    HEAD_META.put(resource, new HeadMeta(url, len, lastModified));
                    ResourceUrlCreator.notePackHost(url, url.getPath() != null
                            && url.getPath().endsWith(".pack.gz"));
                    log(OutputController.Level.MESSAGE_DEBUG,
                            "Size-first HEAD winner " + resourceName(resource)
                                    + " " + formatSize(len) + " lm=" + lastModified + " " + url);
                    return;
                }
            } catch (Exception e) {
                log(OutputController.Level.MESSAGE_DEBUG,
                        "Size-first HEAD failed " + resourceName(resource) + " " + url + " " + e);
            }
        }
    }

    private static long contentLength(HttpResponse response) {
        long len = response.getContentLength();
        if (len > 0) {
            return len;
        }
        String raw = response.getHeader("Content-Length");
        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void startDownload(Resource resource) {
        CachedDaemonThreadPoolProvider.noteJarDownloadStarting();
        CachedDaemonThreadPoolProvider.getThreadPool().execute(new ResourceDownloader(resource, null));
    }

    private static int headSlots() {
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

    private static void logLanePlan(List<Resource> largestFirst, int largeLanes, int smallLanes) {
        StringBuilder table = new StringBuilder();
        table.append("Size-first GET lanes: ").append(largeLanes)
                .append(" largest→smallest + ").append(smallLanes)
                .append(" smallest→largest (continuous refill), ")
                .append(largestFirst.size()).append(" jars:\n");
        int large = Math.min(largeLanes, largestFirst.size());
        table.append("  large held:\n");
        for (int i = 0; i < large; i++) {
            Resource r = largestFirst.get(i);
            table.append(String.format("  %3d  %12s  %s%n",
                    i + 1, formatSize(r.getSize()), resourceName(r)));
        }
        table.append("  small refill (smallest first):\n");
        int n = 0;
        for (int i = largestFirst.size() - 1; i >= large; i--) {
            n++;
            Resource r = largestFirst.get(i);
            table.append(String.format("  %3d  %12s  %s%n",
                    n, formatSize(r.getSize()), resourceName(r)));
        }
        log(OutputController.Level.MESSAGE_ALL, table.toString().trim());
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "unknown";
        }
        if (bytes >= 1024L * 1024L) {
            return bytes + " (" + String.format("%.1f", bytes / (1024.0 * 1024.0)) + " MB)";
        }
        if (bytes >= 1024L) {
            return bytes + " (" + String.format("%.1f", bytes / 1024.0) + " KB)";
        }
        return Long.toString(bytes);
    }

    static String resourceName(Resource resource) {
        if (resource == null || resource.getLocation() == null) {
            return "?";
        }
        String path = resource.getLocation().getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void log(OutputController.Level level, String message) {
        OutputController.getLogger().log(level, message);
    }
}
