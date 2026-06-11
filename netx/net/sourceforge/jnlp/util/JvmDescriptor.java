package net.sourceforge.jnlp.util;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.controlpanel.JVMPanel;
import net.sourceforge.jnlp.controlpanel.JVMPanel.JvmValidationResult;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

public final class JvmDescriptor {

    private static final Pattern QUOTED_VERSION = Pattern.compile("\"([^\"]+)\"");

    private final String homePath;
    private final String flavour;
    private final String version;
    private final String displayName;
    private final boolean valid;
    private final JvmValidationResult.STATE validationState;

    public JvmDescriptor(String homePath, String flavour, String version, boolean valid,
            JvmValidationResult.STATE validationState) {
        this.homePath = homePath;
        this.flavour = flavour == null ? "" : flavour;
        this.version = version == null ? "" : version;
        this.valid = valid;
        this.validationState = validationState;
        if (!this.flavour.isEmpty() && !this.version.isEmpty()) {
            this.displayName = this.flavour + " " + this.version;
        } else if (!this.flavour.isEmpty()) {
            this.displayName = this.flavour;
        } else if (!this.version.isEmpty()) {
            this.displayName = this.version;
        } else {
            this.displayName = homePath;
        }
    }

    public String getHomePath() {
        return homePath;
    }

    public String getFlavour() {
        return flavour;
    }

    public String getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isValid() {
        return valid;
    }

    public JvmValidationResult.STATE getValidationState() {
        return validationState;
    }

    public static JvmDescriptor describeCurrentRuntime() {
        String home = System.getProperty("java.home", "").trim();
        String combined = (safeProperty("java.runtime.name") + " "
                + safeProperty("java.runtime.version") + " "
                + safeProperty("java.vm.name") + " "
                + safeProperty("java.vm.version") + " "
                + safeProperty("java.vm.vendor")).toLowerCase(Locale.ROOT);
        String flavour = detectFlavour(combined);
        String version = detectVersion(combined);
        if (version.isEmpty()) {
            version = detectVersionFromPath(home);
        }
        if (version.isEmpty()) {
            version = Integer.toString(JavaVersionUtils.getRunningMajorVersion());
        }
        return new JvmDescriptor(home, flavour, version, true, JvmValidationResult.STATE.VALID_JDK);
    }

    private static String safeProperty(String name) {
        String value = System.getProperty(name);
        return value == null ? "" : value;
    }

    public static JvmDescriptor describe(String homePath) {
        if (homePath == null || homePath.trim().isEmpty()) {
            return new JvmDescriptor("", "", "", false, JvmValidationResult.STATE.EMPTY);
        }
        String normalized = homePath.trim();
        JvmValidationResult validation = JVMPanel.validateJvm(normalized);
        boolean valid = validation.id == JvmValidationResult.STATE.VALID_JDK;
        String combined = (validation.getReportableOutput() == null ? "" : validation.getReportableOutput())
                .toLowerCase(Locale.ROOT);
        String flavour = detectFlavour(combined);
        String version = detectVersion(combined);
        if (version.isEmpty()) {
            version = detectVersionFromPath(normalized);
        }
        return new JvmDescriptor(normalized, flavour, version, valid, validation.id);
    }

    static String detectFlavour(String versionOutput) {
        if (versionOutput == null || versionOutput.isEmpty()) {
            return "";
        }
        if (versionOutput.contains("corretto")) {
            return "Amazon Corretto";
        }
        if (versionOutput.contains("temurin")) {
            return "Eclipse Temurin";
        }
        if (versionOutput.contains("zulu")) {
            return "Azul Zulu";
        }
        if (versionOutput.contains("semeru")) {
            return "IBM Semeru";
        }
        if (versionOutput.contains("graalvm")) {
            return "GraalVM";
        }
        if (versionOutput.contains("sapmachine")) {
            return "SapMachine";
        }
        if (versionOutput.contains("dragonwell")) {
            return "Dragonwell";
        }
        if (versionOutput.contains("microsoft") && versionOutput.contains("openjdk")) {
            return "Microsoft OpenJDK";
        }
        if (versionOutput.contains("java(tm)") || versionOutput.contains("oracle")) {
            return "Oracle JDK";
        }
        if (versionOutput.contains("ibm") || versionOutput.contains("j9")) {
            return "IBM JDK";
        }
        if (versionOutput.contains("gij")) {
            return "GIJ";
        }
        if (versionOutput.contains("openjdk")) {
            return "OpenJDK";
        }
        return "Java";
    }

    static String detectVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isEmpty()) {
            return "";
        }
        Matcher matcher = QUOTED_VERSION.matcher(versionOutput);
        if (matcher.find()) {
            return normalizeVersionToken(matcher.group(1));
        }
        return "";
    }

    private static String detectVersionFromPath(String homePath) {
        String name = new File(homePath).getName().toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("java-?(\\d+)(?:\\.\\d+)?").matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile("jdk-?(\\d+)(?:\\.\\d+)?").matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String normalizeVersionToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("1.")) {
            String[] parts = trimmed.split("[._-]");
            if (parts.length >= 2 && parts[1].matches("\\d+")) {
                return parts[1];
            }
        }
        return trimmed;
    }

    public File javaExecutable() {
        return new File(homePath + File.separator + "bin" + File.separator + "java"
                + (JNLPRuntime.isWindows() ? ".exe" : ""));
    }
}
