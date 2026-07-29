package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.util.JvmSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Match strategy defines which JDKs are eligible; preference list order breaks ties.
 */
class JvmMatchStrategyIT {

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void maximumStrategyUsesPreferenceOrderAmongMatchingMajors() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        File higher = null;
        for (File home : ControlPanelTestSupport.discoverValidJdkHomes()) {
            if (JvmSelectionTestSupport.majorVersionOf(home) > 11) {
                higher = home;
                break;
            }
        }
        assertThat(jdk11).isNotNull();
        assertThat(higher).isNotNull();

        String requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");

        DeploymentConfiguration preferEleven = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(jdk11, higher), JdkMatchStrategy.MAXIMUM);
        assertThat(JvmSelector.selectBestJvmHome(preferEleven, requested))
                .isEqualTo(jdk11.getAbsolutePath());

        DeploymentConfiguration preferHigher = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(higher, jdk11), JdkMatchStrategy.MAXIMUM);
        assertThat(JvmSelector.selectBestJvmHome(preferHigher, requested))
                .isEqualTo(higher.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmSelectionTestSupport#hasJava11AndHigherJdks")
    void minimumStrategyAlsoDefersToPreferenceOrderAmongMatches() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);
        File higher = jdks.get(jdks.size() - 1);

        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(higher, jdk11), JdkMatchStrategy.MINIMUM);
        String requested = JvmSelectionTestSupport.requestedJreVersionFromJnlpResource(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");

        // Both match 11+; first in preference wins (no longer "lowest major wins").
        assertThat(JvmSelector.selectBestJvmHome(config, requested)).isEqualTo(higher.getAbsolutePath());
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
    void fileJnlpUrlUsesPreferenceOrderForRequestedVersionLookup() throws Exception {
        File jdk11 = JvmSelectionTestSupport.findJdkHomeWithMajor(11);
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(2);
        File higher = jdks.get(jdks.size() - 1);

        DeploymentConfiguration config = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(jdk11, higher), JdkMatchStrategy.MAXIMUM);
        java.net.URL jnlpUrl = JvmSelectionTestSupport.materializeResourceJnlp(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");
        JNLPFile file = JvmSelectionTestSupport.parseMaterializedJnlp(
                "/jnlp-samples/minimum-java11-plus/app.jnlp");
        String requested = file.getResources().getJREs()[0].getVersion().toString();

        assertThat(JvmSelector.selectBestJvmHome(config, requested, jnlpUrl.toExternalForm()))
                .isEqualTo(jdk11.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmMatchStrategyIT#twoSameMajorJdksAvailable")
    void sameMajorPreferenceOrderWinsUnderExactAndMaximum() throws Exception {
        List<File> sameMajor = findTwoHomesSharingMajor();
        File first = sameMajor.get(0);
        File second = sameMajor.get(1);
        int major = JvmSelectionTestSupport.majorVersionOf(first);
        String exact = Integer.toString(major);
        String maximum = major + "+";

        DeploymentConfiguration preferFirst = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(first, second), JdkMatchStrategy.EXACT);
        assertThat(JvmSelector.selectBestJvmHome(preferFirst, exact)).isEqualTo(first.getAbsolutePath());

        DeploymentConfiguration preferSecond = JvmSelectionTestSupport.configurationWithKnownJvms(
                Arrays.asList(second, first), JdkMatchStrategy.MAXIMUM);
        assertThat(JvmSelector.selectBestJvmHome(preferSecond, maximum)).isEqualTo(second.getAbsolutePath());
    }

    static boolean twoSameMajorJdksAvailable() throws Exception {
        return findTwoHomesSharingMajor() != null;
    }

    private static List<File> findTwoHomesSharingMajor() throws Exception {
        for (int major : new int[] {17, 21, 11}) {
            List<File> homes = ControlPanelTestSupport.findJdkHomesWithMajor(major, 2);
            if (homes.size() >= 2) {
                return homes;
            }
        }
        return null;
    }
}
