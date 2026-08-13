package net.sourceforge.jnlp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class OptionsDefinitionsResourcesTest {

    @Test
    public void documentedOptionsHaveResolvableHelpDescriptions() {
        Set<OptionsDefinitions.OPTIONS> documented = new LinkedHashSet<>();
        documented.addAll(OptionsDefinitions.getJavaWsRuntimeOptions());
        documented.addAll(OptionsDefinitions.getJavaWsControlOptions());
        documented.addAll(OptionsDefinitions.getItwsettingsCommands());
        documented.addAll(OptionsDefinitions.getPolicyEditorOptions());

        for (OptionsDefinitions.OPTIONS option : documented) {
            String description = option.getLocalizedDescription();
            assertFalse(option.name() + " (" + option.option + ") missing resource " + option.decriptionKey
                            + ": " + description,
                    description.contains("Missing Resource:"));
            assertTrue(option.name() + " help text is empty", description.trim().length() > 0);
        }
    }
}
