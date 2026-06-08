/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.asm.Advice;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * Bootstrap-visible {@link ZipFile#close()} advice. Must not reference ITW application classes.
 */
public final class BootstrapZipFileCloseAdvice {

    static final Set<String> protectedJarPaths = ConcurrentHashMap.newKeySet();

    private BootstrapZipFileCloseAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean intercept(@Advice.This Object zipFile) {
        if (!(zipFile instanceof JarFile)) {
            return false;
        }
        String mode = System.getProperty("itw.jarfile.close.mode", "PREVENT_ALL");
        if (mode == null || mode.isEmpty()) {
            mode = "PREVENT_ALL";
        }
        switch (mode.toUpperCase()) {
            case "DISABLED":
            case "LOG_ONLY":
                System.err.println("[ITW] JarFile.close() on: " + ((ZipFile) zipFile).getName());
                return false;
            case "PREVENT_PROTECTED":
                return protectedJarPaths.contains(((ZipFile) zipFile).getName());
            case "PREVENT_ALL":
            default:
                return true;
        }
    }
}
