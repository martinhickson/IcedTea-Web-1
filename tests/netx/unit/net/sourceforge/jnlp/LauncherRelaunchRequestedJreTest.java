package net.sourceforge.jnlp;

import org.junit.Assert;
import org.junit.Test;

public class LauncherRelaunchRequestedJreTest {

    @Test
    public void encodeRequestedJreVersionEscapesMinimumModifier() {
        Assert.assertEquals("18%2B", JNLPFile.encodeRequestedJreVersionForRelaunch("18+"));
    }

    @Test
    public void decodeRequestedJreVersionRestoresMinimumModifier() {
        Assert.assertEquals("18+", Launcher.decodeRequestedJreVersionForRelaunch("18%2B"));
        Assert.assertEquals("18", Launcher.decodeRequestedJreVersionForRelaunch("18"));
    }
}
