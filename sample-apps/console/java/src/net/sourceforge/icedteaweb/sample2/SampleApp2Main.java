package net.sourceforge.icedteaweb.sample2;

/**
 * Console JNLP sample for IcedTea-Web manual testing and output assertion.
 */
public final class SampleApp2Main {

    public static final String SUCCESS_MARKER = "ITW_SAMPLE_APP_2_SUCCESS";

    private SampleApp2Main() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("INFO: Sample App 2 console harness starting");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.vendor=" + System.getProperty("java.vendor"));
        System.out.println(SUCCESS_MARKER + " console jdk="
                + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor"));
        System.out.flush();

        int holdSeconds = Integer.getInteger("itw.sample.hold.seconds", 3);
        if (holdSeconds > 0) {
            Thread.sleep(holdSeconds * 1000L);
        }
    }
}
