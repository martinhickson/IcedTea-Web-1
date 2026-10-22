package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Framebuffer checks of the installed release's Running applications memory bars.
 * The oracle is jcmd and ps, not the product parser.
 */
@EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
class ControlPanelReleaseMemoryIT {

    private static final Pattern GENERATION_USED = Pattern.compile(
            "total\\s+(\\d+)K,\\s+used\\s+(\\d+)K", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZHEAP_USED = Pattern.compile(
            "ZHeap\\s+used\\s+(\\d+)([KMG])", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAR_USED = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?) MB(?: of |$)");
    private static final String JCMD = "/usr/lib/jvm/java-17-openjdk-amd64/bin/jcmd";

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.resetCacheDir();
        robot = BasicRobot.robotWithCurrentAwtHierarchy();
        controlPanel = ControlPanelTestSupport.launchControlPanel();
        window = new FrameFixture(robot, controlPanel);
        window.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        JnlpLaunchTestSupport.stopLaunchedProcesses();
        if (window != null) {
            window.cleanUp();
        }
        if (robot != null) {
            robot.cleanUp();
        }
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
    }

    @Test
    void controlPanelClassesComeFromTheInstalledRelease() {
        String location = ControlPanel.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertThat(location).contains("/opt/icedtea-web/lib/icedtea-web-uber.jar");
        assertThat(JnlpLaunchTestSupport.javawsBin()).isEqualTo("/usr/bin/javaws");
    }

    @Test
    @Timeout(180)
    void serialHeapUsedMatchesTheSumOfGenerations() throws Exception {
        RunningProcess running = launch("java17-app", "-XX:+UseSerialGC");
        String flags = jcmd(running.getPid(), "VM.flags");
        assertThat(flags).as("the launched JVM is Serial").contains("UseSerialGC");

        Bars bars = waitForBars(running.getPid());
        String heapInfo = jcmd(running.getPid(), "GC.heap_info");
        long oracleUsed = sumGenerationUsed(heapInfo);
        long productUsed = usedBytes(bars.heap.getString());

        assertThat(oracleUsed).as("jcmd heap_info\n" + heapInfo).isGreaterThan(256L * 1024L);
        assertThat(productUsed)
                .as("heap bar '" + bars.heap.getString() + "' vs jcmd used " + oracleUsed + "\n" + heapInfo)
                .isGreaterThanOrEqualTo(oracleUsed / 2);
        assertThat(labelTexts(running.getPid())).contains("Heap", "Memory");
        assertThat(bars.memory.getString()).doesNotContain("Unavailable");
        assertMemoryPercentUsesRam(running.getPid(), bars.memory);
    }

    @Test
    @Timeout(180)
    void zgcHeapUsedMatchesHeapInfo() throws Exception {
        RunningProcess running = launch("java17-app", "-XX:+UseZGC", "-Xms32m", "-Xmx64m");
        String flags = jcmd(running.getPid(), "VM.flags");
        assertThat(flags).as("the launched JVM is ZGC").contains("UseZGC");

        Bars bars = waitForBars(running.getPid());
        String heapInfo = jcmd(running.getPid(), "GC.heap_info");
        long oracleUsed = zheapUsed(heapInfo);
        long productUsed = usedBytes(bars.heap.getString());

        assertThat(oracleUsed).as("jcmd heap_info\n" + heapInfo).isGreaterThan(0L);
        assertThat(productUsed)
                .as("heap bar '" + bars.heap.getString() + "' vs ZHeap used " + oracleUsed + "\n" + heapInfo)
                .isGreaterThanOrEqualTo(oracleUsed / 2);
    }

    @Test
    @Timeout(240)
    void twoRunningAppsUseThePluralStatus() throws Exception {
        Assumptions.assumeTrue(JnlpLaunchTestSupport.sampleBuilt("java17-app"));
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
        Process first = JnlpLaunchTestSupport.launchHeldApp("java17-app");
        Process second = JnlpLaunchTestSupport.launchHeldApp("java17-app");
        List<RunningProcess> listed = waitForListedApps(2, 60_000);
        assertThat(listed).as("two launches of the same application stay on the running list").hasSize(2);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        ControlPanelTestSupport.openRunningApps(window);
        robot.waitForIdle();
        assertThat(window.label("runningAppsStatusLabel").target().getText())
                .isEqualTo("2 applications running.");
    }

    private static List<RunningProcess> waitForListedApps(int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<RunningProcess> listed = JnlpRunningProcessSupport.listRunningJnlpProcesses();
        while (System.currentTimeMillis() < deadline && listed.size() < count) {
            Thread.sleep(500);
            listed = JnlpRunningProcessSupport.listRunningJnlpProcesses();
        }
        return listed;
    }

