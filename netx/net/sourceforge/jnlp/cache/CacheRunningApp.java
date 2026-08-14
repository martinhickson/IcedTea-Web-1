package net.sourceforge.jnlp.cache;

/**
 * A JNLP JVM that has marked itself in the catalog so {@code -Xclearcache}
 * can refuse to delete its files after a JDK relaunch (the parent releases
 * {@code MAIN_LOCK} when it exits).
 */
public final class CacheRunningApp {

    public final int pid;
    public final String jnlpPath;
    public final String processStart;

    public CacheRunningApp(int pid, String jnlpPath, String processStart) {
        this.pid = pid;
        this.jnlpPath = jnlpPath;
        this.processStart = processStart;
    }
}
