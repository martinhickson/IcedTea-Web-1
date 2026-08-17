package net.sourceforge.jnlp.cache;

/**
 * Per-resource cache metadata. Lives on the catalog row (SQLite), not a sidecar
 * {@code .info} file. Legacy properties catalog stores the same fields in the
 * catalog file, still not next to the jar.
 */
public final class CacheEntryMeta {

    public String path;
    public String resourceUrl;
    public String jnlpPath;
    public Long contentLength;
    /** HTTP Content-Length (pack.gz wire size). Not the unpacked jar length. */
    public Long wireLength;
    public Long lastModified;
    public Long lastUpdated;
    public boolean markedDelete;

    public CacheEntryMeta() {
    }

    /** Same keys the cache viewer used to read from a sidecar {@code .info} file. */
    public String formatAsInfoText() {
        StringBuilder sb = new StringBuilder();
        appendInfoLine(sb, CacheEntry.KEY_JNLP_PATH, jnlpPath);
        appendInfoLine(sb, "content-length", contentLength == null ? null : Long.toString(contentLength));
        appendInfoLine(sb, "wire-length", wireLength == null ? null : Long.toString(wireLength));
        appendInfoLine(sb, "last-modified", lastModified == null ? null : Long.toString(lastModified));
        appendInfoLine(sb, "last-updated", lastUpdated == null ? null : Long.toString(lastUpdated));
        appendInfoLine(sb, "delete", Boolean.toString(markedDelete));
        return sb.toString();
    }

    private static void appendInfoLine(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(key).append('=').append(value);
    }

    public CacheEntryMeta copy() {
        CacheEntryMeta c = new CacheEntryMeta();
        c.path = path;
        c.resourceUrl = resourceUrl;
        c.jnlpPath = jnlpPath;
        c.contentLength = contentLength;
        c.wireLength = wireLength;
        c.lastModified = lastModified;
        c.lastUpdated = lastUpdated;
        c.markedDelete = markedDelete;
        return c;
    }
}
