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
package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.logging.OutputController.Level;

/**
 * Manages copying JAR files to the Java temp directory (icedtea-web folder)
 * to avoid interference with classloaders and keeps file handles open for performance.
 */
public class JarFileTempManager {

    private static final OutputController LOGGER = OutputController.getLogger();
    private static final JarFileTempManager INSTANCE = new JarFileTempManager();
    
    /**
     * Enable optimized temp file handling:
     * - Create temp file in same folder as original (enables hard links)
     * - Use predictable name: <original>_itw.jar (reuse across launches)
     * - Use hard link (Windows) or symlink (Unix) instead of copy when possible
     * - Use deleteOnExit() for cleanup
     */
    private static final boolean OPTIMISED_TEMP_FILE_HANDLING = true;

    /**
     * Enable temp file copying/links for JARs.
     *
     * When false, ITW will use the original JAR path directly and skip copying
     * or hard/symlinking. ByteBuddy close protection handles the "zip file closed"
     * issue, so copying is unnecessary by default.
     */
    private static final boolean COPY_JARS = false;
    
    /**
     * CRITICAL: JarFile close() calls MUST be disabled to prevent "zip file closed" errors.
     * 
     * The JDK's ZipFile.Source cache shares RandomAccessFile handles across multiple JarFile
     * instances. Closing a JarFile decrements the reference count, and when it reaches 0,
     * the shared RandomAccessFile is closed, breaking ALL other JarFile instances that
     * reference the same file.
     * 
     * ITW's temp file strategy creates separate files (myapp.jar vs myapp_itw.jar) to get
     * separate ZipFile.Source cache entries, but closing EITHER file can still cause issues
     * if any code path (e.g., URLClassLoader) is still using it.
     * 
     * Therefore, JarFile instances MUST be kept open for the lifetime of the application.
     * The OS will close file handles on JVM exit. Memory usage is acceptable (~100KB per JAR
     * for central directory cache).
     * 
     * This flag exists for emergency debugging only. It should ALWAYS be false in production.
     * Setting it to true WILL cause "IllegalStateException: zip file closed" errors.
     * 
     * @see <a href="jdk.md">JDK ZipFile Analysis</a>
     * @see <a href="itw.md">ITW Architecture Documentation</a>
     */
    private static final boolean ENABLE_JARFILE_CLOSE = false;
    
    /**
     * Detect operating system for link type selection.
     * 
     * The real issue was URLClassLoader opening the ORIGINAL file while ITW opened
     * the temp file. Now both use the same temp file, so hard links work on Windows.
     */
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    
    private static final String TEMP_DIR_NAME = "icedtea-web";
    
    private final File tempBaseDir;
    private final ConcurrentHashMap<File, File> originalToTempMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<File, java.util.jar.JarFile> openJarFiles = new ConcurrentHashMap<>();
    
    private JarFileTempManager() {
        tempBaseDir = AccessController.doPrivileged(new PrivilegedAction<File>() {
            @Override
            public File run() {
                String javaTempDir = System.getProperty("java.io.tmpdir");
                File baseDir = new File(javaTempDir, TEMP_DIR_NAME);
                if (!baseDir.exists()) {
                    baseDir.mkdirs();
                }
                return baseDir;
            }
        });
    }
    
    public static JarFileTempManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Get or create a temporary copy of the JAR file.
     * The file handle is kept open for performance.
     * 
     * @param originalFile the original JAR file
     * @return the temporary copy (either in same folder or icedtea-web directory)
     * @throws IOException if the copy fails
     */
    public File getTempJarFile(File originalFile) throws IOException {
        if (originalFile == null) {
            throw new IllegalArgumentException("Original file cannot be null");
        }

        if (!COPY_JARS) {
            LOGGER.log(Level.MESSAGE_DEBUG,
                String.format("[ITW-TEMP] COPY_JARS disabled, using original file: %s",
                    originalFile.getAbsolutePath()));
            return originalFile;
        }
        
        LOGGER.log(Level.MESSAGE_ALL, 
            String.format("[ITW-TEMP] getTempJarFile called for: %s (optimized=%b)", 
                originalFile.getAbsolutePath(), OPTIMISED_TEMP_FILE_HANDLING));
        
        if (OPTIMISED_TEMP_FILE_HANDLING) {
            return getTempJarFileOptimised(originalFile);
        } else {
            return getTempJarFileLegacy(originalFile);
        }
    }
    
