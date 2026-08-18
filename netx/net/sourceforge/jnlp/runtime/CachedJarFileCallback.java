/* CachedJarFileCallback.java
   Copyright (C) 2011 Red Hat, Inc.
   Copyright (c) 1997, 2006, Oracle and/or its affiliates. All rights reserved.

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.

IcedTea is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to
the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version.
*/

package net.sourceforge.jnlp.runtime;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.sourceforge.jnlp.security.ConnectionFactory;
import net.sourceforge.jnlp.util.JarFileTempManager;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Resolves {@code jar:} URLs to locally cached JAR files.
 * <p>
 * On JDK 23 and earlier, registered as {@code URLJarFileCallBack} via
 * {@link LegacyUrlJarFileCallbackRegistrar}. On JDK 24+, the same {@link #retrieve(URL)}
 * logic is invoked by {@link JarUrlCacheProtection} through ByteBuddy.
 */
final class CachedJarFileCallback {

    private static final CachedJarFileCallback INSTANCE = new CachedJarFileCallback();

    public static synchronized CachedJarFileCallback getInstance() {
        return INSTANCE;
    }

    private final Map<String, URL> mapping = new ConcurrentHashMap<>();

    private CachedJarFileCallback() {
    }

    void addMapping(URL remoteUrl, URL localUrl) {
        mapping.put(UrlUtils.urlKey(remoteUrl), localUrl);
    }

    /**
     * Return a cached/open JarFile for the given jar URL, downloading through ITW if needed.
     */
    public JarFile retrieve(URL url) throws IOException {
        URL localUrl = mapping.get(UrlUtils.urlKey(url));
        if (localUrl == null && url.getRef() != null) {
            url = new URL(url.toString().substring(0, url.toString().lastIndexOf(url.getRef()) - 1));
            localUrl = mapping.get(UrlUtils.urlKey(url));
        }

        if (localUrl == null) {
            if (isRemoteHttpUrl(url)) {
                // JDK URLClassLoader follows Manifest Class-Path against the
                // remote jar: URL. Those names are not JNLP <resources> (often
                // Maven-versioned siblings). Opening them is a new TLS probe
                // per token on the application thread. Web Start does not
                // download Class-Path; skip without connecting.
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "CachedJarFileCallback: skip unmapped remote jar (not a JNLP resource): " + url);
                throw quietUnmappedRemote(url);
            }
            return cacheJarFile(url);
        }

        if (UrlUtils.isLocalFile(localUrl)) {
            String path = UrlUtils.decodeUrlQuietly(localUrl).getPath();
            JarFile cached = JarFileCache.getInstance().getJarFile(path);
            if (isRemoteHttpUrl(url)) {
                clearManifestClassPath(cached);
            }
            return cached;
        }

        return null;
    }

    static boolean isRemoteHttpUrl(URL url) {
        if (url == null) {
            return false;
        }
        String p = url.getProtocol();
        return "http".equalsIgnoreCase(p) || "https".equalsIgnoreCase(p);
    }

    /**
     * Web Start does not honour Manifest Class-Path. Clearing the in-memory
     * attribute stops {@code URLClassLoader} from opening each token as a new
     * remote {@code jar:} URL (plugin already did this; javaws did not).
     */
    static void clearManifestClassPath(JarFile jarFile) {
        if (jarFile == null) {
            return;
        }
        try {
            Manifest mf = jarFile.getManifest();
            if (mf == null) {
                return;
            }
            Attributes attrs = mf.getMainAttributes();
            if (attrs == null) {
                return;
            }
            String existing = attrs.getValue(Attributes.Name.CLASS_PATH);
            if (existing == null || existing.isEmpty()) {
                return;
            }
            attrs.putValue(Attributes.Name.CLASS_PATH.toString(), "");
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "CachedJarFileCallback: cleared Manifest Class-Path on " + jarFile.getName());
        } catch (Exception ignored) {
            // no manifest / attributes
        }
    }

    /**
     * {@link java.io.FileNotFoundException} with no stack. URLClassLoader
     * treats this as a missing optional Class-Path jar; a filled stack would
     * flood the ITW log (~one per token).
     */
    private static FileNotFoundException quietUnmappedRemote(URL url) {
        FileNotFoundException e = new FileNotFoundException("not a JNLP-cached jar: " + url) {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        };
        e.setStackTrace(new StackTraceElement[0]);
        return e;
    }

    private JarFile cacheJarFile(URL url) throws IOException {
        if (isRemoteHttpUrl(url)) {
            throw quietUnmappedRemote(url);
        }
        final int bufSize = 2048;
        URLConnection conn = ConnectionFactory.getConnectionFactory().openConnection(url);
        final InputStream in = conn.getInputStream();

        try {
            return AccessController.doPrivileged(
                    (PrivilegedExceptionAction<JarFile>) () -> {
                        OutputStream out = null;
                        File tmpFile = null;
                        try {
                            File tempBaseDir = new File(System.getProperty("java.io.tmpdir"), "icedtea-web");
                            if (!tempBaseDir.exists()) {
                                tempBaseDir.mkdirs();
                            }
                            tmpFile = new File(tempBaseDir,
                                    "jar_cache_" + System.currentTimeMillis() + "_" + UrlUtils.urlKey(url).hashCode() + ".jar");

                            out = new FileOutputStream(tmpFile);
                            byte[] buf = new byte[bufSize];
                            int read;
                            while ((read = in.read(buf)) != -1) {
                                out.write(buf, 0, read);
                            }
                            out.close();
                            out = null;

                            return JarFileCache.getInstance().getURLJarFile(tmpFile);
                        } catch (IOException e) {
                            if (tmpFile != null) {
                                tmpFile.delete();
                            }
                            throw e;
                        } finally {
                            in.close();
                            if (out != null) {
                                out.close();
                            }
                        }
                    });
        } catch (PrivilegedActionException pae) {
            throw (IOException) pae.getException();
        } finally {
            ConnectionFactory.getConnectionFactory().disconnect(conn);
        }
    }
}
