package net.sourceforge.icedteaweb.it.apps;

/**
 * Swing GUI sample for java18-app JNLP demos (missing JDK autodetect flow).
 */
public final class Java18GuiSampleJnlpMain {

    public static final String FRAME_TITLE = "ITW Java 18 GUI Sample";

    private Java18GuiSampleJnlpMain() {
    }

    public static void main(String[] args) throws Exception {
        GuiSampleJnlpMain.run(args, FRAME_TITLE);
    }
}
