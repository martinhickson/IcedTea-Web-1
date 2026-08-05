/*
 * Copyright (C) 2024 IcedTea-Web Contributors
 *
 * This file is part of IcedTea-Web.
 *
 * IcedTea-Web is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 2.
 *
 * IcedTea-Web is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IcedTea-Web; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * Runtime protection against premature JarFile.close() calls that cause
 * "IllegalStateException: zip file closed" errors.
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * ║ ⚠️  ENABLED BY DEFAULT  ⚠️                                              ║
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This protection is AUTOMATICALLY ENABLED when ByteBuddy is available.
 * 
 * WHY: There is NO LEGITIMATE REASON to close JARs early:
 * - OS closes file handles on JVM exit anyway
 * - Memory savings are negligible (~100KB per JAR)
 * - Performance benefit is ZERO (reopening is slower)
 * - Closing early CAUSES CRASHES ("zip file closed" errors)
 * 
 * Therefore: ALL JarFile.close() calls are BLOCKED by default.
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This class uses ByteBuddy (if available) to intercept ALL JarFile.close() 
 * calls at runtime and:
 * 1. Log them (if debug enabled)
 * 2. Prevent them (ENABLED BY DEFAULT)
 * 3. Track which JarFiles should never be closed
 * 
 * DISABLING (NOT RECOMMENDED):
 * 
 *   javaws -Ditw.jarfile.close.mode=DISABLED app.jnlp
 * 
 * Only disable if you:
 * - Are testing a fix
 * - Need to diagnose close() behavior
 * - Have explicitly fixed all close() calls in your code
 * 
 * MODES:
 * 
 * - PREVENT_ALL: Block ALL close() calls (DEFAULT - most reliable)
 *   -Ditw.jarfile.close.mode=PREVENT_ALL
 * 
 * - PREVENT_PROTECTED: Only block close() on explicitly protected JarFiles
 *   -Ditw.jarfile.close.mode=PREVENT_PROTECTED
 * 
 * - LOG_ONLY: Log all close() calls but allow them (debugging)
 *   -Ditw.jarfile.close.mode=LOG_ONLY
 * 
 * - DISABLED: No interception (NOT RECOMMENDED - may cause crashes!)
 *   -Ditw.jarfile.close.mode=DISABLED
 * 
 * PROGRAMMATIC USAGE:
 * 
 *   // Protection is automatic, but you can also explicitly protect:
 *   JarFile jar = new JarFile("important.jar");
 *   JarFileCloseProtection.protectJarFile(jar);
 *   jar.close();  // Silently ignored
 * 
 * @see <a href="jdk.md">JDK ZipFile Analysis</a>
 * @see <a href="itw.md">ITW Architecture Documentation</a>
 * @see <a href="DEBUGGING_ZIP_FILE_CLOSED.md">Debugging Guide</a>
 * @see <a href="JarFile_Close_Protection_README.md">Production Guide</a>
 */
public class JarFileCloseProtection {
    
    private static final OutputController LOGGER = OutputController.getLogger();
    
    /**
     * Protection mode.
     */
    public enum Mode {
        /** Block ALL close() calls on ALL JarFiles (most aggressive) */
        PREVENT_ALL,
        
        /** Block close() only on explicitly protected JarFiles (default) */
        PREVENT_PROTECTED,
        
        /** Log all close() calls but allow them (debug mode) */
        LOG_ONLY,
        
        /** No interception (normal behavior) */
        DISABLED
    }
    
    /**
     * Current protection mode.
     * Default: PREVENT_ALL (enabled by default - there is no reason to ever close JARs)
     * 
     * Closing JARs early provides ZERO benefit:
     * - OS closes file handles on JVM exit anyway
     * - Memory savings are negligible (~100KB per JAR)
     * - Performance benefit is zero (reopening is slower than keeping open)
     * 
     * Closing JARs early causes MAJOR problems:
     * - "IllegalStateException: zip file closed" errors
     * - Application crashes
     * - Difficult-to-debug race conditions
     * 
     * Therefore: Protection is ENABLED BY DEFAULT.
     * To disable (not recommended): -Ditw.jarfile.close.mode=DISABLED
     */
    private static Mode currentMode = Mode.PREVENT_ALL;
    
