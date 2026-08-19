// Copyright (C) 2001-2003 Jon A. Maxwell (JAM)
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp.runtime;

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ProxySelector;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.AllPermission;
import java.security.KeyStore;
import java.security.Policy;
import java.security.Security;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import javax.jnlp.ServiceManager;
import javax.naming.ConfigurationException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.text.html.parser.ParserDelegator;

import net.sourceforge.jnlp.DefaultLaunchHandler;
import net.sourceforge.jnlp.GuiLaunchHandler;
import net.sourceforge.jnlp.LaunchHandler;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.browser.BrowserAwareProxySelector;
import net.sourceforge.jnlp.cache.CacheLRUWrapper;
import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.cache.DefaultDownloadIndicator;
import net.sourceforge.jnlp.cache.DownloadIndicator;
import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.security.HttpClientProvider;
import net.sourceforge.jnlp.security.ItwSslSocketFactory;
import net.sourceforge.jnlp.security.ItwTls;
import net.sourceforge.jnlp.security.JNLPAuthenticator;
import net.sourceforge.jnlp.security.KeyStores;
import net.sourceforge.jnlp.security.SecurityDialogMessageHandler;
import net.sourceforge.jnlp.security.SecurityUtil;
import net.sourceforge.jnlp.services.XServiceManagerStub;
import net.sourceforge.jnlp.util.BasicExceptionDialog;
import net.sourceforge.jnlp.util.FileUtils;
import net.sourceforge.jnlp.util.JvmArgumentPolicy;
import net.sourceforge.jnlp.util.NetxRunningDetailsRegistry;
import net.sourceforge.jnlp.util.JnlpLockMetadata;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.logging.JavaConsole;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.logging.LogConfig;
import net.sourceforge.jnlp.util.JavaVersionUtils;

/**
 * <p>
 * Configure and access the runtime environment.  This class
 * stores global jnlp properties such as default download
 * indicators, the install/base directory, the default resource
 * update policy, etc.  Some settings, such as the base directory,
 * cannot be changed once the runtime has been initialized.
 * </p>
 * <p>
 * The JNLP runtime can be locked to prevent further changes to
 * the runtime environment except by a specified class.  If set,
 * only instances of the <i>exit class</i> can exit the JVM or
 * change the JNLP runtime settings once the runtime has been
 * initialized.
 * </p>
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.19 $
 */
public class JNLPRuntime {

    /**
     * java-abrt-connector can print out specific application String method, it is good to save visited urls for reproduce purposes.
     * For javaws we can read the destination jnlp from commandline
     * However for plugin (url arrive via pipes). Also for plugin we can not be sure which opened tab/window
     * have caused the crash. Thats why the individual urls are added, not replaced.
     */
    private static String history = "";

    /** the security manager */
    private static JNLPSecurityManager security;

    /** the security policy */
    private static JNLPPolicy policy;

    /** handles all security message to show appropriate security dialogs */
    private static SecurityDialogMessageHandler securityDialogMessageHandler;

    /** a default launch handler */
    private static LaunchHandler handler = null;

    /** default download indicator */
    private static DownloadIndicator indicator = null;

    /** update policy that controls when to check for updates */
    private static UpdatePolicy updatePolicy = UpdatePolicy.ALWAYS;

    /** whether initialized */
    private static boolean initialized = false;

    /** SSL context wired with ITW's VariableX509TrustManager chain (set in setSSL); used by the HTTP clients. */
    private static volatile SSLContext ITW_SSL_CONTEXT;

    public static SSLContext getSslContext() {
        SSLContext c = ITW_SSL_CONTEXT;
        if (c != null) {
            return c;
        }
        try {
            return SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to obtain a default SSL context", e);
        }
    }

    /** whether netx is in command-line mode (headless) */
    private static boolean headless = false;
    private static boolean headlessChecked = false;
    /** whether netx answers security Y/N prompts from the CLI with a single key */
    private static volatile boolean cliMode = false;

    /** whether we'll be checking for jar signing */
    private static boolean verify = true;

    /** whether the runtime uses security */
    private static boolean securityEnabled = true;

    /** whether debug mode is on */
    private static boolean debug = false;

    /** whether trace mode is on (more verbose than debug; favicon diagnostics) */
    private static boolean trace = false;

