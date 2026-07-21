package net.sourceforge.jnlp.cache;

import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTED;
import static net.sourceforge.jnlp.cache.Resource.Status.CONNECTING;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADED;
import static net.sourceforge.jnlp.cache.Resource.Status.DOWNLOADING;
import static net.sourceforge.jnlp.cache.Resource.Status.ERROR;
import static net.sourceforge.jnlp.cache.Resource.Status.PRECONNECT;
import static net.sourceforge.jnlp.cache.Resource.Status.PREDOWNLOAD;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;

import io.pack200.Pack200;
import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.OptionsDefinitions;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.security.ConnectionFactory;
import net.sourceforge.jnlp.security.SecurityDialogs;
import net.sourceforge.jnlp.security.dialogs.InetSecurity511Panel;
import net.sourceforge.jnlp.util.HttpUtils;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.XDesktopEntry;
import net.sourceforge.jnlp.util.logging.OutputController;

public class ResourceDownloader implements Runnable {

    private static final long[] RETRY_DELAYS = {2000L, 3000L, 5000L, 8000L};
    private static final int RETRY_COUNT = 5;
    private static final Set<String> LOGGED_MISSING_FAVICONS = new HashSet<>();
    private final Resource resource;
    private final Object lock;

    public ResourceDownloader(Resource resource, Object lock) {
        this.resource = resource;
        this.lock = lock;
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

    static void logMissingFavIconInfo(URL url) {
        String key = url != null ? url.toExternalForm() : "<unknown>";
        synchronized (LOGGED_MISSING_FAVICONS) {
            if (!LOGGED_MISSING_FAVICONS.add(key)) {
                return;
            }
        }
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "INFO: This application does not have a favourite icon yet"
                + (url != null ? " (" + url + ")" : "")
                + ". Add favicon.ico to the JNLP codebase root"
                + " (for example sample-apps/public/favicon.ico on the Angular dev server).");
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
            OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, message);
        }
    }

    private static void logResourceDebug(URL context, Throwable ex) {
        if (isFavIconUrl(context)) {
            logFavIconTrace(ex);
        } else {
            OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, ex);
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
        URLConnection connection = ConnectionFactory.getConnectionFactory().openConnection(url);

        for (Map.Entry<String, String> property : requestProperties.entrySet()) {
            connection.addRequestProperty(property.getKey(), property.getValue());
        }

        if (connection instanceof HttpURLConnection) {
            HttpURLConnection httpConnection = (HttpURLConnection) connection;
            httpConnection.setRequestMethod(requestMethod.toString());

            int responseCode = httpConnection.getResponseCode();

            /* Fully consuming current request helps with connection re-use
             * See http://docs.oracle.com/javase/1.5.0/docs/guide/net/http-keepalive.html */
            HttpUtils.consumeAndCloseConnectionSilently(httpConnection, url);

            result.result = responseCode;
        }

        if (!isFavIconUrl(url)) {
            Map<String, List<String>> header = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : header.entrySet()) {
                OutputController.getLogger().log("Key : " + entry.getKey() + " ,Value : " + entry.getValue());
            }
        }
        /*
         * Do this only on 301,302,303(?)307,308>
         * Now setting value for all, and lets upper stack to handle it
         */
        String possibleRedirect = connection.getHeaderField("Location");
        if (possibleRedirect != null && possibleRedirect.trim().length() > 0) {
            result.URL = new URL(possibleRedirect);
        }
        ConnectionFactory.getConnectionFactory().disconnect(connection);

        result.lastModified = connection.getLastModified();
        result.length = connection.getContentLengthLong();

        return result;

    }

    @Override
    public void run() {
        if (resource.isSet(PRECONNECT) && !resource.hasFlags(EnumSet.of(ERROR, CONNECTING, CONNECTED))) {
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(CONNECTING));
            resource.fireDownloadEvent(); // fire CONNECTING
            initializeResource();
        }
        if (resource.isSet(PREDOWNLOAD) && !resource.hasFlags(EnumSet.of(ERROR, DOWNLOADING, DOWNLOADED))) {
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(DOWNLOADING));
            resource.fireDownloadEvent(); // fire CONNECTING
            downloadResource();
        }
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
            UrlRequestResult finalLocation = findBestUrl(resource);
            if (finalLocation != null) {
                initializeFromURL(finalLocation);
            } else {
                initializeOfflineResource();
            }
        } catch (Exception e) {
            OutputController.getLogger().log(e);
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
            synchronized (lock) {
                lock.notifyAll(); // wake up wait's to check for completion
            }
            resource.fireDownloadEvent(); // fire ERROR
        }
    }

    private void initializeFromURL(UrlRequestResult location) throws IOException {
        CacheEntry entry = new CacheEntry(resource.getLocation(), resource.getRequestVersion());
        entry.lock();
        try {
            resource.setDownloadLocation(location.URL);
            URLConnection connection = ConnectionFactory.getConnectionFactory().openConnection(location.URL); // this won't change so should be okay not-synchronized
            connection.addRequestProperty("Accept-Encoding", "pack200-gzip, gzip");

            File localFile = null;
            if (resource.getRequestVersion() == resource.getDownloadVersion()) {
                localFile = entry.getLocalFile();
            } else {
                localFile = CacheUtil.getCacheFile(resource.getLocation(), resource.getDownloadVersion());
            }
            Long size = location.length;
            if (size == null) {
                size = connection.getContentLengthLong();
            }
            Long lm = location.lastModified;
            if (lm == null) {
                lm = connection.getLastModified();
            }
            boolean current = CacheUtil.isCurrent(resource.getLocation(), resource.getRequestVersion(), lm, entry, localFile) && resource.getUpdatePolicy() != UpdatePolicy.FORCE;
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

            synchronized (resource) {
                resource.setLocalFile(localFile);
                // resource.connection = connection;
                resource.setSize(size);
                resource.changeStatus(EnumSet.of(PRECONNECT, CONNECTING), EnumSet.of(CONNECTED, PREDOWNLOAD));

                // check if up-to-date; if so set as downloaded
                if (current) {
                    resource.changeStatus(EnumSet.of(PREDOWNLOAD, DOWNLOADING), EnumSet.of(DOWNLOADED));
                }
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
            } catch (Exception ex){
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, ex);
            }
            entry.store();

            synchronized (lock) {
                lock.notifyAll(); // wake up wait's to check for completion
            }
            resource.fireDownloadEvent(); // fire CONNECTED

            // explicitly close the URLConnection.
            ConnectionFactory.getConnectionFactory().disconnect(connection);
        } finally {
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

                synchronized (resource) {
                    resource.setLocalFile(localFile);
                    resource.setSize(size);
                    resource.changeStatus(EnumSet.of(PREDOWNLOAD, DOWNLOADING), EnumSet.of(DOWNLOADED));
                }
            } else {
                if (isFavIconUrl(resource.getLocation())) {
                    logMissingFavIconInfo(resource.getLocation());
                } else {
                    OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "You are trying to get resource " + resource.getLocation().toExternalForm() + " but it is not in cache and could not be downloaded. Attempting to continue, but you may expect failure");
                }
                resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
            }

            synchronized (lock) {
                lock.notifyAll(); // wake up wait's to check for completion
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
                    requestProperties.put("Accept-Encoding", "pack200-gzip, gzip");

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
                    // continue to next candidate
                    logResourceDebug(resourceLocation, "While processing " + url.toString() + " by " + requestMethod + " for resource " + resource.toString() + " got " + e + ": ");
                    logResourceDebug(resourceLocation, e);
                }
            }
        }

        /* No valid URL, return null */
        return null;
    }

    private void downloadResource() {
        URLConnection connection = null;
        URL downloadFrom = resource.getDownloadLocation(); //Where to download from
        URL downloadTo = resource.getLocation(); //Where to download to

        try {
            connection = getDownloadConnection(downloadFrom);

            String contentEncoding = connection.getContentEncoding();

            logResourceDebug(downloadTo, "Downloading " + downloadTo + " using "
                    + downloadFrom + " (encoding : " + contentEncoding + ") ");

            boolean packgz = "pack200-gzip".equals(contentEncoding)
                    || downloadFrom.getPath().endsWith(".pack.gz");
            boolean gzip = "gzip".equals(contentEncoding);

            // It's important to check packgz first. If a stream is both
            // pack200 and gz encoded, then con.getContentEncoding() could
            // return ".gz", so if we check gzip first, we would end up
            // treating a pack200 file as a jar file.
            if (packgz) {
                downloadPackGzFileDirectly(connection, downloadFrom, downloadTo);
            } else if (gzip) {
                downloadGZipFile(connection, downloadFrom, downloadTo);
            } else {
                downloadFile(connection, downloadTo, false, null);
            }

            resource.changeStatus(EnumSet.of(DOWNLOADING), EnumSet.of(DOWNLOADED));
            synchronized (lock) {
                lock.notifyAll(); // wake up wait's to check for completion
            }
            resource.fireDownloadEvent(); // fire DOWNLOADED
        } catch (Exception ex) {
            logDownloadFailure(downloadFrom, ex);
            resource.changeStatus(EnumSet.noneOf(Resource.Status.class), EnumSet.of(ERROR));
            synchronized (lock) {
                lock.notifyAll();
            }
            resource.fireDownloadEvent(); // fire ERROR
        } finally {
            if (connection != null) {
                ConnectionFactory.getConnectionFactory().disconnect(connection);
            }
        }
    }

    private URLConnection getDownloadConnection(URL location) throws IOException {
        URLConnection con = ConnectionFactory.getConnectionFactory().openConnection(location);
        con.addRequestProperty("Accept-Encoding", "pack200-gzip, gzip");
        con.connect();
        return con;
    }

    public InputStream unpack(InputStream input) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (final JarOutputStream outputStream = new JarOutputStream(buffer)) {
            Pack200.newUnpacker().unpack(new GZIPInputStream(input), outputStream);
        }
        return new ByteArrayInputStream(buffer.toByteArray());
    }

    private void downloadPackGzFile(URLConnection connection, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo)) {
            downloadFrom = new URL(downloadFrom + ".pack.gz");
        }
        downloadFile(connection, downloadFrom, true, null);

        uncompressPackGz(downloadFrom, downloadTo, resource.getDownloadVersion());
        CacheEntry entry = new CacheEntry(downloadFrom, resource.getDownloadVersion());
        storeEntryFields(entry, entry.getCacheFile().length(), connection.getLastModified());
        markForDelete(downloadFrom);
    }

    private void downloadPackGzFileDirectly(URLConnection connection, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo)) {
            downloadFrom = new URL(downloadFrom + ".pack.gz");
        }
        CacheEntry entry = new CacheEntry(downloadTo, resource.getDownloadVersion(), true);
        downloadFile(connection, downloadFrom, true, entry);
        storeEntryFields(entry, entry.getCacheFile().length(), connection.getLastModified());
    }

    private void downloadGZipFile(URLConnection connection, URL downloadFrom, URL downloadTo) throws IOException {
        if (downloadFrom.equals(downloadTo))
            downloadFrom = new URL(downloadFrom + ".gz");
        downloadFile(connection, downloadFrom, false, null);

        uncompressGzip(downloadFrom, downloadTo, resource.getDownloadVersion());
        CacheEntry entry = new CacheEntry(downloadTo, resource.getDownloadVersion());
        storeEntryFields(entry, entry.getCacheFile().length(), connection.getLastModified());
        markForDelete(downloadFrom);
    }

    private void downloadFile(URLConnection connection, URL downloadLocation, boolean packGZ, CacheEntry entry) throws IOException {
        CacheEntry downloadEntry = entry != null ? entry
                : new CacheEntry(downloadLocation, resource.getDownloadVersion());
        // Always persist to the cache entry location (usually resource.getLocation()).
        // downloadLocation may be a version-encoded / .pack.gz URL used only for HTTP.
        final URL cacheLocation = downloadEntry.getLocation();
        logResourceDebug(downloadLocation, "Downloading file: " + downloadLocation + " into: " + downloadEntry.getCacheFile().getCanonicalPath());
        if (!downloadEntry.isCurrent(connection.getLastModified())) {
            boolean wrote = false;
            try {
                writeDownloadStream(cacheLocation, connection.getInputStream(), packGZ);
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
                    Object[] result = UrlUtils.loadUrlWithInvalidHeaderBytes(connection.getURL());
                    OutputController.getLogger().log("Header of: " + connection.getURL() + " (" + downloadLocation + ")");
                    String head = (String) result[0];
                    byte[] body = (byte[]) result[1];
                    OutputController.getLogger().log(head);
                    OutputController.getLogger().log("Body is: " + body.length + " bytes long");
                    writeDownloadStream(cacheLocation, new ByteArrayInputStream(body), packGZ);
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
                            retryDownload(connection, downloadLocation, downloadEntry, packGZ, i);
                            wrote = true;
                            break;
                        } catch (IOException ex2) {
                            lastFailure = ex2;
                            logDownloadFailure(downloadLocation, ex2);
                        }
                    }
                    if (!wrote) {
                        throw lastFailure;
                    }
                }
            }
            File cached = downloadEntry.getCacheFile();
            if (cached == null || !cached.isFile() || cached.length() == 0) {
                throw new IOException("Download of " + downloadLocation + " did not produce a cache file at "
                        + (cached != null ? cached.getAbsolutePath() : cacheLocation));
            }
        } else {
            resource.setTransferred(downloadEntry.getCacheFile().length());
        }

        // After pack200 unpack the on-disk size differs from Content-Length; store actual size.
        long storedLength = downloadEntry.getCacheFile().isFile()
                ? downloadEntry.getCacheFile().length()
                : connection.getContentLengthLong();
        storeEntryFields(downloadEntry, storedLength, connection.getLastModified());
    }

    /**
     * Write a download stream into the cache. When {@code packGZ} is true the stream is
     * pack200-gzip decoded first — retries must use the same path as the first attempt.
     */
    private void writeDownloadStream(URL cacheLocation, InputStream raw, boolean packGZ) throws IOException {
        InputStream in = packGZ ? unpack(raw) : new BufferedInputStream(raw);
        writeDownloadToFile(cacheLocation, in);
    }

    private void retryDownload(URLConnection connection, URL downloadLocation, CacheEntry downloadEntry,
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
        OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, "Redownloading file: " + downloadLocation + " into: " + downloadEntry.getCacheFile().getCanonicalPath());
        connection = getDownloadConnection(connection.getURL());
        // Must write to the cache entry location and unpack pack200 the same as the first attempt.
        // Writing downloadLocation (version-encoded / .pack.gz URL) left ghost cache slots and
        // raw gzip bytes under names like sonata-dao__V....jar while JarCertVerifier opened the
        // missing unversioned sonata-dao.jar (Windows production launch failure).
        writeDownloadStream(downloadEntry.getLocation(), connection.getInputStream(), packGZ);
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

    private void writeDownloadToFile(URL downloadLocation, InputStream in) throws IOException {
        byte buf[] = new byte[1024];
        int rlen;
        try (OutputStream out = CacheUtil.getOutputStream(downloadLocation, resource.getDownloadVersion())) {
            while (-1 != (rlen = in.read(buf))) {
                resource.incrementTransferred(rlen);
                out.write(buf, 0, rlen);
            }

            in.close();
        }
    }

    private void uncompressGzip(URL compressedLocation, URL uncompressedLocation, Version version) throws IOException {
        OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, "Extracting gzip: " + compressedLocation + " to " + uncompressedLocation);
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
        OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, "Extracting packgz: " + compressedLocation + " to " + uncompressedLocation);

        try (GZIPInputStream gzInputStream = new GZIPInputStream(new FileInputStream(CacheUtil
                .getCacheFile(compressedLocation, version)))) {
            InputStream inputStream = new BufferedInputStream(gzInputStream);
            JarOutputStream outputStream = new JarOutputStream(new FileOutputStream(CacheUtil
                    .getCacheFile(uncompressedLocation, version)));
            Pack200.Unpacker unpacker = Pack200.newUnpacker();
            unpacker.unpack(inputStream, outputStream);
            outputStream.close();
            inputStream.close();
        }
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
                    + "length: " + length == null ? "null" : length.toString() + "; ";
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
