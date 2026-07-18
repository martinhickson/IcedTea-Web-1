/* 
 Copyright (C) 2011 Red Hat, Inc.

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
package net.sourceforge.jnlp.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import net.sourceforge.jnlp.util.logging.OutputController;

public class HttpUtils {

    private static boolean isFavIconUrl(URL url) {
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

    private static void logFavIconTrace(String message) {
        OutputController.getLogger().log(OutputController.Level.TRACE, message);
    }

    private static void logFavIconTrace(Throwable ex) {
        OutputController.getLogger().log(OutputController.Level.TRACE, ex);
    }

    /**
     * Ensure a HttpURLConnection is fully read, required for correct behavior.
     * Captured IOException is consumed and printed
     * @param c the connection to be closed silently
     */
    public static void consumeAndCloseConnectionSilently(HttpURLConnection c) {
        consumeAndCloseConnectionSilently(c, null);
    }

    /**
     * Ensure a HttpURLConnection is fully read, required for correct behavior.
     * Captured IOException is consumed and printed
     * @param c the connection to be closed silently
     * @param contextUrl URL used to classify harmless favicon failures
     */
    public static void consumeAndCloseConnectionSilently(HttpURLConnection c, URL contextUrl) {
        try {
            consumeAndCloseConnection(c);
        } catch (IOException ex) {
            URL url = contextUrl;
            if (url == null) {
                url = c.getURL();
            }
            if (isFavIconUrl(url)) {
                logFavIconTrace("Following exception: '" + ex.getMessage() + "' should be harmless, but may help in finding root cause.");
                logFavIconTrace(ex);
                return;
            }
            OutputController.getLogger().log("Following exception: '" + ex.getMessage() + "' should be harmless, but may help in finding root cause.");
            OutputController.getLogger().log(ex);
        }
    }

    /**
     * Ensure a HttpURLConnection is fully read, required for correct behavior
     * 
     * @param c connection to be closed
     * @throws IOException if connection fade
     */
    public static void consumeAndCloseConnection(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            byte[] throwAwayBuffer = new byte[256];
            while (in.read(throwAwayBuffer) > 0) {
                /* ignore contents */
            }
        }
    }
}
