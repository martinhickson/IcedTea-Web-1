package net.sourceforge.jnlp.util;

/**
 * Child JVM for {@link ItwLauncherPathsTest#ensureSunSecurityProviderAccessWorksWithoutAddExports()}.
 * Started without {@code --add-exports} so the runtime export can be checked.
 */
public final class SunSecurityProviderAccessProbe {

    private SunSecurityProviderAccessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (ItwLauncherPaths.canAccessSunSecurityProvider()) {
            ItwLauncherPaths.ensureSunSecurityProviderAccess();
            System.out.println("OK already-exported");
            return;
        }
        ItwLauncherPaths.ensureSunSecurityProviderAccess();
        if (!ItwLauncherPaths.canAccessSunSecurityProvider()) {
            System.err.println("FAILED still not exported");
            System.exit(2);
        }
        Class<?> parser = Class.forName("sun.security.provider.PolicyParser");
        parser.getConstructor(boolean.class).newInstance(Boolean.FALSE);
        System.out.println("OK");
    }
}
