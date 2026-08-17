package net.sourceforge.jnlp.cache.download;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.sourceforge.jnlp.util.UrlUtils;

public final class JarGroupState {

    final JarSlot[] jars;
    final ConcurrentHashMap<String, JarSlot> byUrl;
    final AtomicInteger settledCount = new AtomicInteger();
    final AtomicBoolean statsLogged = new AtomicBoolean();
    final CompletableFuture<Void> done = new CompletableFuture<>();
    volatile long groupStartMillis;
    volatile long groupEndMillis = -1;

    private static final ScheduledExecutorService RECLAIM =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "itw-jar-reclaim");
                t.setDaemon(true);
                return t;
            });

    public static JarGroupState forJars(List<URL> jarUrls) {
        JarGroupState g = new JarGroupState(jarUrls.size());
        long start = System.currentTimeMillis();
        g.groupStartMillis = start;
        for (int i = 0; i < jarUrls.size(); i++) {
            JarSlot slot = new JarSlot(i, jarUrls.get(i), g, start);
            g.jars[i] = slot;
            g.byUrl.put(UrlUtils.urlKey(jarUrls.get(i)), slot);
        }
        return g;
    }

    private JarGroupState(int size) {
        this.jars = new JarSlot[size];
        this.byUrl = new ConcurrentHashMap<>(size * 2);
    }

    public JarSlot slot(int index)        { return jars[index]; }
    public JarSlot slot(URL url)          { return byUrl.get(UrlUtils.urlKey(url)); }
    public int size()                     { return jars.length; }
    public CompletableFuture<Void> done() { return done; }
    public void awaitAll()                { done.join(); }
    public CompletableFuture<Void> await(URL url) { return byUrl.get(UrlUtils.urlKey(url)).settled(); }

    void onSettled() {
        if (settledCount.incrementAndGet() == jars.length) {
            groupEndMillis = System.currentTimeMillis();
            done.complete(null);
        }
    }

    public GroupStats stats() {
        if (!done.isDone()) throw new IllegalStateException("call after awaitAll()");
        return GroupStats.from(this);
    }

    /** First caller logs Download stats; later wait() ticks must not reprint. */
    public boolean markStatsLogged() {
        return statsLogged.compareAndSet(false, true);
    }

    public int settledSoFar() { return settledCount.get(); }

    public long bytesSoFar() {
        long s = 0;
        for (JarSlot j : jars) s += j.transferred.get();
        return s;
    }

    public long wallDurationMillis() {
        long end = groupEndMillis > 0 ? groupEndMillis : System.currentTimeMillis();
        return end - groupStartMillis;
    }

    /**
     * Force-claim every jar still parked in {@link JarState#RETRY_PENDING}
     * (the one-shot retry was never claimed by a waiter). Returns the indices
     * reclaimed; the coordinator must re-enqueue downloads for them. This is the
     * safety net so an orphaned RETRY_PENDING can never strand {@code done}.
     */
    public List<Integer> reclaimRetryPending() {
        List<Integer> reclaimed = new java.util.ArrayList<>();
        for (int i = 0; i < jars.length; i++) {
            if (jars[i].state() == JarState.RETRY_PENDING && jars[i].claimRetry()) {
                reclaimed.add(i);
            }
        }
        return reclaimed;
    }
}
