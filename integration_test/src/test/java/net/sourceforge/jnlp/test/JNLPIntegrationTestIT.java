package net.sourceforge.jnlp.test;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;


import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.runtime.ApplicationInstance;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full integration test for JNLP application launching.
 * 
 * This test:
 * 1. Uses Arquillian to deploy WAR to WildFly (runs in separate process)
 * 2. Starts an embedded HTTP server (Undertow) in test process to serve the JNLP application
 * 3. Uses IcedTea-Web classes directly (not javaws executable) to launch the JNLP application in test process
 */
@ExtendWith(ArquillianExtension.class)
public class JNLPIntegrationTestIT {
    
    @Deployment(testable = false, name = "ROOT.war")  // Deploy as ROOT.war to get root context path (/)
    public static WebArchive createDeployment() {
        System.out.println("\n=== Creating Deployment Archive ===");
        // Deploy the WAR to WildFly (runs in separate process)
        // testable=false ensures tests run in the test client process, not in WildFly container
        // Use a different name to avoid conflicts with auto-deployed WAR
        File warFile = new File("target/icedtea-web-integration-tests-2.0.1-SNAPSHOT.war");
        System.out.println("  Looking for WAR file: " + warFile.getAbsolutePath());
        if (!warFile.exists()) {
            throw new RuntimeException("WAR file not found: " + warFile.getAbsolutePath());
        }
        System.out.println("  ✓ WAR file found (" + warFile.length() + " bytes)");
        
        // Create archive directly from the WAR file
        // ShrinkWrap will read the file in-memory, so no file is left for the deployment scanner
        System.out.println("  Creating WebArchive from WAR file...");
        WebArchive archive = ShrinkWrap.createFromZipFile(WebArchive.class, warFile);
        System.out.println("  ✓ WebArchive created");
        
        // Add JNLP application JAR and dependencies to WAR so they're served by WildFly
        System.out.println("  Adding JNLP application files to WAR...");
        
        // Add JNLP application JAR and dependencies to WAR web root so they're served by WildFly
        System.out.println("  Adding JNLP application files to WAR web root...");
        
        // Add sample application JAR to web root (not WEB-INF/classes)
        File sampleJar = new File("target/icedtea-web-integration-tests-2.0.1-SNAPSHOT-sample-app.jar");
        if (sampleJar.exists()) {
            archive.addAsWebResource(sampleJar, "sample-app.jar");
            System.out.println("  ✓ Added sample-app.jar to web root");
        } else {
            System.out.println("  ✗ WARNING: sample-app.jar not found");
        }
        
        // Add lib directory with dependencies to web root
        String userHome = System.getProperty("user.home");
        String mavenRepo = userHome + File.separator + ".m2" + File.separator + "repository";
        List<String[]> jarsToCopy = new ArrayList<>();
        jarsToCopy.add(new String[]{"org/apache/cxf/cxf-rt-rs-client/4.0.3/cxf-rt-rs-client-4.0.3.jar", "lib/cxf-rt-rs-client-4.0.3.jar"});
        jarsToCopy.add(new String[]{"org/apache/cxf/cxf-rt-rs-extension-providers/4.0.3/cxf-rt-rs-extension-providers-4.0.3.jar", "lib/cxf-rt-rs-extension-providers-4.0.3.jar"});
        jarsToCopy.add(new String[]{"org/apache/cxf/cxf-rt-frontend-jaxrs/4.0.3/cxf-rt-frontend-jaxrs-4.0.3.jar", "lib/cxf-rt-frontend-jaxrs-4.0.3.jar"});
        String[] cxfCorePaths = {
            "org/apache/cxf/cxf-core/4.0.3/cxf-core-4.0.3.jar",
            "org/apache/cxf/cxf-core/4.0.6/cxf-core-4.0.6.jar"
        };
        boolean cxfCoreFound = false;
        for (String cxfCorePath : cxfCorePaths) {
            File cxfCoreJar = new File(mavenRepo + File.separator + cxfCorePath);
            if (cxfCoreJar.exists()) {
                archive.addAsWebResource(cxfCoreJar, "lib/cxf-core-4.0.3.jar");
                System.out.println("  ✓ Added lib/cxf-core-4.0.3.jar to web root");
                cxfCoreFound = true;
                break;
            }
        }
        jarsToCopy.add(new String[]{"jakarta/ws/rs/jakarta.ws.rs-api/3.1.0/jakarta.ws.rs-api-3.1.0.jar", "lib/jakarta.ws.rs-api-3.1.0.jar"});
        jarsToCopy.add(new String[]{"jakarta/annotation/jakarta.annotation-api/2.1.1/jakarta.annotation-api-2.1.1.jar", "lib/jakarta.annotation-api-2.1.1.jar"});
        jarsToCopy.add(new String[]{"com/fasterxml/jackson/jaxrs/jackson-jaxrs-json-provider/2.15.2/jackson-jaxrs-json-provider-2.15.2.jar", "lib/jackson-jaxrs-json-provider-2.15.2.jar"});
        jarsToCopy.add(new String[]{"com/fasterxml/jackson/core/jackson-core/2.15.2/jackson-core-2.15.2.jar", "lib/jackson-core-2.15.2.jar"});
        jarsToCopy.add(new String[]{"com/fasterxml/jackson/core/jackson-databind/2.15.2/jackson-databind-2.15.2.jar", "lib/jackson-databind-2.15.2.jar"});
        jarsToCopy.add(new String[]{"com/fasterxml/jackson/core/jackson-annotations/2.15.2/jackson-annotations-2.15.2.jar", "lib/jackson-annotations-2.15.2.jar"});
        
        int addedCount = cxfCoreFound ? 1 : 0;
        for (String[] jarInfo : jarsToCopy) {
            File sourceJar = new File(mavenRepo + File.separator + jarInfo[0]);
            if (sourceJar.exists()) {
                archive.addAsWebResource(sourceJar, jarInfo[1]);
                System.out.println("  ✓ Added " + jarInfo[1] + " to web root");
                addedCount++;
            }
        }
        System.out.println("  ✓ Added " + addedCount + " JAR files to lib directory in web root");
        
        // Update JNLP file codebase to use WildFly port 8080 and ensure it's in web root
        File jnlpFile = new File("src/main/webapp/sample-app.jnlp");
        if (jnlpFile.exists()) {
            try {
                String jnlpContent = new String(Files.readAllBytes(jnlpFile.toPath()));
                String codebase = "http://localhost:8080";
                jnlpContent = jnlpContent.replace("@CODEBASE@", codebase);
                // Replace the JNLP file in web root with updated content
                archive.addAsWebResource(new StringAsset(jnlpContent), "sample-app.jnlp");
                System.out.println("  ✓ Updated JNLP file in web root with codebase: " + codebase);
            } catch (Exception e) {
                System.out.println("  ✗ WARNING: Could not update JNLP file: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("  ✗ WARNING: JNLP file not found at: " + jnlpFile.getAbsolutePath());
        }
        
        System.out.println("  Archive name: " + archive.getName());
        System.out.println("  Archive toString: " + archive.toString());
        
        // The @Deployment(name = "ROOT.war") annotation deploys it as root context (/)
        // This allows accessing files at http://localhost:8080/sample-app.jnlp
        // The deployment scanner is disabled in pom.xml to prevent auto-deployment
        return archive;
    }

    private static URL baseUrl;
    // WildFly serves both the REST API and JNLP files/JARs on port 8080
    private static final int WILDFLY_PORT = 8080;

    @BeforeAll
    public static void startServer() throws Exception {
        // Note: WildFly deployment scanner is disabled by Maven Replacer Plugin
        // in process-test-resources phase (before Arquillian starts)
        System.out.println("\n=== IcedTea-Web Integration Test Diagnostics ===");
        System.out.println("Setting system properties BEFORE any icedtea-web classes are loaded...");
        long propStartTime = System.currentTimeMillis();
        
        // Set debug properties BEFORE any icedtea-web classes are loaded
        // This ensures they're picked up during configuration initialization
        System.setProperty("deployment.log", "true");
        System.setProperty("deployment.log.headers", "true");
        System.setProperty("deployment.log.stdstreams", "true");
        System.setProperty("deployment.log.file", "true");
        System.setProperty("deployment.log.file.clientapp", "true");
        System.setProperty("deployment.console.startup.mode", "SHOW");
        
        long propTime = System.currentTimeMillis() - propStartTime;
        System.out.println("✓ System properties set in " + propTime + "ms");
        System.out.println("  Note: DeploymentConfiguration will load from files, which may contain old properties like 'implying'");
        System.out.println("  This is normal - icedtea-web will log warnings for unknown properties but continue");
        System.out.println();
        
        // Get the sample JAR
        File sampleJar = new File("target/icedtea-web-integration-tests-2.0.1-SNAPSHOT-sample-app.jar");
        if (!sampleJar.exists()) {
            String error = "ERROR: Sample JAR not found at: " + sampleJar.getAbsolutePath();
            System.out.println(error);
            throw new RuntimeException(error);
        }
        System.out.println("✓ Sample JAR found: " + sampleJar.getAbsolutePath());

        // JNLP files and JARs are now served by WildFly (added in createDeployment())
        // Set baseUrl to point to WildFly on port 8080
        baseUrl = new URL("http://localhost:" + WILDFLY_PORT + "/");
        
        System.out.println("✓ JNLP files and JARs will be served by WildFly on " + baseUrl);
        System.out.println();
    }

    @AfterAll
    public static void stopServer() {
        // No separate server to stop - WildFly is managed by Arquillian
        System.out.println("\n=== Test Complete ===");
    }

    @Test
    public void testJNLPAvailable() throws Exception {
        System.out.println("\n--- Test: JNLP File Availability ---");
        // Verify baseUrl is set
        if (baseUrl == null) {
            throw new IllegalStateException("baseUrl is null - @BeforeAll may not have run");
        }
        // Verify the JNLP file is accessible
        URL jnlpUrl = new URL(baseUrl, "sample-app.jnlp");
        
        System.out.println("Testing JNLP URL: " + jnlpUrl);
        
        // Try to open the connection to verify it's accessible
        java.net.URLConnection conn = jnlpUrl.openConnection();
        conn.connect();
        assertNotNull(conn.getInputStream(), "JNLP file should be accessible");
        
        System.out.println("✓ JNLP file is accessible");
    }

    @Test
    public void testLaunchJNLPApplication() throws Exception {
        System.out.println("\n--- Test: Launch JNLP Application ---");
        // Verify baseUrl is set
        if (baseUrl == null) {
            throw new IllegalStateException("baseUrl is null - @BeforeAll may not have run");
        }
        // Get the JNLP URL from the server
        URL jnlpUrl = new URL(baseUrl, "sample-app.jnlp");
        
        System.out.println("Launching JNLP application from: " + jnlpUrl);
        
        try {
            // Debug properties are already set in @BeforeAll
            System.out.println("Debug properties status:");
            System.out.println("  deployment.log: " + System.getProperty("deployment.log"));
            System.out.println("  deployment.log.file: " + System.getProperty("deployment.log.file"));
            System.out.println("  deployment.log.stdstreams: " + System.getProperty("deployment.log.stdstreams"));
            
            // Check for headless mode
            String headless = System.getProperty("java.awt.headless");
            System.out.println("java.awt.headless property: " + headless);
            if ("true".equals(headless)) {
                System.out.println("⚠ WARNING: Running in headless mode - GUI will not display!");
            }
            
            // Check if display is available
            try {
                java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
                System.out.println("Graphics environment available: " + !ge.isHeadlessInstance());
                if (ge.isHeadlessInstance()) {
                    System.out.println("⚠ WARNING: Graphics environment is headless - GUI will not display!");
                }
            } catch (Exception ge) {
                System.out.println("⚠ WARNING: Could not check graphics environment: " + ge.getMessage());
            }
            
            // Verify the JNLP URL is accessible before launching
            System.out.println("Verifying JNLP URL is accessible: " + jnlpUrl);
            try {
                java.net.URLConnection conn = jnlpUrl.openConnection();
                conn.connect();
                System.out.println("✓ JNLP URL is accessible");
                System.out.println("  Content-Type: " + conn.getContentType());
                System.out.println("  Content-Length: " + conn.getContentLength());
            } catch (Exception e) {
                System.out.println("✗ ERROR: Cannot access JNLP URL: " + e.getMessage());
                throw e;
            }
            
            // Call Boot.main() to launch the JNLP application
            // This matches how the Rust launcher works: it spawns Java with Boot.main() as entry point
            System.out.println("\n=== Calling Boot.main() to Launch Application ===");
            System.out.println("  This matches the Rust launcher flow:");
            System.out.println("    1. Boot.main() parses command-line arguments (JNLP URL)");
            System.out.println("    2. Boot.main() calls AccessController.doPrivileged(new Boot())");
            System.out.println("    3. Boot.run() calls JnlpBoot.run()");
            System.out.println("    4. JnlpBoot.run() calls Boot.init() which calls JNLPRuntime.initialize(true)");
            System.out.println("    5. JnlpBoot.run() creates Launcher and calls launcher.launch(URL)");
            System.out.println("  JNLP URL: " + jnlpUrl);
            System.out.println("  Arguments to Boot.main(): [" + jnlpUrl.toString() + "]");
            System.out.println("  Current time: " + new java.util.Date());
            
            // Install SecurityManager to catch System.exit() calls
            // Boot.main() may call JNLPRuntime.exit() or System.exit() which would terminate the test JVM
            final SecurityManager originalSM = System.getSecurityManager();
            final boolean[] exitCalled = new boolean[1];
            final int[] exitCode = new int[1];
            
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkExit(int status) {
                    exitCalled[0] = true;
                    exitCode[0] = status;
                    if (status == 0) {
                        // Normal exit - throw SecurityException to prevent actual exit
                        throw new SecurityException("Exit prevented (status=" + status + ")");
                    } else {
                        // Error exit - allow it but log it
                        System.out.println("  [SecurityManager] Exit called with non-zero status: " + status);
                        throw new SecurityException("Exit prevented (status=" + status + ")");
                    }
                }
                
                @Override
                public void checkPermission(java.security.Permission perm) {
                    // Allow all other permissions
                }
            });
            
            // Launch Boot.main() in a separate thread with timeout to prevent hangs
            final Exception[] launchException = new Exception[1];
            final boolean[] launchCompleted = new boolean[1];
            final String[] launchStage = new String[1];
            launchStage[0] = "Not started";
            
            Thread launchThread = new Thread(() -> {
                try {
                    System.out.println("  [Boot Thread] ========================================");
                    System.out.println("  [Boot Thread] Starting Boot.main() at " + new java.util.Date());
                    System.out.println("  [Boot Thread] Thread: " + Thread.currentThread().getName());
                    System.out.println("  [Boot Thread] Thread ID: " + Thread.currentThread().getId());
                    System.out.println("  [Boot Thread] Thread state: " + Thread.currentThread().getState());
                    System.out.println("  [Boot Thread] JNLP URL: " + jnlpUrl);
                    launchStage[0] = "About to call Boot.main()";
                    System.out.println("  [Boot Thread] Stage: " + launchStage[0]);
                    System.out.println("  [Boot Thread] Calling Boot.main(new String[]{\"" + jnlpUrl.toString() + "\"})...");
                    long launchStartTime = System.currentTimeMillis();
                    
                    launchStage[0] = "Calling Boot.main()";
                    System.out.println("  [Boot Thread] Stage: " + launchStage[0]);
                    System.out.println("  [Boot Thread] This will:");
                    System.out.println("    1. Parse command-line arguments");
                    System.out.println("    2. Initialize JNLPRuntime");
                    System.out.println("    3. Create Launcher and launch application");
                    System.out.println("  This may take several seconds...");
                    
                    // Call Boot.main() with JNLP URL as argument
                    Boot.main(new String[]{jnlpUrl.toString()});
                    
                    long launchTime = System.currentTimeMillis() - launchStartTime;
                    launchCompleted[0] = true;
                    launchStage[0] = "Boot.main() completed";
                    System.out.println("  [Boot Thread] ========================================");
                    System.out.println("  [Boot Thread] Boot.main() completed at " + new java.util.Date());
                    System.out.println("  [Boot Thread] Total time: " + launchTime + "ms");
                    System.out.println("  [Boot Thread] Stage: " + launchStage[0]);
                } catch (SecurityException e) {
                    // This is expected if Boot.main() calls System.exit(0)
                    if (e.getMessage() != null && e.getMessage().contains("Exit prevented")) {
                        launchCompleted[0] = true;
                        launchStage[0] = "Boot.main() called exit (prevented by SecurityManager)";
                        System.out.println("  [Boot Thread] ========================================");
                        System.out.println("  [Boot Thread] Boot.main() attempted to exit with status: " + exitCode[0]);
                        if (exitCode[0] == 0) {
                            System.out.println("  [Boot Thread] ✓ Normal exit (status 0) - application likely launched successfully");
                        } else {
                            System.out.println("  [Boot Thread] ✗ Exit with error status: " + exitCode[0]);
                            launchException[0] = new RuntimeException("Boot.main() exited with status " + exitCode[0]);
                        }
                    } else {
                        // Unexpected SecurityException
                        launchException[0] = e;
                        launchCompleted[0] = true;
                        launchStage[0] = "Boot.main() failed with SecurityException";
                        System.out.println("  [Boot Thread] ✗ Unexpected SecurityException: " + e.getMessage());
                        e.printStackTrace();
                    }
                } catch (Exception e) {
                    launchException[0] = e;
                    launchCompleted[0] = true;
                    launchStage[0] = "Boot.main() failed with exception";
                    System.out.println("  [Boot Thread] ========================================");
                    System.out.println("  [Boot Thread] ✗ Boot.main() FAILED with exception!");
                    System.out.println("  [Boot Thread] Stage: " + launchStage[0]);
                    System.out.println("  [Boot Thread] Exception class: " + e.getClass().getName());
                    System.out.println("  [Boot Thread] Exception message: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("  [Boot Thread] Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
                    }
                    System.out.println("  [Boot Thread] Full stack trace:");
                    e.printStackTrace();
                } catch (Throwable t) {
                    launchException[0] = new RuntimeException("Unexpected Throwable in Boot thread", t);
                    launchCompleted[0] = true;
                    launchStage[0] = "Boot.main() failed with Throwable";
                    System.out.println("  [Boot Thread] ========================================");
                    System.out.println("  [Boot Thread] ✗ Boot.main() FAILED with Throwable!");
                    System.out.println("  [Boot Thread] Stage: " + launchStage[0]);
                    System.out.println("  [Boot Thread] Throwable class: " + t.getClass().getName());
                    System.out.println("  [Boot Thread] Throwable message: " + t.getMessage());
                    t.printStackTrace();
                }
            }, "Boot-Main-Thread");
            launchThread.setDaemon(false);
            
            System.out.println("  Starting Boot.main() thread...");
            System.out.println("  Boot thread name: " + launchThread.getName());
            System.out.println("  Boot thread will be daemon: " + launchThread.isDaemon());
            launchThread.start();
            System.out.println("  ✓ Boot thread started");
            System.out.println("  Boot thread state after start: " + launchThread.getState());
            System.out.println("  Current stage: " + launchStage[0]);
            
            // Wait for Boot.main() to complete with timeout (5 minutes)
            long startTime = System.currentTimeMillis();
            long timeout = 300000; // 5 minutes
            boolean timedOut = false;
            long lastProgressLog = 0;
            
            while (!launchCompleted[0] && !timedOut) {
                Thread.sleep(1000); // Check every second
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > timeout) {
                    timedOut = true;
                    System.out.println("  ✗ TIMEOUT: Boot.main() took longer than " + (timeout/1000) + " seconds!");
                    System.out.println("  Current stage: " + launchStage[0]);
                } else if (elapsed - lastProgressLog >= 5000) {
                    // Log progress every 5 seconds
                    lastProgressLog = elapsed;
                    System.out.println("  [Main Thread] ========================================");
                    System.out.println("  [Main Thread] Still waiting for Boot.main()... (" + (elapsed/1000) + "s elapsed)");
                    System.out.println("  [Main Thread] Current stage: " + launchStage[0]);
                    System.out.println("  [Main Thread] Boot thread state: " + launchThread.getState());
                    System.out.println("  [Main Thread] Boot thread alive: " + launchThread.isAlive());
                    System.out.println("  [Main Thread] Active threads: " + Thread.activeCount());
                    System.out.println("  [Main Thread] Launch completed flag: " + launchCompleted[0]);
                    System.out.println("  [Main Thread] Exit called: " + exitCalled[0] + (exitCalled[0] ? " (status=" + exitCode[0] + ")" : ""));
                    System.out.println("  [Main Thread] Launch exception: " + (launchException[0] != null ? launchException[0].getClass().getName() : "none"));
                    // Print thread dump if taking too long
                    if (elapsed > 10000) {
                        System.out.println("  [Main Thread] Thread dump (first 30 threads):");
                        Thread[] threads = new Thread[Thread.activeCount()];
                        Thread.enumerate(threads);
                        int count = 0;
                        for (Thread t : threads) {
                            if (t != null && count < 30) {
                                System.out.println("      - " + t.getName() + " [" + t.getState() + "] " + (t.isDaemon() ? "(daemon)" : "(non-daemon)"));
                                count++;
                            }
                        }
                    }
                    System.out.println("  [Main Thread] ========================================");
                }
            }
            
            // Restore original SecurityManager
            try {
                System.setSecurityManager(originalSM);
                System.out.println("  ✓ SecurityManager restored");
            } catch (Exception e) {
                System.out.println("  ⚠ WARNING: Could not restore SecurityManager: " + e.getMessage());
            }
            
            if (timedOut) {
                System.out.println("  ✗ Boot.main() timed out after " + (timeout/1000) + " seconds");
                System.out.println("  Boot thread state: " + launchThread.getState());
                System.out.println("  Boot thread stack trace:");
                StackTraceElement[] stack = launchThread.getStackTrace();
                for (StackTraceElement element : stack) {
                    System.out.println("    at " + element);
                }
                throw new RuntimeException("Boot.main() timed out after " + (timeout/1000) + " seconds");
            }
            
            if (launchException[0] != null) {
                System.out.println("  ✗ Boot.main() failed with exception");
                throw launchException[0];
            }
            
            if (exitCalled[0] && exitCode[0] != 0) {
                System.out.println("  ✗ Boot.main() exited with error status: " + exitCode[0]);
                throw new RuntimeException("Boot.main() exited with status " + exitCode[0]);
            }
            
            System.out.println("  ✓ Boot.main() completed successfully!");
            if (exitCalled[0]) {
                System.out.println("  Exit was called with status " + exitCode[0] + " (prevented by SecurityManager)");
            }
            System.out.println("  Total time: " + (System.currentTimeMillis() - startTime) + "ms");
            System.out.println("  Application should now be running (GUI should be visible)");
            
            // Give the application a moment to run and display GUI
            System.out.println("Application running - GUI should be visible with 'Test REST Calls' button");
            System.out.println("Waiting 15 seconds for GUI to appear and user interaction...");
            for (int i = 0; i < 15; i++) {
                Thread.sleep(1000);
                if (i % 5 == 0) {
                    System.out.println("  Still waiting... (" + (15 - i) + " seconds remaining)");
                }
            }
            
            // Check if log files were created in the standard log directory
            try {
                net.sourceforge.jnlp.config.PathsAndFiles.LOG_DIR.getFullPath();
                String logDirPath = net.sourceforge.jnlp.config.PathsAndFiles.LOG_DIR.getFullPath();
                if (logDirPath != null) {
                    File logDir = new File(logDirPath);
                    System.out.println("\nChecking for log files in standard log directory: " + logDirPath);
                    if (logDir.exists() && logDir.isDirectory()) {
                        File[] logFiles = logDir.listFiles((dir, name) -> name.endsWith(".log"));
                        if (logFiles != null && logFiles.length > 0) {
                            System.out.println("✓ Found " + logFiles.length + " log file(s):");
                            for (File logFile : logFiles) {
                                System.out.println("  - " + logFile.getName() + " (" + logFile.length() + " bytes)");
                            }
                        } else {
                            System.out.println("⚠ WARNING: No log files found in log directory");
                        }
                    } else {
                        System.out.println("⚠ WARNING: Log directory does not exist: " + logDirPath);
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠ WARNING: Could not check log directory: " + e.getMessage());
            }
            
            System.out.println("✓ JNLP application launch test completed");
        } catch (Exception e) {
            System.out.println("✗ ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("  Caused by: " + e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
            }
            System.out.println("  Stack trace:");
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    public void testSampleJarAccessible() throws Exception {
        System.out.println("\n--- Test: JAR File Availability ---");
        // Verify baseUrl is set
        if (baseUrl == null) {
            throw new IllegalStateException("baseUrl is null - @BeforeAll may not have run");
        }
        // Verify the JAR file is accessible
        URL jarUrl = new URL(baseUrl, "sample-app.jar");
        
        System.out.println("Testing JAR URL: " + jarUrl);
        
        // Try to open the connection to verify it's accessible
        java.net.URLConnection conn = jarUrl.openConnection();
        conn.connect();
        assertNotNull(conn.getInputStream(), "JAR file should be accessible");
        
        System.out.println("✓ JAR file is accessible");
    }
}

