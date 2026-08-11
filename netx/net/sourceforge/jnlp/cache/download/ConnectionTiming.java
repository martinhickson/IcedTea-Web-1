package net.sourceforge.jnlp.cache.download;

public final class ConnectionTiming {
    public long connectStartMillis = -1;
    public long connectEndMillis   = -1;
    public long firstByteMillis    = -1;
    public long contentLength      = -1;
    public String  contentEncoding = null;
    public long lastModified        = -1;
}
