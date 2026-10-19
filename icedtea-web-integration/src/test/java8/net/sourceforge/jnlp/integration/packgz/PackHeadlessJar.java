package net.sourceforge.jnlp.integration.packgz;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the signed {@code .pack.gz} fixture with JDK 11 {@link Pack200}.
 * Invoked from the integration-module antrun step; not a JUnit test.
 * Pack200 was removed from the JDK in 14, so this must run on JDK 11.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code repack <jar>} — normalize the jar in place (pack then unpack)
 *       so a later {@code jarsigner} round-trips through pack200;</li>
 *   <li>{@code <jar> <output.pack.gz>} — pack an already-signed jar.</li>
 * </ul>
 */
public final class PackHeadlessJar {

    private PackHeadlessJar() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "repack".equals(args[0])) {
            repack(new File(args[1]));
            return;
        }
        if (args.length == 2) {
            pack(new File(args[0]), new File(args[1]));
            return;
        }
        throw new IllegalArgumentException(
                "usage: PackHeadlessJar repack <jar> | PackHeadlessJar <jar> <output.pack.gz>");
    }

    /** Normalize {@code jar} so signing it and packing again preserves the signature. */
    static void repack(File jar) throws Exception {
        File dir = jar.getAbsoluteFile().getParentFile();
        File packed = File.createTempFile("repack", ".pack.gz", dir);
        File normalized = File.createTempFile("repack", ".jar", dir);
        try {
            pack(jar, packed);
            try (InputStream in = new GZIPInputStream(new FileInputStream(packed));
                 JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(normalized)))) {
                Pack200.newUnpacker().unpack(in, out);
            }
            if (!normalized.renameTo(jar)) {
                copyFile(normalized, jar);
            }
        } finally {
            packed.delete();
            normalized.delete();
        }
    }

    static void pack(File jar, File packGz) throws Exception {
        JarFile input = new JarFile(jar);
        try {
            OutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(packGz)));
            try {
                Pack200.newPacker().pack(input, out);
            } finally {
                out.close();
            }
        } finally {
            input.close();
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