    /**
     * Optimized temp file handling:
     * - Creates temp file in same folder as original (enables hard links)
     * - Uses predictable name: <original>_itw.jar (reuse across launches)
     * - Uses hard link instead of copy when possible (instant, no disk waste)
     * - Uses deleteOnExit() for cleanup
     */
    private File getTempJarFileOptimised(File originalFile) throws IOException {
        // Check if file already has _itw.jar suffix (prevent double-copying)
        String originalName = originalFile.getName();
        if (originalName.endsWith("_itw.jar")) {
            LOGGER.log(Level.MESSAGE_ALL, 
                String.format("[ITW-TEMP] File is already ITW temp file, skipping: %s", 
                    originalFile.getAbsolutePath()));
            return originalFile;
        }
        
        // Check if we already have a temp copy for this original file
        File tempFile = originalToTempMap.get(originalFile);
        if (tempFile != null && tempFile.exists()) {
            return tempFile;
        }
        
        // Create temp file in same folder with _itw.jar suffix
        File parentDir = originalFile.getParentFile();
        String baseName = originalName.replaceFirst("\\.jar$", "");
        String tempFileName = baseName + "_itw.jar";
        tempFile = new File(parentDir, tempFileName);
        
        // Platform-specific strategy:
        // - Unix/Mac: Use symlink (different inode, fast, minimal disk)
        // - Windows: Use copy (hard links share OS file handles, symlinks need admin)
        boolean linkSuccess = false;
        String linkType = "";
        if (!tempFile.exists()) {
            if (!IS_WINDOWS) {
                // Unix/Mac: Try symlink first
                try {
                    Files.createSymbolicLink(tempFile.toPath(), originalFile.toPath());
                    linkSuccess = true;
                    linkType = "symbolic link";
                    LOGGER.log(Level.MESSAGE_ALL, 
                        String.format("[ITW-TEMP] Created symbolic link: %s -> %s", 
                            tempFile.getAbsolutePath(), originalFile.getAbsolutePath()));
                } catch (IOException | UnsupportedOperationException e) {
                    // Symlink failed, will fall through to copy
                    LOGGER.log(Level.WARNING_ALL, 
                        String.format("[ITW-TEMP] Symbolic link failed, falling back to copy: %s", e.getMessage()));
                }
            } else {
                // Windows: Skip directly to copy (hard links share file handles, symlinks need admin)
                LOGGER.log(Level.MESSAGE_ALL, "[ITW-TEMP] Windows detected, using copy (hard links not reliable)");
                linkType = "copy";
            }
        }
        
        // Fall back to copy if link failed or file already exists
        if (!linkSuccess && !tempFile.exists()) {
            LOGGER.log(Level.MESSAGE_ALL, 
                String.format("[ITW-TEMP] Copying JAR to temp file: %s -> %s", 
                    originalFile.getAbsolutePath(), tempFile.getAbsolutePath()));
            
            try {
                Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Fallback to manual copy if Files.copy fails
                copyFile(originalFile, tempFile);
            }
        }
        
        // Mark for deletion on JVM exit
        tempFile.deleteOnExit();
        
        // Cache the mapping
        originalToTempMap.put(originalFile, tempFile);
        
        // Print for debugging
        System.err.println("[ITW] Created temp JAR: " + originalFile.getName() + " -> " + tempFile.getName() + 
                          " (link=" + (linkSuccess ? linkType : "copy") + ")");
        
        return tempFile;
    }
    