    /**
     * whether plugin debug mode is on
     */
    private static Boolean pluginDebug = null;

    /** mutex to wait on, for initialization */
    public static Object initMutex = new Object();

    /** set to true if this is a webstart application. */
    private static boolean isWebstartApplication;

    /** set to false to indicate another JVM should not be spawned, even if necessary */
    private static boolean forksAllowed = true;

    /** all security dialogs will be consumed and pretented as being verified by user and allowed.*/
    private static boolean trustAll=false;

    /** HTTPS certificate prompts will be accepted without user interaction. Intended for explicit test opt-in. */
    private static boolean autoAcceptHttpsCertificate = false;
    
    /** flag keeping rest of jnlpruntime live that javaws was lunched as -html */
    private static boolean html=false;

    /** all security dialogs will be consumed and we will pretend the Sandbox option was chosen */
    private static boolean trustNone = false;
    
    /** allows 301.302.303.307.308 redirects to be followed when downloading resources*/
    private static boolean allowRedirect = false;;
    
    /** when this is true, ITW will not attempt any inet connections and will work only with what is in cache*/
    private static boolean offlineForced = false;

    private static Boolean onlineDetected = null;

    private static long startupTrackerMoment = 0;

    /** 
     * Header is not checked and so eg
     * <a href="https://en.wikipedia.org/wiki/Gifar">gifar</a> exploit is
     * possible.<br/>
     * However if jar file is a bit corrupted, then it sometimes can work so 
     * this switch can disable the header check.
     * @see <a href="https://en.wikipedia.org/wiki/Gifar">Gifar attack</a>
     */
    private static boolean ignoreHeaders=false;

    /** contains the arguments passed to the jnlp runtime */
    private static List<String> initialArguments;

    /** a lock which is held to indicate that an instance of netx is running */
    private static FileLock fileLock;

    /**
     * Returns whether the JNLP runtime environment has been
     * initialized. Once initialized, some properties such as the
     * base directory cannot be changed. Before
     * @return whether this runtime was already initialilsed
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Initialize the JNLP runtime environment by installing the
     * security manager and security policy, initializing the JNLP
     * standard services, etc.
     * <p>
     * This method should be called from the main AppContext/Thread.
     * </p>
     * <p>
     * This method cannot be called more than once. Once
     * initialized, methods that alter the runtime can only be
     * called by the exit class.
     * </p>
     *
     * @param isApplication is {@code true} if a webstart application is being
     * initialized
     * @throws IllegalStateException if the runtime was previously initialized
     */
    public static void initialize(boolean isApplication) throws IllegalStateException {
        checkInitialized();

        // Install JarFile.close protection as early as possible.
        // This is critical to prevent "zip file closed" errors during classloading.
        JarFileCloseProtection.install();
        JarUrlCacheProtection.install();

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            OutputController.getLogger().log("Unable to set system look and feel");
        }

