package net.sourceforge.icedteaweb.autodetect.it;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around {@code scripts/click-dialog-button.ps1} for cross-process
 * Swing {@code JOptionPane} dialogs shown by {@code javaws.exe}.
 */
final class WindowsUiAutomation {

    private WindowsUiAutomation() {
    }

    static void clickDialogButton(String windowTitleContains, String buttonName, int timeoutSeconds)
            throws Exception {
        String script = System.getProperty("itw.uia.click.script");
        if (script == null || script.isBlank()) {
            throw new IllegalStateException("itw.uia.click.script system property is required");
        }

        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script);
        command.add("-WindowTitle");
        command.add(windowTitleContains);
        command.add("-ButtonName");
        command.add(buttonName);
        command.add("-TimeoutSeconds");
        command.add(Integer.toString(timeoutSeconds));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(timeoutSeconds + 30L, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("UIAutomation click timed out. Output:\n" + output);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "UIAutomation click failed (exit " + process.exitValue() + "):\n" + output);
        }
    }
}
