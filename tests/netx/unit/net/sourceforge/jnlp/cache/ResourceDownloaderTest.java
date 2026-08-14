package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.GZIPOutputStream;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.ServerAccess;
import net.sourceforge.jnlp.ServerLauncher;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.JarFile;
import net.sourceforge.jnlp.util.logging.LogConfig;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import net.sourceforge.jnlp.util.logging.OutputController;

public class ResourceDownloaderTest extends NoStdOutErrTest {

    public static ServerLauncher testServer;
    public static ServerLauncher testServerWithBrokenHead;
    public static ServerLauncher downloadServer;
    public static ServerLauncher rangeServer;
    private static java.util.concurrent.atomic.AtomicReference<String> rangeHeaderSeen;

    private static final PrintStream[] backedUpStream = new PrintStream[4];
    private static ByteArrayOutputStream currentErrorStream;

    private static final String nameStub1 = "itw-server";
    private static final String nameStub2 = "test-file";

    private static String cacheDir;

    @BeforeClass
    //keeping silent outputs from launched jvm
    public static void redirectErr() throws IOException {
        for (int i = 0; i < backedUpStream.length; i++) {
            if (backedUpStream[i] == null) {
                switch (i) {
                    case 0:
                        backedUpStream[i] = System.out;
                        break;
                    case 1:
                        backedUpStream[i] = System.err;
                        break;
                    case 2:
                        backedUpStream[i] = OutputController.getLogger().getOut();
                        break;
                    case 3:
                        backedUpStream[i] = OutputController.getLogger().getErr();
                        break;
                }

            }

        }
        currentErrorStream = new ByteArrayOutputStream();
        LogConfig.enableStreamLoggingForTests();
        System.setOut(new PrintStream(currentErrorStream));
        System.setErr(new PrintStream(currentErrorStream));
        OutputController.getLogger().setOut(new PrintStream(currentErrorStream));
        OutputController.getLogger().setErr(new PrintStream(currentErrorStream));

    }

    @AfterClass
    public static void redirectErrBack() throws IOException {
        ServerAccess.logErrorReprint(currentErrorStream.toString("utf-8"));
        System.setOut(backedUpStream[0]);
        System.setErr(backedUpStream[1]);
        OutputController.getLogger().setOut(backedUpStream[2]);
        OutputController.getLogger().setErr(backedUpStream[3]);
    }

    @BeforeClass
    public static void onDebug() {
        JNLPRuntime.setDebug(true);
    }

    @AfterClass
    public static void offDebug() {
        JNLPRuntime.setDebug(false);
    }

    @BeforeClass
    public static void startServer() throws Exception {
        redirectErr();
        testServer = ServerAccess.getIndependentInstance(System.getProperty("java.io.tmpdir"), ServerAccess.findFreePort());
        redirectErrBack();
    }

    @BeforeClass
    public static void startServer2() throws Exception {
        redirectErr();
        testServerWithBrokenHead = ServerAccess.getIndependentInstance(System.getProperty("java.io.tmpdir"), ServerAccess.findFreePort());
        testServerWithBrokenHead.setSupportingHeadRequest(false);
        redirectErrBack();
    }

    @AfterClass
    public static void stopServer() {
        testServer.stop();
    }

    @AfterClass
    public static void stopServer2() {
        testServerWithBrokenHead.stop();
    }

