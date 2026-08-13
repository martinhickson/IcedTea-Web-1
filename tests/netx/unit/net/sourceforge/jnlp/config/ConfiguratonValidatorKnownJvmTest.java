package net.sourceforge.jnlp.config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

public class ConfiguratonValidatorKnownJvmTest extends NoStdOutErrTest {

    @Test
    public void doesNotFlagDeploymentJdkKeysAsUnrecognized() {
        Map<String, Setting<String>> settings = new HashMap<>();
        settings.put("deployment.jdk.1",
                new Setting<>("deployment.jdk.1", null, false, null, null, "C:\\jdk-21", null));
        settings.put("deployment.jdk.2",
                new Setting<>("deployment.jdk.2", null, false, null, null, "C:\\jdk-17", null));
        settings.put(KnownJvmStore.KEY_MATCH_STRATEGY,
                new Setting<>(KnownJvmStore.KEY_MATCH_STRATEGY, null, false, null, null, "exact", null));
        settings.put("deployment.totally.unknown",
                new Setting<>("deployment.totally.unknown", null, false, null, null, "x", null));
        settings.put(DeploymentConfiguration.KEY_JRE_DIRS,
                new Setting<>(DeploymentConfiguration.KEY_JRE_DIRS, null, false, null, null, "/opt/jdk", null));

        ConfiguratonValidator validator = new ConfiguratonValidator(settings);
        validator.validate();

        Set<String> unrecognized = new HashSet<>();
        for (Setting<String> setting : validator.getUnrecognizedSetting()) {
            unrecognized.add(setting.getName());
        }
        Assert.assertEquals(new HashSet<>(Arrays.asList(
                "deployment.totally.unknown", DeploymentConfiguration.KEY_JRE_DIRS)), unrecognized);
    }
}
