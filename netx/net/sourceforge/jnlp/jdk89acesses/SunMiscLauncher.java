/*
 Copyright (C) 2016 Red Hat, Inc.

 This file is part of IcedTea.

 IcedTea is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation, version 2.

 IcedTea is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 IcedTea; see the file COPYING. If not, write to the
 Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 02110-1301 USA.

 Linking this library statically or dynamically with other modules is making a
 combined work based on this library. Thus, the terms and conditions of the GNU
 General Public License cover the whole combination.

 As a special exception, the copyright holders of this library give you
 permission to link this library with independent modules to produce an
 executable, regardless of the license terms of these independent modules, and
 to copy and distribute the resulting executable under terms of your choice,
 provided that you also meet, for each linked independent module, the terms and
 conditions of the license of that module. An independent module is a module
 which is not derived from or based on this library. If you modify this library,
 you may extend this exception to your version of the library, but you are not
 obligated to do so. If you do not wish to do so, delete this exception
 statement from your version.*/
package net.sourceforge.jnlp.jdk89acesses;

import java.io.InputStream;
import java.net.URL;

import javax.swing.ImageIcon;

import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Loads bundled dialog and UI icons from the IcedTea-Web uber JAR.
 *
 * <p>Historically this accessed {@code sun.misc.Launcher} on JDK 8. The extension
 * class loader was removed in JDK 9+, so icons must be resolved from the
 * IcedTea-Web class loader instead. All standard icons live under
 * {@code net/sourceforge/jnlp/resources/} inside the uber JAR.</p>
 *
 * @author jvanek
 */
public final class SunMiscLauncher {

    private SunMiscLauncher() {
    }

    public static ImageIcon getSecureImageIcon(String resource) {
        URL url = getResourceUrl(resource);
        if (url == null) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "Bundled icon resource not found: " + resource);
            return new ImageIcon();
        }
        return new ImageIcon(url);
    }

    public static URL getResourceUrl(String resource) {
        return locateResource(normalizeResourcePath(resource));
    }

    public static InputStream getResourceAsStream(String resource) {
        URL url = getResourceUrl(resource);
        if (url == null) {
            return null;
        }
        try {
            return url.openStream();
        } catch (Exception ex) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL, ex);
            return null;
        }
    }

    private static String normalizeResourcePath(String resource) {
        if (resource == null || resource.isEmpty()) {
            return resource;
        }
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        return resource;
    }

    private static URL locateResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            return null;
        }
        ClassLoader[] loaders = new ClassLoader[] {
            SunMiscLauncher.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            URL url = loader.getResource(resource);
            if (url != null) {
                return url;
            }
        }
        ClassLoader extensionLoader = ClassLoader.getSystemClassLoader().getParent();
        if (extensionLoader != null) {
            return extensionLoader.getResource(resource);
        }
        return null;
    }
}
