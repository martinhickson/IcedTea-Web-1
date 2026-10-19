package net.sourceforge.jnlp;

import org.junit.Assert;
import org.junit.Test;

import net.sourceforge.jnlp.cache.UpdatePolicy;

public class UpdateIndicatorTest {

    private static UpdateIndicator.Snapshot snap(boolean cached, String path, long length,
            long fileModified, long catalogModified, long contentLength) {
        return new UpdateIndicator.Snapshot(cached, path, length, fileModified, catalogModified, contentLength);
    }

    private static UpdateIndicator.Snapshot cached() {
        return snap(true, "/cache/stamp.jar", 128L, 1_000L, 2_000L, 128L);
    }

    @Test
    public void matchingSnapshotIsUnchanged() {
        Assert.assertTrue(UpdateIndicator.unchanged(cached(), cached()));
    }

    @Test
    public void missingCacheIsNotUnchanged() {
        UpdateIndicator.Snapshot missing = snap(false, null, 0L, 0L, 0L, 0L);
        Assert.assertFalse(UpdateIndicator.unchanged(missing, cached()));
        Assert.assertFalse(UpdateIndicator.unchanged(cached(), missing));
        Assert.assertFalse(UpdateIndicator.unchanged(null, cached()));
    }

    @Test
    public void differentPathLengthOrTimesIsChanged() {
        UpdateIndicator.Snapshot before = cached();
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar.new", 128L, 1_000L, 2_000L, 128L)));
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar", 129L, 1_000L, 2_000L, 128L)));
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar", 128L, 1_001L, 2_000L, 128L)));
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar", 128L, 1_000L, 2_001L, 128L)));
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar", 128L, 1_000L, 2_000L, 129L)));
        Assert.assertFalse(UpdateIndicator.unchanged(before,
                snap(true, "/cache/stamp.jar", 0L, 1_000L, 2_000L, 0L)));
    }

    @Test
    public void otherCachedJarsSkipOnlyWhenTheIndicatorIsUnchanged() {
        Assert.assertSame(UpdatePolicy.NEVER, UpdateIndicator.policy(true, false, true));
        Assert.assertNull(UpdateIndicator.policy(true, true, true));
        Assert.assertNull(UpdateIndicator.policy(true, false, false));
        Assert.assertSame(UpdatePolicy.FORCE, UpdateIndicator.policy(false, false, true));
    }
}
