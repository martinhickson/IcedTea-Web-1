package net.sourceforge.jnlp.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Objects;

import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Tracks in-memory deployment property edits made during a control-panel session.
 * Changes are logged here and replayed to disk only when Apply commits them.
 */
final class DeploymentConfigurationPendingChanges {

    private final Map<String, String> pending = new LinkedHashMap<>();

    void recordChange(String key, String persistedValue, String newValue) {
        recordChangeReturningChanged(key, persistedValue, newValue);
    }

    boolean recordChangeReturningChanged(String key, String persistedValue, String newValue) {
        if (Objects.equals(persistedValue, newValue)) {
            return pending.remove(key) != null;
        }
        String previousPending = pending.put(key, newValue);
        if (!Objects.equals(previousPending, newValue)) {
            OutputController.getLogger().log("Pending deployment property change: " + key
                    + " (" + describe(persistedValue) + " -> " + describe(newValue) + ")");
        }
        return true;
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int size() {
        return pending.size();
    }

    Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pending));
    }

    void clear() {
        pending.clear();
    }

    void logReplay() {
        if (pending.isEmpty()) {
            OutputController.getLogger().log("Applying pending deployment property changes: none");
            return;
        }
        OutputController.getLogger().log("Applying " + pending.size() + " pending deployment property change(s):");
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            OutputController.getLogger().log("  " + entry.getKey() + " = " + describe(entry.getValue()));
        }
    }

    private static String describe(String value) {
        return value == null ? "<null>" : value;
    }
}