        if (JavaConsole.canShowOnStartup(isApplication)) {
            JavaConsole.getConsole().showConsoleLater();
        }
        /* exit if there is a fatal exception loading the configuration */
        if (getConfiguration().getLoadingException() != null) {
            if (getConfiguration().getLoadingException() instanceof ConfigurationException){
                // ConfigurationException is thrown only if deployment.config's field
                // deployment.system.config.mandatory is true, and the destination
                //where deployment.system.config points is not readable
                throw new RuntimeException(getConfiguration().getLoadingException());
            }
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL, R("RConfigurationError")+": "+getConfiguration().getLoadingException().getMessage());
        }

        isWebstartApplication = isApplication;

        //Setting the system property for javawebstart's version.
        //The version stored will be the same as java's version.
        System.setProperty("javawebstart.version", "javaws-" +
                System.getProperty("java.version"));

        // Prefer deployment.jvm.ip.type over any user -Djava.net.preferIPv* for in-process launches.
        JvmArgumentPolicy.applyConfiguredIpStackToSystemProperties();

        if (!isHeadless() && indicator == null)
            indicator = new DefaultDownloadIndicator();

        if (handler == null) {
            if (isHeadless()) {
                handler = new DefaultLaunchHandler(OutputController.getLogger());
            } else {
                handler = new GuiLaunchHandler(OutputController.getLogger());
            }
        }

        ServiceManager.setServiceManagerStub(new XServiceManagerStub()); // ignored if we're running under Web Start

        policy = new JNLPPolicy();
        security = new JNLPSecurityManager(); // side effect: create JWindow

        doMainAppContextHacks();

        if (securityEnabled && JavaVersionUtils.isSecurityManagerSupported()) {
            Policy.setPolicy(policy); // do first b/c our SM blocks setPolicy
            try {
                System.setSecurityManager(security);
            } catch (UnsupportedOperationException ex) {
                OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                        "SecurityManager could not be installed on JDK "
                                + JavaVersionUtils.getRunningMajorVersion()
                                + "; only signed applications may launch.");
                OutputController.getLogger().log(OutputController.Level.WARNING_ALL, ex);
            }
        } else if (securityEnabled) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "SecurityManager is not supported on JDK "
                            + JavaVersionUtils.getRunningMajorVersion()
                            + "; only signed applications may launch.");
        }

        securityDialogMessageHandler = startSecurityThreads();

        // wire in custom authenticator for SSL connections
        try {
            SSLSocketFactory sslSocketFactory;
            SSLContext context = SSLContext.getInstance("SSL");
            KeyStore ks = KeyStores.getKeyStore(KeyStores.Level.USER, KeyStores.Type.CLIENT_CERTS).getKs();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            SecurityUtil.initKeyManagerFactory(kmf, ks);
            TrustManager[] trust = new TrustManager[] { getSSLSocketTrustManager() };
            context.init(kmf.getKeyManagers(), trust, null);
            sslSocketFactory = context.getSocketFactory();
            ITW_SSL_CONTEXT = context;

            // Stamp cipher order on every HTTPS socket (Apache downloads + URLConnection).
            ItwSslSocketFactory itwFactory = ItwSslSocketFactory.install(sslSocketFactory);
            HttpsURLConnection.setDefaultSSLSocketFactory(itwFactory);
            ItwTls.warm();
        } catch (Exception e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "Unable to set SSLSocketfactory (may _prevent_ access to sites that should be trusted)! Continuing anyway...");
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }

        // plug in a custom authenticator and proxy selector before the download
        // client is built so Apache HttpClient sees deployment.proxy.* on the
        // first GET (route planner also re-reads ProxySelector.getDefault()).
        Authenticator.setDefault(new JNLPAuthenticator());
        BrowserAwareProxySelector proxySelector = new BrowserAwareProxySelector(getConfiguration());
        proxySelector.initialize();
        ProxySelector.setDefault(proxySelector);

        // Build the download HTTP client now so the first jar GET already uses
        // ItwSslSocketFactory (Apache does not consult HttpsURLConnection's default).
        HttpClientProvider.getDefault();

        // Configure cookie handling for JNLP/WebStart
        initializeCookieHandler();

        // Restrict access to netx classes
        Security.setProperty("package.access", 
                             Security.getProperty("package.access")+",net.sourceforge.jnlp");

        LegacyUrlJarFileCallbackRegistrar.register(CachedJarFileCallback.getInstance());

        initialized = true;

    }

    /**
     * Initialize cookie handling for JNLP/WebStart applications.
     * This enables session cookies (like JSESSIONID) to work properly
     * for authenticated JNLP and JAR downloads.
     * <p>
     * The CookieManager is configured to:
     * - Accept cookies from the original server only (not third-party)
     * - Store cookies in memory (lost on JVM restart)
     * - Share cookies across all download threads (required for session continuity)
     * - Automatically handle Cookie and Set-Cookie headers
     * </p>
     */
    private static void initializeCookieHandler() {
        try {
            // Create a CookieManager with default cookie store and policy
            CookieManager cookieManager = new CookieManager();
            
            // Set cookie policy to accept cookies from original server only
            // This prevents third-party cookie tracking while allowing session cookies
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            
            // Set as default cookie handler for all URLConnections in this JVM
            CookieHandler.setDefault(cookieManager);
        } catch (Exception e) {
            // Log error but don't fail initialization - cookies are nice to have but not critical
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL, 
                "Failed to initialize cookie handler: " + e.getMessage());
            OutputController.getLogger().log(e);
        }
    }

    public static void reloadPolicy() {
        policy.refresh();
    }

    /**
     * Returns a TrustManager ideal for the running VM.
     *
     * @return TrustManager the trust manager to use for verifying https certificates
     */
    private static TrustManager getSSLSocketTrustManager() throws
                                ClassNotFoundException, IllegalAccessException, InstantiationException, InvocationTargetException {

        try {

            Class<?> trustManagerClass;
            Constructor<?> tmCtor;

            if (System.getProperty("java.version").startsWith("1.6")) { // Java 6
                try {
                    trustManagerClass = Class.forName("net.sourceforge.jnlp.security.VariableX509TrustManagerJDK6");
                 } catch (ClassNotFoundException cnfe) {
                     OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "Unable to find class net.sourceforge.jnlp.security.VariableX509TrustManagerJDK6");
                     return null;
                 }
            } else { // Java 7 or more (technically could be <= 1.5 but <= 1.5 is unsupported)
                try {
                    trustManagerClass = Class.forName("net.sourceforge.jnlp.security.VariableX509TrustManagerJDK7");
                 } catch (ClassNotFoundException cnfe) {
                     OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "Unable to find class net.sourceforge.jnlp.security.VariableX509TrustManagerJDK7");
                     return null;
                 }
            }

            Constructor<?>[] tmCtors = trustManagerClass.getDeclaredConstructors();
            tmCtor = tmCtors[0];

            for (Constructor<?> ctor : tmCtors) {
                if (tmCtor.getGenericParameterTypes().length == 0) {
                    tmCtor = ctor;
                    break;
                }
            }

            return (TrustManager) tmCtor.newInstance();
        } catch (RuntimeException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, "Unable to load JDK-specific TrustManager. Was this version of IcedTea-Web compiled with JDK 6 or 7?");
            OutputController.getLogger().log(e);
            throw e;
        }
    }

    /**
     * This must NOT be called form the application ThreadGroup. An application
     * can inject events into its {@link EventQueue} and bypass the security
     * dialogs.
     *
     * @return a {@link SecurityDialogMessageHandler} that can be used to post
     * security messages
     */
    private static SecurityDialogMessageHandler startSecurityThreads() {
        ThreadGroup securityThreadGroup = new ThreadGroup("NetxSecurityThreadGroup");
        SecurityDialogMessageHandler runner = new SecurityDialogMessageHandler();
        Thread securityThread = new Thread(securityThreadGroup, runner, "NetxSecurityThread");
        securityThread.setDaemon(true);
        securityThread.start();
        return runner;
    }

    /**
     * Performs a few hacks that are needed for the main AppContext
     *
     * @see Launcher#doPerApplicationAppContextHacks
     */
    private static void doMainAppContextHacks() {

        /*
         * With OpenJDK6 (but not with 7) a per-AppContext dtd is maintained.
         * This dtd is created by the ParserDelgate. However, the code in
         * HTMLEditorKit (used to render HTML in labels and textpanes) creates
         * the ParserDelegate only if there are no existing ParserDelegates. The
         * result is that all other AppContexts see a null dtd.
         */
        new ParserDelegator();
    }


    public static void setOfflineForced(boolean b) {
        offlineForced = b;
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Forcing of offline set to: " + offlineForced);
    }

    public static boolean isOfflineForced() {
        return offlineForced;
    }

    public static void setOnlineDetected(boolean online) {
        onlineDetected = online;
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, "Detected online set to: " + onlineDetected);
    }

    public static boolean isOnlineDetected() {
        if (onlineDetected == null) {
            //"file" protocol do not do online check
            //sugest online for this case
            return true;
        }
        return onlineDetected;
    }

    public static boolean isOnline() {
        if (isOfflineForced()) {
            return false;
        }
        return isOnlineDetected();
    }

    public static void detectOnline(URL location) {
        if (onlineDetected != null) {
            return;
        }

        JNLPRuntime.setOnlineDetected(isConnectable(location));
    }

    public static boolean isConnectable(URL location) {
        if (location == null) {
            return false;
        }
        if (location.getProtocol().equals("file")) {
            return true;
        }
        String host = location.getHost();
        return host != null && !host.isEmpty();
    }
   
    /**
     * see <a href="https://en.wikipedia.org/wiki/Double-checked_locking#Usage_in_Java">Double-checked locking in Java</a>
     * for cases how not to do lazy initialization
     * and <a href="https://en.wikipedia.org/wiki/Initialization_on_demand_holder_idiom">Initialization on demand holder idiom</a>
     * for ITW approach
     */
    private static class DeploymentConfigurationHolder {

        private static final DeploymentConfiguration INSTANCE = initConfiguration();

        private static DeploymentConfiguration initConfiguration() {
            DeploymentConfiguration config = new DeploymentConfiguration();
            try {
                config.load();
                config.copyTo(System.getProperties());
            } catch (ConfigurationException ex) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, R("RConfigurationError"));
                //mark this exceptionas we can die on it later
                config.setLoadingException(ex);
                //to be sure - we MUST die - http://docs.oracle.com/javase/6/docs/technotes/guides/deployment/deployment-guide/properties.html
            }catch(Exception t){
                //all exceptions are causing InstantiatizationError so this do it much more readble
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, t);
                OutputController.getLogger().log(OutputController.Level.WARNING_ALL, R("RFailingToDefault"));
                if (!JNLPRuntime.isHeadless()){
                    JOptionPane.showMessageDialog(null, R("RFailingToDefault")+"\n"+t.toString());
                }
                //try to survive this unlikely exception
                config.resetToDefaults();
            } finally {
                OutputController.getLogger().startConsumer();
            }
            return config;
        }
    }

    /**
     * Gets the Configuration associated with this runtime
     *
     * @return a {@link DeploymentConfiguration} object that can be queried to
     * find relevant configuration settings
     */
    public static DeploymentConfiguration getConfiguration() {
        return DeploymentConfigurationHolder.INSTANCE;
    }

    /**
     * @return true if a webstart application has been initialized, and false
     * for a plugin applet.
     */
    public static boolean isWebstartApplication() {
        return isWebstartApplication;
    }

    /**
     * @return whether the JNLP client will use any AWT/Swing
     * components.
     */
    public static boolean isHeadless() {
        if (!headless && !headlessChecked) {
            checkHeadless();

        }
        return headless;
    }

    /**
     * @return whether we are verifying code signing.
     */
    public static boolean isVerifying() {
        return verify;
    }

    /**
     * Sets whether the JNLP client will use any AWT/Swing
     * components.  In headless mode, client features that use the
     * AWT are disabled such that the client can be used in
     * headless mode ({@code java.awt.headless=true}).
     *
     * @param enabled true if application do not wont/need gui or X at all
     * @throws IllegalStateException if the runtime was previously initialized
     */
    public static void setHeadless(boolean enabled) {
        checkInitialized();
        headless = enabled;
    }

    /**
     * @return whether security Y/N prompts are answered from the command line
     * with a single key (only {@code y}/{@code Y} means yes, anything else no).
     */
    public static boolean isCliMode() {
        return cliMode;
    }

    /**
     * Enables CLI-style security prompts. A CLI runtime has no GUI interaction,
     * so enabling it also switches off all AWT/Swing usage.
     *
     * @param enabled true to answer security prompts from the command line
     * @throws IllegalStateException if the runtime was previously initialized
     */
    public static void setCliMode(boolean enabled) {
        checkInitialized();
        cliMode = enabled;
        if (enabled) {
            headless = true;
        }
    }
    
    public static void setAllowRedirect(boolean enabled) {
        checkInitialized();
        allowRedirect = enabled;
    }

    public static boolean isAllowRedirect() {
        return allowRedirect;
    }
    

    /**
     * Sets whether we will verify code signing.
     *
     * @param enabled true if app should verify signatures
     * @throws IllegalStateException if the runtime was previously initialized
     */
    public static void setVerify(boolean enabled) {
        checkInitialized();
        verify = enabled;
    }

    /**
     * Returns whether the secure runtime environment is enabled.
     * @return true if security manager is created
     */
    public static boolean isSecurityEnabled() {
        return securityEnabled;
    }

    /**
     * Sets whether to enable the secure runtime environment.
     * Disabling security can increase performance for some
     * applications, and can be used to use netx with other code
     * that uses its own security manager or policy.
     * <p>
     * Disabling security is not recommended and should only be
     * used if the JNLP files opened are trusted. This method can
     * only be called before initalizing the runtime.
     * </p>
     *
     * @param enabled whether security should be enabled
     * @throws IllegalStateException if the runtime is already initialized
     */
    public static void setSecurityEnabled(boolean enabled) {
        checkInitialized();
        securityEnabled = enabled;
    }

    /**
     *
     * @return the {@link SecurityDialogMessageHandler} that should be used to
     * post security dialog messages
     */
    public static SecurityDialogMessageHandler getSecurityDialogHandler() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new AllPermission());
        }
        return securityDialogMessageHandler;
    }

    /**
     * Set a class that can exit the JVM; if not set then any class
     * can exit the JVM.
     *
     * @param exitClass a class that can exit the JVM
     * @throws IllegalStateException if caller is not the exit class
     */
    public static void setExitClass(Class<?> exitClass) {
        checkExitClass();
        security.setExitClass(exitClass);
    }

    /**
     * Disables applets from calling exit.
     *
     * Once disabled, exit cannot be re-enabled for the duration of the JVM instance
     */
    public static void disableExit() {
        security.disableExit();
    }

    /**
     * @return the current Application, or null if none can be
     * determined.
     */
    public static ApplicationInstance getApplication() {
        return security.getApplication();
    }

    /**
     * @return whether debug statements for the JNLP client code
     * should be printed.
     */
    public static boolean isDebug() {
        return isSetDebug() ||  isPluginDebug() || LogConfig.getLogConfig().isEnableLogging();
    }

     public static boolean isSetDebug() {
        return debug;
    }

    /**
     * @return whether trace-level diagnostics should be printed.
     */
    public static boolean isTrace() {
        return trace || Boolean.getBoolean("icedtea.trace");
    }

    public static boolean isSetTrace() {
        return trace;
    }

    /**
     * Sets whether debug statements for the JNLP client code
     * should be printed to the standard output.
     *
     * @param enabled set to true if you need full debug output
     * @throws IllegalStateException if caller is not the exit class
     */
    public static void setDebug(boolean enabled) {
        checkExitClass();
        debug = enabled;
    }

    /**
     * Sets whether trace-level diagnostics should be printed.
     *
     * @param enabled set to true for trace output (e.g. favicon download details)
     * @throws IllegalStateException if caller is not the exit class
     */
    public static void setTrace(boolean enabled) {
        checkExitClass();
        trace = enabled;
    }

  
    /**
     * Sets the default update policy.
     *
     * @param policy global update policy of environment
     * @throws IllegalStateException if caller is not the exit class
     */
    public static void setDefaultUpdatePolicy(UpdatePolicy policy) {
        checkExitClass();
        updatePolicy = policy;
    }

    /**
     * @return the default update policy.
     */
    public static UpdatePolicy getDefaultUpdatePolicy() {
        return updatePolicy;
    }

    /**
     * Sets the default launch handler.
     * @param handler default handler
     */
    public static void setDefaultLaunchHandler(LaunchHandler handler) {
        checkExitClass();
        JNLPRuntime.handler = handler;
    }

    /**
     * Returns the default launch handler.
     * @return default handler
     */
    public static LaunchHandler getDefaultLaunchHandler() {
        return handler;
    }

    /**
     * Sets the default download indicator.
     *
     * @param indicator where to show progress
     * @throws IllegalStateException if caller is not the exit class
     */
    public static void setDefaultDownloadIndicator(DownloadIndicator indicator) {
        checkExitClass();
        JNLPRuntime.indicator = indicator;
    }

    /**
     * @return the default download indicator.
     */
    public static DownloadIndicator getDefaultDownloadIndicator() {
        return indicator;
    }

    public static String getLocalisedTimeStamp(Date timestamp) {
        return DateFormat.getInstance().format(timestamp);
    }

    /**
     * @return {@code true} if the current runtime will fork
     */
    public static boolean getForksAllowed() {
        return forksAllowed;
    }

    public static void setForksAllowed(boolean value) {
        checkInitialized();
        forksAllowed = value;
    }

    /**
     * Throws an exception if called when the runtime is already initialized.
     */
    private static void checkInitialized() {
        if (initialized)
            throw new IllegalStateException("JNLPRuntime already initialized.");
    }

    /**
     * Throws an exception if called with security enabled but a caller is not
     * the exit class and the runtime has been initialized.
     */
    private static void checkExitClass() {
        if (securityEnabled && initialized)
            if (!security.isExitClass())
                throw new IllegalStateException("Caller is not the exit class");
    }

    /**
     * Check whether the VM is in headless mode.
     */
    private static void checkHeadless() {
        //if (GraphicsEnvironment.isHeadless()) // jdk1.4+ only
        //    headless = true;
        try {
            if ("true".equalsIgnoreCase(System.getProperty("java.awt.headless"))) {
                headless = true;
            }
            if (!headless) {
                boolean noCheck = Boolean.valueOf(JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.IGNORE_HEADLESS_CHECK));
                if (noCheck) {
                    headless = false;
                    OutputController.getLogger().log(DeploymentConfiguration.IGNORE_HEADLESS_CHECK + " set to " + noCheck + ". Avoding headless check.");
                } else {
                    try {
                        if (GraphicsEnvironment.isHeadless()) {
                            throw new HeadlessException();
                        }
                    } catch (HeadlessException ex) {
                        headless = true;
                        OutputController.getLogger().log(ex);
                        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, Translator.R("HEADLESS_MISSCONFIGURED"));
                    }
                }
            }
        } catch (SecurityException ex) {
        } finally {
            headlessChecked = true;
        }
    }

    /**
     * @return {@code true} if running on Windows
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        return (os != null && os.startsWith("Windows"));
    }

    /**
     * @return {@code true} if running on a Unix or Unix-like system (including
     * Linux and *BSD)
     */
    @Deprecated
    public static boolean isUnix() {
        String sep = System.getProperty("file.separator");
        return (sep != null && sep.equals("/"));
    }

    public static void setInitialArgments(List<String> args) {
        checkInitialized();
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null)
            securityManager.checkPermission(new AllPermission());
        initialArguments = args;
    }

    public static List<String> getInitialArguments() {
        return initialArguments;
    }

    /**
     * Indicate that netx is running by creating the
     * {@link DeploymentConfiguration#KEY_USER_NETX_RUNNING_FILE} and
     * acquiring a shared lock on it
     */
    public synchronized static void markNetxRunning() {
        markNetxRunning(null);
    }

    public synchronized static void markNetxRunning(net.sourceforge.jnlp.JNLPFile jnlpFile) {
        if (fileLock != null) {
            if (jnlpFile != null) {
                NetxRunningDetailsRegistry.registerProcess(jnlpFile);
            }
            registerCacheRunningApp(jnlpFile);
            return;
        }
        try {
            String message = "This file is used to check if netx is running";

            File netxRunningFile = PathsAndFiles.MAIN_LOCK.getFile();
            if (!netxRunningFile.exists()) {
                FileUtils.createParentDir(netxRunningFile);
                FileUtils.createRestrictedFile(netxRunningFile, true);
                try (FileOutputStream fos = new FileOutputStream(netxRunningFile)) {
                    fos.write(message.getBytes());
                }
            }

            FileInputStream is = new FileInputStream(netxRunningFile);
            FileChannel channel = is.getChannel();
            fileLock = channel.lock(0, 1, true);
            if (!fileLock.isShared()){ // We know shared locks aren't offered on this system.
                FileLock temp = null;
                for (long pos = 1; temp == null && pos < Long.MAX_VALUE - 1; pos++){
                    temp = channel.tryLock(pos, 1, false); // No point in requesting for shared lock.
                }
                fileLock.release(); // We can release now, since we hold another lock.
                fileLock = temp; // Keep the new lock so we can release later.
            }
            
            if (fileLock != null && fileLock.isShared()) {
                OutputController.getLogger().log("Acquired shared lock on " +
                            netxRunningFile.toString() + " to indicate javaws is running");
            }

            if (jnlpFile != null) {
                NetxRunningDetailsRegistry.registerProcess(jnlpFile);
            } else {
                NetxRunningDetailsRegistry.registerProcess(JnlpRunningProcessSupport.currentPid());
            }
            registerCacheRunningApp(jnlpFile);
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread("JNLPRuntimeShutdownHookThread") {
            @Override
            public void run() {
                markNetxStopped();
                CacheUtil.cleanCacheOnShutdown();
            }
        });
    }

    /**
     * Settings, policy editor, and {@code -Xclearcache} call
     * {@link #markNetxRunning()} with no file. Those must not take a
     * {@code running_app} lease — that table is JNLP apps that own cache files.
     */
    static boolean shouldRegisterCacheRunningApp(net.sourceforge.jnlp.JNLPFile jnlpFile) {
        return jnlpFile != null;
    }

    private static void registerCacheRunningApp(net.sourceforge.jnlp.JNLPFile jnlpFile) {
        if (!shouldRegisterCacheRunningApp(jnlpFile)) {
            return;
        }
        int pid = JnlpRunningProcessSupport.currentPid();
        if (pid <= 0) {
            return;
        }
        String jnlpPath = JnlpLockMetadata.extractJnlpPath(jnlpFile);
        String start = null;
        try {
            java.time.Instant instant = java.lang.ProcessHandle.current().info().startInstant().orElse(null);
            if (instant != null) {
                start = instant.toString();
            }
        } catch (Exception ignored) {
        }
        try {
            CacheLRUWrapper.getInstance().registerRunningApp(pid, jnlpPath, start);
        } catch (Exception e) {
            OutputController.getLogger().log(e);
        }
    }

    /**
     * Indicate that netx is stopped by releasing the shared lock on
     * {@link DeploymentConfiguration#KEY_USER_NETX_RUNNING_FILE}.
     */
    private static void markNetxStopped() {
        int pid = JnlpRunningProcessSupport.currentPid();
        NetxRunningDetailsRegistry.unregisterProcess(pid);
        try {
            CacheLRUWrapper.getInstance().unregisterRunningApp(pid);
        } catch (Exception e) {
            OutputController.getLogger().log(e);
        }
        if (fileLock == null) {
            return;
        }
        try {
            fileLock.release();
            fileLock.channel().close();
            fileLock = null;
            OutputController.getLogger().log("Release shared lock on " + PathsAndFiles.MAIN_LOCK.getFullPath());
        } catch (IOException e) {
            OutputController.getLogger().log(e);
        }
    }

    public static void setHtml(boolean html) {
        JNLPRuntime.html = html;
    }

    public static boolean isHtml() {
        return html;
    }

    public static void setTrustAll(boolean b) {
        trustAll=b;
    }

    public static boolean isTrustAll() {
        return trustAll;
    }

    public static void setAutoAcceptHttpsCertificate(boolean b) {
        autoAcceptHttpsCertificate = b;
    }

    public static boolean isAutoAcceptHttpsCertificate() {
        return autoAcceptHttpsCertificate;
    }

    public static void setTrustNone(final boolean b) {
        trustNone = b;
    }

    public static boolean isTrustNone() {
        return trustNone;
    }

    public static boolean isIgnoreHeaders() {
        return ignoreHeaders;
    }

    public static void setIgnoreHeaders(boolean ignoreHeaders) {
        JNLPRuntime.ignoreHeaders = ignoreHeaders;
    }

    // may only be called from Boot
    public static void initStartupTracker() {
        startupTrackerMoment = System.currentTimeMillis();
    }

    public static void addStartupTrackingEntry(String message) {
        if (startupTrackerMoment > 0) {
            long time = (System.currentTimeMillis() - startupTrackerMoment)/1000;
            String msg = "Startup tracker: seconds elapsed: [" + time + "], message: [" + message + "]";
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, msg);
        }
    }

    private static boolean isPluginDebug() {
        if (pluginDebug == null) {
            try {
                //there are cases when this itself is not allowed by security manager, and so
                //throws exception. Under some conditions it can couse deadlock
                pluginDebug = System.getenv().containsKey("ICEDTEAPLUGIN_DEBUG");
            } catch (Exception ex) {
                pluginDebug = false;
                OutputController.getLogger().log(ex);
            }
        }
        return pluginDebug;
    }

    public static void exit(int i) {
        try {
            OutputController.getLogger().close();
            while (BasicExceptionDialog.areShown()){
                Thread.sleep(100);
            }
        } catch (Exception ex) {
            //to late
        }
        System.exit(i);
    }


    public static void saveHistory(String documentBase) {
        JNLPRuntime.history += " " + documentBase + " ";
    }

    /**
     * Used by java-abrt-connector via reflection
     * @return history
     */
    private static String getHistory() {
        return history;
    }
    
    

}
