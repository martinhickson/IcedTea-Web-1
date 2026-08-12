package net.sourceforge.icedteaweb.multijar;

/**
 * Main class of the multi-jar test application. Placed inside the first
 * generated jar by the IT. Loads a class from every jar on the classpath
 * (proving each of the 20 jars downloaded and became loadable) plus a
 * third-party class, then prints the success marker. When
 * {@code itw.multijar.marker} is set, the marker is also written to that file
 * (the packaged launcher's stdout redirect is unreliable across platforms).
 */
public final class MultijarMain {

    public static final int JAR_COUNT = 20;
    public static final String MARKER = "ITW_MULTIJAR_SUCCESS";

    private MultijarMain() {
    }

    public static void main(String[] args) throws Exception {
        int loaded = 0;
        for (int i = 0; i < JAR_COUNT; i++) {
            Class.forName("net.sourceforge.icedteaweb.multijar.jars.Jar_" + i, false,
                    MultijarMain.class.getClassLoader());
            loaded++;
        }
        Class.forName("org.apache.commons.lang3.StringUtils", false,
                MultijarMain.class.getClassLoader());
        String marker = MARKER + " loaded=" + loaded + " thirdparty=StringUtils";
        System.out.println(marker);
        // best-effort marker file (denied in the sandbox -> ignored)
        try {
            String markerFile = System.getProperty("itw.multijar.marker");
            if (markerFile != null && !markerFile.isEmpty()) {
                java.nio.file.Files.write(java.nio.file.Paths.get(markerFile),
                        (marker + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // sandboxed app: property/file permission not granted
        }
    }
}
