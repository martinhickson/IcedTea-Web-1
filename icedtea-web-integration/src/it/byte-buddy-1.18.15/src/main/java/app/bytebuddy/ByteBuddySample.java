package app.bytebuddy;

import net.bytebuddy.utility.FileSystem;

/**
 * Sample application compiled against byte-buddy 1.18.15.
 * {@code FileSystem.validated(String)} was added in that line and is not on 1.18.8.
 */
public final class ByteBuddySample {

    public static void main(String[] args) throws Exception {
        String path = FileSystem.validated("lib.jar");
        if (!"lib.jar".equals(path)) {
            throw new IllegalStateException("FileSystem.validated returned " + path);
        }
        System.out.println("ITW_LIBRARY_SAMPLE_OK byte-buddy validated=" + path);
    }
}
