package net.sourceforge.jnlp.jdk89acesses;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;

import javax.swing.ImageIcon;

import org.junit.Test;

public class SunMiscLauncherTest {

    private static final String[] BUNDLED_ICONS = {
        "net/sourceforge/jnlp/resources/warning.png",
        "net/sourceforge/jnlp/resources/warning-small.png",
        "net/sourceforge/jnlp/resources/question.png",
        "net/sourceforge/jnlp/resources/info-small.png",
        "net/sourceforge/jnlp/resources/showDownloadDetails.png",
        "net/sourceforge/jnlp/resources/hideDownloadDetails.png",
        "net/sourceforge/jnlp/resources/netx-icon.png",
        "net/sourceforge/jnlp/resources/install.png",
        "net/sourceforge/jnlp/resources/itw_logo.png"
    };

    @Test
    public void loadsBundledWarningIconWithoutNullPointerException() {
        ImageIcon icon = SunMiscLauncher.getSecureImageIcon("net/sourceforge/jnlp/resources/warning.png");
        assertNotNull(icon);
        assertTrue(icon.getIconWidth() >= 0);
    }

    @Test
    public void allStandardDialogIconsArePresentInUberJar() {
        for (String resource : BUNDLED_ICONS) {
            URL url = SunMiscLauncher.getResourceUrl(resource);
            assertNotNull("Missing bundled icon: " + resource, url);
            ImageIcon icon = SunMiscLauncher.getSecureImageIcon(resource);
            assertNotNull(icon);
        }
    }

    @Test
    public void missingResourceReturnsEmptyIconInsteadOfThrowing() {
        ImageIcon icon = SunMiscLauncher.getSecureImageIcon("net/sourceforge/jnlp/resources/does-not-exist.png");
        assertNotNull(icon);
    }

    @Test
    public void acceptsLeadingSlashInResourcePath() {
        URL url = SunMiscLauncher.getResourceUrl("/net/sourceforge/jnlp/resources/warning.png");
        assertNotNull(url);
    }
}
