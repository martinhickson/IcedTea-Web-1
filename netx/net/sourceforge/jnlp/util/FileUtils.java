// Copyright (C) 2009 Red Hat, Inc.
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

package net.sourceforge.jnlp.util;

import java.awt.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import net.sourceforge.swing.SwingUtils;

import net.sourceforge.jnlp.config.DirectoryValidator;
import net.sourceforge.jnlp.config.DirectoryValidator.DirectoryCheckResults;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;
import static net.sourceforge.jnlp.runtime.Translator.R;
import static java.lang.Boolean.parseBoolean;
import static net.sourceforge.jnlp.config.DeploymentConfiguration.KEY_SECURITY_DISABLE_RESTRICTED_FILES;

/**
 * This class contains a few file-related utility functions.
 *
 * @author Omair Majid
 */

public final class FileUtils {

    private static final String WIN_DRIVE_LETTER_COLON_WILDCHAR = "WINDOWS_VERY_SPECIFIC_DOUBLEDOT";

    private static final List<String> WIN_ROOT_PRINCIPALS = Arrays.asList("NT AUTHORITY\\SYSTEM", "BUILTIN\\Administrators");

    /**
     * Indicates whether a file was successfully opened. If not, provides specific reasons
     * along with a general failure case
     */
    public enum OpenFileResult {
        /** The file was successfully opened */
        SUCCESS,
        /** The file could not be opened, for non-specified reasons */
        FAILURE,
        /** The file could not be opened because it did not exist and could not be created */
        CANT_CREATE,
        /** The file can be opened but in read-only */
        CANT_WRITE,
        /** The specified path pointed to a non-file filesystem object, ie a directory */
        NOT_FILE;
    }

    /**
     * list of characters not allowed in filenames
     */
    public static final List<Character> INVALID_PATH = Arrays.asList(new Character[]{':', '*', '?', '"', '<', '>', '|', '[', ']', '\'', ';', '=', ','});
    public static final List<Character> INVALID_NAME = new ArrayList<>(INVALID_PATH);

    static {
        INVALID_NAME.add(0, '\\');
        INVALID_NAME.add(0, '/');
    }

    private static final char SANITIZED_CHAR = '_';

    /**
     * Clean up a string by removing characters that can't appear in a local
     * file name.
     *
     * @param path the path to sanitize
     * @return a sanitized version of the input which is suitable for using as a
     * file path
     */
    public static String sanitizePath(String path) {
        return sanitizePath(path, SANITIZED_CHAR);
    }

    public static String sanitizePath(String path, char substitute) {
        //on windows, we can recieve both c:/path/ and c:\path\
        path = path.replace("\\", "/");
        if (JNLPRuntime.isWindows() && path.matches("^[a-zA-Z]\\:.*")) {
            path = path.replaceFirst(":", WIN_DRIVE_LETTER_COLON_WILDCHAR);
        }
        for (int i = 0; i < INVALID_PATH.size(); i++) {
            if (-1 != path.indexOf(INVALID_PATH.get(i))) {
                path = path.replace(INVALID_PATH.get(i), substitute);
            }
        }
        if (JNLPRuntime.isWindows()) {
            path = path.replaceFirst(WIN_DRIVE_LETTER_COLON_WILDCHAR, ":");
        }
        return path;
    }

    /**
     * Given an input, return a sanitized form of the input suitable for use as
     * a file/directory name
     *
     * @param filename the filename to sanitize.
     * @return a sanitized version of the input
     */
    public static String sanitizeFileName(String filename) {
        return sanitizeFileName(filename, SANITIZED_CHAR);
    }

    public static String sanitizeFileName(String filename, char substitute) {

        for (int i = 0; i < INVALID_NAME.size(); i++) {
            if (-1 != filename.indexOf(INVALID_NAME.get(i))) {
                filename = filename.replace(INVALID_NAME.get(i), substitute);
            }
        }

        return filename;
    }

    /**
     * Creates a new directory with minimum permissions. The directory is not
     * readable or writable by anyone other than the owner. The parent
     * directories are not created; they must exist before this is called.
     *
     * @param directory directory to be created
     * @throws IOException if IO fails
     */
    public static void createRestrictedDirectory(File directory) throws IOException {
        createRestrictedFile(directory, true, true);
    }

