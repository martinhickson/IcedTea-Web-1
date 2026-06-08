using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace IcedTeaWeb.Launcher;

internal static class Program
{
    private const string JavawsMainClass = "net.sourceforge.jnlp.runtime.JavawsUberLauncher";
    private const string SettingsMainClass = "net.sourceforge.jnlp.controlpanel.CommandLine";

    private static int Main(string[] args)
    {
        try
        {
            var executablePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName
                ?? throw new InvalidOperationException("Unable to resolve launcher path");
            var executableFile = new FileInfo(executablePath);
            var launcherName = Path.GetFileNameWithoutExtension(executableFile.Name);
            var mainClass = ResolveMainClass(launcherName);
            var binDirectory = executableFile.Directory
                ?? throw new InvalidOperationException("Unable to resolve launcher directory");
            var installRoot = string.Equals(binDirectory.Name, "bin", StringComparison.OrdinalIgnoreCase)
                ? binDirectory.Parent ?? binDirectory
                : binDirectory;

            var javaArgs = new List<string>();
            var javawsArgs = new List<string>();
            foreach (var arg in args)
            {
                if (arg.StartsWith("-J", StringComparison.Ordinal))
                {
                    javaArgs.Add(arg[2..]);
                }
                else
                {
                    javawsArgs.Add(arg);
                }
            }

            var javaExecutable = ResolveJavaExecutable(installRoot, javawsArgs);
            var uberJar = ResolveRequiredFile(installRoot, "ITW_UBER_JAR",
                Path.Combine("lib", "icedtea-web-uber.jar"));
            var byteBuddyAgent = ResolveOptionalFile(installRoot, "ITW_BYTEBUDDY_AGENT_JAR",
                Path.Combine("bin", "byte-buddy-agent.jar"));

            var majorVersion = DetectJavaMajorVersion(javaExecutable);
            var command = ComposeJavaCommand(
                javaExecutable,
                uberJar,
                byteBuddyAgent,
                executablePath,
                launcherName,
                mainClass,
                majorVersion,
                javaArgs,
                javawsArgs);

            return RunJava(javaExecutable, command);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("IcedTea-Web .NET launcher failed: " + ex.Message);
            Console.Error.WriteLine(ex);
            return 1;
        }
    }

    private static string ResolveJavaExecutable(DirectoryInfo installRoot, IReadOnlyCollection<string> javawsArgs)
    {
        var forcedBundledJava = Environment.GetEnvironmentVariable("ITW_BUNDLED_JAVA");
        if (!string.IsNullOrWhiteSpace(forcedBundledJava) && File.Exists(forcedBundledJava))
        {
            return forcedBundledJava;
        }

        if (IsRelaunch(javawsArgs))
        {
            var configuredJava = ResolveConfiguredJavaExecutable();
            if (!string.IsNullOrWhiteSpace(configuredJava))
            {
                return configuredJava;
            }
        }

        var runtimeRoot = new DirectoryInfo(Path.Combine(installRoot.FullName, "runtime"));
        if (runtimeRoot.Exists)
        {
            var bundledJava = runtimeRoot.EnumerateFiles(JavaExecutableName(), SearchOption.AllDirectories)
                .FirstOrDefault(file => string.Equals(file.Directory?.Name, "bin", StringComparison.OrdinalIgnoreCase));
            if (bundledJava != null)
            {
                return bundledJava.FullName;
            }
        }

        var legacyJre = Path.Combine(installRoot.FullName, "jre", "bin", JavaExecutableName());
        if (File.Exists(legacyJre))
        {
            return legacyJre;
        }

        if (IsTruthy(Environment.GetEnvironmentVariable("ITW_USE_SYSTEM_JAVA")))
        {
            var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrWhiteSpace(javaHome))
            {
                var javaFromHome = Path.Combine(javaHome, "bin", JavaExecutableName());
                if (File.Exists(javaFromHome))
                {
                    return javaFromHome;
                }
            }

            return JavaExecutableName();
        }