    /**
     * JarFiles that should never be closed (PREVENT_PROTECTED mode).
     * Stored in {@link BootstrapZipFileCloseAdvice} for bootstrap-visible advice.
     */
    private static Set<String> protectedJarFiles() {
        return BootstrapZipFileCloseAdvice.protectedJarPaths;
    }
    
    /**
     * Track if ByteBuddy is available and installed.
     */
    private static boolean byteBuddyInstalled = false;
    private static boolean byteBuddyAttempted = false;
    
    /**
     * Enable debug logging of intercepted close() calls.
     */
    private static final boolean DEBUG = resolveDebugEnabled();
    private static final boolean JARFILE_CLOSE_PROTECTION_VERBOSE = false;
    
    static {
        // Read mode from system property (defaults to PREVENT_ALL)
        String modeStr = System.getProperty("itw.jarfile.close.mode", "PREVENT_ALL");
        try {
            currentMode = Mode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                "Invalid itw.jarfile.close.mode: " + modeStr + ", using PREVENT_ALL");
            currentMode = Mode.PREVENT_ALL;
        }
        
        // Auto-install if protection is not explicitly disabled
        // This ensures protection is active by default if ByteBuddy is available
        if (currentMode != Mode.DISABLED) {
            install();
            
            if (byteBuddyInstalled) {
                if (JARFILE_CLOSE_PROTECTION_VERBOSE) {
                    LOGGER.log(OutputController.Level.MESSAGE_ALL,
                        "[ITW] ✓ JarFile close protection ENABLED by default (mode=" + currentMode + ")");
                    LOGGER.log(OutputController.Level.MESSAGE_ALL,
                        "[ITW]   This prevents 'zip file closed' errors. To disable: -Ditw.jarfile.close.mode=DISABLED");
                }
            }
        } else {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                "[ITW] ⚠️  JarFile close protection DISABLED - you may experience 'zip file closed' errors!");
            LOGGER.log(OutputController.Level.WARNING_ALL,
                "[ITW]   To re-enable: remove -Ditw.jarfile.close.mode=DISABLED");
        }
    }

    private static boolean resolveDebugEnabled() {
        String systemProp = System.getProperty("itw.debug.jarfile.close");
        if (systemProp != null) {
            return isTruthy(systemProp);
        }

        try {
            String deploymentProp = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_DEBUG_JARFILE_CLOSE);
            return isTruthy(deploymentProp);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return "true".equalsIgnoreCase(trimmed)
            || "1".equals(trimmed)
            || "yes".equalsIgnoreCase(trimmed)
            || "on".equalsIgnoreCase(trimmed);
    }
    
    /**
     * Install ByteBuddy interception if available.
     * 
     * This method attempts to use ByteBuddy to intercept ALL JarFile.close() calls.
     * If ByteBuddy is not available, falls back to manual protection only.
     * 
     * @return true if ByteBuddy was installed successfully, false otherwise
     */
    public static synchronized boolean install() {
        if (byteBuddyAttempted) {
            return byteBuddyInstalled;
        }
        
        byteBuddyAttempted = true;
        
        if (currentMode == Mode.DISABLED) {
            LOGGER.log(OutputController.Level.MESSAGE_ALL,
                "[ITW] JarFile close protection DISABLED (mode=DISABLED)");
            return false;
        }
        
        try {
            Class.forName("net.bytebuddy.ByteBuddy");
            Class.forName("net.bytebuddy.agent.ByteBuddyAgent");

            ByteBuddyAgent.install();
            BootstrapAdviceSupport.adviseBootstrapMethod(
                    ZipFile.class, BootstrapZipFileCloseAdvice.class, "close");

            byteBuddyInstalled = true;
            if (JARFILE_CLOSE_PROTECTION_VERBOSE) {
                LOGGER.log(OutputController.Level.MESSAGE_ALL,
                    "[ITW] ✓ JarFile close protection INSTALLED via ByteBuddy (mode=" + currentMode + ")");
                LOGGER.log(OutputController.Level.MESSAGE_ALL,
                    "[ITW]   All JarFile.close() calls will be " +
                    (currentMode == Mode.LOG_ONLY ? "logged" : "intercepted"));
            }
            
            return true;
            
        } catch (ClassNotFoundException e) {
            LOGGER.log(OutputController.Level.MESSAGE_ALL,
                "[ITW] ByteBuddy not available, using manual protection only");
            LOGGER.log(OutputController.Level.MESSAGE_ALL,
                "[ITW] To enable full protection, add ByteBuddy to classpath");
            return false;
            
        } catch (Exception e) {
            LOGGER.log(OutputController.Level.WARNING_ALL,
                "[ITW] Failed to install ByteBuddy interceptor: " + e);
            if (DEBUG) {
                LOGGER.log(e);
            }
            return false;
        }
    }

    /**
     * Mark a JarFile as protected (should never be closed).
     * Used in PREVENT_PROTECTED mode.
     * 
     * @param jarFile The JarFile to protect
     */
    public static void protectJarFile(JarFile jarFile) {
        if (jarFile != null) {
            protectedJarFiles().add(jarFile.getName());
            if (DEBUG) {
                LOGGER.log(OutputController.Level.MESSAGE_DEBUG,
                    "[ITW] Protected JarFile from closing: " + jarFile.getName());
            }
        }
    }
    
    /**
     * Mark a JarFile path as protected (should never be closed).
     * Used in PREVENT_PROTECTED mode.
     * 
     * @param jarPath The path to the JarFile to protect
     */
    public static void protectJarFile(String jarPath) {
        if (jarPath != null) {
            protectedJarFiles().add(jarPath);
            if (DEBUG) {
                LOGGER.log(OutputController.Level.MESSAGE_DEBUG,
                    "[ITW] Protected JarFile from closing: " + jarPath);
            }
        }
    }
    
    /**
     * Unprotect a JarFile (allow it to be closed).
     * 
     * @param jarFile The JarFile to unprotect
     */
    public static void unprotectJarFile(JarFile jarFile) {
        if (jarFile != null) {
            protectedJarFiles().remove(jarFile.getName());
        }
    }
    
    /**
     * Unprotect a JarFile path (allow it to be closed).
     * 
     * @param jarPath The path to the JarFile to unprotect
     */
    public static void unprotectJarFile(String jarPath) {
        if (jarPath != null) {
            protectedJarFiles().remove(jarPath);
        }
    }
    
    /**
     * Set the protection mode.
     * 
     * @param mode The new protection mode
     */
    public static void setMode(Mode mode) {
        currentMode = mode;
        LOGGER.log(OutputController.Level.MESSAGE_ALL,
            "[ITW] JarFile close protection mode set to: " + mode);
    }
    
    /**
     * Get the current protection mode.
     * 
     * @return The current protection mode
     */
    public static Mode getMode() {
        return currentMode;
    }
    
    /**
     * Check if ByteBuddy interception is active.
     * 
     * @return true if ByteBuddy is installed and intercepting close() calls
     */
    public static boolean isActive() {
        return byteBuddyInstalled && currentMode != Mode.DISABLED;
    }
    
    /**
     * Get the number of protected JarFiles.
     * 
     * @return The count of protected JarFiles
     */
    public static int getProtectedCount() {
        return protectedJarFiles().size();
    }

    /**
     * Get all protected JarFile paths.
     *
     * @return A copy of the protected JarFile paths
     */
    public static Set<String> getProtectedJarFiles() {
        return new HashSet<>(protectedJarFiles());
    }
}

