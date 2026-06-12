package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.util.JvmSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class JvmMatchStrategyIT {

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void maximumStrategySelectsHighestMatchingJdkForJava11PlusJnlp() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);
        File highest = jdks.get(jdks.size() - 1);

        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                jdks, JdkMatchStrategy.MAXIMUM);
        String requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");

        assertThat(JvmSelectionTestSupport.majorVersionOf(jdk11)).isEqualTo(11);
        assertThat(JvmSelector.selectBestJvmHome(config, requested)).isEqualTo(highest.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void minimumStrategySelectsLowestMatchingJdkForJava11PlusJnlp() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);

        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                jdks, JdkMatchStrategy.MINIMUM);
        String requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");

        assertThat(JvmSelector.selectBestJvmHome(config, requested)).isEqualTo(jdk11.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void exactStrategyIgnoresPlusAndSelectsOnlyMatchingMajor() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);

        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                jdks, JdkMatchStrategy.EXACT);
        String requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/exact-java8-plus/app.jnlp");

        assertThat(JvmSelector.selectBestJvmHome(config, requested)).isNull();

        config = JvmSelectionTestSupport.configurationWithKnownJvms(jdks, JdkMatchStrategy.EXACT);
        requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/exact-java11/app.jnlp");
        assertThat(JvmSelector.selectBestJvmHome(config, requested)).isEqualTo(jdk11.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void fileJnlpUrlIsUsedForRequestedVersionLookup() throws Exception {
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);
        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                jdks, JdkMatchStrategy.MAXIMUM);
        java.net.URL jnlpUrl = JvmSelectionTestSupport.materializeResourceJnlp(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");
        JNLPFile file = JvmSelectionTestSupport.parseMaterializedJnlp(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");
        String requested = file.getResources().getJREs()[0].getVersion().toString();

        assertThat(JvmSelector.selectBestJvmHome(config, requested, jnlpUrl.toExternalForm()))
                .isEqualTo(jdks.get(jdks.size() - 1).getAbsolutePath());
    }
}
