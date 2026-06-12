package net.sourceforge.jnlp.controlpanel;

/**
 * Settings panels that edit {@link net.sourceforge.jnlp.config.DeploymentConfiguration}
 * implement this so Revert can refresh widgets from the last applied state.
 */
public interface SettingsPanelReloader {

    void reloadFromConfiguration();
}