    /**
     * Legacy temp file handling (for OPTIMISED_TEMP_FILE_HANDLING=false):
     * - Creates temp file in java.io.tmpdir/icedtea-web
     * - Uses unique timestamp-based name
     * - Always copies (no hard link optimization)
     */
    private File getTempJarFileLegacy(File originalFile) throws IOException {
        // Check if the file is already in the temp directory (prevent double-copying)
        String normalizedFilePath = originalFile.getAbsolutePath().replace('/', File.separatorChar);
        String normalizedTempDir = tempBaseDir.getAbsolutePath().replace('/', File.separatorChar);
        if (normalizedFilePath.startsWith(normalizedTempDir)) {
            // File is already in temp directory, return as-is
            LOGGER.log(Level.MESSAGE_ALL, 
                String.format("[ITW-TEMP-LEGACY] File is already in temp directory, skipping copy: %s", 
                    originalFile.getAbsolutePath()));
            return originalFile;
        }
        
        // Check if we already have a temp copy for this original file
        File tempFile = originalToTempMap.get(originalFile);
        if (tempFile != null && tempFile.exists()) {
            return tempFile;
        }
        
        // Create a unique temp file name based on the original file
        String originalName = originalFile.getName();
        String tempFileName = originalName + "_" + System.currentTimeMillis() + "_" + 
                             originalFile.hashCode() + ".jar";
        tempFile = new File(tempBaseDir, tempFileName);
        
        // Copy the file to temp directory
        LOGGER.log(Level.MESSAGE_ALL, 
            String.format("[ITW-TEMP-LEGACY] Copying JAR to temp directory: %s -> %s", 
                originalFile.getAbsolutePath(), tempFile.getAbsolutePath()));
        
        try {
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback to manual copy if Files.copy fails
            copyFile(originalFile, tempFile);
        }
        
        // Mark for deletion on JVM exit
        tempFile.deleteOnExit();
        
        // Cache the mapping
        originalToTempMap.put(originalFile, tempFile);
        
        return tempFile;
    }
    
    /**
     * Get or create a temporary copy of the JAR file from a file path.
     * 
     * @param filePath the path to the original JAR file
     * @return the temporary copy in icedtea-web directory
     * @throws IOException if the copy fails
     */
    public File getTempJarFile(String filePath) throws IOException {
        return getTempJarFile(new File(filePath));
    }
    
    /**
     * Get an open java.util.jar.JarFile handle for the temp file, keeping it open for performance.
     * 
     * @param tempFile the temporary JAR file
     * @return an open JarFile handle (cached and kept open)
     * @throws IOException if the JAR file cannot be opened
     */
    public java.util.jar.JarFile getOpenJarFile(File tempFile) throws IOException {
        if (tempFile == null) {
            throw new IllegalArgumentException("Temp file cannot be null");
        }
        
        // Check if we already have an open handle
        java.util.jar.JarFile jarFile = openJarFiles.get(tempFile);
        if (jarFile != null) {
            return jarFile;
        }
        
        // Open and cache the JAR file handle
        jarFile = new java.util.jar.JarFile(tempFile, true);
        openJarFiles.put(tempFile, jarFile);
        
        LOGGER.log(Level.MESSAGE_ALL, 
            String.format("[ITW-TEMP] Opened and cached JAR file handle: %s", tempFile.getAbsolutePath()));
        
        return jarFile;
    }
    
    /**
     * Manual file copy fallback method.
     */
    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }
    
    /**
     * Clean up temp files (called on shutdown).
     * 
     * NOTE: JarFile close() calls are DISABLED by default (ENABLE_JARFILE_CLOSE=false)
     * to prevent "zip file closed" errors. See ENABLE_JARFILE_CLOSE documentation for details.
     */
    public void cleanup() {
        // Close all open JAR file handles (DISABLED by default - see ENABLE_JARFILE_CLOSE)
        if (ENABLE_JARFILE_CLOSE) {
            for (java.util.jar.JarFile jarFile : openJarFiles.values()) {
                try {
                    jarFile.close();
                } catch (IOException e) {
                    LOGGER.log(OutputController.Level.ERROR_ALL, e);
                }
            }
        } else {
            LOGGER.log(Level.MESSAGE_DEBUG, 
                "[ITW-TEMP] Skipping JarFile.close() calls (ENABLE_JARFILE_CLOSE=false). " +
                "JarFiles kept open to prevent 'zip file closed' errors. " +
                "OS will close file handles on JVM exit.");
        }
        openJarFiles.clear();
        originalToTempMap.clear();
    }
}

