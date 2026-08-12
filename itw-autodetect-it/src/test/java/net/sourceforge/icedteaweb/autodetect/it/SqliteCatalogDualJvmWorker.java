package net.sourceforge.icedteaweb.autodetect.it;

import java.io.File;
import java.util.List;
import java.util.Map.Entry;
import net.sourceforge.jnlp.cache.CacheDirectory;
import net.sourceforge.jnlp.cache.CacheLRUWrapper;
import net.sourceforge.jnlp.cache.CacheUtil;

/**
 * Child-process worker for {@link SqliteCacheCatalogDualJvmIT}. Speaks a tiny
 * line protocol on stdout ({@code OK ...} / {@code ERR ...}).
 *
 * <p>Args: {@code <cacheParent> <command> [args...]}
 * <ul>
 *   <li>{@code insert <workerId> <count>} — reserve {@code count} jars under {@code db/}</li>
 *   <li>{@code count} — print catalog size</li>
 *   <li>{@code find <urlPath>} — print matching paths (one per line) then {@code OK}</li>
 * </ul>
 */
public final class SqliteCatalogDualJvmWorker {

    private SqliteCatalogDualJvmWorker() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                fail("usage: cacheParent command ...");
            }
            File cacheParent = new File(args[0]).getAbsoluteFile();
            String command = args[1];
            CacheLRUWrapper wrapper = CacheLRUWrapper.createForTests(true, cacheParent);
            try {
                if ("insert".equals(command)) {
                    if (args.length < 4) {
                        fail("insert needs workerId count");
                    }
                    int workerId = Integer.parseInt(args[2]);
                    int count = Integer.parseInt(args[3]);
                    File dbRoot = wrapper.getCacheDir().getFile();
                    int added = 0;
                    for (int i = 0; i < count; i++) {
                        File jar = new File(dbRoot, workerId + "/http/dual.example/w" + workerId + "-" + i + ".jar");
                        if (!jar.getParentFile().mkdirs() && !jar.getParentFile().isDirectory()) {
                            fail("mkdir " + jar.getParent());
                        }
                        if (!jar.exists() && !jar.createNewFile()) {
                            fail("create " + jar);
                        }
                        File info = new File(jar.getPath() + CacheDirectory.INFO_SUFFIX);
                        if (!info.exists() && !info.createNewFile()) {
                            fail("create " + info);
                        }
                        wrapper.lock();
                        try {
                            wrapper.load();
                            String key = System.nanoTime() + "," + workerId;
                            if (wrapper.addEntry(key, jar.getAbsolutePath())) {
                                added++;
                            }
                            wrapper.store();
                        } finally {
                            wrapper.unlock();
                        }
                    }
                    ok("added=" + added);
                } else if ("count".equals(command)) {
                    wrapper.lock();
                    try {
                        wrapper.load();
                        ok("count=" + wrapper.getLRUSortedEntries().size());
                    } finally {
                        wrapper.unlock();
                    }
                } else if ("find".equals(command)) {
                    if (args.length < 3) {
                        fail("find needs urlPath");
                    }
                    String urlPath = args[2];
                    wrapper.lock();
                    try {
                        wrapper.load();
                        List<Entry<String, String>> found = wrapper.findEntriesByUrlPath(urlPath);
                        for (Entry<String, String> e : found) {
                            System.out.println(e.getValue());
                        }
                        ok("matches=" + found.size());
                    } finally {
                        wrapper.unlock();
                    }
                } else if ("path-of".equals(command)) {
                    if (args.length < 3) {
                        fail("path-of needs absoluteJar");
                    }
                    String jar = args[2];
                    String url = CacheUtil.pathToURLPath(jar, wrapper.getCacheDir().getFullPath());
                    ok("urlPath=" + url);
                } else {
                    fail("unknown command " + command);
                }
            } finally {
                try {
                    wrapper.close();
                } catch (Throwable ignored) {
                    // process is exiting
                }
            }
            Runtime.getRuntime().halt(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void ok(String detail) {
        System.out.println("OK " + detail);
    }

    private static void fail(String detail) {
        System.out.println("ERR " + detail);
        Runtime.getRuntime().halt(1);
    }
}
