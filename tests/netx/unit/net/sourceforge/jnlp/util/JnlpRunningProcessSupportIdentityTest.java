package net.sourceforge.jnlp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class JnlpRunningProcessSupportIdentityTest {

    @Test
    public void matchesCurrentProcessWithCorrectStart() {
        long pid = java.lang.ProcessHandle.current().pid();
        String start = java.lang.ProcessHandle.current().info().startInstant()
                .map(java.time.Instant::toString).orElse(null);
        assertTrue(JnlpRunningProcessSupport.isSameProcess((int) pid, start));
    }

    @Test
    public void rejectsWrongStartInstant() {
        long pid = java.lang.ProcessHandle.current().pid();
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, "2000-01-01T00:00:00Z"));
    }

    @Test
    public void rejectsNullRecordedStart() {
        long pid = java.lang.ProcessHandle.current().pid();
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, null));
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, "  "));
    }

    @Test
    public void rejectsNonexistentPid() {
        // a PID far beyond typical ranges is not a live process → startInstant absent → false
        assertFalse(JnlpRunningProcessSupport.isSameProcess(9999999, "2000-01-01T00:00:00Z"));
    }
}
