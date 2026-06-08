package net.sourceforge.jnlp.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;

import net.sourceforge.jnlp.util.JavaVersionUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Registers {@link CachedJarFileCallback} with {@code URLJarFile.setCallBack} on JDK 23 and earlier.
 */
final class LegacyUrlJarFileCallbackRegistrar {

    private static final OutputController LOGGER = OutputController.getLogger();

    private LegacyUrlJarFileCallbackRegistrar() {
    }

    static void register(CachedJarFileCallback callback) {
        if (JavaVersionUtils.getRunningMajorVersion() >= JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            return;
        }
        try {
            Class<?> callbackInterface = Class.forName("sun.net.www.protocol.jar.URLJarFileCallBack");
            Class<?> urlJarFileClass = Class.forName("sun.net.www.protocol.jar.URLJarFile");

            Object proxy = Proxy.newProxyInstance(
                    callbackInterface.getClassLoader(),
                    new Class<?>[] { callbackInterface },
                    (p, method, args) -> {
                        if ("retrieve".equals(method.getName()) && args != null && args.length == 1) {
                            return callback.retrieve((URL) args[0]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });

            Method setCallBack = urlJarFileClass.getMethod("setCallBack", callbackInterface);
            setCallBack.invoke(null, proxy);
            LOGGER.log(OutputController.Level.MESSAGE_DEBUG,
                    "Registered CachedJarFileCallback via URLJarFile.setCallBack");
        } catch (ReflectiveOperationException e) {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                    "Unable to register URLJarFileCallBack: " + e.getMessage());
            LOGGER.log(OutputController.Level.ERROR_DEBUG, e);
        }
    }
}
