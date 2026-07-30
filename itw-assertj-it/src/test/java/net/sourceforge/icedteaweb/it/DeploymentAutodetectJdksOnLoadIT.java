package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.util.JvmAutodetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class DeploymentAutodetectJdksOnLoadIT {

    @AfterEach
    void tearDown() {
        ControlPanelTestSupport.resetDeploymentConfig();
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.DeploymentAutodetectJdksOnLoadIT#autodetectAvailable")
    void autodetectJdksOnLoadPopulatesEmptyDeploymentProperties() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        Properties props = new Properties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "true");
        ControlPanelTestSupport.writeDeploymentProperties(props);

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();

        // Autodetect persists discovery order after KnownJvmStore's preference sort
        // (Corretto → Temurin → others; majors 17 → 21 → 11 → rest).
        List<String> expected = new ArrayList<>(JvmAutodetector.discoverValidJvmHomes());
        Collections.sort(expected, KnownJvmStore.autodetectionPreferenceComparator());
        assertThat(expected).isNotEmpty();
        for (int i = 0; i < expected.size(); i++) {
            assertThat(config.getProperty("deployment.jdk." + (i + 1))).isEqualTo(expected.get(i));
        }
        assertThat(config.getProperty("deployment.jdk." + (expected.size() + 1))).isNull();

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(expected.get(0));
        assertThat(saved.getProperty("deployment.autodetectJDKs")).isEqualTo("true");
    }

    @Test
    void autodetectJdksDisabledLeavesEmptyKnownJvms() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        Properties props = new Properties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "false");
        ControlPanelTestSupport.writeDeploymentProperties(props);

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();

        assertThat(config.getProperty("deployment.jdk.1")).isNull();
    }

    static boolean autodetectAvailable() {
        return !JvmAutodetector.discoverValidJvmHomes().isEmpty();
    }
}
