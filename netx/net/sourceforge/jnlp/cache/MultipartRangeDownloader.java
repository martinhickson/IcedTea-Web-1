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
 * Multipart parallel-range download of a single large resource (RFC 7233). The caller has
 * already seen a probe {@code 206} whose {@code Content-Range} total is larger than one slot.
 * The remaining chunks are scheduled immediately, while the probe body is still drained as
 * chunk 0. Every chunk is written to its own temp part file, then concatenated in order into
 * the destination cache file. A server that does not return a satisfiable 206 for a chunk
 * aborts the whole transfer (the caller retries / falls back to a full GET).
 * <p>
 * A chunk whose Content-Encoding is gzip is gzip of that uncompressed slice and is
 * inflated before it is written. pack200-gzip stays on the single-stream path.
 * Integrity (jar signature/digest) is verified afterwards by the caller's
 * settle-good gate on the reassembled file.
 */
final class MultipartRangeDownloader {

    /** Cap on concurrent chunk fetches (chunk count can be higher; extra chunks queue). */
    private static final int MAX_PARALLEL = 8;
    /**
     * Shared daemon pool for ALL multipart chunk fetches across all resources, so concurrent
     * splits can't multiply into one-threadpool-per-download (which would overrun the HTTP
     * connection pool's per-route limit). Sized below the default maxPerRoute (12).
     */
    private static final ExecutorService CHUNK_POOL = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
        Thread t = new Thread(r, "itw-multipart-range");
        t.setDaemon(true);
        return t;
    });
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
     * Schedule every chunk after the probe, then drain {@code firstResponse} as chunk 0 in
     * parallel with those requests, and reassemble into {@code dest}. Returns {@code dest}
     * (the fully-written file). The caller must NOT close {@code firstResponse} afterwards
     * (it is closed here).
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
        // Declared outside the try so a failure while draining chunk 0 can cancel slices
        // that were already scheduled from the probe 206.
        List<Future<Long>> futures = new ArrayList<>();

        boolean success = false;
        try {
            parts[0] = new File(tmpDir, "part-0");
            if (slot != null) {
                slot.onFirstByte(System.currentTimeMillis());
            }

            // The probe 206 is already assured by the caller. Schedule the other slices
            // before reading the probe body so they overlap that transfer.
            List<Integer> submitted = new ArrayList<>();
            long alreadyOnDisk = 0L;
            if (n > 1) {
                for (int i = 1; i < n; i++) {
                    final int idx = i;
                    final File part = new File(tmpDir, "part-" + idx);
                    parts[idx] = part;
                    // Resume: skip chunks a prior attempt already wrote completely.
                    if (part.isFile() && part.length() == expectedChunkLength(idx)) {
                        written[idx] = part.length();
                        alreadyOnDisk += written[idx];
                        continue;
                    }
                    submitted.add(idx);
                    futures.add(CHUNK_POOL.submit(() -> fetchChunk(idx, part)));
                }
            }

            // Chunk 0: drain the probe body unless a prior attempt already left it on disk.
            if (parts[0].isFile() && parts[0].length() == expectedChunkLength(0)) {
                firstResponse.close();
                written[0] = parts[0].length();
            } else {
                try (InputStream body = ResourceDownloader.rangeBody(firstResponse)) {
                    written[0] = drainToPart(body, parts[0]);
                }
                firstResponse.close();
                verifyChunk(0, written[0]);
            }

            long running = written[0] + alreadyOnDisk;
            resource.setTransferred(running);
            if (slot != null) {
                slot.addTransferred(running);
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
            for (Future<Long> future : futures) {
                future.cancel(true);
            }
            deleteCorruptLocal(dest);
            throw (e instanceof IOException) ? (IOException) e : new IOException("Multipart download failed", e);
        } finally {
            // CHUNK_POOL is shared and long-lived; never shut it down here.
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
            // Identity is requested; a server that still gzips the slice is inflated
            // so the part length matches the uncompressed Content-Range.
            try (InputStream body = ResourceDownloader.rangeBody(r)) {
                return drainToPart(body, part);
            }
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
