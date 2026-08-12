package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code --cli} security-prompt contract: a single keystroke, no Enter
 * required, and only {@code y}/{@code Y} is Yes — anything else (including
 * end-of-stream) is No.
 */
public class CliModePromptTest {

    private InputStream originalIn;

    @BeforeEach
    void saveIn() {
        originalIn = System.in;
    }

    @AfterEach
    void restoreIn() {
        System.setIn(originalIn);
    }

    @Test
    public void lowerYAnswersYes() {
        System.setIn(new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)));
        assertTrue(SecurityDialogMessageHandler.readCliYesOrNo());
    }

    @Test
    public void upperYAnswersYes() {
        System.setIn(new ByteArrayInputStream("Y".getBytes(StandardCharsets.UTF_8)));
        assertTrue(SecurityDialogMessageHandler.readCliYesOrNo());
    }

    @Test
    public void nAnswersNo() {
        System.setIn(new ByteArrayInputStream("n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecurityDialogMessageHandler.readCliYesOrNo());
    }

    @Test
    public void anyOtherKeyAnswersNo() {
        System.setIn(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecurityDialogMessageHandler.readCliYesOrNo());
    }

    @Test
    public void emptyInputAnswersNo() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        assertFalse(SecurityDialogMessageHandler.readCliYesOrNo());
    }
}
