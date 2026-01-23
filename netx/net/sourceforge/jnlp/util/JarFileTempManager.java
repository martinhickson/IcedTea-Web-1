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
     * Get or create a temporary copy of the JAR file in the icedtea-web temp directory.
     * The file handle is kept open for performance.
     * 
     * @param originalFile the original JAR file
     * @return the temporary copy in icedtea-web directory
     * @throws IOException if the copy fails
     */
    public File getTempJarFile(File originalFile) throws IOException {
        if (originalFile == null) {
            throw new IllegalArgumentException("Original file cannot be null");
        }
        
        // Check if the file is already in the temp directory (prevent double-copying)
        String normalizedFilePath = originalFile.getAbsolutePath().replace('/', File.separatorChar);
        String normalizedTempDir = tempBaseDir.getAbsolutePath().replace('/', File.separatorChar);
        if (normalizedFilePath.startsWith(normalizedTempDir)) {
            // File is already in temp directory, return as-is
            if (LOGGER.isDebugEnabled()) {
                LOGGER.log(Level.MESSAGE_DEBUG, 
                    String.format("File is already in temp directory, skipping copy: %s", 
                        originalFile.getAbsolutePath()));
            }
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.MESSAGE_DEBUG, 
                String.format("Copying JAR to temp directory: %s -> %s", 
                    originalFile.getAbsolutePath(), tempFile.getAbsolutePath()));
        }
        
        try {
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Fallback to manual copy if Files.copy fails
            copyFile(originalFile, tempFile);
        }
        
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
        
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.MESSAGE_DEBUG, 
                String.format("Opened and cached JAR file handle: %s", tempFile.getAbsolutePath()));
        }
        
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
     */
    public void cleanup() {
        // Close all open JAR file handles
        for (java.util.jar.JarFile jarFile : openJarFiles.values()) {
            try {
                jarFile.close();
            } catch (IOException e) {
                LOGGER.log(OutputController.Level.ERROR_ALL, e);
            }
        }
        openJarFiles.clear();
        originalToTempMap.clear();
    }
}

