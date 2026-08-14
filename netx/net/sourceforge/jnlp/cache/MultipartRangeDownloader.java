package net.sourceforge.jnlp.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.sourceforge.jnlp.cache.download.ConnectionTiming;
import net.sourceforge.jnlp.security.HttpClientProvider;
import net.sourceforge.jnlp.security.HttpResponse;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Multipart parallel-range download of a single large resource (RFC 7233). The first chunk
 * (the probe response, already fetched by {@link ResourceDownloader}) is consumed here; the
 * remaining chunks are fetched concurrently and every chunk is drained to its own temp part
 * file, then concatenated in order into the destination cache file. A server that does not
 * return a satisfiable 206 for a chunk aborts the whole transfer (the caller retries / falls
 * back to a full GET).
 * <p>
 * Only identity (uncompressed) resources are eligible; pack200-gzip / gzip are handled by the
 * single-stream path. Integrity (jar signature/digest) is verified afterwards by the caller's
 * settle-good gate on the reassembled file.
 */
final class MultipartRangeDownloader {

    /** Cap on concurrent chunk fetches (chunk count can be higher; extra chunks queue). */
    private static final int MAX_PARALLEL = 8;
    /** Safety valve against pathological slot sizes (e.g. a few-byte slot on a huge file). */
    private static final int MAX_CHUNKS = 100_000;
    /** Per-chunk retries on a transient failure before aborting the whole transfer. */
    private static final int CHUNK_RETRIES = 2;
    /** Base backoff (ms) between chunk retries; scaled by attempt number. */
    private static final long CHUNK_RETRY_DELAY_MS = 500L;

    private final URL url;
    private final long totalLength;
    private final long slotSize;
    private final long ifRangeMillis;
    private final Resource resource;
    private final File dest;
    private final File tmpDir;

    private MultipartRangeDownloader(URL url, long totalLength, long slotSize, long ifRangeMillis,
            Resource resource, File dest) {
        this.url = url;
        this.totalLength = totalLength;
        this.slotSize = slotSize;
        this.ifRangeMillis = ifRangeMillis;
        this.resource = resource;
        this.dest = dest;
        // Deterministic name so a retried/aborted transfer resumes from the parts already on
        // disk instead of re-fetching every chunk.
        this.tmpDir = new File(dest.getParentFile(), dest.getName() + ".multipart");
    }

    /**
     * Consume {@code firstResponse} as chunk 0, fetch the remaining chunks in parallel, and
     * reassemble into {@code dest}. Returns {@code dest} (the fully-written file). The caller
     * must NOT close {@code firstResponse} afterwards (it is closed here).
     */
    static File download(HttpResponse firstResponse, URL url, long totalLength, long slotSize,
            Resource resource, File dest) throws IOException {
        long ifRange = firstResponse.getLastModified();
        MultipartRangeDownloader d = new MultipartRangeDownloader(url, totalLength, slotSize, ifRange, resource, dest);
        return d.run(firstResponse);
    }

    private File run(HttpResponse firstResponse) throws IOException {
        long nLong = Math.max(1L, (totalLength + slotSize - 1) / slotSize);
        if (nLong > MAX_CHUNKS) {
            throw new IOException("Refusing multipart download: " + nLong
                    + " chunks exceeds limit (slotSize=" + slotSize + ", total=" + totalLength + ")");
        }
        int n = (int) nLong;
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
            throw new IOException("Cannot create multipart temp dir: " + tmpDir);
        }
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        File[] parts = new File[n];
        long[] written = new long[n];

