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

    @Test
    public void shouldForwardRelaunchVmArgKeepsUserDashJProperties() {
        Assert.assertTrue(Launcher.shouldForwardRelaunchVmArg("-Ditw.sample.hold.seconds=70"));
        Assert.assertTrue(Launcher.shouldForwardRelaunchVmArg("-Dmy.app.flag=true"));
        Assert.assertFalse(Launcher.shouldForwardRelaunchVmArg("-Djava.home=/opt/jdk"));
        Assert.assertFalse(Launcher.shouldForwardRelaunchVmArg("-Dicedtea-web.bin.name=javaws"));
        Assert.assertFalse(Launcher.shouldForwardRelaunchVmArg("-Xmx512m"));
        Assert.assertFalse(Launcher.shouldForwardRelaunchVmArg(null));
    }
}
