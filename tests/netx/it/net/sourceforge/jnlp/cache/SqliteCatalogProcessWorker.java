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
            if ("hold-initlock".equals(args[1])) {
                long holdMs = args.length > 2 ? Long.parseLong(args[2]) : 15_000L;
                File dbDir = CacheLRUWrapper.sqliteCacheRoot(parent);
                if (!dbDir.isDirectory() && !dbDir.mkdirs()) {
                    fail("mkdir");
                }
                File dbFile = new File(dbDir, SqliteCacheCatalog.DB_FILE_NAME);
                if (!dbFile.isFile() && !dbFile.createNewFile()) {
                    fail("create empty catalog");
                }
                File lockDir = SqliteCacheCatalog.initLockDir(dbFile);
                if (lockDir.isDirectory()) {
                    fail("initlock dir already held");
                }
                if (!lockDir.mkdir()) {
                    fail("initlock dir");
                }
                try {
                    System.out.println("READY holding");
                    System.out.flush();
                    Thread.sleep(holdMs);
                    System.out.println("OK held");
                } finally {
                    lockDir.delete();
                }
                Runtime.getRuntime().halt(0);
                return;
            }
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
                } else if ("alloc".equals(args[1])) {
                    int count = Integer.parseInt(args[2]);
                    File dbRoot = w.getCacheDir().getFile();
                    StringBuilder ids = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        w.lock();
                        try {
                            w.load();
                            int id = w.nextFolderId();
                            if (ids.length() > 0) {
                                ids.append(',');
                            }
                            ids.append(id);
                            File marker = new File(dbRoot, id + "/claimed");
                            if (!marker.getParentFile().isDirectory() && !marker.getParentFile().mkdirs()) {
                                fail("mkdir " + marker.getParent());
                            }
                            if (!marker.exists() && !marker.createNewFile()) {
                                fail("create " + marker);
                            }
                            w.addEntry(System.nanoTime() + "," + id, marker.getAbsolutePath());
                            w.store();
                        } finally {
                            w.unlock();
                        }
                    }
                    System.out.println("OK ids=" + ids);
                } else if ("count".equals(args[1])) {
                    w.lock();
                    try {
                        w.load();
                        System.out.println("OK count=" + w.getLRUSortedEntries().size());
                    } finally {
                        w.unlock();
                    }
                } else if ("insert-until-killed".equals(args[1])) {
                    int workerId = Integer.parseInt(args[2]);
                    File dbRoot = w.getCacheDir().getFile();
                    int added = 0;
                    System.out.println("READY starting");
                    System.out.flush();
                    for (int i = 0; i < 2000; i++) {
                        File jar = new File(dbRoot, workerId + "/http/kill.example/u" + i + ".jar");
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
                } else {
                    fail("unknown");
                }
            } finally {
                // Timed close: never block System.exit on WAL checkpoint.
                Thread closer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            w.close();
                        } catch (Throwable ignored) {
                            // process is exiting
                        }
                    }
                }, "sqlite-worker-close");
                closer.setDaemon(true);
                closer.start();
                closer.join(2000);
            }
            // halt skips sqlite-jdbc shutdown hooks that wait on WAL while a peer
            // JVM still holds the catalog (System.exit hung Failsafe workers).
            Runtime.getRuntime().halt(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            fail(t.getMessage());
        }
    }

    private static void fail(String msg) {
        System.out.println("ERR " + msg);
        Runtime.getRuntime().halt(1);
    }
}