    private RunningProcess launch(String sample, String... vmArgs) throws Exception {
        Assumptions.assumeTrue(JnlpLaunchTestSupport.javawsAvailable());
        Assumptions.assumeTrue(JnlpLaunchTestSupport.sampleBuilt(sample));
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
        JnlpLaunchTestSupport.launchHeldAppWithVmArgs(sample, vmArgs);
        String titleFragment = sample.contains("21") ? "Java 21 bytecode" : "Java 17 bytecode";
        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp(titleFragment, 60_000);
        assertThat(running).isNotNull();
        ControlPanelTestSupport.openRunningApps(window);
        window.panel("runningAppRow-" + running.getPid()).requireVisible();
        return running;
    }

    private Bars waitForBars(int pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        Bars bars = readBars(pid);
        while (System.currentTimeMillis() < deadline
                && (bars.heap == null || bars.memory == null
                || bars.heap.getString().startsWith("Loading")
                || bars.memory.getString().startsWith("Loading"))) {
            Thread.sleep(200);
            bars = readBars(pid);
        }
        assertThat(bars.heap).as("heap bar").isNotNull();
        assertThat(bars.memory).as("memory bar").isNotNull();
        assertThat(bars.heap.getString()).doesNotStartWith("Loading");
        assertThat(bars.memory.getString()).doesNotStartWith("Loading");
        return bars;
    }

    private Bars readBars(int pid) {
        JPanel row = window.panel("runningAppRow-" + pid).target();
        List<JProgressBar> found = new ArrayList<>();
        collectBars(row, found);
        Bars bars = new Bars();
        if (!found.isEmpty()) {
            bars.heap = found.get(0);
        }
        if (found.size() > 1) {
            bars.memory = found.get(1);
        }
        return bars;
    }

    private static void collectBars(Container container, List<JProgressBar> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof JProgressBar) {
                found.add((JProgressBar) child);
            } else if (child instanceof Container) {
                collectBars((Container) child, found);
            }
        }
    }

    private List<String> labelTexts(int pid) {
        JPanel row = window.panel("runningAppRow-" + pid).target();
        List<String> texts = new ArrayList<>();
        collectLabels(row, texts);
        return texts;
    }

    private static void collectLabels(Container container, List<String> texts) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                texts.add(((JLabel) child).getText());
            } else if (child instanceof Container) {
                collectLabels((Container) child, texts);
            }
        }
    }

    private static void assertMemoryPercentUsesRam(int pid, JProgressBar memoryBar) throws Exception {
        long rss = psRssBytes(pid);
        long ram = meminfoBytes("MemTotal:");
        long swap = meminfoBytes("SwapTotal:");
        assertThat(rss).isGreaterThan(0L);
        assertThat(ram).isGreaterThan(0L);
        int shown = memoryBar.getValue();
        int againstRam = percent(rss, ram);
        int againstRamPlusSwap = percent(rss, ram + swap);
        assertThat(shown)
                .as("memory bar " + shown + " rss " + rss + " ram% " + againstRam + " ram+swap% " + againstRamPlusSwap
                        + " caption " + memoryBar.getString())
                .isBetween(againstRam - 1, againstRam + 1);
    }

    private static int percent(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round((used * 100.0) / total));
    }

    private static long psRssBytes(int pid) throws Exception {
        Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", Integer.toString(pid))
                .redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor(5, TimeUnit.SECONDS);
        return Long.parseLong(text.split("\\s+")[0]) * 1024L;
    }

    private static long meminfoBytes(String prefix) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/meminfo"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix)) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        }
        return 0L;
    }

    private static String jcmd(int pid, String command) throws Exception {
        Process process = new ProcessBuilder(JCMD, Integer.toString(pid), command)
                .redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(15, TimeUnit.SECONDS);
        return text;
    }

    static long sumGenerationUsed(String heapInfo) {
        Matcher matcher = GENERATION_USED.matcher(heapInfo == null ? "" : heapInfo);
        long used = 0L;
        while (matcher.find()) {
            used += Long.parseLong(matcher.group(2)) * 1024L;
        }
        return used;
    }

    static long zheapUsed(String heapInfo) {
        Matcher matcher = ZHEAP_USED.matcher(heapInfo == null ? "" : heapInfo);
        if (!matcher.find()) {
            return 0L;
        }
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        if ("G".equals(unit)) {
            return value * 1024L * 1024L * 1024L;
        }
        if ("M".equals(unit)) {
            return value * 1024L * 1024L;
        }
        return value * 1024L;
    }

    static long usedBytes(String caption) {
        if (caption == null) {
            return 0L;
        }
        Matcher matcher = BAR_USED.matcher(caption.trim());
        if (!matcher.find()) {
            return 0L;
        }
        double megabytes = Double.parseDouble(matcher.group(1));
        return (long) (megabytes * 1024.0 * 1024.0);
    }

    private static final class Bars {
        private JProgressBar heap;
        private JProgressBar memory;
    }
}