        ExecutorService pool = null;
        boolean success = false;
        try {
            // Chunk 0: drain the already-fetched probe response — unless a prior attempt already
            // left a complete part-0 on disk, in which case the probe body is discarded.
            parts[0] = new File(tmpDir, "part-0");
            if (slot != null) {
                slot.onFirstByte(System.currentTimeMillis());
            }
            if (parts[0].isFile() && parts[0].length() == expectedChunkLength(0)) {
                firstResponse.close();
                written[0] = parts[0].length();
            } else {
                written[0] = drainToPart(firstResponse.getBody(), parts[0]);
                firstResponse.close();
                verifyChunk(0, written[0]);
            }

            long running = written[0];
            resource.setTransferred(running);
            if (slot != null) {
                slot.addTransferred(running);
            }

            if (n > 1) {
                int parallel = Math.min(MAX_PARALLEL, n - 1);
                pool = Executors.newFixedThreadPool(parallel, r -> {
                    Thread t = new Thread(r, "itw-multipart-range");
                    t.setDaemon(true);
                    return t;
                });
                List<Future<Long>> futures = new ArrayList<>();
                List<Integer> submitted = new ArrayList<>();
                for (int i = 1; i < n; i++) {
                    final int idx = i;
                    final File part = new File(tmpDir, "part-" + idx);
                    parts[idx] = part;
                    // Resume: skip chunks a prior attempt already wrote completely.
                    if (part.isFile() && part.length() == expectedChunkLength(idx)) {
                        written[idx] = part.length();
                        running += written[idx];
                        resource.setTransferred(running);
                        if (slot != null) {
                            slot.addTransferred(written[idx]);
                        }
                        continue;
                    }
                    submitted.add(idx);
                    futures.add(pool.submit(() -> fetchChunk(idx, part)));
                }
                for (int j = 0; j < futures.size(); j++) {
                    int idx = submitted.get(j);
                    // Blocking get re-throws the worker's IOException wrapped in ExecutionException.
                    written[idx] = futures.get(j).get();
                    verifyChunk(idx, written[idx]);
                    running += written[idx];
                    resource.setTransferred(running); // single-threaded, progressive progress
                    if (slot != null) {
                        slot.addTransferred(written[idx]);
                    }
                }
            }

            concatenate(parts, dest);
            if (slot != null) {
                slot.onLastByte(System.currentTimeMillis());
            }
            if (dest.length() != totalLength) {
                throw new IOException("Multipart reassembly produced " + dest.length()
                        + " bytes, expected " + totalLength);
            }
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "Multipart range download complete: " + n + " chunks, " + totalLength
                            + " bytes from " + url);
            success = true;
            return dest;
        } catch (Exception e) {
            deleteCorruptLocal(dest);
            throw (e instanceof IOException) ? (IOException) e : new IOException("Multipart download failed", e);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
                try {
                    pool.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // Keep the parts on a transfer failure so a retry resumes from them; clean up only
            // once the full file has been reassembled successfully.
            if (success) {
                deleteRecursive(tmpDir);
            }
        }
    }

    /**
     * Remove any stale multipart parts for a resource (used when the resource is downloaded by
     * a non-multipart path — single GET, resume suffix, pack200 — so an abandoned split does not
     * leave orphan part files behind). No-op when no parts directory exists.
     */
    static void cleanupStaleParts(File dest) {
        if (dest == null) {
            return;
        }
        File dir = new File(dest.getParentFile(), dest.getName() + ".multipart");
        if (dir.isDirectory()) {
            deleteRecursive(dir);
        }
    }

    /**
     * Fetch one chunk, retrying transient failures (a dropped connection mid-chunk is exactly
     * what Range resume exists for). A persistent failure re-throws after {@link #CHUNK_RETRIES}
     * attempts so the caller can abort the whole transfer and fall back to a full GET.
     */
    private long fetchChunk(int idx, File part) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= CHUNK_RETRIES; attempt++) {
            try {
                return fetchChunkOnce(idx, part);
            } catch (IOException e) {
                last = e;
                if (attempt < CHUNK_RETRIES) {
                    try {
                        Thread.sleep(CHUNK_RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted retrying multipart chunk " + idx, ie);
                    }
                }
            }
        }
        throw last;
    }

    private long fetchChunkOnce(int idx, File part) throws IOException {
        long start = (long) idx * slotSize;
        long end = Math.min(start + slotSize - 1, totalLength - 1);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Encoding", "identity");
        headers.put("Range", "bytes=" + start + "-" + end);
        if (ifRangeMillis > 0) {
            String ifRange = ResourceDownloader.formatIfRangeDate(ifRangeMillis);
            if (ifRange != null) {
                headers.put("If-Range", ifRange);
            }
        }
        ConnectionTiming timing = new ConnectionTiming();
        try (HttpResponse r = HttpClientProvider.getDefault().open(url, "GET", headers, timing)) {
            int status = r.getStatusCode();
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("Multipart chunk " + idx + " expected 206, got " + status);
            }
            long[] cr = ResourceDownloader.parseContentRange(r.getHeader("Content-Range"));
            if (cr == null || cr[0] != start || (cr[2] >= 0 && cr[2] != totalLength)) {
                throw new IOException("Multipart chunk " + idx + " Content-Range mismatch: "
                        + r.getHeader("Content-Range"));
            }
            return drainToPart(r.getBody(), part);
        }
    }

    private void verifyChunk(int idx, long bytesWritten) throws IOException {
        long expected = expectedChunkLength(idx);
        if (bytesWritten != expected) {
            throw new IOException("Multipart chunk " + idx + " wrote " + bytesWritten
                    + " bytes, expected " + expected);
        }
    }

    private long expectedChunkLength(int idx) {
        long start = (long) idx * slotSize;
        long end = Math.min(start + slotSize - 1, totalLength - 1);
        return end - start + 1;
    }

    private static long drainToPart(InputStream in, File part) throws IOException {
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create multipart part dir: " + parent);
        }
        byte[] buf = new byte[8192];
        long total = 0;
        int rlen;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            while (-1 != (rlen = in.read(buf))) {
                out.write(buf, 0, rlen);
                total += rlen;
            }
        }
        return total;
    }

    private static void concatenate(File[] parts, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create cache dir for reassembly: " + parent);
        }
        byte[] buf = new byte[8192];
        int rlen;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            for (File part : parts) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(part))) {
                    while (-1 != (rlen = in.read(buf))) {
                        out.write(buf, 0, rlen);
                    }
                }
            }
        }
    }

    private static void deleteRecursive(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursive(f);
                } else {
                    f.delete();
                }
            }
        }
        root.delete();
    }

    private static void deleteCorruptLocal(File local) {
        if (local == null || !local.isFile()) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(local.toPath());
        } catch (IOException deleteEx) {
            OutputController.getLogger().log(deleteEx);
        }
    }
}
