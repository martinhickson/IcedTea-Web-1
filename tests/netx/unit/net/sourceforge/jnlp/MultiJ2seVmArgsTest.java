package net.sourceforge.jnlp;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * Multiple {@code <j2se>} entries are alternatives. Relaunch VM args must come from
 * one selected entry only — duplicates caused wrong JDK selection / autodetect.
 */
public class MultiJ2seVmArgsTest extends NoStdOutErrTest {

    private static final String MULTI_J2SE_JNLP =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jnlp spec=\"1.0+\" codebase=\"http://example.com/\">\n"
            + "  <information><title>t</title><vendor>v</vendor></information>\n"
            + "  <resources>\n"
            + "    <j2se version=\"11\" max-heap-size=\"2100M\"/>\n"
            + "    <j2se version=\"17\" max-heap-size=\"2100M\"/>\n"
            + "    <jar href=\"app.jar\" main=\"true\"/>\n"
            + "  </resources>\n"
            + "  <application-desc main-class=\"Main\"/>\n"
            + "</jnlp>\n";

    @Test
    public void getNewVMArgsEmitsSingleRequestedJreAndHeap() throws Exception {
        JNLPFile file = new JNLPFile(
                new ByteArrayInputStream(MULTI_J2SE_JNLP.getBytes(StandardCharsets.UTF_8)),
                new URL("http://example.com/"),
                new ParserSettings(false, false, false));

        List<String> args = file.getNewVMArgs();
        int requestedCount = 0;
        int heapCount = 0;
        boolean saw11 = false;
        boolean saw17 = false;
        for (String arg : args) {
            if (arg.startsWith("-Dicedtea-web.relaunch.requestedJre=")) {
                requestedCount++;
                if (arg.endsWith("=11")) {
                    saw11 = true;
                }
                if (arg.endsWith("=17")) {
                    saw17 = true;
                }
            }
            if (arg.startsWith("-Xmx")) {
                heapCount++;
            }
        }
        Assert.assertTrue("at most one requestedJre for multi-j2se alternatives", requestedCount <= 1);
        Assert.assertFalse("must not emit both requestedJre=11 and =17", saw11 && saw17);
        Assert.assertEquals("exactly one -Xmx for multi-j2se alternatives", 1, heapCount);
        Assert.assertTrue(args.contains("-Xmx2100M"));
    }
}
