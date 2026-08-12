package net.sourceforge.icedteaweb.multijar;

/**
 * Main class of the multi-jar test application. Placed inside the first
 * generated jar by the IT. Loads a class from every jar on the classpath
 * (proving each of the 20 jars downloaded and became loadable) plus a
 * third-party class, then prints the success marker.
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
        System.out.println(MARKER + " loaded=" + loaded + " thirdparty=StringUtils");
    }
}
