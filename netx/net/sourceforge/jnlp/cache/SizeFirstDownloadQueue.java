package net.sourceforge.jnlp.cache;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.security.HttpClientProvider;
import net.sourceforge.jnlp.security.HttpResponse;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code Content-Length}, then GETs are submitted in waves of 10 largest
 * plus 2 smallest so tiny jars finish (and settle) while giants still
 * transfer. Later single-resource waits must not HEAD again — that stole
 * Apache pool slots from in-flight GETs and logged a bogus “1 at a time”
 * sequence in reverse completion order (smallest of the first wave finish first).
 */
public final class SizeFirstDownloadQueue {

    private static final long SWEEP_TIMEOUT_MS = 30_000L;
    static final int LARGE_PER_WAVE = 10;
    static final int SMALL_PER_WAVE = 2;

    private static final ConcurrentLinkedQueue<Resource> PENDING = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Resource, URL> HEAD_WINNER = new ConcurrentHashMap<Resource, URL>();
    private static final Object FLUSH_LOCK = new Object();
    private static final AtomicBoolean MAIN_SWEEP_DONE = new AtomicBoolean();

    /** Test seam: replace to capture submit order without starting HTTP GETs. */
    static volatile Consumer<Resource> downloadStarter = SizeFirstDownloadQueue::startDownload;
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
            long t0 = System.currentTimeMillis();
            probeHeads(batch, width);
            List<Resource> ordered = orderLargestWithSmallTail(batch);
            long wall = System.currentTimeMillis() - t0;
            int known = 0;
            for (Resource r : ordered) {
                if (r.getSize() > 0) {
                    known++;
                }
            }
            log(OutputController.Level.MESSAGE_ALL,
                    "Size-first HEAD complete: " + known + "/" + ordered.size()
                            + " sizes in " + wall + "ms (" + width + " in flight)");
            logOrderTable(ordered);
            MAIN_SWEEP_DONE.set(true);
            for (int i = 0; i < ordered.size(); i++) {
                Resource r = ordered.get(i);
                log(OutputController.Level.MESSAGE_DEBUG,
                        "Size-first GET #" + (i + 1) + "/" + ordered.size()
                                + " " + formatSize(r.getSize()) + " " + resourceName(r));
                downloadStarter.accept(r);
            }
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
     * Largest-first, then weave: each wave takes {@value #LARGE_PER_WAVE} from
     * the large end and {@value #SMALL_PER_WAVE} from the small end so the
     * first 12 pool slots are 10 giants + 2 tiny jars.
     */
    static List<Resource> orderLargestWithSmallTail(List<Resource> batch) {
        return weaveLargeAndSmall(orderLargestFirst(batch), LARGE_PER_WAVE, SMALL_PER_WAVE);
    }

    static List<Resource> weaveLargeAndSmall(List<Resource> largestFirst, int largePerWave, int smallPerWave) {
        List<Resource> src = new ArrayList<Resource>(largestFirst);
        List<Resource> out = new ArrayList<Resource>(src.size());
        int large = Math.max(0, largePerWave);
        int small = Math.max(0, smallPerWave);
        while (!src.isEmpty()) {
            int takeLarge = Math.min(large, src.size());
            for (int i = 0; i < takeLarge; i++) {
                out.add(src.remove(0));
            }
            int takeSmall = Math.min(small, src.size());
            for (int i = 0; i < takeSmall; i++) {
                out.add(src.remove(src.size() - 1));
            }
        }
        return out;
    }

    static URL headWinner(Resource resource) {
        return resource == null ? null : HEAD_WINNER.get(resource);
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
        HEAD_WINNER.clear();
        MAIN_SWEEP_DONE.set(false);
        downloadStarter = SizeFirstDownloadQueue::startDownload;
        headProbe = SizeFirstDownloadQueue::headOne;
        ResourceUrlCreator.resetPackHostForTests();
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
                if (len > 0) {
                    resource.setSize(len);
                    resource.setDownloadLocation(url);
                    HEAD_WINNER.put(resource, url);
                    ResourceUrlCreator.notePackHost(url, url.getPath() != null
                            && url.getPath().endsWith(".pack.gz"));
                    log(OutputController.Level.MESSAGE_DEBUG,
                            "Size-first HEAD winner " + resourceName(resource)
                                    + " " + formatSize(len) + " " + url);
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

    private static void logOrderTable(List<Resource> ordered) {
        StringBuilder table = new StringBuilder();
        table.append("Size-first GET order (10 largest + 2 smallest per wave), ")
                .append(ordered.size()).append(" jars:\n");
        for (int i = 0; i < ordered.size(); i++) {
            Resource r = ordered.get(i);
            table.append(String.format("  %3d  %12s  %s%n",
                    i + 1, formatSize(r.getSize()), resourceName(r)));
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
