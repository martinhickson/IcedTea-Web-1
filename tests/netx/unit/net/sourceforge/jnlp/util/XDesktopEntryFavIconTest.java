package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class XDesktopEntryFavIconTest {

    @Test
    public void remoteWalkStillIncludesSiteRoot() {
        List<String> locations = XDesktopEntry.possibleFavIconLocations("/jnlp/app");
        assertTrue(locations.contains("/jnlp/app"));
        assertTrue(locations.contains("/jnlp"));
        assertTrue(locations.contains(""));
    }

    @Test
    public void boundedWalkDoesNotGoAboveFloor() {
        List<String> locations = XDesktopEntry.possibleFavIconLocations(
                "/C:/work/itw/samples/gui-app",
                "/C:/work/itw/samples");
        assertEquals(
                Arrays.asList("/C:/work/itw/samples/gui-app", "/C:/work/itw/samples"),
                locations);
        assertFalse(locations.contains("/C:/work/itw"));
        assertFalse(locations.contains(""));
    }

    @Test
    public void boundedWalkWithSameStartAndFloorIsSinglePath() {
        List<String> locations = XDesktopEntry.possibleFavIconLocations(
                "/C:/work/itw/samples/gui-app",
                "/C:/work/itw/samples/gui-app");
        assertEquals(Arrays.asList("/C:/work/itw/samples/gui-app"), locations);
    }

    @Test
    public void longestCommonDirectoryPrefixOfSiblingRoots() {
        String prefix = XDesktopEntry.longestCommonDirectoryPrefix(Arrays.asList(
                "/C:/work/samples/gui-app",
                "/C:/work/samples/lib"));
        assertEquals("/C:/work/samples", prefix);
    }

    @Test
    public void directoryPathOfFileUrlStripsFilename() throws Exception {
        assertEquals(
                "/C:/work/samples/gui-app",
                XDesktopEntry.directoryPathOfUrl(new URL("file:/C:/work/samples/gui-app/app.jar")));
        assertEquals(
                "/C:/work/samples/gui-app",
                XDesktopEntry.directoryPathOfUrl(new URL("file:/C:/work/samples/gui-app/")));
    }
}
