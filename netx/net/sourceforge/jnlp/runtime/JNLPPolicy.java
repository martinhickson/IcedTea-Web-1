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

import java.io.File;
import java.net.SocketPermission;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.*;
import java.util.Enumeration;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Policy for JNLP environment.  This class delegates to the
 * system policy but always grants permissions to the JNLP code
 * and system CodeSources (no separate policy file needed).  This
 * class may also grant permissions to applications at runtime if
 * approved by the user.
 *
 * @author <a href="mailto:jmaxwell@users.sourceforge.net">Jon A. Maxwell (JAM)</a> - initial author
 * @version $Revision: 1.7 $
 */
public class JNLPPolicy extends Policy {

    /** classes from this source have all permissions */
    private static CodeSource shellSource;

    /** classes from this source have all permissions */
    private static CodeSource systemSource;

    /** the previous policy */
    private static Policy systemPolicy;

    private final URI jreExtDir;

    /** the system level policy for jnlps */
    private Policy systemJnlpPolicy = null;

    /** the user-level policy for jnlps */
    private Policy userJnlpPolicy = null;

    public JNLPPolicy() {
        shellSource = JNLPPolicy.class.getProtectionDomain().getCodeSource();
        systemSource = Policy.class.getProtectionDomain().getCodeSource();
        systemPolicy = Policy.getPolicy();

        systemJnlpPolicy = getPolicyFromConfig(DeploymentConfiguration.KEY_SYSTEM_SECURITY_POLICY);
        userJnlpPolicy = getPolicyFromUrl(PathsAndFiles.JAVA_POLICY.getFullPath());

        String jre = System.getProperty("java.home");
        jreExtDir = (new File(jre + File.separator + "lib" + File.separator + "ext")).toURI();
    }

    /**
     * Return a mutable, heterogeneous-capable permission collection
     * for the source.
     */
    public PermissionCollection getPermissions(CodeSource source) {
        if (source.equals(systemSource) || source.equals(shellSource))
            return getAllPermissions();

        if (isSystemJar(source)) {
            return getAllPermissions();
        }

        // if we check the SecurityDesc here then keep in mind that
        // code can add properties at runtime to the ResourcesDesc!
        // Defensive: catch any exceptions during getApplication to avoid circular dependencies
        ApplicationInstance app = null;
        try {
            app = JNLPRuntime.getApplication();
        } catch (Exception e) {
            // If getApplication fails (e.g., due to circular dependency), continue without app-specific permissions
            // This can happen during initialization when security manager is being set up
        }

        if (app != null) {
            try {
                if (app.getClassLoader() instanceof JNLPClassLoader) {
                    JNLPClassLoader cl = (JNLPClassLoader) app.getClassLoader();

                    PermissionCollection clPermissions = cl.getPermissions(source);

                    Enumeration<Permission> e;
                    CodeSource appletCS = new CodeSource(app.getJNLPFile().getSourceLocation(), (java.security.cert.Certificate[]) null);

                    // systempolicy permissions need to be accounted for as well
                    e = systemPolicy.getPermissions(appletCS).elements();
                    while (e.hasMoreElements()) {
                        clPermissions.add(e.nextElement());
                    }

                    // and so do permissions from the jnlp-specific system policy
                if (systemJnlpPolicy != null) {
                    e = systemJnlpPolicy.getPermissions(appletCS).elements();
                    while (e.hasMoreElements()) {
                        clPermissions.add(e.nextElement());
                    }
                }

                // and permissiosn from jnlp-specific user policy too
                if (userJnlpPolicy != null) {
                    e = userJnlpPolicy.getPermissions(appletCS).elements();
                    while (e.hasMoreElements()) {
                        clPermissions.add(e.nextElement());
                    }

                    CodeSource appletCodebaseSource = new CodeSource(app.getJNLPFile().getCodeBase(), (java.security.cert.Certificate[]) null);
                    e = userJnlpPolicy.getPermissions(appletCodebaseSource).elements();
                    while (e.hasMoreElements()) {
                        clPermissions.add(e.nextElement());
                    }
                }

                return clPermissions;
            }
            } catch (Exception e) {
                // If accessing application properties fails, continue without app-specific permissions
                // This prevents circular dependencies during security manager initialization
            }
        }

        // delegate to original Policy object; required to run under WebStart
        // But also include user policy permissions to ensure user policy is always consulted
        PermissionCollection result = systemPolicy.getPermissions(source);

        // Add user policy permissions if available
        if (userJnlpPolicy != null) {
            Enumeration<Permission> e = userJnlpPolicy.getPermissions(source).elements();
            while (e.hasMoreElements()) {
                result.add(e.nextElement());
            }
        }

        return result;
    }

    /**
     * Refresh.
     */
    public void refresh() {
        if (userJnlpPolicy != null) {
            userJnlpPolicy.refresh();
        }
        if (systemJnlpPolicy != null) {
            systemJnlpPolicy.refresh();
        }
        // Also refresh the system policy to pick up any changes
        if (systemPolicy != null) {
            systemPolicy.refresh();
        }
    }

    /**
     * Return an all-permissions collection.
     */
    private Permissions getAllPermissions() {
        Permissions result = new Permissions();

        result.add(new AllPermission());
        return result;
    }

