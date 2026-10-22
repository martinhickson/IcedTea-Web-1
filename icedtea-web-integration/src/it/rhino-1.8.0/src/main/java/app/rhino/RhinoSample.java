package app.rhino;

import org.mozilla.javascript.Context;

/**
 * Sample application compiled against Rhino 1.8.0.
 * {@code Context.setInterpretedMode} was added in 1.8.0 and is not on 1.7.15.1.
 */
public final class RhinoSample {

    public static void main(String[] args) {
        Context cx = Context.enter();
        try {
            cx.setInterpretedMode(true);
            if (!cx.isInterpretedMode()) {
                throw new IllegalStateException("Rhino 1.8.0 did not stay in interpreted mode");
            }
            System.out.println("ITW_LIBRARY_SAMPLE_OK rhino interpreted=true");
        } finally {
            Context.exit();
        }
    }
}
