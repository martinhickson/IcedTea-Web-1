package net.sourceforge.jnlp.util;

import net.sourceforge.jnlp.config.JvmTuningGcType;

public final class JvmTuningCapabilities {

    private static final int G1GC_MIN_MAJOR = 7;
    private static final int ZGC_MIN_MAJOR = 11;
    private static final int SOFT_MAX_MIN_MAJOR = 13;

    private JvmTuningCapabilities() {
    }

    public static int majorVersionOfJvmHome(String homePath) {
        if (homePath == null || homePath.trim().isEmpty()) {
            return 0;
        }
        return JvmSelector.parseMajor(JvmDescriptor.describe(homePath.trim()).getVersion());
    }

    public static boolean supportsGcType(int jdkMajor, JvmTuningGcType gcType) {
        if (gcType == null || gcType == JvmTuningGcType.DEFAULT) {
            return true;
        }
        if (gcType == JvmTuningGcType.G1GC) {
            return jdkMajor >= G1GC_MIN_MAJOR;
        }
        if (gcType == JvmTuningGcType.ZGC) {
            return jdkMajor >= ZGC_MIN_MAJOR;
        }
        return false;
    }

    public static boolean supportsSoftMaxHeap(int jdkMajor) {
        return jdkMajor >= SOFT_MAX_MIN_MAJOR;
    }
}
