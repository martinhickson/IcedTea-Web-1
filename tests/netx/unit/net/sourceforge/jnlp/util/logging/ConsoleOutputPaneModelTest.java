package net.sourceforge.jnlp.util.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import net.sourceforge.jnlp.util.logging.headers.MessageWithHeader;
import net.sourceforge.jnlp.util.logging.headers.ObservableMessagesProvider;
import org.junit.jupiter.api.Test;

class ConsoleOutputPaneModelTest {

    @Test
    void wordWrapDefaultsEnabledSoLongDownloadLinesWrap() {
        ConsoleOutputPaneModel model = new ConsoleOutputPaneModel(new EmptyProvider());
        assertTrue(model.wordWrap, "console word wrap should be on by default");
        String html = model.importList(true, 0);
        assertFalse(html.contains("white-space:nowrap"), html);
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
