package net.sourceforge.jnlp.util.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import net.sourceforge.jnlp.util.logging.headers.MessageWithHeader;
import net.sourceforge.jnlp.util.logging.headers.ObservableMessagesProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ConsoleOutputPanePlayPauseTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void playAndPauseToggleLiveRefreshAndButtonEnablement() {
        ConsoleOutputPane pane = new ConsoleOutputPane(new EmptyProvider());
        assertTrue(pane.isAutoRefreshLive());
        assertFalse(pane.isPlayEnabled());
        assertTrue(pane.isPauseEnabled());

        pane.setAutoRefreshLive(false);
        assertFalse(pane.isAutoRefreshLive());
        assertTrue(pane.isPlayEnabled());
        assertFalse(pane.isPauseEnabled());

        pane.setAutoRefreshLive(true);
        assertTrue(pane.isAutoRefreshLive());
        assertFalse(pane.isPlayEnabled());
        assertTrue(pane.isPauseEnabled());
    }

    @Test
    void setAutoRefreshLiveIsIdempotentWhenAlreadyLive() {
        ConsoleOutputPane pane = new ConsoleOutputPane(new EmptyProvider());
        pane.setAutoRefreshLive(true);
        pane.setAutoRefreshLive(true);
        assertTrue(pane.isAutoRefreshLive());
        assertFalse(pane.isPlayEnabled());
        assertTrue(pane.isPauseEnabled());
    }

    @Test
    void playAndPauseStayOnMainChromeWhenDetailsStartHidden() {
        ConsoleOutputPane pane = new ConsoleOutputPane(new EmptyProvider());
        assertTrue(pane.isPlayPauseOnMainPanel());
    }

    private static final class EmptyProvider extends Observable implements ObservableMessagesProvider {
        private final List<MessageWithHeader> data = new ArrayList<>();

        @Override
        public List<MessageWithHeader> getData() {
            return data;
        }

        @Override
        public Observable getObservable() {
            return this;
        }
    }
}
