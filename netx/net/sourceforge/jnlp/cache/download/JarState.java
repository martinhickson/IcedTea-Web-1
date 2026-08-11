package net.sourceforge.jnlp.cache.download;

public enum JarState {
    IN_FLIGHT,
    RETRY_PENDING,
    GOOD,
    SETTLED_BAD;

    public boolean isAbsorbing() { return this == GOOD || this == SETTLED_BAD; }
    public boolean isUsable()    { return this == GOOD; }

    private static final JarState[] VALUES = values();
    static JarState fromOrdinal(int i) { return VALUES[i]; }
}
