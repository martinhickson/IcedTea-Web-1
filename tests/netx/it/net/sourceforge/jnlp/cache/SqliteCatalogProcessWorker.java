/*
 Copyright (C) 2026 IcedTea-Web contributors
*/
package net.sourceforge.jnlp.cache;

import java.io.File;

/**
 * ProcessBuilder worker for {@link SqliteCacheCatalogIT} (Failsafe, not Surefire).
 *
 * <p>Args: {@code <cacheParent> insert <workerId> <count>} or {@code <cacheParent> count}
 */
public final class SqliteCatalogProcessWorker {

    private SqliteCatalogProcessWorker() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                fail("usage");
            }
            File parent = new File(args[0]).getAbsoluteFile();
            CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parent);
            try {
                if ("insert".equals(args[1])) {
                    int workerId = Integer.parseInt(args[2]);
                    int count = Integer.parseInt(args[3]);
                    File dbRoot = w.getCacheDir().getFile();
                    int added = 0;
                    for (int i = 0; i < count; i++) {
                        File jar = new File(dbRoot, workerId + "/http/unit.example/u" + workerId + "-" + i + ".jar");
                        if (!jar.getParentFile().mkdirs() && !jar.getParentFile().isDirectory()) {
                            fail("mkdir");
                        }
                        if (!jar.exists() && !jar.createNewFile()) {
                            fail("create");
                        }
                        w.lock();
                        try {
                            w.load();
                            if (w.addEntry(System.nanoTime() + "," + workerId, jar.getAbsolutePath())) {
                                added++;
                            }
                            w.store();
                        } finally {
                            w.unlock();
                        }
                    }
                    System.out.println("OK added=" + added);
                } else if ("count".equals(args[1])) {
                    w.lock();
                    try {
                        w.load();
                        System.out.println("OK count=" + w.getLRUSortedEntries().size());
                    } finally {
                        w.unlock();
                    }
                } else {
                    fail("unknown");
                }
            } finally {
                w.close();
            }
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            fail(t.getMessage());
        }
    }

    private static void fail(String msg) {
        System.out.println("ERR " + msg);
        System.exit(1);
    }
}
