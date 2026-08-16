package net.sourceforge.jnlp.cache;

import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADED;
import static net.sourceforge.jnlp.cache.Resource.Status.ERROR;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;

import io.pack200.Pack200;
import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.OptionsDefinitions;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.security.ConnectionFactory;
import net.sourceforge.jnlp.security.ItwTls;
import net.sourceforge.jnlp.security.SecurityDialogs;
import net.sourceforge.jnlp.security.dialogs.InetSecurity511Panel;
import net.sourceforge.jnlp.util.HttpUtils;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.XDesktopEntry;
import net.sourceforge.jnlp.util.logging.OutputController;

public class ResourceDownloader implements Runnable {

    private static final long[] RETRY_DELAYS = {2000L, 3000L, 5000L, 8000L};
    private static final int RETRY_COUNT = 5;
    private static final int COPY_BUFFER_SIZE_64KB = 65536;
    /**
     * Advertise pack200-gzip content-encoding for HTTP content negotiation.
     * gzip is only added when {@code deployment.http.useGZip} is true (default false).
     */
    private static final String PACK200_GZIP_ENCODING = "pack200-gzip";

    private static String getAcceptEncoding() {
        if (Boolean.valueOf(JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_HTTP_USE_GZIP))) {
            return PACK200_GZIP_ENCODING + ", gzip";
        }
        return PACK200_GZIP_ENCODING;
    }
    private static final Set<String> LOGGED_MISSING_FAVICONS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Resource resource;
    /**
     * Pre-computed URL candidates (version-encoded, query-param, plain) to
     * try with GET when skipHeadIfNotCached is active and the cache is empty.
     * Null means use the normal single-URL download path.
     */
    private List<URL> downloadUrlCandidates;

    /**
     * The {@code lock} parameter is retained for source/API compatibility but is
     * ignored — downloads settle via the lock-free {@link JarSlot} machine and
     * the old {@code lock.notifyAll()} handshake was removed.
     */
    public ResourceDownloader(Resource resource, Object lock) {
        this.resource = resource;
    }

    static boolean isFavIconUrl(URL url) {
        if (url == null) {
            return false;
        }
        String path = url.getPath();
        if (path == null) {
            return false;
        }
        return path.endsWith("/" + XDesktopEntry.FAVICON)
                || path.endsWith("\\" + XDesktopEntry.FAVICON)
                || path.endsWith(XDesktopEntry.FAVICON);
    }

    /**
     * Logs once per origin when favicon probes fail. Parent-directory walks try
     * many URLs; those must not each print an INFO line.
     */
    static void logMissingFavIconInfo(URL url) {
        String key = faviconMissingLogKey(url);
        if (!LOGGED_MISSING_FAVICONS.add(key)) {
            logFavIconTrace("Favicon missing (already reported for " + key + "): " + url);
            return;
        }
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "INFO: This application does not have a favourite icon yet"
                + (url != null ? " (" + url + ")" : "")
                + ". Add favicon.ico to the JNLP codebase root"
                + " (for example sample-apps/public/favicon.ico on the Angular dev server).");
    }

    /**
     * Deduplicate by origin so {@code /app/favicon.ico}, {@code /favicon.ico}, and
     * further parent probes share one notice. {@code file:} URLs share one key.
     */
    static String faviconMissingLogKey(URL url) {
        if (url == null) {
            return "<unknown>";
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return url.getProtocol() + ":local";
        }
        int port = url.getPort();
        return url.getProtocol() + "://" + host + (port >= 0 ? ":" + port : "");
    }

    static void logFavIconTrace(String message) {
        OutputController.getLogger().log(OutputController.Level.TRACE, message);
    }

    static void logFavIconTrace(Throwable ex) {
        OutputController.getLogger().log(OutputController.Level.TRACE, ex);
    }

    private static void logResourceDebug(URL context, String message) {
        if (isFavIconUrl(context)) {
            logFavIconTrace(message);
        } else {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, message);
        }
    }

    private static void logResourceDebug(URL context, Throwable ex) {
        if (isFavIconUrl(context)) {
            logFavIconTrace(ex);
        } else {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, ex);
        }
    }

    private static void logDownloadFailure(URL url, Exception ex) {
        if (isFavIconUrl(url)) {
            logMissingFavIconInfo(url);
            logFavIconTrace(ex);
            return;
        }
        OutputController.getLogger().log(ex);
    }

    private static void logDownloadFailure(URL url, IOException ex) {
        if (isFavIconUrl(url)) {
            logMissingFavIconInfo(url);
            logFavIconTrace(ex);
            return;
        }
        OutputController.getLogger().log(ex);
    }

    //JDK 14 and later doesn't have built-in Pack200 functionality
    private static boolean isJDK14OrLater() {
        String[] elements = System.getProperty("java.version").split("\\.");
        int version;
        int discard = Integer.parseInt(elements[0]);
        if (discard == 1) {
            version = Integer.parseInt(elements[1]);
        } else {
            version = discard;
        }
        return version >= 14;
    }

    /**
     * Whether to skip the HEAD cache-validation probe when a resource is not
     * in cache.  Controlled by {@code deployment.http.skipHeadIfNotCached}
     * (default true).
     */
    private static boolean isSkipHeadIfNotCached() {
        return Boolean.valueOf(JNLPRuntime.getConfiguration().getProperty(
                DeploymentConfiguration.KEY_HTTP_SKIP_HEAD_IF_NOT_CACHED));
    }

    /**
     * Whether the resource's cache entry exists and has a usable file.
     * Used to decide whether URL probing (HEAD) can be skipped entirely.
     */
    private boolean isResourceCached() {
        CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        return entry.isCached();
    }

    static int getUrlResponseCode(URL url, Map<String, String> requestProperties, ResourceTracker.RequestMethods requestMethod) throws IOException {
        return getUrlResponseCodeWithRedirectonResult(url, requestProperties, requestMethod).result;
    }

    /**
     * Connects to the given URL, and grabs a response code and redirecton if
     * the URL uses the HTTP protocol, or returns an arbitrary valid HTTP
     * response code.
     *
     * @return the response code if HTTP connection and redirection value, or
     * HttpURLConnection.HTTP_OK and null if not.
     * @throws IOException
     */
    static UrlRequestResult getUrlResponseCodeWithRedirectonResult(URL url, Map<String, String> requestProperties, ResourceTracker.RequestMethods requestMethod) throws IOException {
        UrlRequestResult result = new UrlRequestResult();
        // Freshness is handled via conditional requests (If-Modified-Since) and ITW's own
        // cache layer, not JVM response caching. The HTTP client implementation is chosen
        // by deployment.http.client (apache default, oracle escape hatch).
        try (net.sourceforge.jnlp.security.HttpResponse response =
                     net.sourceforge.jnlp.security.HttpClientProvider.getDefault()
                             .open(url, requestMethod.toString(), requestProperties, null)) {
            int responseCode = response.getStatusCode();

            /* Fully consuming current request helps with connection re-use
             * See http://docs.oracle.com/javase/1.5.0/docs/guide/net/http-keepalive.html */
            result.result = responseCode;

            if (!isFavIconUrl(url)) {
                Map<String, List<String>> header = response.getHeaders();
                for (Map.Entry<String, List<String>> entry : header.entrySet()) {
                    OutputController.getLogger().log("Key : " + entry.getKey() + " ,Value : " + entry.getValue());
                }
            }
            /*
             * Do this only on 301,302,303(?)307,308>
             * Now setting value for all, and lets upper stack to handle it
             */
            String possibleRedirect = response.getHeader("Location");
            if (possibleRedirect != null && possibleRedirect.trim().length() > 0) {
                result.URL = new URL(possibleRedirect);
            }
            result.lastModified = response.getLastModified();
            result.length = response.getContentLength();
        }
        return result;

    }

    @Override
    public void run() {
        // The download thread drives its own one-shot retry: when an attempt parks
        // the slot in RETRY_PENDING (terminal-unusable, retry available), it claims
        // the retry itself and runs a second attempt. RETRY_PENDING therefore never
        // persists, which lets ResourceTracker.wait() be a pure done.get() barrier
        // (no polling, no monitor). The slot's retried latch bounds it to one retry.
        try {
            if (resource.isSet(DOWNLOADED) || resource.isSet(ERROR)) {
                return;   // already terminal before we started
            }
            doAttempt();
            if (resource.isTerminal()) {
                return;
            }
            net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
            if (slot != null && slot.claimRetry()) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "Download retry for " + resource.getLocation()
                                + " after unusable/corrupt attempt — one shot left");
                doAttempt();   // attempt 2
            }
            if (!resource.isTerminal()) {
                // No more retries: never leave IN_FLIGHT / orphaned RETRY_PENDING.
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "Download of " + resource.getLocation()
                                + " out of retries — failing fast");
                failFastOutOfRetries();
            }
        } catch (Throwable t) {
            // never leave the group hanging: settle bad on any unexpected failure
            OutputController.getLogger().log(t);
            if (t instanceof Error) {
                // OOM / linkage / etc.: retry rarely helps and settleUnusable→RETRY_PENDING
                // used to strand the group when settleBadFinal only accepted IN_FLIGHT.
                failFastOutOfRetries();
            } else {
                settleSlotBad();
                if (!resource.isTerminal()) {
                    failFastOutOfRetries();
                }
            }
        }
    }

    private void doAttempt() {
        resource.fireDownloadEvent(); // fire CONNECTING
        initializeResource();
        if (resource.isTerminal() || isParkedForRetry()) {
            return;   // initialize completed it, or parked — retry re-runs the full attempt
        }
        resource.fireDownloadEvent(); // fire CONNECTING
        downloadResource();
    }

    private boolean isParkedForRetry() {
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        return slot != null && slot.state() == net.sourceforge.jnlp.cache.download.JarState.RETRY_PENDING;
    }

    private void initializeResource() {
        if (!JNLPRuntime.isOfflineForced() && resource.isConnectable()) {
            initializeOnlineResource();
        } else {
            initializeOfflineResource();
        }
    }

    private void initializeOnlineResource() {
        try {
            URL headWinner = SizeFirstDownloadQueue.headWinner(resource);
            if (headWinner != null) {
                downloadUrlCandidates = Collections.singletonList(headWinner);
                resource.setDownloadLocation(headWinner);
                resource.fireDownloadEvent(); // fire CONNECTED
                return;
            }
            // When skipHeadIfNotCached is enabled (default) and the resource is
            // not in cache, skip ALL URL probing (HEAD/GET) via findBestUrl
            // and go straight to download.  Java's HttpURLConnection follows
            // HTTP redirects automatically, so we do not lose redirect support
            // by skipping the application-level probe.
            if (isSkipHeadIfNotCached() && !isResourceCached()) {
                // Pre-compute URL candidates for GET-based download (no HEAD probe).
                // Most likely first (__V, then ?version-id=, then plain). Pack.gz
                // is omitted once the host has 404'd it.
                DownloadOptions options = resource.getDownloadOptions();
                if (options == null) {
                    options = new DownloadOptions(false, false);
                }
                downloadUrlCandidates = new ResourceUrlCreator(resource, options).getUrls();
                resource.setDownloadLocation(downloadUrlCandidates.get(0));

                resource.fireDownloadEvent(); // fire CONNECTED
                return;
            }

            UrlRequestResult finalLocation = findBestUrl(resource);
            if (finalLocation != null) {
                initializeFromURL(finalLocation);
            } else {
                initializeOfflineResource();
            }
        } catch (Exception e) {
            OutputController.getLogger().log(e);
            settleSlotBad();
            resource.fireDownloadEvent(); // fire ERROR
        }
    }

    private void initializeFromURL(UrlRequestResult location) throws IOException {
        CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        net.sourceforge.jnlp.security.HttpResponse response = null;
        entry.lock();
        try {
            resource.setDownloadLocation(location.URL);

            // When the resource is not in cache and skipHeadIfNotCached is enabled
            // (default), bypass the HEAD cache-validation probe. The server round-trip
            // through an intercepting proxy is expensive; if there is nothing to
            // validate we can go straight to the download phase.
            if (isSkipHeadIfNotCached() && !entry.isCached()) {
                resource.setSize(location.length != null ? location.length : -1);
                if (location.length != null) {
                    resource.setWireSize(location.length.longValue());
                }

                resource.fireDownloadEvent(); // fire CONNECTED
                return;
            }

            // Cache-freshness only. Never GET here: an unread GET's close()
            // drains chunked/gzip (the whole jar) and steals the HTTP pool.
            Long size = location.length;
            Long lm = location.lastModified;
            if (size == null || lm == null) {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Accept-Encoding", getAcceptEncoding());
                response = net.sourceforge.jnlp.security.HttpClientProvider.getDefault()
                        .open(location.URL, "HEAD", headers, null);
            }

            File localFile = null;
            if (resource.getRequestVersion() == resource.getDownloadVersion()) {
                localFile = entry.getLocalFile();
            } else {
                localFile = CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion());
            }
            if (size == null && response != null) {
                size = response.getContentLength();
            }
            if (lm == null && response != null) {
                lm = response.getLastModified();
            }
            // If the newest LRU slot is a ghost (catalog row, no bytes) but an older folder still has the
            // jar, reuse that copy when it is still current. Do not point localFile at the old
            // copy when a re-download is required — writes must keep using the newest slot.
            File existingOnDisk = null;
            boolean localUsable = localFile != null && localFile.isFile() && localFile.length() > 0
                    && (!CacheUtil.isJarResourceUrl(resource.getLocation())
                    || jarPassesIntegrity(localFile));
            if (!localUsable) {
                existingOnDisk = CacheUtil.findExistingCacheFile(resource.getLocation(), resource.getDownloadVersion());
            }
            File fileForCurrency = localUsable ? localFile : existingOnDisk;
            boolean current = fileForCurrency != null
                    && CacheUtil.isCurrent(resource.getLocation(), resource.getRequestVersion(), lm, entry, fileForCurrency)
                    && resource.getUpdatePolicy() != UpdatePolicy.FORCE;
            if (current && existingOnDisk != null && !localUsable) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "Reusing existing cache file " + existingOnDisk + " instead of missing/corrupt " + localFile);
                localFile = existingOnDisk;
            }
            if (!current) {
                if (entry.isCached()) {
                    entry.markForDelete();
                    entry.store();
                    // Old entry will still exist. (but removed at cleanup)
                    localFile = CacheUtil.makeNewCacheFile(resource.getLocation(), resource.getDownloadVersion());
                    CacheEntry newEntry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
                    newEntry.lock();
                    entry.unlock();
                    entry = newEntry;
                }
            }

            resource.setLocalFile(localFile);
            // resource.connection = connection;
            resource.setSize(size);
            if (size != null) {
                resource.setWireSize(size.longValue());
            }

            // Never mark DOWNLOADED when the local file is missing — that is what produced
            // NoSuchFileException in JarCertVerifier with corrupt/partial cache state.
            // Signature/digest verify happens here before confirming cache-hit success.
            if (current && localFile != null && localFile.isFile() && localFile.length() > 0
                    && (!CacheUtil.isJarResourceUrl(resource.getLocation())
                    || jarPassesIntegrity(localFile))) {
                settleSlotGood(true);
            }

            // update cache entry
            if (!current) {
                entry.setRemoteContentLength(size);
                entry.setLastModified(lm);
            }
            entry.setLastUpdated(System.currentTimeMillis());
            try {
                //do not die here no metter of cost. Just metadata
                //is the path from user best to store? He can run some jnlp from temp which then be stored
                //on contrary, this downloads the jnlp, we actually do not have jnlp parsed during first interaction
                //in addition, downloaded name can be really nasty (some generated has from dynamic servlet.jnlp)
                //anjother issue is forking. If this (eg local) jnlp starts its second isntance, the url *can* be different
                //in contrary, usally si no. as fork is reusing all args, and only adding xmx/xms and xnofork.
                if (Boot.getOptionParser() == null) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                            "Not-setting jnlp-path: option parser not initialized yet");
                } else {
                    String jnlpPath = Boot.getOptionParser().getMainArg(); //get jnlp from args passed
                    if (jnlpPath == null || jnlpPath.equals("")) {
                        jnlpPath = Boot.getOptionParser().getParam(OptionsDefinitions.OPTIONS.JNLP);
                        if (jnlpPath == null || jnlpPath.equals("")) {
                            jnlpPath = Boot.getOptionParser().getParam(OptionsDefinitions.OPTIONS.HTML);
                            if (jnlpPath == null || jnlpPath.equals("")) {
                                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, "Not-setting jnlp-path for missing main/jnlp/html argument");
                            } else {
                                entry.setJnlpPath(jnlpPath);
                            }
                        } else {
                            entry.setJnlpPath(jnlpPath);
                        }
                    } else {
                        entry.setJnlpPath(jnlpPath);
                    }
                }
            } catch (Exception ex){
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, ex);
            }
            entry.store();
            resource.fireDownloadEvent(); // fire CONNECTED
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception ignored) {
                }
            }
            entry.unlock();
        }
    }

    private void initializeOfflineResource() {
        CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        entry.lock();

        try {
            File localFile = CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion());

            if (localFile != null && localFile.exists()) {
                long size = localFile.length();

                resource.setLocalFile(localFile);
                resource.setSize(size);
                settleSlotGood(true);
            } else {
                if (isFavIconUrl(resource.getLocation())) {
                    logMissingFavIconInfo(resource.getLocation());
                } else {
                    OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "You are trying to get resource " + resource.getLocation().toExternalForm() + " but it is not in cache and could not be downloaded. Attempting to continue, but you may expect failure");
                }
                settleSlotBad();
            }
            resource.fireDownloadEvent(); // fire CONNECTED or ERROR

        } finally {
            entry.unlock();
        }

    }

    /**
     * Returns the 'best' valid URL for the given resource. This first adjusts
     * the file name to take into account file versioning and packing, if
     * possible.
     *
     * @param resource the resource
     * @return the best URL, or null if all failed to resolve
     */
    protected UrlRequestResult findBestUrl(Resource resource) {
        DownloadOptions options = resource.getDownloadOptions();
        if (options == null) {
            options = new DownloadOptions(false, false);
        }

        List<URL> urls = new ResourceUrlCreator(resource, options).getUrls();
        URL resourceLocation = resource.getLocation();
        logResourceDebug(resourceLocation, "Finding best URL for: " + resource.getLocation() + " : " + options.toString());
        logResourceDebug(resourceLocation, "All possible urls for "
                + resource.toString() + " : " + urls);
        for (ResourceTracker.RequestMethods requestMethod : ResourceTracker.RequestMethods.getValidRequestMethods()) {
            for (int i = 0; i < urls.size(); i++) {
                URL url = urls.get(i);
                try {
                    Map<String, String> requestProperties = new HashMap<>();
                    requestProperties.put("Accept-Encoding", getAcceptEncoding());

                    UrlRequestResult response = getUrlResponseCodeWithRedirectonResult(url, requestProperties, requestMethod);
                    if (response.result == 511) {
                        if (!InetSecurity511Panel.isSkip()) {

                            boolean result511 = SecurityDialogs.show511Dialogue(resource);
                            if (!result511) {
                                throw new RuntimeException("Terminated on users request after encauntering 'http 511 authentication'.");
                            }
                            //try again, what to do with original resource was nowhere specified
                            i--;
                            continue;
                        }
                    }
                    if (response.shouldRedirect()) {
                        if (response.URL == null) {
                            logResourceDebug(resourceLocation, "Although " + resource.toString() + " got redirect " + response.result + " code for " + requestMethod + " request for " + url.toExternalForm() + " the target was null. Not following");
                        } else {
                            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Resource " + resource.toString() + " got redirect " + response.result + " code for " + requestMethod + " request for " + url.toExternalForm() + " adding " + response.URL.toExternalForm() + " to list of possible urls");
                            if (!JNLPRuntime.isAllowRedirect()) {
                                throw new RedirectionException("The resource " + url.toExternalForm() + " is being redirected (" + response.result + ") to " + response.URL.toExternalForm() + ". This is disabled by default. If you wont to allow it, run javaws with -allowredirect parameter.");
                            }
                            urls.add(response.URL);
                        }
                    } else if (response.isInvalid()) {
                        logResourceDebug(resourceLocation, "For " + resource.toString() + " the server returned " + response.result + " code for " + requestMethod + " request for " + url.toExternalForm());
                    } else {
                        logResourceDebug(resourceLocation, "best url for " + resource.toString() + " is " + url.toString() + " by " + requestMethod);
                        if (response.URL == null) {
                            response.URL = url;
                        }
                        return response; /* This is the best URL */

                    }
                } catch (IOException e) {
                    if (ItwTls.isCipherNegotiationFailure(e)) {
                        logResourceDebug(resourceLocation, "TLS cipher suite not negotiated for "
                                + url + " by " + requestMethod + " ("
                                + ItwTls.handshakeMissReason(e) + ")");
                    } else {
                        logResourceDebug(resourceLocation, "While processing " + url.toString()
                                + " by " + requestMethod + " for resource " + resource.toString()
                                + " got " + e + ": ");
                        logResourceDebug(resourceLocation, e);
                    }
                }
            }
        }

        /* No valid URL, return null */
        return null;
    }

    private void downloadResource() {
        URL downloadTo = resource.getLocation(); //Where to download to
        net.sourceforge.jnlp.security.HttpResponse response = null;
        URL downloadFrom = null;

        try {
            // When skipHeadIfNotCached set up URL candidates during initialize,
            // try each with a GET. The first successful GET IS the download
            // (no separate HEAD probe), giving at most one GET per download
            // on a clean cache.
            List<URL> tryUrls = downloadUrlCandidates != null
                    ? downloadUrlCandidates
                    : Collections.singletonList(resource.getDownloadLocation());

            IOException lastError = null;
            for (URL candidate : tryUrls) {
                try {
                    response = getDownloadConnection(candidate);
                    // HTTP error codes (404 etc.) are not exceptions; check the
                    // status explicitly to fall through to the next candidate.
                    if (response.getStatusCode() >= 400) {
                        logResourceDebug(downloadTo, "GET returned " + response.getStatusCode()
                                + " for " + candidate + ", trying next URL candidate");
                        ResourceUrlCreator.notePackHost(candidate, false);
                        response.close();
                        response = null;
                        continue;
                    }
                    downloadFrom = candidate;
                    resource.setDownloadLocation(candidate);
                    ResourceUrlCreator.notePackHost(candidate, candidate.getPath() != null
                            && candidate.getPath().endsWith(".pack.gz"));
                    break;
                } catch (IOException e) {
                    lastError = e;
                    if (ItwTls.isCipherNegotiationFailure(e)) {
                        logResourceDebug(downloadTo, "TLS cipher suite not negotiated for "
                                + candidate + " (" + ItwTls.handshakeMissReason(e) + ")");
                    } else {
                        logResourceDebug(downloadTo, "GET failed for " + candidate
                                + ", trying next URL candidate");
                    }
                }
            }
            if (response == null) {
                throw lastError != null ? lastError : new IOException("No URL candidates");
            }
            // Chunked GETs have no Content-Length; size-first / HEAD already planted
            // resource.size. Adopt GET length only when size is still unknown.
            long responseLength = response.getContentLength();
            if (responseLength > 0 && resource.getSize() <= 0) {
                resource.setSize(responseLength);
            }
            if (responseLength > 0) {
                resource.setWireSize(responseLength);
            }

            String contentEncoding = response.getContentEncoding();

            logResourceDebug(downloadTo, "Downloading " + downloadTo + " using "
                    + downloadFrom + " (encoding : " + contentEncoding + ") ");

            boolean packgz = "pack200-gzip".equals(contentEncoding)
                    || downloadFrom.getPath().endsWith(".pack.gz");
            boolean gzip = "gzip".equals(contentEncoding);

            // It's important to check packgz first. If a stream is both
            // pack200 and gz encoded, then the Content-Encoding could
            // return ".gz", so if we check gzip first, we would end up
            // treating a pack200 file as a jar file.
            if (packgz) {
                CachedDaemonThreadPoolProvider.notePackGzDetected();
                downloadPackGzFileDirectly(response, downloadFrom, downloadTo);
            } else if (gzip) {
                downloadGZipFile(response, downloadFrom, downloadTo);
            } else {
                downloadFile(response, downloadTo, false, null);
            }
            settleSlotGood(false);
            CachedDaemonThreadPoolProvider.noteJarDownloadSucceeded();
            resource.fireDownloadEvent(); // fire DOWNLOADED
        } catch (Exception ex) {
            logDownloadFailure(downloadFrom, ex);
            settleSlotBad();
            resource.fireDownloadEvent(); // fire ERROR
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private net.sourceforge.jnlp.security.HttpResponse getDownloadConnection(URL location) throws IOException {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Accept-Encoding", getAcceptEncoding());
        net.sourceforge.jnlp.cache.download.ConnectionTiming timing = new net.sourceforge.jnlp.cache.download.ConnectionTiming();
        net.sourceforge.jnlp.security.HttpResponse response =
                net.sourceforge.jnlp.security.HttpClientProvider.getDefault().open(location, "GET", headers, timing);
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        if (slot != null) {
            long end = timing.connectEndMillis > 0 ? timing.connectEndMillis : System.currentTimeMillis();
            long start = timing.connectStartMillis > 0 ? timing.connectStartMillis : end;
            slot.onConnect(start, end, timing.reused);
        }
        return response;
    }

    /** Package-visible for Groovy probes / unit tests. */
    void settleSlotGood(boolean fromCache) {
        // Keep enqueued until the slot is absorbing. Splash wait() ticks every
        // ~150ms and calls startResource; clearing here (before integrity) let
        // a second GET start for a jar that had just finished writing.
        // ZIP structure already ran at write (download) or jarPassesIntegrity
        // (cache). JarCertVerifier is the single signature/trust pass.
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        if (slot == null) {
            File local = resource.getLocalFile();
            if (local != null && CacheUtil.isJarResourceUrl(resource.getLocation())) {
                LaunchPrep.prepare(local);
            }
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.GOOD);
            resource.clearEnqueued();
            return;
        }
        if (fromCache && !ResourceTracker.hasUsableLocalFile(resource)) {
            // ghost cache entry: don't settle GOOD — park for the one-shot retry so
            // the wait path re-enqueues and re-downloads instead of launching from a
            // phantom file. Keep enqueued so wait() cannot start a second GET.
            slot.settleUnusable(System.currentTimeMillis());
            return;
        }
        File local = resource.getLocalFile();
        if (local != null && CacheUtil.isJarResourceUrl(resource.getLocation())) {
            // Signature read + nested extract on this worker, overlapping other GETs.
            LaunchPrep.prepare(local);
        }
        slot.settleGood(System.currentTimeMillis(), fromCache);
        // Persist absorbing outcome on the Resource. wait() builds a fresh JarGroupState
        // every progress tick (DefaultDownloadIndicator updateRate=150ms) and preSettleSlots
        // only skips re-download when getTerminalState() is already GOOD. Without this,
        // cache hits loop forever: isCurrent=true → "Downloading" → new IN_FLIGHT slot.
        if (slot.state() == net.sourceforge.jnlp.cache.download.JarState.GOOD) {
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.GOOD);
            resource.clearEnqueued();
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, slot.settleStatsLine());
        }
    }

    /** Package-visible for unit tests of the settle / fail-fast paths. */
    void settleSlotBad() {
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        if (slot == null) {
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD);
            resource.clearEnqueued();
            return;
        }
        // settleUnusable → RETRY_PENDING (not absorbing) on first failure; SETTLED_BAD after retry.
        boolean terminal = slot.settleUnusable(System.currentTimeMillis())
                || slot.state() == net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD;
        if (terminal) {
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD);
            resource.clearEnqueued();
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                    "Download failed (out of retries or terminal error): " + slot.settleStatsLine());
        }
    }

    /** Force SETTLED_BAD when attempts are exhausted and the slot never absorbed. Package-visible for tests. */
    void failFastOutOfRetries() {
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        if (slot == null) {
            resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD);
            resource.clearEnqueued();
            resource.fireDownloadEvent(); // ERROR
            return;
        }
        slot.settleBadFinal(System.currentTimeMillis());
        resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD);
        resource.clearEnqueued();
        OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                "Download failed fast after retries exhausted: " + slot.settleStatsLine());
        resource.fireDownloadEvent(); // ERROR
    }

    private static boolean jarPassesIntegrity(File file) {
        try {
            CacheUtil.verifyJarIntegrity(file);
            return true;
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, e);
            return false;
        }
    }

    private static void deleteCorruptLocal(File local) {
        if (local == null || !local.isFile()) {
            return;
        }
        try {
            Files.deleteIfExists(local.toPath());
        } catch (IOException deleteEx) {
            OutputController.getLogger().log(deleteEx);
        }
    }

    private void downloadPackGzFile(net.sourceforge.jnlp.security.HttpResponse response, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo)) {
            downloadFrom = new URL(downloadFrom + ".pack.gz");
        }
        downloadFile(response, downloadFrom, true, null);

        uncompressPackGz(downloadFrom, downloadTo, resource.getDownloadVersion());
        CacheEntry entry = new CacheEntry(downloadFrom, resource.getDownloadVersion());
        storeEntryFields(entry, entry.getCacheFile().length(), response.getLastModified());
        markForDelete(downloadFrom);
    }

    private void downloadPackGzFileDirectly(net.sourceforge.jnlp.security.HttpResponse response, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo)) {
            downloadFrom = new URL(downloadFrom + ".pack.gz");
        }
        CacheEntry entry = new CacheEntry(downloadTo, resource.getDownloadVersion(), true);
        downloadFile(response, downloadFrom, true, entry);
        storeEntryFields(entry, entry.getCacheFile().length(), response.getLastModified());
    }

    private void downloadGZipFile(net.sourceforge.jnlp.security.HttpResponse response, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo))
            downloadFrom = new URL(downloadFrom + ".gz");
        downloadFile(response, downloadFrom, false, null);

        uncompressGzip(downloadFrom, downloadTo, resource.getDownloadVersion());
        CacheEntry entry = new CacheEntry(downloadTo, resource.getDownloadVersion());
        storeEntryFields(entry, entry.getCacheFile().length(), response.getLastModified());
        markForDelete(downloadFrom);
    }

    private void downloadFile(net.sourceforge.jnlp.security.HttpResponse response, URL downloadLocation, boolean packGZ, CacheEntry entry) throws IOException {
        CacheEntry downloadEntry = entry != null ? entry
                : new CacheEntry(downloadLocation, resource.getDownloadVersion());
        // Always persist to the cache entry location (usually resource.getLocation()).
        // downloadLocation may be a version-encoded / .pack.gz URL used only for HTTP.
        final URL cacheLocation = downloadEntry.getLocation();
        logResourceDebug(downloadLocation, "Downloading file: " + downloadLocation + " into: " + downloadEntry.getCacheFile().getCanonicalPath());
        File existingCached = downloadEntry.getCacheFile();
        boolean existingUsable = existingCached != null && existingCached.isFile() && existingCached.length() > 0
                && (!CacheUtil.isJarResourceUrl(cacheLocation) || CacheUtil.isValidJarFile(existingCached));
        // isCurrent alone is not enough: a stale catalog row / vanished file must not mark
        // DOWNLOADED with a null localFile (that produced Unknown Main-Class).
        if (!downloadEntry.isCurrent(response.getLastModified()) || !existingUsable) {
            boolean wrote = false;
            File writtenFile = null;
            // Pin the cache jar path for this attempt. A second getCacheFile() during
            // Pack200 admission wait can resolve a different LRU slot (issue #15).
            final File pinnedJar = downloadEntry.getCacheFile();
            try {
                writtenFile = writeDownloadStream(cacheLocation, response.getBody(), packGZ,
                        response.getContentLength(), pinnedJar);
                wrote = true;
            } catch (IOException ex) {
                if (isFavIconUrl(downloadLocation)) {
                    logMissingFavIconInfo(downloadLocation);
                    return;
                }
                String IH = "Invalid Http response";
                if (ex.getMessage() != null && ex.getMessage().equals(IH)) {
                    OutputController.getLogger().log(ex);
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, "'" + IH + "' message detected. Attempting direct socket");
                    Object[] result = UrlUtils.loadUrlWithInvalidHeaderBytes(response.getFinalUrl());
                    OutputController.getLogger().log("Header of: " + response.getFinalUrl() + " (" + downloadLocation + ")");
                    String head = (String) result[0];
                    byte[] body = (byte[]) result[1];
                    OutputController.getLogger().log(head);
                    OutputController.getLogger().log("Body is: " + body.length + " bytes long");
                    writtenFile = writeDownloadStream(cacheLocation, new ByteArrayInputStream(body), packGZ,
                            body != null ? body.length : -1L, pinnedJar);
                    wrote = true;
                } else {
                    logDownloadFailure(downloadLocation, ex);
                    int retryCount = RETRY_COUNT;
                    String retryCountString = System.getProperty("sonata.rda.retry.count");
                    if (retryCountString != null) {
                        try {
                            retryCount = Integer.parseInt(retryCountString);
                        } catch (NumberFormatException e) {
                            retryCount = RETRY_COUNT;
                        }
                    }
                    IOException lastFailure = ex;
                    for (int i = 0; i < retryCount; i++) {
                        try {
                            writtenFile = retryDownload(response, downloadLocation, downloadEntry, packGZ, i);
                            wrote = true;
                            break;
                        } catch (IOException ex2) {
                            lastFailure = ex2;
                            logDownloadFailure(downloadLocation, ex2);
                        }
                    }
                    if (!wrote) {
                        OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                                "Download of " + downloadLocation + " out of IO retries ("
                                        + retryCount + ") — failing fast");
                        throw lastFailure;
                    }
                }
            }
            // Prefer the path actually written; CacheEntry.localFile can lag a new LRU slot.
            File cached = writtenFile != null ? writtenFile : downloadEntry.getCacheFile();
            if (cached == null || !cached.isFile() || cached.length() == 0) {
                throw new IOException("Download of " + downloadLocation + " did not produce a cache file at "
                        + (cached != null ? cached.getAbsolutePath() : cacheLocation));
            }
            resource.setLocalFile(cached);
        } else {
            resource.setLocalFile(existingCached);
            resource.setTransferred(existingCached.length());
        }

        // After pack200 unpack the on-disk size differs from Content-Length; store actual size.
        File storedFile = resource.getLocalFile() != null ? resource.getLocalFile() : downloadEntry.getCacheFile();
        long storedLength = storedFile != null && storedFile.isFile()
                ? storedFile.length()
                : response.getContentLength();
        storeEntryFields(downloadEntry, storedLength, response.getLastModified());
    }

    /**
     * Write a download stream into the cache. When {@code packGZ} is true the stream is
     * pack200-gzip decoded straight into the cache jar file (no full-jar heap buffer).
     * Retries must use the same path as the first attempt.
     *
     * @return the cache file that was written
     */
    private File writeDownloadStream(URL cacheLocation, InputStream raw, boolean packGZ) throws IOException {
        return writeDownloadStream(cacheLocation, raw, packGZ, -1L, null);
    }

    private File writeDownloadStream(URL cacheLocation, InputStream raw, boolean packGZ, long contentLength)
            throws IOException {
        return writeDownloadStream(cacheLocation, raw, packGZ, contentLength, null);
    }

    private File writeDownloadStream(URL cacheLocation, InputStream raw, boolean packGZ, long contentLength,
            File pinnedJar) throws IOException {
        // Validate the exact file we wrote — a second getCacheFile() can resolve a different
        // LRU slot and falsely reject a good pack200 unpack (or leave poison on disk).
        File written = packGZ
                ? drainThenUnpackPackGz(cacheLocation, raw, pinnedJar)
                : writeDownloadToFile(cacheLocation, new BufferedInputStream(raw), pinnedJar);
        // compressionRatio metric: on-disk decompressed size vs wire bytes
        net.sourceforge.jnlp.cache.download.JarSlot slot = resource.getJarSlot();
        if (slot != null && written != null) {
            slot.onDecompressed(written.length(), packGZ);
        }
        if (CacheUtil.isJarResourceUrl(cacheLocation)) {
            try {
                String integrity = CacheUtil.verifyJarIntegrity(written);
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        "Download integrity (post-write): " + integrity);
            } catch (IOException badJar) {
                String preview = CacheUtil.previewFileHead(written, 80);
                deleteCorruptLocal(written);
                throw new IOException("Download of " + cacheLocation + " failed integrity/signature check"
                        + (preview != null && !preview.isEmpty() ? " (" + preview + ")" : "")
                        + ": " + badJar.getMessage(), badJar);
            }
        }
        return written;
    }

    /**
     * Drain the HTTP pack.gz body to disk first (records real TTFB, releases the
     * connection), then Pack200-unpack under admission using the exact wire size.
     * Waiting for a heap slot with the response unread made mean TTFB ≈ wall clock.
     * <p>
     * Sidecar files are unique per attempt so a concurrent downloader's {@code finally}
     * cannot delete the file this thread still needs while queued in
     * {@link net.sourceforge.jnlp.cache.download.PackUnpackAdmission} (GitHub #15).
     * The jar destination is the CacheEntry-pinned path when provided so
     * {@code storeEntryFields} cannot lock a dead LRU slot after a successful unpack.
     */
    private File drainThenUnpackPackGz(URL cacheLocation, InputStream packGzStream, File pinnedJar)
            throws IOException {
        File jarFile = pinnedJar != null ? pinnedJar
                : CacheUtil.getCacheFile(cacheLocation, resource.getDownloadVersion());
        File packed = newPackedSidecar(jarFile);
        try {
            File parent = packed.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create cache directory for Pack200 sidecar: " + parent);
            }
            writeCountedStreamToFile(packed, new BufferedInputStream(packGzStream));
            long wire = packed.isFile() ? packed.length() : 0L;
            long sizeHint = resource.getSize();
            long estimate = net.sourceforge.jnlp.cache.download.PackUnpackAdmission
                    .estimateReserveBytes(wire, sizeHint);
            net.sourceforge.jnlp.cache.download.PackUnpackAdmission.getInstance().runUnpack(estimate, () -> {
                ensurePackedSidecarPresent(packed);
                DownloadProgress.beginUnpack(SizeFirstDownloadQueue.resourceName(resource), wire);
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(packed.toPath())));
                     OutputStream fileOut = Files.newOutputStream(jarFile.toPath(),
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                     JarOutputStream jarOut = new JarOutputStream(new BufferedOutputStream(
                             DownloadProgress.countingOutput(fileOut)))) {
                    Pack200.newUnpacker().unpack(in, jarOut);
                } finally {
                    DownloadProgress.endUnpack(jarFile.isFile() ? jarFile.length() : 0L);
                }
            });
            return jarFile;
        } finally {
            // Only this attempt's uniquely named sidecar — never a shared fixed suffix.
            deleteCorruptLocal(packed);
        }
    }

    /**
     * Unique drain file beside {@code jarFile}. Package-visible for unit tests.
     * Shared {@code .pack.gz.download} names let one attempt's finally delete another's
     * sidecar during PackUnpackAdmission wait.
     */
    static File newPackedSidecar(File jarFile) {
        return new File(jarFile.getPath() + ".pack.gz.download." + Long.toHexString(System.nanoTime()));
    }

    /** Fail with a retryable message when the sidecar vanished during admission wait. */
    static void ensurePackedSidecarPresent(File packed) throws IOException {
        if (packed != null && packed.isFile() && packed.length() > 0L) {
            return;
        }
        throw new IOException("Pack200 sidecar missing after admission wait: "
                + (packed != null ? packed.getAbsolutePath() : "null"));
    }

    private File retryDownload(net.sourceforge.jnlp.security.HttpResponse response, URL downloadLocation, CacheEntry downloadEntry,
            boolean packGZ, int count) throws IOException {
        try {
            int retryDelay = -1;
            String retryDelayString = System.getProperty("sonata.rda.retry.delay");
            if (retryDelayString != null) {
                try {
                    retryDelay = Integer.parseInt(retryDelayString);
                } catch (NumberFormatException e) {
                    retryDelay = -1;
                }
            }
            Thread.sleep(retryDelay == -1 ? RETRY_DELAYS[Math.min(count, RETRY_DELAYS.length-1)] : retryDelay);
        } catch (InterruptedException e) {
            //ignore
        }
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Redownloading file: " + downloadLocation + " into: " + downloadEntry.getCacheFile().getCanonicalPath());
        net.sourceforge.jnlp.security.HttpResponse retried = getDownloadConnection(response.getFinalUrl());
        // Must write to the cache entry location and unpack pack200 the same as the first attempt.
        // Writing downloadLocation (version-encoded / .pack.gz URL) left ghost cache slots and
        // raw gzip bytes under names like sonata-dao__V....jar while JarCertVerifier opened the
        // missing unversioned sonata-dao.jar (Windows production launch failure).
        try {
            return writeDownloadStream(downloadEntry.getLocation(), retried.getBody(), packGZ,
                    retried.getContentLength(), downloadEntry.getCacheFile());
        } finally {
            retried.close();
        }
    }

    private void storeEntryFields(CacheEntry entry, long contentLength, long lastModified) {
        entry.lock();
        try {
            entry.setRemoteContentLength(contentLength);
            entry.setLastModified(lastModified);
            entry.store();
        } finally {
            entry.unlock();
        }
    }

    private void markForDelete(URL location) {
        CacheEntry entry = new CacheEntry(location,
                                          resource.getDownloadVersion());
        entry.lock();
        try {
            entry.markForDelete();
            entry.store();
        } finally {
            entry.unlock();
        }
    }

    /**
     * Prefer already-counted wire bytes, else HTTP Content-Length, else declared resource size.
     * Package-visible for unit tests.
     */
    static long packWireHintBytes(long slotTransferred, long contentLength, long resourceSize) {
        if (slotTransferred > 0L) {
            return slotTransferred;
        }
        if (contentLength > 0L) {
            return contentLength;
        }
        if (resourceSize > 0L) {
            return resourceSize;
        }
        return 0L;
    }

    private File writeDownloadToFile(URL downloadLocation, InputStream in) throws IOException {
        return writeDownloadToFile(downloadLocation, in, null);
    }

    private File writeDownloadToFile(URL downloadLocation, InputStream in, File pinnedJar) throws IOException {
        File localFile = pinnedJar != null ? pinnedJar
                : CacheUtil.getCacheFile(downloadLocation, resource.getDownloadVersion());
        File parent = localFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create cache directory: " + parent);
        }
        // Raw JAR/GET only. Pack200 sidecars use writeCountedStreamToFile with
        // expected=-1: resource.size is the unpacked jar, not the .pack.gz wire size.
        writeCountedStreamToFile(localFile, in, resource, resource.getJarSlot(), resource.getSize());
        return localFile;
    }

    /** Copy HTTP bytes to {@code dest} immediately; first/last-byte clocks follow the wire, not unpack. */
    private void writeCountedStreamToFile(File dest, InputStream in) throws IOException {
        writeCountedStreamToFile(dest, in, resource, resource.getJarSlot(), -1L);
    }

    static void writeCountedStreamToFile(File dest, InputStream in, Resource resource,
            net.sourceforge.jnlp.cache.download.JarSlot slot) throws IOException {
        writeCountedStreamToFile(dest, in, resource, slot, -1L);
    }

    static void writeCountedStreamToFile(File dest, InputStream in, Resource resource,
            net.sourceforge.jnlp.cache.download.JarSlot slot, long expected) throws IOException {
        byte buf[] = new byte[COPY_BUFFER_SIZE_64KB];
        int rlen;
        long written = 0L;
        DownloadProgress.noteWireStart();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            while (-1 != (rlen = in.read(buf))) {
                written += rlen;
                if (expected > 0 && written > expected) {
                    throw new IOException("Download of " + dest
                            + " exceeded expected length: got " + written + " of " + expected);
                }
                if (resource != null) {
                    resource.incrementTransferred(rlen);
                }
                if (slot != null) {
                    long now = System.currentTimeMillis();
                    slot.onFirstByte(now);
                    slot.addTransferred(rlen);
                }
                if (DownloadProgress.isActive()) {
                    DownloadProgress.addBytes(rlen);
                }
                out.write(buf, 0, rlen);
            }
            if (slot != null) {
                slot.onLastByte(System.currentTimeMillis());
            }
            in.close();
        } finally {
            DownloadProgress.noteWireEnd();
        }
        if (expected > 0 && written != expected) {
            throw new IOException("Download of " + dest
                    + " was truncated: got " + written + " of " + expected + " bytes");
        }
    }

    private void uncompressGzip(URL compressedLocation, URL uncompressedLocation, Version version) throws IOException {
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Extracting gzip: " + compressedLocation + " to " + uncompressedLocation);
        byte buf[] = new byte[1024];
        int rlen;

        try (GZIPInputStream gzInputStream = new GZIPInputStream(new FileInputStream(CacheUtil
                .getCacheFile(compressedLocation, version)))) {
            InputStream inputStream = new BufferedInputStream(gzInputStream);

            BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(CacheUtil
                    .getCacheFile(uncompressedLocation, version)));

            while (-1 != (rlen = inputStream.read(buf))) {
                outputStream.write(buf, 0, rlen);
            }

            outputStream.close();
            inputStream.close();
        }
    }

    private void uncompressPackGz(URL compressedLocation, URL uncompressedLocation, Version version) throws IOException {
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Extracting packgz: " + compressedLocation + " to " + uncompressedLocation);

        File packed = CacheUtil.getCacheFile(compressedLocation, version);
        File unpacked = CacheUtil.getCacheFile(uncompressedLocation, version);
        long estimate = net.sourceforge.jnlp.cache.download.PackUnpackAdmission
                .estimateReserveBytes(packed.isFile() ? packed.length() : 0L, 0L);
        String unpackName = uncompressedLocation != null && uncompressedLocation.getPath() != null
                ? new File(uncompressedLocation.getPath()).getName() : "pack.gz";
        long wire = packed.isFile() ? packed.length() : 0L;
        net.sourceforge.jnlp.cache.download.PackUnpackAdmission.getInstance().runUnpack(estimate, () -> {
            DownloadProgress.beginUnpack(unpackName, wire);
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(packed.toPath())));
                 OutputStream fileOut = Files.newOutputStream(unpacked.toPath(),
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 JarOutputStream jarOut = new JarOutputStream(new BufferedOutputStream(
                         DownloadProgress.countingOutput(fileOut)))) {
                Pack200.newUnpacker().unpack(in, jarOut);
            } finally {
                DownloadProgress.endUnpack(unpacked.isFile() ? unpacked.length() : 0L);
            }
        });
    }

    /**
     * Complex wrapper around url request Contains return code (default is
     * HTTP_OK), length and last modified
     *
     * The storing of redirect target is quite obvious The storing length and
     * last modified may be not, but appearently
     * (http://icedtea.classpath.org/bugzilla/show_bug.cgi?id=2591) the url
     * conenction is not always chaced as expected, and so another request may
     * be sent when length and lastmodified are checked
     *
     */
    static class UrlRequestResult {

        //http response code
        int result = HttpURLConnection.HTTP_OK;
        URL URL;

        Long lastModified;
        Long length;

        public UrlRequestResult() {
        }

        public UrlRequestResult(URL URL) {
            this.URL = URL;
        }

        URL getURL() {
            return URL;
        }

        /**
         * @return whether the result code is redirect one. Rigth now 301-303
         * and 307-308
         */
        public boolean shouldRedirect() {
            return (result == 301
                    || result == 302
                    || result == 303/*?*/
                    || result == 307
                    || result == 308);
        }

        /**
         * @return whether the return code is OK one - anything except <200,300)
         */
        public boolean isInvalid() {
            return (result < 200 || result >= 300);
        }

        @Override
        public String toString() {
            return ""
                    + "url: " + (URL == null ? "null" : URL.toExternalForm()) + "; "
                    + "result:" + result + "; "
                    + "lastModified: " + (lastModified == null ? "null" : lastModified.toString()) + "; "
                    + "length: " + (length == null ? "null" : length.toString()) + "; ";
        }
    }

    private static class RedirectionException extends RuntimeException {

        public RedirectionException(String string) {
            super(string);
        }

        public RedirectionException(Throwable cause) {
            super(cause);
        }

    }

}