    /**
     * Returns true if the CodeSource corresponds to a system jar. That is,
     * it's part of the JRE.
     */
    private boolean isSystemJar(CodeSource source) {
        if (source == null || source.getLocation() == null) {
            return false;
        }

        // anything in JRE/lib/ext is a system jar and has full permissions
        String sourceProtocol = source.getLocation().getProtocol();
        String sourcePath = source.getLocation().getPath();
        if (sourceProtocol.toUpperCase().equals("FILE") &&
                sourcePath.startsWith(jreExtDir.getRawPath())) {
            return true;
        }

        // check to see if source protocol is a Java System Library protocol
        if (sourceProtocol.equalsIgnoreCase("jrt")) {
            // jrt protocols are only for system code return true
            return true;
        }

        return false;
    }

    /**
     * Constructs a delegate policy based on a config setting
     * @param key a KEY_* in DeploymentConfiguration
     * @return a policy based on the configuration set by the user
     */
    private Policy getPolicyFromConfig(String key) {
        DeploymentConfiguration config = JNLPRuntime.getConfiguration();
        String policyLocation = config.getProperty(key);
        return getPolicyFromUrl(policyLocation);
    }

    /**
     * Constructs a delegate policy based on a config setting
     * @param key a KEY_* in DeploymentConfiguration
     * @return a policy based on the configuration set by the user
     */
    private Policy getPolicyFromUrl(String policyLocation) {
        Policy policy = null;
        if (policyLocation != null) {
            try {
                final URI policyUri;
                if (policyLocation.startsWith("file://")) {
                    // File URIs must use forward slashes, not backslashes
                    // Extract the path part after "file://" and normalize it
                    String pathPart = policyLocation.substring(7); // Remove "file://" prefix

                    // If path contains backslashes (Windows paths), normalize using File.getAbsolutePath()
                    // Use getAbsolutePath() instead of canonicalize to avoid recursive loops on Windows
                    if (pathPart.contains("\\")) {
                        // Create a File object from the path
                        File file = new File(pathPart);
                        // Check if file exists before trying to load policy to avoid hangs
                        if (!file.exists()) {
                            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, 
                                "Policy file does not exist, skipping: " + pathPart);
                            return null;
                        }
                        // Use getAbsolutePath() to get absolute path without canonicalization
                        // This avoids recursive loops in WinNTFileSystem.canonicalize0
                        String absolutePath = file.getAbsolutePath();
                        // Convert to URI manually to avoid File.toURI() which may call canonicalize
                        // Replace backslashes with forward slashes for URI
                        String uriPath = absolutePath.replace("\\", "/");
                        // Ensure leading slash for Windows absolute paths
                        if (!uriPath.startsWith("/")) {
                            uriPath = "/" + uriPath;
                        }
                        policyUri = new URI("file", null, uriPath, null);
                    } else {
                        // For already normalized paths, handle specific cases
                        String normalizedLocation = policyLocation;
                        if (JNLPRuntime.isWindows() && policyLocation.startsWith("file:///c/")) {
                            normalizedLocation = policyLocation.replace("file:///c/", "file:///C:/");
                        }
                        // Ensure forward slashes (in case of any remaining backslashes)
                        normalizedLocation = normalizedLocation.replace("\\", "/");
                        // Check if file exists for file:// URIs
                        if (normalizedLocation.startsWith("file://")) {
                            String filePath = normalizedLocation.substring(7);
                            File file = new File(filePath);
                            if (!file.exists()) {
                                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, 
                                    "Policy file does not exist, skipping: " + filePath);
                                return null;
                            }
                        }
                        policyUri = new URI(normalizedLocation);
                    }
                } else {
                    // For non-file URIs, first try to treat as file path
                    File file = new File(policyLocation);
                    if (file.exists()) {
                        // Use getAbsolutePath() instead of canonicalize to avoid recursive loops
                        String absolutePath = file.getAbsolutePath();
                        // Convert to URI manually to avoid File.toURI() which may call canonicalize
                        String uriPath = absolutePath.replace("\\", "/");
                        if (!uriPath.startsWith("/")) {
                            uriPath = "/" + uriPath;
                        }
                        policyUri = new URI("file", null, uriPath, null);
                    } else {
                        // For non-file URIs, replace backslashes with forward slashes
                        policyUri = new URI(policyLocation.replace("\\", "/"));
                    }
                }
                // Wrap Policy.getInstance() in try-catch to catch any blocking or unexpected exceptions
                try {
                    policy = getInstance("JavaPolicy", new URIParameter(policyUri));
                } catch (Exception e) {
                    // Catch all exceptions including RuntimeException and Error to prevent hangs
                    OutputController.getLogger().log(OutputController.Level.ERROR_ALL, 
                        "Failed to load policy from: " + policyLocation);
                    OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
                    return null;
                }
            } catch (IllegalArgumentException | URISyntaxException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            } catch (Exception e) {
                // Catch any other unexpected exceptions to prevent hangs
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, 
                    "Unexpected error loading policy from: " + policyLocation);
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            }
        }
        return policy;
    }

    public boolean implies(ProtectionDomain domain, Permission permission) {
        if (permission instanceof SocketPermission && JNLPClassLoader.isTrustedElevatedLaunch()) {
            return true;
        }
        //Include the permissions that may be added during runtime.
        PermissionCollection pc = getPermissions(domain.getCodeSource());
        return super.implies(domain, permission) || pc.implies(permission);
    }
}
