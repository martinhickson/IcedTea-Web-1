package net.sourceforge.jnlp.config;

import java.util.HashMap;
import java.util.Map;
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

        ConfiguratonValidator validator = new ConfiguratonValidator(settings);
        validator.validate();

        Assert.assertEquals(1, validator.getUnrecognizedSetting().size());
        Assert.assertEquals("deployment.totally.unknown", validator.getUnrecognizedSetting().get(0).getName());
    }
}
