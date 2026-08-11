/* 
   Copyright (C) 2014  Red Hat

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2, or (at your option)
any later version.

IcedTea is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to the
Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms and conditions of these
independent modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on, or is not derived from, or based on, this library. If you modify
this library, you may extend this exception to your version of the
library, but you are not obligated to do so.  If you do not wish to do
so, delete this exception statement from your version.
*/

package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;
import net.sourceforge.jnlp.util.logging.OutputController;


public class ConnectionFactory {

    public static ConnectionFactory getConnectionFactory() {
        return ConnectionFactoryHolder.INSTANCE;
    }

    private static class ConnectionFactoryHolder {

        //https://en.wikipedia.org/wiki/Double-checked_locking#Usage_in_Java
        //https://en.wikipedia.org/wiki/Initialization_on_demand_holder_idiom
        private static volatile ConnectionFactory INSTANCE = new ConnectionFactory();
    }

    /**
     * Opens a URLConnection.  No synchronisation, no shared state —
     * Java's built-in keep-alive pool handles connection reuse.
     */
    public URLConnection openConnection(URL url) throws IOException {
        OutputController.getLogger().log("Connecting " + url.toExternalForm());
        if (url.getProtocol().equalsIgnoreCase("https")) {
            return openHttpsConnection(url);
        } else {
            URLConnection conn = url.openConnection();
            OutputController.getLogger().log("done " + url.toExternalForm());
            return conn;
        }
    }

    /**
     * Delegates to {@link URL#openConnection()}.
     * No synchronisation — the previous list-tracking + synchronized lifecycle
     * was dead code (isSyncForced() always returned false) and serialized
     * all HTTPS connection creation/teardown.
     */
    private URLConnection openHttpsConnection(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        OutputController.getLogger().log("done " + url.toExternalForm());
        return conn;
    }

    /**
     * No-op.  Previously called {@code conn.disconnect()} which destroyed the
     * keep-alive connection, forcing a full TCP+TLS handshake through any
     * intercepting proxy for every resource.  Leaving the connection in Java's
     * keep-alive pool lets it be reused, matching master/OWS behaviour.
     */
    public void disconnect(URLConnection conn) {
        // intentionally empty — do NOT disconnect
    }

}
