package net.sourceforge.jnlp.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.sourceforge.jnlp.util.FileTestUtils;
import net.sourceforge.jnlp.util.FileUtils;

public class CachedJarFileCallbackTest {
	private File tempDirectory;

	@Before
	public void before() throws IOException {
		tempDirectory = FileTestUtils.createTempDirectory();
	}

	@After
	public void after() {
		// retrieve() may leave the JAR open in the JDK jar cache; Windows then refuses delete.
		try {
			FileUtils.recursiveDelete(tempDirectory, tempDirectory.getParentFile());
		} catch (IOException e) {
			if (tempDirectory != null) {
				tempDirectory.deleteOnExit();
			}
		}
	}

	@Test
	public void testRetrieve() throws Exception {
		List<String> names = Arrays.asList("test1.0.jar", "test@1.0.jar");
		
		for (String name: names) {
			// URL-encode the filename
			name = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
			// create temp jar file
			File jarFile = new File(tempDirectory, name);
			FileTestUtils.createJarWithContents(jarFile /* no contents */);

			// JNLPClassLoader.activateJars uses toUri().toURL() to get the local file URL
			URL localUrl = jarFile.toURI().toURL();
			URL remoteUrl = new URL("http://localhost/" + name);
			// add jar to cache
			CachedJarFileCallback cachedJarFileCallback = CachedJarFileCallback.getInstance();
			cachedJarFileCallback.addMapping(remoteUrl, localUrl);
			// retrieve from cache (throws exception if file not found)
			JarFile fromCacheJarFile = cachedJarFileCallback.retrieve(remoteUrl);
			// Note: Do NOT close fromCacheJarFile here - it may come from JDK's global cache
			// and should not be closed by application code
		}
	}

	@Test
	public void retrieveClearsManifestClassPathForRemoteMapping() throws Exception {
		Manifest mf = new Manifest();
		mf.getMainAttributes().putValue(Attributes.Name.CLASS_PATH.toString(),
				"batik-ext-1.7.jar other.jar");
		File jarFile = new File(tempDirectory, "with-classpath.jar");
		FileTestUtils.createJarWithContents(jarFile, mf);

		URL localUrl = jarFile.toURI().toURL();
		URL remoteUrl = new URL("https://example.test/downloads/with-classpath.jar");
		CachedJarFileCallback cb = CachedJarFileCallback.getInstance();
		cb.addMapping(remoteUrl, localUrl);
		JarFile fromCache = cb.retrieve(remoteUrl);
		String cp = fromCache.getManifest().getMainAttributes()
				.getValue(Attributes.Name.CLASS_PATH);
		assertTrue("Class-Path must be blanked for remote-mapped jars",
				cp == null || cp.trim().isEmpty());
	}

	@Test
	public void retrieveDoesNotConnectForUnmappedRemoteClassPathToken() throws Exception {
		URL phantom = new URL("https://192.0.2.10/downloads/batik-ext-1.7.jar");
		long t0 = System.nanoTime();
		try {
			CachedJarFileCallback.getInstance().retrieve(phantom);
			fail("unmapped remote jar must not download");
		} catch (FileNotFoundException expected) {
			assertTrue(expected.getMessage().contains("not a JNLP-cached jar"));
			assertEquals("Class-Path miss must not carry a stack", 0,
					expected.getStackTrace().length);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		assertTrue("must fail without TLS (was " + ms + "ms)", ms < 2000);
	}
}