    /**
     * Creates a new file with minimum permissions. The file is not readable or
     * writable by anyone other than the owner. If writeableByOnwer is false,
     * even the owner can not write to it.
     *
     * @param file path to file
     * @param writableByOwner true if can be writable by owner
     * @throws IOException if IO fails
     */
    public static void createRestrictedFile(File file, boolean writableByOwner) throws IOException {
        createRestrictedFile(file, false, writableByOwner);
    }

    /**
     * Tries to create the ancestor directories of file f. Throws
     * an IOException if it can't be created (but not if it was
     * already there).
     * @param f file to provide parent directory
     * @param eMsg - the message to use for the exception. null
     * if the file name is to be used.
     * @throws IOException if the directory can't be created and doesn't exist.
     */
    public static void createParentDir(File f, String eMsg) throws IOException {
        File parent = f.getParentFile();
        // warning, linux and windows behave differently. Below snippet will pass on win(security hole), fail on linux
        // warning  mkdir is canonicaling, but exists/isDirectory is not. So  where mkdirs return true, and really creates dir, isDirectory can still return false
        // can be seen on this example
        // mkdirs /a/b/../c
        // where b do not exists will lead creation of /a/c
        // but exists on /a/b/../c is false on linux  even afterwards
        // without hexing of .. paths,
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException(R("RCantCreateDir",
                    eMsg == null ? parent : eMsg));
        }
    }

    /**
     * Tries to create the ancestor directories of file f. Throws
     * an IOException if it can't be created (but not if it was
     * already there).
     * @param f file which parent will be created
     * @throws IOException if the directory can't be created and doesn't exist.
     */
    public static void createParentDir(File f) throws IOException {
        createParentDir(f, null);
    }

    /**
     * Tries to delete file f. If the file exists but couldn't be deleted,
     * print an error message to stderr with the file name, or eMsg if eMsg
     * is not null.
     * @param f the file to be deleted
     * @param eMsg the message to print on failure (or null to print the
     * the file name).
     */
    public static void deleteWithErrMesg(File f, String eMsg) {
        if (f.exists()) {
            if (!f.delete()) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, R("RCantDeleteFile", eMsg == null ? f : eMsg));
            }
        }
    }

    /**
     * Tries to delete file f. If the file exists but couldn't be deleted,
     * print an error message to stderr with the file name.
     * @param f the file to be deleted
     */
    public static void deleteWithErrMesg(File f) {
        deleteWithErrMesg(f, null);
    }

    private static void createFileOrDir(File file, boolean isDir) throws IOException {
        if (isDir) {
            if (!file.mkdir()) {
                throw new IOException("Cannot create directory {} " + file);
            }
        } else {
            if (!file.createNewFile()) {
                throw new IOException("Cannot create file {} " + file);
            }
        }
    }

    /**
     * Creates a new file or directory with minimum permissions. The file is not
     * readable or writable by anyone other than the owner. If writeableByOnwer
     * is false, even the owner can not write to it. If isDir is true, then the
     * directory can be executed by the owner
     *
     * @throws IOException
     */
    private static void createRestrictedFile(File file, boolean isDir, boolean writableByOwner) throws IOException {

        final String disableRestrictedFiles = JNLPRuntime.getConfiguration().getProperty(KEY_SECURITY_DISABLE_RESTRICTED_FILES);
        if (parseBoolean(disableRestrictedFiles)) {
            createFileOrDir(file, isDir);
            return;
        }

        File tempFile = new File(file.getCanonicalPath() + ".temp");

        if (isDir) {
            if (!tempFile.mkdir()) {
                throw new IOException(R("RCantCreateDir", tempFile));
            }
        } else {
            if (!tempFile.createNewFile()) {
                throw new IOException(R("RCantCreateFile", tempFile));
            }
        }

        if (JNLPRuntime.isWindows()) {
            // prepare ACL flags
            Set<AclEntryFlag> flags = new LinkedHashSet<>();
            if (tempFile.isDirectory()) {
                flags.add(AclEntryFlag.DIRECTORY_INHERIT);
                flags.add(AclEntryFlag.FILE_INHERIT);
            }

            // prepare ACL permissions
            Set<AclEntryPermission> permissions = new LinkedHashSet<>();
            permissions.addAll(Arrays.asList(
                    AclEntryPermission.READ_DATA,
                    AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.EXECUTE,
                    AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_ACL,
                    AclEntryPermission.SYNCHRONIZE));
            if (writableByOwner) {
                permissions.addAll(Arrays.asList(
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.DELETE_CHILD,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.DELETE,
                        AclEntryPermission.WRITE_ACL,
                        AclEntryPermission.WRITE_OWNER));
            }

            // filter ACL's leaving only root and owner
            AclFileAttributeView view = Files.getFileAttributeView(tempFile.toPath(), AclFileAttributeView.class);
            List<AclEntry> list = new ArrayList<>();
            String owner = view.getOwner().getName();
            for (AclEntry ae : view.getAcl()) {
                String principalName = ae.principal().getName();
                if (WIN_ROOT_PRINCIPALS.contains(principalName) || owner.equals(principalName)) {
                    list.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(ae.principal())
                            .setPermissions(permissions)
                            .setFlags(flags)
                            .build());
                }
            }

            // apply ACL
            view.setAcl(list);
        } else {
        // remove all permissions
        if (!tempFile.setExecutable(false, false)) {
            throw new IOException(R("RRemoveXPermFailed", tempFile));
        }
        if (!tempFile.setReadable(false, false)) {
            throw new IOException(R("RRemoveRPermFailed", tempFile));
        }
        if (!tempFile.setWritable(false, false)) {
            throw new IOException(R("RRemoveWPermFailed", tempFile));
        }

        // allow owner to read
        if (!tempFile.setReadable(true, true)) {
            throw new IOException(R("RGetRPermFailed", tempFile));
        }

        // allow owner to write
        if (writableByOwner && !tempFile.setWritable(true, true)) {
            throw new IOException(R("RGetWPermFailed", tempFile));
        }

        // allow owner to enter directories
        if (isDir && !tempFile.setExecutable(true, true)) {
            throw new IOException(R("RGetXPermFailed", tempFile));
        }
        }

        // rename this file. Unless the file is moved/renamed, any program that
        // opened the file right after it was created might still be able to
        // read the data.
        if (!tempFile.renameTo(file)) {
            throw new IOException(R("RCantRename", tempFile, file));
        }
    }

    /**
     * Ensure that the parent directory of the file exists and that we are
     * able to create and access files within this directory
     * @param file the {@link File} representing a Java Policy file to test
     * @return a {@link DirectoryCheckResults} object representing the results of the test
     */
    public static DirectoryCheckResults testDirectoryPermissions(File file) {
        try {
            file = file.getCanonicalFile();
        } catch (final IOException e) {
            OutputController.getLogger().log(e);
            return null;
        }
        if (file == null || file.getParentFile() == null || !file.getParentFile().exists()) {
            return null;
        }
        final List<File> policyDirectory = new ArrayList<>();
        policyDirectory.add(file.getParentFile());
        final DirectoryValidator validator = new DirectoryValidator(policyDirectory);
        final DirectoryCheckResults result = validator.ensureDirs();

        return result;
    }

    /**
     * Verify that a given file object points to a real, accessible plain file.
     * @param file the {@link File} to verify
     * @return an {@link OpenFileResult} representing the accessibility level of the file
     */
    public static OpenFileResult testFilePermissions(File file) {
        if (file == null || !file.exists()) {
            return OpenFileResult.FAILURE;
        }
        try {
            file = file.getCanonicalFile();
        } catch (final IOException e) {
            return OpenFileResult.FAILURE;
        }
        final DirectoryCheckResults dcr = FileUtils.testDirectoryPermissions(file);
        if (dcr != null && dcr.getFailures() == 0) {
            if (file.isDirectory())
                return OpenFileResult.NOT_FILE;
            try {
                if (!file.exists() && !file.createNewFile()) {
                    return OpenFileResult.CANT_CREATE;
                }
            } catch (IOException e) {
                return OpenFileResult.CANT_CREATE;
            }
            final boolean read = file.canRead(), write = file.canWrite();
            if (read && write)
                return OpenFileResult.SUCCESS;
            else if (read)
                return OpenFileResult.CANT_WRITE;
            else
                return OpenFileResult.FAILURE;
        }
        return OpenFileResult.FAILURE;
    }

    /**
     * Show a dialog informing the user that the file is currently read-only
     * @param frame a {@link JFrame} to act as parent to this dialog
     */
    public static void showReadOnlyDialog(final Component frame) {
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(frame, R("RFileReadOnly"), R("Warning"), JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    /**
     * Show a generic error dialog indicating the  file could not be opened
     * @param frame a {@link JFrame} to act as parent to this dialog
     * @param filePath a {@link String} representing the path to the file we failed to open
     */
    public static void showCouldNotOpenFilepathDialog(final Component frame, final String filePath) {
        showCouldNotOpenDialog(frame, R("RCantOpenFile", filePath));
    }

    /**
     * Show an error dialog indicating the file could not be opened, with a particular reason
     * @param frame a {@link JFrame} to act as parent to this dialog
     * @param filePath a {@link String} representing the path to the file we failed to open
     * @param reason a {@link OpenFileResult} specifying more precisely why we failed to open the file
     */
    public static void showCouldNotOpenFileDialog(final Component frame, final String filePath, final OpenFileResult reason) {
        final String message;
        switch (reason) {
            case CANT_CREATE:
                message = R("RCantCreateFile", filePath);
                break;
            case CANT_WRITE:
                message = R("RCantWriteFile", filePath);
                break;
            case NOT_FILE:
                message = R("RExpectedFile", filePath);
                break;
            default:
                message = R("RCantOpenFile", filePath);
                break;
        }
        showCouldNotOpenDialog(frame, message);
    }

    /**
     * Show a dialog informing the user that the file could not be opened
     * @param frame a {@link JFrame} to act as parent to this dialog
     * @param message a {@link String} giving the specific reason the file could not be opened
     */
    public static void showCouldNotOpenDialog(final Component frame, final String message) {
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(frame, message, R("Error"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Returns a String that is suitable for using in GUI elements for
     * displaying (long) paths to users.
     *
     * @param path a path that should be shortened
     * @return a shortened path suitable for displaying to the user
     */
    public static String displayablePath(String path) {
        final int DEFAULT_LENGTH = 40;
        return displayablePath(path, DEFAULT_LENGTH);
    }

    /**
     * Return a String that is suitable for using in GUI elements for displaying
     * paths to users. If the path is longer than visibleChars, it is truncated
     * in a display-friendly way
     *
     * @param path a path that should be shorted
     * @param visibleChars the maximum number of characters that path should fit
     *        into. Also the length of the returned string
     * @return a shortened path that contains limited number of chars
     */
    public static String displayablePath(String path, int visibleChars) {
        /*
         * use a very simple method: prefix + "..." + suffix
         *
         * where prefix is the beginning part of path (as much as we can squeeze in)
         * and suffix is the end path of path
         */

        if (path == null || path.length() <= visibleChars) {
            return path;
        }

        final String OMITTED = "...";
        final int OMITTED_LENGTH = OMITTED.length();
        final int MIN_PREFIX_LENGTH = 4;
        final int MIN_SUFFIX_LENGTH = 4;
        /*
         * we want to show things other than OMITTED. if we have too few for
         * suffix and prefix, then just return as much as we can of the filename
         */
        if (visibleChars < (OMITTED_LENGTH + MIN_PREFIX_LENGTH + MIN_SUFFIX_LENGTH)) {
            return path.substring(path.length() - visibleChars);
        }

        int affixLength = (visibleChars - OMITTED_LENGTH) / 2;
        String prefix = path.substring(0, affixLength);
        String suffix = path.substring(path.length() - affixLength);

        return prefix + OMITTED + suffix;
    }

    /**
     * Recursively delete everything under a directory. Works on either files or
     * directories
     *
     * @param file the file object representing what to delete. Can be either a
     *        file or a directory.
     * @param base the directory under which the file and its subdirectories must be located
     * @throws IOException on an io exception or if trying to delete something
     *         outside the base
     */
    public static void recursiveDelete(File file, File base) throws IOException {
        OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, "Deleting: " + file);

        if (!(file.getCanonicalPath().startsWith(base.getCanonicalPath()))) {
            throw new IOException("Trying to delete a file outside Netx's basedir: "
                    + file.getCanonicalPath());
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            for (File children1 : children) {
                recursiveDelete(children1, base);
            }
        }
        if (!file.delete()) {
            throw new IOException("Unable to delete file: " + file);
        }

    }

    /**
     * This will return a lock to the file specified.
     * 
     * @param path File path to file we want to lock.
     * @param shared Specify if the lock will be a shared lock.
     * @param allowBlock Specify if we should block when we can not get the
     *            lock. Getting a shared lock will always block.
     * @return FileLock if we were successful in getting a lock, otherwise null.
     * @throws FileNotFoundException If the file does not exist.
     */
    public static FileLock getFileLock(String path, boolean shared, boolean allowBlock) throws FileNotFoundException {
        RandomAccessFile rafFile = new RandomAccessFile(path, "rw");
        FileChannel fc = rafFile.getChannel();
        FileLock lock = null;
        try {
            if (!shared) {
                if (allowBlock) {
                    lock = fc.lock(0, Long.MAX_VALUE, false);
                } else {
                    lock = fc.tryLock(0, Long.MAX_VALUE, false);
                }
            } else { // We want shared lock. This will block regardless if allowBlock is true or not.
                // Test to see if we can get a shared lock.
                lock = fc.lock(0, 1, true); // Block if a non exclusive lock is being held.
                if (!lock.isShared()) { // This lock is an exclusive lock. Use alternate solution.
                    FileLock tempLock = null;
                    for (long pos = 1; tempLock == null && pos < Long.MAX_VALUE - 1; pos++) {
                        tempLock = fc.tryLock(pos, 1, false);
                    }
                    lock.release();
                    lock = tempLock; // Get the unique exclusive lock.
                }
            }
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        return lock;
    }

/**
     * Method to save String as file in UTF-8 encoding.
     * 
     * @param content which will be saved as it is saved in this String
     * @param f file to be saved. No warnings provided
     * @throws IOException if save fails
     */
    public static void saveFile(String content, File f) throws IOException {
        saveFile(content, f, "utf-8");
    }

    /**
     * Method to save String as file in specified encoding/.
     *
     * @param content which will be saved as it is saved in this String
     * @param f file to be saved. No warnings provided
     * @param encoding of output byte representation
     * @throws IOException if save fails
     */
    public static void saveFile(String content, File f, String encoding) throws IOException {
        try (Writer output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), encoding))) {
            output.write(content);
            output.flush();
        }
    }

    /**
     * utility method which can read from any stream as one long String
     * 
     * @param is stream
     * @param encoding the encoding to use to convert the bytes from the stream
     * @return stream as string
     * @throws IOException if connection can't be established or resource does not exist
     */
    public static String getContentOfStream(InputStream is, String encoding) throws IOException {
         try {
            return getContentOfReader(new InputStreamReader(is, encoding));
        } finally {
            is.close();
        }
    }
     public static String getContentOfReader(Reader r) throws IOException {
        try {
            BufferedReader br = new BufferedReader(r);
            StringBuilder sb = new StringBuilder();
            while (true) {
                String s = br.readLine();
                if (s == null) {
                    break;
                }
                sb.append(s).append("\n");

            }
            return sb.toString();
        } finally {
            r.close();
        }

    }

    /**
     * utility method which can read from any stream as one long String
     *
     * @param is stream
     * @return stream as string
     * @throws IOException if connection can't be established or resource does not exist
     */
    public static String getContentOfStream(InputStream is) throws IOException {
        return getContentOfStream(is, "UTF-8");

    }

    public static String loadFileAsString(File f) throws IOException {
        return getContentOfStream(new FileInputStream(f));
    }

    public static String loadFileAsString(File f, String encoding) throws IOException {
        return getContentOfStream(new FileInputStream(f), encoding);
    }

    public static byte[] getFileMD5Sum(final File file, final String algorithm) throws NoSuchAlgorithmException,
            FileNotFoundException, IOException {
        final MessageDigest md5;
        InputStream is = null;
        DigestInputStream dis = null;
        try {
            md5 = MessageDigest.getInstance(algorithm);
            is = new FileInputStream(file);
            dis = new DigestInputStream(is, md5);

            md5.update(getContentOfStream(dis).getBytes());
        } finally {
            if (is != null) {
                is.close();
            }
            if (dis != null) {
                dis.close();
            }
        }

        return md5.digest();
    }
}
