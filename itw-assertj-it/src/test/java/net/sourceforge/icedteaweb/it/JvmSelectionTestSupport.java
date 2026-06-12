package net.sourceforge.icedteaweb.it;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.ParseException;
import net.sourceforge.jnlp.ParserSettings;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JvmSelector;
import org.assertj.swing.fixture.FrameFixture;

final class JvmSelectionTestSupport {

    private JvmSelectionTestSupport() {
    }

    static File findJdkHomeWithMajor(int major) throws Exception {
        for (File home : ControlPanelTestSupport.discoverValidJdkHomes()) {
            if (majorVersionOf(home) == major) {
                return home;
            }
        }
        return null;
    }

    static int majorVersionOf(File jdkHome) {
        return JvmSelector.parseMajor(JvmDescriptor.describe(jdkHome.getAbsolutePath()).getVersion());
    }

    static List<File> discoverJdksWithDistinctMajors(int minimumCount) throws Exception {
        List<File> jdks = ControlPanelTestSupport.discoverValidJdkHomes();
        jdks.sort(Comparator.comparingInt(JvmSelectionTestSupport::majorVersionOf));
        List<File> distinct = new ArrayList<>();
        int lastMajor = -1;
        for (File jdk : jdks) {
            int major = majorVersionOf(jdk);
            if (major != lastMajor) {
                distinct.add(jdk);
                lastMajor = major;
            }
        }
        return distinct.size() >= minimumCount ? distinct : jdks;
    }

    static URL materializeResourceJnlp(String classpathResource) throws IOException {
        URL resource = JvmSelectionTestSupport.class.getResource(classpathResource);
        if (resource == null) {
            throw new IOException("Missing test resource " + classpathResource);
        }
        Path tempDir = Files.createTempDirectory("itw-jnlp-");
        tempDir.toFile().deleteOnExit();
        String fileName = classpathResource.substring(classpathResource.lastIndexOf('/') + 1);
        Path target = tempDir.resolve(fileName);
        try (InputStream in = resource.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toUri().toURL();
    }

    static JNLPFile parseMaterializedJnlp(String classpathResource) throws Exception {
        URL url = materializeResourceJnlp(classpathResource);
        try (InputStream in = new FileInputStream(new File(url.toURI()))) {
            return new JNLPFile(in, new ParserSettings());
        }
    }

    static String requestedJreVersionFromJnlpResource(String classpathResource) throws Exception {
        JNLPFile file = parseMaterializedJnlp(classpathResource);
        if (file.getResources().getJREs().length == 0) {
            throw new ParseException("No j2se/java element in " + classpathResource);
        }
        return file.getResources().getJREs()[0].getVersion().toString();
    }

    static DeploymentConfiguration configurationWithKnownJvms(List<File> jdks, JdkMatchStrategy strategy) throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.beginEditorSession();
        config.load();
        List<String> homes = new ArrayList<>();
        for (File jdk : jdks) {
            homes.add(jdk.getAbsolutePath());
        }
        KnownJvmStore.setKnownJvmHomes(config, homes);
        KnownJvmStore.setMatchStrategy(config, strategy);
        return config;
    }

    static void selectMatchStrategy(FrameFixture window, String label) {
        window.comboBox("jdkMatchStrategyCombo").selectItem(label);
    }

    static boolean atLeastTwoDistinctJdkMajorsAvailable() throws Exception {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && discoverJdksWithDistinctMajors(2).size() >= 2;
    }

    static boolean hasJava11AndHigherJdks() throws Exception {
        if (!ControlPanelJvmSelectionIT.displayAvailable()) {
            return false;
        }
        boolean has11 = findJdkHomeWithMajor(11) != null;
        boolean hasHigher = false;
        for (File home : ControlPanelTestSupport.discoverValidJdkHomes()) {
            if (majorVersionOf(home) > 11) {
                hasHigher = true;
                break;
            }
        }
        return has11 && hasHigher;
    }

    static Properties loadDeploymentProperties() throws Exception {
        return ControlPanelTestSupport.loadDeploymentProperties();
    }
}