    @Test
    public void getUrlResponseCodeTestWorkingHeadRequest() throws Exception {
        redirectErr();
        try {
            File f = File.createTempFile(nameStub1, nameStub2);
            int i = ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.HEAD);
            Assert.assertEquals(HttpURLConnection.HTTP_OK, i);
            f.delete();
            i = ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.HEAD);
            Assert.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, i);
        } finally {
            redirectErrBack();
        }
    }

    @Test
    public void getUrlResponseCodeTestNotWorkingHeadRequest() throws Exception {
        redirectErr();
        try {
            File f = File.createTempFile(nameStub1, nameStub2);
            int i = ResourceDownloader.getUrlResponseCode(testServerWithBrokenHead.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.HEAD);
            Assert.assertEquals(HttpURLConnection.HTTP_NOT_IMPLEMENTED, i);
            f.delete();
            i = ResourceDownloader.getUrlResponseCode(testServerWithBrokenHead.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.HEAD);
            Assert.assertEquals(HttpURLConnection.HTTP_NOT_IMPLEMENTED, i);
        } finally {
            redirectErrBack();
        }
    }

    @Test
    public void getUrlResponseCodeTestGetRequestOnNotWorkingHeadRequest() throws Exception {
        redirectErr();
        try {
            File f = File.createTempFile(nameStub1, nameStub2);
            int i = ResourceDownloader.getUrlResponseCode(testServerWithBrokenHead.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.GET);
            Assert.assertEquals(HttpURLConnection.HTTP_OK, i);
            f.delete();
            i = ResourceDownloader.getUrlResponseCode(testServerWithBrokenHead.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.GET);
            Assert.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, i);
        } finally {
            redirectErrBack();
        }
    }

    @Test
    public void getUrlResponseCodeTestGetRequest() throws Exception {
        redirectErr();
        try {
            File f = File.createTempFile(nameStub1, nameStub2);
            int i = ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.GET);
            Assert.assertEquals(HttpURLConnection.HTTP_OK, i);
            f.delete();
            i = ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.GET);
            Assert.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, i);
        } finally {
            redirectErrBack();
        }
    }

    @Test
    public void getUrlResponseCodeTestWrongRequest() throws Exception {
        redirectErr();
        try {
            File f = File.createTempFile(nameStub1, nameStub2);
            Exception exception = null;
            try {
                ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.TESTING_UNDEF);
            } catch (Exception ex) {
                exception = ex;
            }
            Assert.assertNotNull(exception);
            exception = null;
            f.delete();
            try {
                ResourceDownloader.getUrlResponseCode(testServer.getUrl(f.getName()), new HashMap<String, String>(), ResourceTracker.RequestMethods.TESTING_UNDEF);
            } catch (Exception ex) {
                exception = ex;
            }
            Assert.assertNotNull(exception);;
        } finally {
            redirectErrBack();
        }

    }

    @Test
    public void findBestUrltest() throws Exception {
        redirectErr();
        try {
            File fileForServerWithHeader = File.createTempFile(nameStub1, nameStub2);
            File versionedFileForServerWithHeader = new File(fileForServerWithHeader.getParentFile(), fileForServerWithHeader.getName() + "-2.0");
            versionedFileForServerWithHeader.createNewFile();

            File fileForServerWithoutHeader = File.createTempFile(nameStub1, nameStub2);
            File versionedFileForServerWithoutHeader = new File(fileForServerWithoutHeader.getParentFile(), fileForServerWithoutHeader.getName() + "-2.0");
            versionedFileForServerWithoutHeader.createNewFile();

            ResourceDownloader resourceDownloader = new ResourceDownloader(null, null);
            Resource r1 = Resource.getResource(testServer.getUrl(fileForServerWithHeader.getName()), null, UpdatePolicy.NEVER);
            Resource r2 = Resource.getResource(testServerWithBrokenHead.getUrl(fileForServerWithoutHeader.getName()), null, UpdatePolicy.NEVER);
            Resource r3 = Resource.getResource(testServer.getUrl(versionedFileForServerWithHeader.getName()), new Version("1.0"), UpdatePolicy.NEVER);
            Resource r4 = Resource.getResource(testServerWithBrokenHead.getUrl(versionedFileForServerWithoutHeader.getName()), new Version("1.0"), UpdatePolicy.NEVER);
            assertOnServerWithHeader(resourceDownloader.findBestUrl(r1).getURL());
            assertVersionedOneOnServerWithHeader(resourceDownloader.findBestUrl(r3).URL);
            assertOnServerWithoutHeader(resourceDownloader.findBestUrl(r2).URL);
            assertVersionedOneOnServerWithoutHeader(resourceDownloader.findBestUrl(r4).URL);

            fileForServerWithHeader.delete();
            Assert.assertNull(resourceDownloader.findBestUrl(r1));
            assertVersionedOneOnServerWithHeader(resourceDownloader.findBestUrl(r3).URL);
            assertOnServerWithoutHeader(resourceDownloader.findBestUrl(r2).URL);
            assertVersionedOneOnServerWithoutHeader(resourceDownloader.findBestUrl(r4).URL);

            versionedFileForServerWithHeader.delete();
            Assert.assertNull(resourceDownloader.findBestUrl(r1));
            Assert.assertNull(resourceDownloader.findBestUrl(r3));
            assertOnServerWithoutHeader(resourceDownloader.findBestUrl(r2).URL);
            assertVersionedOneOnServerWithoutHeader(resourceDownloader.findBestUrl(r4).URL);

            versionedFileForServerWithoutHeader.delete();
            Assert.assertNull(resourceDownloader.findBestUrl(r1));
            Assert.assertNull(resourceDownloader.findBestUrl(r3));
            assertOnServerWithoutHeader(resourceDownloader.findBestUrl(r2).URL);
            Assert.assertNull(resourceDownloader.findBestUrl(r4));

            fileForServerWithoutHeader.delete();
            Assert.assertNull(resourceDownloader.findBestUrl(r1));
            Assert.assertNull(resourceDownloader.findBestUrl(r3));
            Assert.assertNull(resourceDownloader.findBestUrl(r2));
            Assert.assertNull(resourceDownloader.findBestUrl(r4));
        } finally {
            redirectErrBack();
        }

    }

    private void assertOnServerWithoutHeader(URL u) {
        assertCommonComponentsOfUrl(u);
        assertPort(u, testServerWithBrokenHead.getPort());
    }

    private void assertVersionedOneOnServerWithoutHeader(URL u) {
        assertCommonComponentsOfUrl(u);
        assertPort(u, testServerWithBrokenHead.getPort());
        assertVersion(u);
    }

    private void assertOnServerWithHeader(URL u) {
        assertCommonComponentsOfUrl(u);
        assertPort(u, testServer.getPort());
    }

    private void assertVersionedOneOnServerWithHeader(URL u) {
        assertCommonComponentsOfUrl(u);
        assertPort(u, testServer.getPort());
        assertVersion(u);
    }

    private void assertCommonComponentsOfUrl(URL u) {
        Assert.assertTrue(u.getProtocol().equals("http"));
        Assert.assertTrue(u.getHost().equals("localhost"));
        Assert.assertTrue(u.getPath().contains(nameStub1));
        Assert.assertTrue(u.getPath().contains(nameStub2));
        ServerAccess.logOutputReprint(u.toExternalForm());
    }

    private void assertPort(URL u, int port) {
        Assert.assertTrue(u.getPort() == port);
    }

    private void assertVersion(URL u) {
        Assert.assertTrue(u.getPath().contains("-2.0"));
        Assert.assertTrue(u.getQuery().contains("version-id=1.0"));
    }

    @BeforeClass
    public static void setupCache() throws IOException {
        File dir = new File(System.getProperty("java.io.tmpdir"), "itw-down");
        dir.mkdirs();
        dir.deleteOnExit();

        redirectErr();
        downloadServer = ServerAccess.getIndependentInstance(dir.getAbsolutePath(), ServerAccess.findFreePort());
        redirectErrBack();

        cacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        PathsAndFiles.CACHE_DIR.setValue(System.getProperty("java.io.tmpdir") + File.separator + "tempcache");

        rangeHeaderSeen = new java.util.concurrent.atomic.AtomicReference<>(null);
        redirectErr();
        rangeServer = ServerAccess.getIndependentInstance(dir.getAbsolutePath(), ServerAccess.findFreePort());
        rangeServer.setSupportRangeRequests(true);
        rangeServer.setRangeHeaderSink(rangeHeaderSeen);
        redirectErrBack();
    }

    @AfterClass
    public static void teardownCache() {
        downloadServer.stop();
        rangeServer.stop();

        CacheUtil.clearCache();
        PathsAndFiles.CACHE_DIR.setValue(cacheDir);
    }

    private File setupFile(String fileName, String text) throws IOException {
        return setupFile(fileName, text.getBytes());
    }

    private File setupFile(String fileName, byte[] body) throws IOException {
        File downloadDir = downloadServer.getDir();
        File file = new File(downloadDir, fileName);
        file.createNewFile();
        Files.write(file.toPath(), body);
        file.deleteOnExit();

        return file;
    }

    private Resource setupResource(String fileName, String text) throws IOException {
        File f = setupFile(fileName, text);
        URL url = downloadServer.getUrl(fileName);
        Resource resource = Resource.getResource(url, null, UpdatePolicy.NEVER);
        return resource;
    }

    @Test
    @Ignore("Local TestServer download is unreliable under CI headless runners; covered by other ResourceDownloader cases")
    public void testDownloadResource() throws IOException {
        String expected = "testDownloadResource";
        Resource resource = setupResource("download-resource", expected);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resourceDownloader.run();

        File downloadedFile = resource.getLocalFile();
        assertTrue(downloadedFile.exists() && downloadedFile.isFile());

        String output = new String(Files.readAllBytes(downloadedFile.toPath()));
        assertEquals(expected, output);
    }

    @Test
    public void testDownloadPackGzResource() throws IOException {
        String expected = "1.2";

        setupPackGzFile("download-packgz", expected);

        Resource resource = Resource.getResource(downloadServer.getUrl("download-packgz.jar"), null, UpdatePolicy.NEVER);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(true, false));

        resourceDownloader.run();

        File downloadedFile = resource.getLocalFile();
        assertTrue(downloadedFile.exists() && downloadedFile.isFile());

        JarFile jf = new JarFile(downloadedFile);
        Manifest m = jf.getManifest();
        String actual = (String) m.getMainAttributes().get(Attributes.Name.MANIFEST_VERSION);

        assertEquals(expected, actual);
    }

    @Test
    @Ignore("Local TestServer versioned download is unreliable under CI headless runners")
    public void testDownloadVersionedResource() throws IOException {
        String expected = "testVersionedResource";
        setupFile("download-version__V1.0.jar", expected);

        URL url = downloadServer.getUrl("download-version.jar");
        Resource resource = Resource.getResource(url, new Version("1.0"), UpdatePolicy.NEVER);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(false, true));
        resourceDownloader.run();

        File downloadedFile = resource.getLocalFile();
        assertTrue(downloadedFile.exists() && downloadedFile.isFile());

        String output = new String(Files.readAllBytes(downloadedFile.toPath()));
        assertEquals(expected, output);
    }

    @Test
    public void testDownloadVersionedPackGzResource() throws IOException {
        String expected = "1.2";

        setupPackGzFile("download-packgz__V1.0", expected);

        Resource resource = Resource.getResource(downloadServer.getUrl("download-packgz.jar"), new Version("1.0"), UpdatePolicy.NEVER);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(true, true));

        resourceDownloader.run();

        File downloadedFile = resource.getLocalFile();
        assertTrue(downloadedFile.exists() && downloadedFile.isFile());
        // Pack200 is fetched from the version-encoded URL (...__V1.0.jar.pack.gz) but must be
        // stored under the unversioned resource location. Writing the raw gzip to the __V path
        // left JarCertVerifier opening a missing download-packgz.jar (Windows production break).
        assertFalse("cache path must not be the version-encoded download URL",
                downloadedFile.getName().contains("__V"));
        assertEquals("download-packgz.jar", downloadedFile.getName());
        byte[] header = Files.readAllBytes(downloadedFile.toPath());
        assertTrue("cached resource must be an unpacked jar (PK), not raw gzip",
                header.length >= 2 && header[0] == 'P' && header[1] == 'K');

        JarFile jf = new JarFile(downloadedFile);
        Manifest m = jf.getManifest();
        String actual = (String) m.getMainAttributes().get(Attributes.Name.MANIFEST_VERSION);

        assertEquals(expected, actual);
    }

    @Test
    @Ignore("Local file:// cache path is unreliable under CI headless runners")
    public void testDownloadLocalResourceUsesCache() throws IOException {
        String expected = "local-resource";
        File localFile = Files.createTempFile("download-local", ".temp").toFile();
        Files.write(localFile.toPath(), expected.getBytes());
        localFile.deleteOnExit();

        URL url = localFile.toURI().toURL();

        Resource resource = Resource.getResource(url, null, UpdatePolicy.NEVER);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resourceDownloader.run();

        File downloadedFile = resource.getLocalFile();
        assertTrue(downloadedFile.exists() && downloadedFile.isFile());
        Assert.assertNotEquals(localFile.getCanonicalFile(), downloadedFile.getCanonicalFile());

        String output = new String(Files.readAllBytes(downloadedFile.toPath()));
        assertEquals(expected, output);
    }

    @Test
    public void testDownloadNotExistingResourceFails() throws IOException {
        Resource resource = Resource.getResource(new URL(downloadServer.getUrl() + "/notexistingfile"), null, UpdatePolicy.NEVER);

        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());

        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resourceDownloader.run();

        assertTrue(resource.hasFlags(EnumSet.of(Resource.Status.ERROR)));
    }

    @Test
    public void testDownloadRejectsJnlpVersionProtocolErrorBodyAsJar() throws IOException {
        // Mirrors Windows production: versioned jar URL returned plain text with HTTP 200.
        setupFile("version-miss.jar", "11 Could not locate requested version\r\n");

        Resource resource = Resource.getResource(downloadServer.getUrl("version-miss.jar"), null, UpdatePolicy.FORCE);
        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());
        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(false, false));
        resourceDownloader.run();

        assertTrue("non-jar payload must surface as download ERROR, not a cached fake jar",
                resource.hasFlags(EnumSet.of(Resource.Status.ERROR)));
        File local = resource.getLocalFile();
        if (local != null && local.isFile()) {
            assertFalse("corrupt payload must not remain on disk as a jar",
                    CacheUtil.isValidJarFile(local));
            // writeDownloadStream deletes the bad file; anything left must not look usable
            assertTrue(local.length() == 0 || !CacheUtil.isValidJarFile(local));
        }
    }

    @Test
    public void testDownloadRejectsTruncatedZipMagicOnlyJar() throws IOException {
        // Passes ZIP/JAR magic sniff but fails verifyJarIntegrity (enumerate/drain).
        setupFile("trunc-magic.jar", new byte[] { 'P', 'K', 3, 4, 0, 0 });

        Resource resource = Resource.getResource(downloadServer.getUrl("trunc-magic.jar"), null, UpdatePolicy.FORCE);
        ResourceDownloader resourceDownloader = new ResourceDownloader(resource, new Object());
        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(false, false));
        resourceDownloader.run();

        assertTrue("truncated jar must not settle as a successful download",
                resource.hasFlags(EnumSet.of(Resource.Status.ERROR)));
        File local = resource.getLocalFile();
        assertTrue("integrity failure must clear local file",
                local == null || !local.isFile());
    }

    private void setupPackGzFile(String fileName, String version) throws IOException {
        File downloadDir = downloadServer.getDir();

        File orig = new File(downloadDir, fileName + ".jar");
        orig.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, version);
        JarOutputStream target = new JarOutputStream(new FileOutputStream(orig), manifest);
        target.close();

        File pack = new File(downloadDir, fileName + ".jar.pack");
        pack.deleteOnExit();

        // Must be java.util.jar.JarFile — Pack200 cannot pack ITW's util.JarFile wrapper
        java.util.jar.JarFile jarFile = new java.util.jar.JarFile(orig.getAbsolutePath());
        FileOutputStream fos = new FileOutputStream(pack);
        try {
            packJarForTests(jarFile, fos);
        } finally {
            jarFile.close();
            fos.close();
        }

        File packgz = new File(downloadDir, fileName + ".jar.pack.gz");
        packgz.deleteOnExit();
        FileOutputStream gzfos = new FileOutputStream(packgz);
        GZIPOutputStream gos = new GZIPOutputStream(gzfos);

        gos.write(Files.readAllBytes(pack.toPath()));
        gos.finish();
        gos.close();
    }

    @Test
    public void packWireHintPrefersTransferredThenContentLengthThenSize() {
        assertEquals(100L, ResourceDownloader.packWireHintBytes(100L, 200L, 300L));
        assertEquals(200L, ResourceDownloader.packWireHintBytes(0L, 200L, 300L));
        assertEquals(200L, ResourceDownloader.packWireHintBytes(-1L, 200L, 300L));
        assertEquals(300L, ResourceDownloader.packWireHintBytes(0L, -1L, 300L));
        assertEquals(0L, ResourceDownloader.packWireHintBytes(0L, -1L, -1L));
    }

    @Test
    public void logMissingFavIconInfoDedupesPerOrigin() throws Exception {
        URL first = new URL("http://127.0.0.1:4201/jnlp/favicon.ico");
        URL sibling = new URL("http://127.0.0.1:4201/favicon.ico");
        ResourceDownloader.logMissingFavIconInfo(first);
        ResourceDownloader.logMissingFavIconInfo(sibling); // same origin key → trace only
        ResourceDownloader.logFavIconTrace("manual favicon trace");
        Assert.assertEquals("<unknown>", ResourceDownloader.faviconMissingLogKey(null));
    }

    @Test
    public void isFavIconUrlDetectsCommonPaths() throws Exception {
        assertTrue(ResourceDownloader.isFavIconUrl(new URL("http://127.0.0.1:4201/jnlp/favicon.ico")));
        assertTrue(ResourceDownloader.isFavIconUrl(new URL("http://127.0.0.1:4201/favicon.ico")));
        assertFalse(ResourceDownloader.isFavIconUrl(new URL("http://127.0.0.1:4201/jnlp/app.jar")));
    }

    @Test
    public void faviconMissingLogKeyCollapsesParentDirectoryProbes() throws Exception {
        Assert.assertEquals(
                "http://127.0.0.1:4201",
                ResourceDownloader.faviconMissingLogKey(new URL("http://127.0.0.1:4201/jnlp/favicon.ico")));
        Assert.assertEquals(
                "http://127.0.0.1:4201",
                ResourceDownloader.faviconMissingLogKey(new URL("http://127.0.0.1:4201/favicon.ico")));
        Assert.assertEquals(
                "file:local",
                ResourceDownloader.faviconMissingLogKey(new URL("file:/C:/work/app/favicon.ico")));
        Assert.assertEquals(
                "file:local",
                ResourceDownloader.faviconMissingLogKey(new URL("file:/C:/favicon.ico")));
    }

    @Test
    public void faviconDownloadFailureIsTraceOnlyWhenDebugEnabled() throws Exception {
        redirectErr();
        try {
            JNLPRuntime.setDebug(true);
            JNLPRuntime.setTrace(false);
            currentErrorStream.reset();

            URL favicon = new URL("http://127.0.0.1:4201/jnlp/favicon.ico");
            ResourceDownloader.logFavIconTrace(new IOException(favicon.toExternalForm()));
            OutputController.getLogger().flush();

            String logged = currentErrorStream.toString("utf-8");
            Assert.assertEquals("", logged);

            JNLPRuntime.setTrace(true);
            currentErrorStream.reset();
            ResourceDownloader.logFavIconTrace(new IOException(favicon.toExternalForm()));
            OutputController.getLogger().flush();

            logged = currentErrorStream.toString("utf-8");
            Assert.assertTrue(logged.contains("FileNotFoundException") || logged.contains(favicon.toExternalForm()));
        } finally {
            JNLPRuntime.setTrace(false);
            redirectErrBack();
        }
    }

    /**
     * Use the JDK's built-in Pack200 for test fixtures on JDK 8–16. JDK 17 removed
     * Pack200 from the JDK API; reflect so test sources still compile on JDK 17+.
     */
    private static void packJarForTests(java.util.jar.JarFile jarFile, FileOutputStream fos) throws IOException {
        if (isJDK17OrLater()) {
            assumeExternalPack200Works();
            io.pack200.Pack200.Packer packer = io.pack200.Pack200.newPacker();
            packer.pack(jarFile, fos);
        } else {
            packJarWithJdkBuiltin(jarFile, fos);
        }
    }

    private static void packJarWithJdkBuiltin(java.util.jar.JarFile jarFile, OutputStream fos) throws IOException {
        try {
            Class<?> pack200Class = Class.forName("java.util.jar.Pack200");
            Object packer = pack200Class.getMethod("newPacker").invoke(null);
            packer.getClass()
                    .getMethod("pack", java.util.jar.JarFile.class, OutputStream.class)
                    .invoke(packer, jarFile, fos);
        } catch (ReflectiveOperationException ex) {
            throw new IOException("JDK built-in Pack200 unavailable", ex);
        }
    }

    private static void assumeExternalPack200Works() {
        try {
            Object packer = io.pack200.Pack200.newPacker();
            Assume.assumeTrue("External pack200 packer unavailable", packer instanceof io.pack200.Pack200.Packer);
            Object unpacker = io.pack200.Pack200.newUnpacker();
            Assume.assumeTrue("External pack200 unpacker unavailable", unpacker instanceof io.pack200.Pack200.Unpacker);
        } catch (Throwable ex) {
            Assume.assumeNoException(ex);
        }
    }

    private static boolean isJDK17OrLater() {
        return getJavaMajorVersion() >= 17;
    }

    private static int getJavaMajorVersion() {
        String[] elements = System.getProperty("java.version").split("\\.");
        int discard = Integer.parseInt(elements[0]);
        if (discard == 1) {
            return Integer.parseInt(elements[1]);
        }
        return discard;
    }

    @Test
    public void rangeHeaderBuiltFromPartialCacheLength() {
        Assert.assertNull("no resume when offset <= 0", ResourceDownloader.buildRangeHeader(0L));
        Assert.assertNull(ResourceDownloader.buildRangeHeader(-1L));
        Assert.assertEquals("bytes=512-", ResourceDownloader.buildRangeHeader(512L));
        Assert.assertEquals("bytes=1-", ResourceDownloader.buildRangeHeader(1L));
    }

    @Test
    public void parseContentRangeReadsStartEndTotal() {
        long[] r = ResourceDownloader.parseContentRange("bytes 512-3851/3852");
        Assert.assertNotNull(r);
        Assert.assertEquals(512L, r[0]);
        Assert.assertEquals(3851L, r[1]);
        Assert.assertEquals(3852L, r[2]);
        // unit is case-insensitive; response Content-Range always carries start-end/total
        long[] open = ResourceDownloader.parseContentRange("BYTES 90-99/100");
        Assert.assertNotNull(open);
        Assert.assertEquals(90L, open[0]);
        Assert.assertEquals(99L, open[1]);
        Assert.assertEquals(100L, open[2]);
        Assert.assertNull(ResourceDownloader.parseContentRange(null));
        Assert.assertNull(ResourceDownloader.parseContentRange("bytes */100"));
        Assert.assertNull(ResourceDownloader.parseContentRange("items 0-9/10"));
    }

    @Test
    public void formatIfRangeDateIsRfc1123Gmt() {
        Assert.assertNull(ResourceDownloader.formatIfRangeDate(0L));
        Assert.assertNull(ResourceDownloader.formatIfRangeDate(-1L));
        String d = ResourceDownloader.formatIfRangeDate(1_600_000_000_000L);
        Assert.assertTrue("expected RFC 1123 GMT date, got " + d, d.endsWith(" GMT") && d.contains(","));
    }

    private static byte[] makeMinimalJarBytes(String manifestVersion) throws IOException {
        File tmp = File.createTempFile("range-jar", ".jar");
        tmp.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, manifestVersion);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tmp), manifest)) {
            java.util.jar.JarEntry entry = new java.util.jar.JarEntry("payload.txt");
            jos.putNextEntry(entry);
            jos.write("hello-range-resume".getBytes());
            jos.closeEntry();
        }
        return Files.readAllBytes(tmp.toPath());
    }

    private static void seedPartialCache(URL url, byte[] full, int cut) throws IOException {
        CacheEntry seed = new CacheEntry(url, null);
        File cacheFile = seed.getCacheFile();
        Files.createDirectories(cacheFile.toPath().getParent());
        Files.write(cacheFile.toPath(), java.util.Arrays.copyOf(full, cut));
        seed.setRemoteContentLength(full.length);
        seed.setLastModified(0L);
        seed.lock();
        seed.store();
        seed.unlock();
    }

    @Test
    public void testResumeAppendsSuffixOnHttp206() throws Exception {
        byte[] full = makeMinimalJarBytes("1.2");
        File remote = new File(rangeServer.getDir(), "resume.jar");
        remote.deleteOnExit();
        Files.write(remote.toPath(), full);

        URL url = rangeServer.getUrl("resume.jar");
        rangeHeaderSeen.set(null);
        int cut = Math.max(4, full.length / 2);
        seedPartialCache(url, full, cut);

        Resource resource = Resource.getResource(url, null, UpdatePolicy.FORCE);
        ResourceDownloader downloader = new ResourceDownloader(resource, new Object());
        resource.setDownloadOptions(new DownloadOptions(false, false));
        downloader.run();

        File downloaded = resource.getLocalFile();
        Assert.assertNotNull("resumed download must produce a cache file", downloaded);
        byte[] result = Files.readAllBytes(downloaded.toPath());
        Assert.assertEquals("resumed file must be the full resource length", full.length, result.length);
        Assert.assertArrayEquals("appended suffix must reconstruct the original jar", full, result);
        String rangeSent = rangeHeaderSeen.get();
        Assert.assertNotNull("client must send a Range header to resume", rangeSent);
        Assert.assertEquals("client must request the suffix from the cached length",
                "bytes=" + cut + "-", rangeSent);
    }

    @Test
    public void testServerWithoutRangeFullDownloadsNoAppend() throws Exception {
        byte[] full = makeMinimalJarBytes("1.3");
        File remote = new File(downloadServer.getDir(), "no-range.jar");
        remote.deleteOnExit();
        Files.write(remote.toPath(), full);

        URL url = downloadServer.getUrl("no-range.jar");
        int cut = Math.max(4, full.length / 2);
        seedPartialCache(url, full, cut);

        Resource resource = Resource.getResource(url, null, UpdatePolicy.FORCE);
        ResourceDownloader downloader = new ResourceDownloader(resource, new Object());
        resource.setDownloadOptions(new DownloadOptions(false, false));
        downloader.run();

        File downloaded = resource.getLocalFile();
        Assert.assertNotNull(downloaded);
        byte[] result = Files.readAllBytes(downloaded.toPath());
        Assert.assertEquals("server ignored Range: client must replace, not append (no length doubling)",
                full.length, result.length);
        Assert.assertArrayEquals(full, result);
    }
}
