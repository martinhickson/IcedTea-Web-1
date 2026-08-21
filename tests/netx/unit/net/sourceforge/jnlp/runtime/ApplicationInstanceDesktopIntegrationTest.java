package net.sourceforge.jnlp.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.sourceforge.jnlp.ShortcutDesc;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import org.junit.Test;

public class ApplicationInstanceDesktopIntegrationTest {

    @Test
    public void neverSkipsDesktopIntegration() throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();
        config.setProperty(DeploymentConfiguration.KEY_CREATE_DESKTOP_SHORTCUT, ShortcutDesc.CREATE_NEVER);
        assertTrue(ApplicationInstance.isDesktopIntegrationDisabled(config));
    }

    @Test
    public void alwaysDoesNotSkipDesktopIntegration() throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();
        config.setProperty(DeploymentConfiguration.KEY_CREATE_DESKTOP_SHORTCUT, ShortcutDesc.CREATE_ALWAYS);
        assertFalse(ApplicationInstance.isDesktopIntegrationDisabled(config));
        assertFalse(ApplicationInstance.isDesktopIntegrationDisabled(null));
    }
}