        throw new FileNotFoundException(
            "Bundled Corretto runtime not found. Expected runtime/**/bin/" + JavaExecutableName()
            + " next to the Maven distribution launcher.");
    }

    private static bool IsRelaunch(IEnumerable<string> javawsArgs) =>
        javawsArgs.Any(arg => arg.Equals("-Xnofork", StringComparison.OrdinalIgnoreCase));

    private static string? ResolveConfiguredJavaExecutable()
    {
        var configuredJreDir = ReadDeploymentProperty("deployment.jre.dir");
        if (!string.IsNullOrWhiteSpace(configuredJreDir))
        {
            var configuredJava = Path.Combine(configuredJreDir, "bin", JavaExecutableName());
            if (File.Exists(configuredJava))
            {
                return configuredJava;
            }
        }

        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            var javaFromHome = Path.Combine(javaHome, "bin", JavaExecutableName());
            if (File.Exists(javaFromHome))
            {
                return javaFromHome;
            }
        }

        return null;
    }

    private static string? ReadDeploymentProperty(string key)
    {
        foreach (var path in CandidateDeploymentPropertyFiles())
        {
            if (!File.Exists(path))
            {
                continue;
            }

            foreach (var line in File.ReadLines(path))
            {
                var trimmed = line.Trim();
                if (trimmed.Length == 0 || trimmed.StartsWith("#", StringComparison.Ordinal))
                {
                    continue;
                }

                var separator = trimmed.IndexOf('=');
                if (separator <= 0)
                {
                    continue;
                }

                var name = trimmed[..separator].Trim();
                if (name.Equals(key, StringComparison.Ordinal))
                {
                    return trimmed[(separator + 1)..].Trim();
                }
            }
        }

        return null;
    }

    private static IEnumerable<string> CandidateDeploymentPropertyFiles()
    {
        var xdgConfigHome = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
        if (!string.IsNullOrWhiteSpace(xdgConfigHome))
        {
            yield return Path.Combine(xdgConfigHome, "icedtea-web", "deployment.properties");
        }

        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrWhiteSpace(userProfile))
        {
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            {
                yield return Path.Combine(userProfile, "AppData", "LocalLow", "Sun", "Java", "Deployment", "deployment.properties");
            }
            else
            {
                yield return Path.Combine(userProfile, ".config", "icedtea-web", "deployment.properties");
            }
        }
    }

    private static string ResolveRequiredFile(DirectoryInfo installRoot, string envVar, string relativePath)
    {
        var fromEnv = Environment.GetEnvironmentVariable(envVar);
        if (!string.IsNullOrWhiteSpace(fromEnv) && File.Exists(fromEnv))
        {
            return fromEnv;
        }

        var candidate = Path.Combine(installRoot.FullName, relativePath);
        if (File.Exists(candidate))
        {
            return candidate;
        }

        throw new FileNotFoundException("Required file not found: " + candidate);
    }

    private static string? ResolveOptionalFile(DirectoryInfo installRoot, string envVar, string relativePath)
    {
        var fromEnv = Environment.GetEnvironmentVariable(envVar);
        if (!string.IsNullOrWhiteSpace(fromEnv) && File.Exists(fromEnv))
        {
            return fromEnv;
        }

        var candidate = Path.Combine(installRoot.FullName, relativePath);
        return File.Exists(candidate) ? candidate : null;
    }

    private static List<string> ComposeJavaCommand(
        string javaExecutable,
        string uberJar,
        string? byteBuddyAgent,
        string launcherPath,
        string launcherName,
        string mainClass,
        int javaMajorVersion,
        IReadOnlyCollection<string> forwardedJvmArgs,
        IReadOnlyCollection<string> javawsArgs)
    {
        var command = new List<string> { "-Xms8m" };

        if (javaMajorVersion >= 9)
        {
            command.AddRange(ModularJdkArguments());
        }

        if (javaMajorVersion >= 18 && !HasSecurityManagerCompatibilityFlag(forwardedJvmArgs))
        {
            command.Add("-Djava.security.manager=allow");
        }

        command.AddRange(forwardedJvmArgs);

        if (javaMajorVersion <= 8)
        {
            command.Add("-Xbootclasspath/a:" + uberJar);
        }

        if (!string.IsNullOrWhiteSpace(byteBuddyAgent))
        {
            command.Add("-javaagent:" + byteBuddyAgent);
        }

        command.Add("-Dicedtea-web.bin.name=" + launcherName);
        command.Add("-Dicedtea-web.bin.location=" + launcherPath);
        command.Add("-cp");
        command.Add(uberJar);
        command.Add(mainClass);
        command.AddRange(javawsArgs);
        return command;
    }

    private static string ResolveMainClass(string launcherName)
    {
        var configuredMain = Environment.GetEnvironmentVariable("ITW_MAIN_CLASS");
        if (!string.IsNullOrWhiteSpace(configuredMain))
        {
            return configuredMain;
        }

        return launcherName.Equals("itweb-settings", StringComparison.OrdinalIgnoreCase)
            || launcherName.Equals("itweb_settings", StringComparison.OrdinalIgnoreCase)
            ? SettingsMainClass
            : JavawsMainClass;
    }

    private static int RunJava(string javaExecutable, IReadOnlyList<string> command)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = javaExecutable,
            UseShellExecute = false,
        };
        foreach (var arg in command)
        {
            startInfo.ArgumentList.Add(arg);
        }

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start Java process");
        process.WaitForExit();
        return process.ExitCode;
    }

    private static int DetectJavaMajorVersion(string javaExecutable)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = javaExecutable,
            UseShellExecute = false,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
        };
        startInfo.ArgumentList.Add("-version");

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start Java to detect version");
        var output = new StringBuilder();
        output.Append(process.StandardOutput.ReadToEnd());
        output.Append(process.StandardError.ReadToEnd());
        process.WaitForExit(10_000);

        var match = Regex.Match(output.ToString(), "version \"(?<version>[^\"]+)\"");
        if (!match.Success)
        {
            return 8;
        }

        var version = match.Groups["version"].Value;
        var firstPart = version.Split('.', '-', '+')[0];
        if (firstPart == "1")
        {
            var parts = version.Split('.');
            return parts.Length > 1 && int.TryParse(parts[1], out var legacyMajor) ? legacyMajor : 8;
        }

        return int.TryParse(firstPart, out var major) ? major : 8;
    }

    private static IEnumerable<string> ModularJdkArguments() => new[]
    {
        "--add-exports", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.action=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.provider=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.validator=ALL-UNNAMED",
        "--add-exports", "java.base/sun.security.x509=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.util.jar=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.util.jar=ALL-UNNAMED",
        "--add-exports", "java.base/sun.net.www.protocol.http=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.applet=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.awt.windows=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.awt.image=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.awt.X11=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.swing.table=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.swing=ALL-UNNAMED",
        "--add-exports", "java.desktop/sun.swing.plaf=ALL-UNNAMED",
        "--add-exports", "java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED",
        "--add-exports", "java.naming/com.sun.jndi.toolkit.url=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    };

    private static bool HasSecurityManagerCompatibilityFlag(IEnumerable<string> forwardedJvmArgs) =>
        forwardedJvmArgs.Any(arg => arg.StartsWith("-Djava.security.manager=", StringComparison.Ordinal));

    private static string JavaExecutableName() =>
        RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? "java.exe" : "java";

    private static bool IsTruthy(string? value) =>
        value != null && (value.Equals("true", StringComparison.OrdinalIgnoreCase)
            || value.Equals("1", StringComparison.OrdinalIgnoreCase)
            || value.Equals("yes", StringComparison.OrdinalIgnoreCase));
}
