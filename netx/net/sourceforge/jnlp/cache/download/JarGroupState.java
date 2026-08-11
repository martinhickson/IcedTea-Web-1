package net.sourceforge.jnlp.cache.download;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class JarGroupState {

    final JarSlot[] jars;
    final ConcurrentHashMap<URL, JarSlot> byUrl;
    final AtomicInteger settledCount = new AtomicInteger();
    final CompletableFuture<Void> done = new CompletableFuture<>();
    volatile long groupStartMillis;
    volatile long groupEndMillis = -1;

    public static JarGroupState forJars(List<URL> jarUrls) {
        JarGroupState g = new JarGroupState(jarUrls.size());
        long start = System.currentTimeMillis();
        g.groupStartMillis = start;
        for (int i = 0; i < jarUrls.size(); i++) {
            JarSlot slot = new JarSlot(i, jarUrls.get(i), g, start);
            g.jars[i] = slot;
            g.byUrl.put(jarUrls.get(i), slot);
        }
        return g;
    }

    private JarGroupState(int size) {
        this.jars = new JarSlot[size];
        this.byUrl = new ConcurrentHashMap<>(size * 2);
    }

    public JarSlot slot(int index)        { return jars[index]; }
    public JarSlot slot(URL url)          { return byUrl.get(url); }
    public int size()                     { return jars.length; }
    public CompletableFuture<Void> done() { return done; }
    public void awaitAll()                { done.join(); }
    public CompletableFuture<Void> await(URL url) { return byUrl.get(url).settled(); }

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
}
