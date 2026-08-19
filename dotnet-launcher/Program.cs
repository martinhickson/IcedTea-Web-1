using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
namespace IcedTeaWeb.Launcher;

internal static class Program
{
    private const string JavawsMainClass = "net.sourceforge.jnlp.runtime.JavawsUberLauncher";
    private const string SettingsMainClass = "net.sourceforge.jnlp.controlpanel.CommandLine";
    private const string PolicyEditorMainClass = "net.sourceforge.jnlp.security.policyeditor.PolicyEditor";
    private const string JavaVersionProbeArg = "--java-version";

    // GUI / non-console launches: Rust used Stdio::null() (discard). Set true to capture Java
    // stdout+stderr (combined) into ITW log files (deployment.user.logdir / XDG_CONFIG_HOME/icedtea-web/log).
    private const bool CaptureGuiStdioToLogFiles = false;

    // Console-preserving launches: Rust blocked on child.wait() with inherited stdio after AttachConsole.
    // true  = blocking stream drain for non-Windows / redirected fallback paths.
    // false = CopyToAsync relay; still waits at end, but stream pumping uses async I/O.
    private const bool WaitForJavaStdioSynchronously = true;

    private const string KeepJavawsProcessProperty = "deployment.keepJavawsProcess";
    private const string KeepJavaPrelaunchProcessProperty = "deployment.keepjavaPrelaunchProcess";
    private const string WindowsGrantForegroundProperty = "deployment.windows.grantForeground";
    private const string UserLogDirProperty = "deployment.user.logdir";
    private const string JvmIpTypeProperty = "deployment.jvm.ip.type";
    private const string PreferIpv4StackProperty = "java.net.preferIPv4Stack";
    private const string PreferIpv6AddressesProperty = "java.net.preferIPv6Addresses";
    private const int PrelaunchProbeTimeoutMs = 15_000;

    /// <summary>
    /// Set on the .NET process so child JVMs inherit it. Java uses this to tell
    /// native wrapper launches (javaws/javawsc/itweb-settings) apart from direct
    /// {@code java -cp uber.jar ...} runs, which must relaunch the same way.
    /// </summary>
    private const string NativeLauncherEnvVar = "ITW_NATIVE_LAUNCHER";

    private static int Main(string[] args)
    {
        var exitCode = 1;
        try
        {
            // Inherit to all CreateProcess / Process.Start children (lpEnvironment null).
            Environment.SetEnvironmentVariable(NativeLauncherEnvVar, "1");

            SplitLauncherArgs(args, out var javaArgs, out var javawsArgs);

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

#if ITW_LAUNCHER_CONSOLE
            const bool preserveStdio = true;
#else
            // Rust WinExe path: AttachConsole(ATTACH_PARENT_PROCESS) → inherit stdio when launched from cmd.
            var preserveStdio = ResolveGuiPreserveStdio();
#endif
            var javaExecutable = ResolveJavaExecutable(installRoot, javawsArgs, javaArgs);
            var uberJar = ResolveRequiredFile(installRoot, "ITW_UBER_JAR",
                Path.Combine("lib", "icedtea-web-uber.jar"));
            var byteBuddyAgent = ResolveOptionalFile(installRoot, "ITW_BYTEBUDDY_AGENT_JAR",
                Path.Combine("bin", "byte-buddy-agent.jar"));

            // Uber-jar --java-version probe always uses JavawsUberLauncher (Boot); CommandLine does not handle it.
            var majorVersion = DetectJavaMajorVersion(
                javaExecutable, uberJar, preserveStdio, javawsArgs);
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

            exitCode = RunJava(javaExecutable, command, preserveStdio, launcherName, javawsArgs);
        }
        catch (Exception ex)
        {
            WriteLauncherFailure(ex);
#if ITW_LAUNCHER_CONSOLE
            Console.Error.WriteLine("IcedTea-Web .NET launcher failed: " + ex.Message);
            Console.Error.WriteLine(ex);
#endif
            exitCode = 1;
        }

        return exitCode;
    }

#if !ITW_LAUNCHER_CONSOLE
    private static bool ResolveGuiPreserveStdio()
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            return NativeMethods.TryAttachParentConsole();
        }

        // Rust Linux::inside_console() is always true.
        return true;
    }
#endif

    private static void SplitLauncherArgs(
        string[] args,
        out List<string> javaArgs,
        out List<string> javawsArgs)
    {
        javaArgs = new List<string>();
        javawsArgs = new List<string>();
        foreach (var arg in args)
        {
            if (arg.StartsWith("-J", StringComparison.Ordinal))
            {
                javaArgs.Add(arg[2..]);
            }
            else
            {
                javawsArgs.Add(NormalizeLauncherArg(arg));
            }
        }
    }

    private static string ResolveJavaExecutable(
        DirectoryInfo installRoot,
        IReadOnlyCollection<string> javawsArgs,
        IReadOnlyCollection<string> javaArgs)
    {
        var forcedBundledJava = Environment.GetEnvironmentVariable("ITW_BUNDLED_JAVA");
        if (!string.IsNullOrWhiteSpace(forcedBundledJava) && File.Exists(forcedBundledJava))
        {
            return forcedBundledJava;
        }

        if (IsRelaunch(javawsArgs))
        {
            var configuredJava = ResolveConfiguredJavaExecutable(javaArgs);
            if (!string.IsNullOrWhiteSpace(configuredJava))
            {
                return configuredJava;
            }
        }

        var runtimeRoot = new DirectoryInfo(Path.Combine(installRoot.FullName, "runtime"));
        if (runtimeRoot.Exists)
        {
            // Preferred download JVM: the java under runtime\temurin-21. JDK 25 is
            // bundled but NOT the default — ITW's JarFileCloseProtection depends on
            // jdk.internal.util.jar which JDK 25 removed, so apps fail to load there.
            // Temurin 21 gives the modern TLS fingerprint (GREASE, X25519, ChaCha).
            // The Temurin tarball extracts to runtime\temurin-21\<jdk-ver>\bin\java.exe
            // (macOS: ...\Contents\Home\bin\java), so scan within temurin-21 rather than
            // a fixed path — a blind runtime-wide scan would be non-deterministic.
            var t21 = new DirectoryInfo(Path.Combine(runtimeRoot.FullName, "temurin-21"));
            if (t21.Exists)
            {
                var preferred = t21.EnumerateFiles(JavaExecutableName(), SearchOption.AllDirectories)
                    .FirstOrDefault(file => string.Equals(file.Directory?.Name, "bin", StringComparison.OrdinalIgnoreCase));
                if (preferred != null)
                {
                    return preferred.FullName;
                }
            }

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
            "Bundled Temurin runtime not found. Expected runtime/**/bin/" + JavaExecutableName()
            + " next to the Maven distribution launcher.");
    }

    private static bool IsRelaunch(IEnumerable<string> javawsArgs) =>
        javawsArgs.Any(arg => arg.Equals("-Xnofork", StringComparison.OrdinalIgnoreCase));

    private static string? ResolveConfiguredJavaExecutable(IReadOnlyCollection<string> javaArgs)
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

        var requestedJre = ExtractRequestedJreVersion(javaArgs);
        var knownHomes = ReadKnownJvmHomes();
        var selectedHome = SelectBestJvmHome(knownHomes, requestedJre);
        if (!string.IsNullOrWhiteSpace(selectedHome))
        {
            var selectedJava = Path.Combine(selectedHome, "bin", JavaExecutableName());
            if (File.Exists(selectedJava))
            {
                return selectedJava;
            }
        }

        var configuredJreDir = ReadDeploymentProperty("deployment.jre.dir");
        if (!string.IsNullOrWhiteSpace(configuredJreDir))
        {
            var configuredJava = Path.Combine(configuredJreDir, "bin", JavaExecutableName());
            if (File.Exists(configuredJava))
            {
                return configuredJava;
            }
        }

        return null;
    }

    private static string? ExtractRequestedJreVersion(IReadOnlyCollection<string> javaArgs)
    {
        const string prefix = "-Dicedtea-web.relaunch.requestedJre=";
        foreach (var arg in javaArgs)
        {
            if (arg.StartsWith(prefix, StringComparison.Ordinal))
            {
                return arg[prefix.Length..];
            }
        }
        return null;
    }

    private static IReadOnlyList<string> ReadKnownJvmHomes()
    {
        // Preference order: legacy deployment.jre.dir first, then numbered deployment.jdk.N.
        const int maxJdkEntries = 64;
        var homes = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        void AddHome(string? home)
        {
            if (string.IsNullOrWhiteSpace(home))
            {
                return;
            }
            var trimmed = home.Trim();
            if (seen.Add(trimmed))
            {
                homes.Add(trimmed);
            }
        }

        AddHome(ReadDeploymentProperty("deployment.jre.dir"));
        for (var i = 1; i <= maxJdkEntries; i++)
        {
            var home = ReadDeploymentProperty("deployment.jdk." + i);
            if (string.IsNullOrWhiteSpace(home))
            {
                break;
            }
            AddHome(home);
        }

        return homes;
    }

    private static string? SelectBestJvmHome(IReadOnlyList<string> homes, string? requestedVersion)
    {
        var candidates = new List<(string Home, int Major)>();
        foreach (var home in homes)
        {
            var java = Path.Combine(home, "bin", JavaExecutableName());
            if (!File.Exists(java))
            {
                continue;
            }
            candidates.Add((home, DetectJvmMajorVersion(home, java)));
        }

        if (candidates.Count == 0)
        {
            return null;
        }

        if (string.IsNullOrWhiteSpace(requestedVersion))
        {
            return candidates[0].Home;
        }

        var requestedMajor = ParseRequestedMajor(requestedVersion);
        // Version-level match first; among matches keep configured preference order.
        foreach (var candidate in candidates)
        {
            if (requestedMajor == 0 || candidate.Major == requestedMajor || VersionMatches(requestedVersion, candidate.Major))
            {
                return candidate.Home;
            }
        }

        return null;
    }

    private static bool VersionMatches(string requestedVersion, int major)
    {
        if (major <= 0)
        {
            return false;
        }
        return requestedVersion.Contains(major.ToString(), StringComparison.Ordinal)
            || requestedVersion.Contains("1." + major, StringComparison.Ordinal);
    }

    private static int ParseRequestedMajor(string requestedVersion)
    {
        var token = requestedVersion.Split('.', '+', '-', '_')[0];
        if (int.TryParse(token, out var major) && major > 1)
        {
            return major;
        }
        var parts = requestedVersion.Split('.', '+', '-', '_');
        if (parts.Length > 1 && parts[0] == "1" && int.TryParse(parts[1], out var legacyMajor))
        {
            return legacyMajor;
        }
        return 0;
    }

    private static int DetectJvmMajorVersion(string home, string javaExecutable)
    {
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = javaExecutable,
                Arguments = "-version",
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(startInfo);
            if (process == null)
            {
                return ParseMajorFromPath(home);
            }
            var stderr = process.StandardError.ReadToEnd();
            var stdout = process.StandardOutput.ReadToEnd();
            process.WaitForExit();
            var combined = stderr + "\n" + stdout;
            var quoted = System.Text.RegularExpressions.Regex.Match(combined, "\"([^\"]+)\"");
            if (quoted.Success)
            {
                return ParseRequestedMajor(quoted.Groups[1].Value);
            }
        }
        catch
        {
            // Fall back to path-based detection.
        }
        return ParseMajorFromPath(home);
    }

    private static int ParseMajorFromPath(string home)
    {
        var name = Path.GetFileName(home);
        var match = System.Text.RegularExpressions.Regex.Match(name, @"java-?(\d+)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (match.Success && int.TryParse(match.Groups[1].Value, out var major))
        {
            return major;
        }
        match = System.Text.RegularExpressions.Regex.Match(name, @"jdk-?(\d+)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (match.Success && int.TryParse(match.Groups[1].Value, out major))
        {
            return major;
        }
        return 0;
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
                yield return Path.Combine(userProfile, ".config", "icedtea-web", "deployment.properties");
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

        if (javaMajorVersion >= 18 && javaMajorVersion < 24
            && !HasSecurityManagerCompatibilityFlag(forwardedJvmArgs))
        {
            command.Add("-Djava.security.manager=allow");
        }

        // deployment.jvm.ip.type wins over any user -Djava.net.preferIPv* (default auto).
        command.AddRange(ApplyConfiguredIpStack(forwardedJvmArgs));

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

        if (IsSettingsLauncher(launcherName))
        {
            return SettingsMainClass;
        }

        if (launcherName.Equals("policyeditor", StringComparison.OrdinalIgnoreCase))
        {
            return PolicyEditorMainClass;
        }

        return JavawsMainClass;
    }

    private static int RunJava(
        string javaExecutable,
        IReadOnlyList<string> command,
        bool preserveStdio,
        string launcherName,
        IReadOnlyCollection<string> javawsArgs)
    {
#if ITW_LAUNCHER_CONSOLE
        return RunJavaWithInheritedStdio(javaExecutable, command);
#else
#pragma warning disable CS0162 // CaptureGuiStdioToLogFiles is false by default.
        if (CaptureGuiStdioToLogFiles)
        {
            return RunJavaWithRedirectedOutput(javaExecutable, command);
        }
#pragma warning restore CS0162

        if (!ShouldKeepJavawsProcess() && !ShouldWaitForCliChild(launcherName, javawsArgs))
        {
            return LaunchJavaDetachedAndExit(javaExecutable, command, "main");
        }

        if (preserveStdio)
        {
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
                && NativeMethods.TrySpawnProcessWithInheritedStdio(javaExecutable, command, out var exitCode))
            {
                return exitCode;
            }

            return RunJavaWithInheritedStdio(javaExecutable, command);
        }

        return RunJavaWithNullStdio(javaExecutable, command);
#endif
    }

    private static int RunJavaWithInheritedStdio(string javaExecutable, IReadOnlyList<string> command)
    {
        // Inherited stdio: javawsc (console subsystem) or javaws when Rust-style inside_console.
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
            ?? throw new InvalidOperationException("Unable to start Java process: " + javaExecutable);
        process.WaitForExit();
        return process.ExitCode;
    }

    /// <summary>
    /// True when argv is a text CLI / control operation that must wait and inherit stdio.
    /// Detach remains the default for GUI JNLP launches and bare settings/policyeditor.
    /// </summary>
    private static bool ShouldWaitForCliChild(string launcherName, IReadOnlyCollection<string> javawsArgs)
    {
        if (javawsArgs == null || javawsArgs.Count == 0)
        {
            return false;
        }

        if (IsSettingsLauncher(launcherName))
        {
            return true;
        }

        foreach (var arg in javawsArgs)
        {
            if (IsJavawsTextControlOption(arg))
            {
                return true;
            }
        }

        return false;
    }

    private static bool IsSettingsLauncher(string launcherName) =>
        launcherName.Equals("icedtea-web-settings", StringComparison.OrdinalIgnoreCase)
        || launcherName.Equals("icedtea_web_settings", StringComparison.OrdinalIgnoreCase)
        || launcherName.Equals("itweb-settings", StringComparison.OrdinalIgnoreCase);

    private static bool IsJavawsTextControlOption(string arg)
    {
        if (string.IsNullOrEmpty(arg) || arg.StartsWith("-J", StringComparison.Ordinal))
        {
            return false;
        }

        var key = arg.Split(':', 2)[0];
        return key.Equals("-version", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--version", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-help", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--help", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-?", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-license", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--license", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-Xcacheids", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-Xclearcache", StringComparison.OrdinalIgnoreCase);
    }

    private static string NormalizeLauncherArg(string arg) =>
        arg.Equals("--version", StringComparison.OrdinalIgnoreCase) ? "-version"
        : arg.Equals("--about", StringComparison.OrdinalIgnoreCase) ? "-about"
        : arg.Equals("--help", StringComparison.OrdinalIgnoreCase) ? "-help"
        : arg;

    private static int RunJavaWithNullStdio(string javaExecutable, IReadOnlyList<string> command)
    {
        // Match rust-launcher GUI path: java.exe + CREATE_NO_WINDOW + Stdio::null() at CreateProcess.
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            return NativeMethods.SpawnProcessWithNullStdio(javaExecutable, command);
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = javaExecutable,
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var arg in command)
        {
            startInfo.ArgumentList.Add(arg);
        }

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start Java process");
        process.StandardInput.Close();
        return WaitForProcessWithDrainedStreams(
            process,
            process.StandardOutput.BaseStream,
            process.StandardError.BaseStream,
            Stream.Null);
    }

    private static int RunJavaWithRedirectedOutput(string javaExecutable, IReadOnlyList<string> command)
    {
        var logPath = CreateLauncherLogPath("redirect");
        var startInfo = new ProcessStartInfo
        {
            FileName = javaExecutable,
            UseShellExecute = false,
            CreateNoWindow = RuntimeInformation.IsOSPlatform(OSPlatform.Windows),
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var arg in command)
        {
            startInfo.ArgumentList.Add(arg);
        }

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start Java process");
        using var log = File.Create(logPath);
        return WaitForProcessWithDrainedStreams(
            process,
            process.StandardOutput.BaseStream,
            process.StandardError.BaseStream,
            log);
    }

    private static int WaitForProcessWithDrainedStreams(
        Process process,
        Stream stdoutSource,
        Stream stderrSource,
        Stream logTarget)
    {
        var gate = new object();
        if (WaitForJavaStdioSynchronously)
        {
            var stdoutDrain = Task.Run(() => CopyStreamSynchronously(stdoutSource, logTarget, gate));
            var stderrDrain = Task.Run(() => CopyStreamSynchronously(stderrSource, logTarget, gate));
            process.WaitForExit();
            // Task.GetAwaiter().GetResult() is the .NET equivalent of Thread.Join on each drain thread.
            stdoutDrain.GetAwaiter().GetResult();
            stderrDrain.GetAwaiter().GetResult();
            return process.ExitCode;
        }

#pragma warning disable CS0162 // Unreachable when WaitForJavaStdioSynchronously is true (default).
        var stdoutRelay = Task.Run(() => CopyStreamSynchronously(stdoutSource, logTarget, gate));
        var stderrRelay = Task.Run(() => CopyStreamSynchronously(stderrSource, logTarget, gate));
        process.WaitForExit();
        stdoutRelay.GetAwaiter().GetResult();
        stderrRelay.GetAwaiter().GetResult();
        return process.ExitCode;
#pragma warning restore CS0162
    }

    private static void CopyStreamSynchronously(Stream source, Stream target, object gate)
    {
        var buffer = new byte[8192];
        int read;
        while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
        {
            lock (gate)
            {
                target.Write(buffer, 0, read);
                target.Flush();
            }
        }
    }

    private const string ChildPidPlaceholder = "__CHILD_PID__";

    private static string CreateLauncherLogPath(string launchKind)
    {
        var logDirectory = ResolveLauncherLogDirectory();
        Directory.CreateDirectory(logDirectory);
        var stamp = CreateItwLogStamp();
        var pid = Environment.ProcessId;
        var prelaunchSuffix = launchKind == "prelaunch" ? "-prelaunch" : "";
        var streamBase = "itw-javantx-" + stamp + "-" + pid + prelaunchSuffix;
        return Path.Combine(logDirectory, streamBase + ".log");
    }

    private static string CreateItwLogStamp()
    {
        var now = DateTime.Now;
        return RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? now.ToString("yyyy-MM-dd_HH_mm_ss.fff")
            : now.ToString("yyyy-MM-dd_HH:mm:ss.fff");
    }

    private static string ResolveLauncherLogDirectory()
    {
        var configured = ReadDeploymentProperty(UserLogDirProperty);
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return configured.Trim();
        }

        var xdgConfigHome = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
        if (!string.IsNullOrWhiteSpace(xdgConfigHome))
        {
            return Path.Combine(xdgConfigHome, "icedtea-web", "log");
        }

        var userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrWhiteSpace(userProfile))
        {
            return Path.Combine(userProfile, ".config", "icedtea-web", "log");
        }

        return Path.Combine(Path.GetTempPath(), "icedtea-web", "log");
    }

    private static bool ShouldKeepJavawsProcess() =>
        ReadDeploymentBooleanProperty(KeepJavawsProcessProperty, defaultValue: false);

    private static bool ShouldKeepJavaPrelaunchProcess() =>
        ReadDeploymentBooleanProperty(KeepJavaPrelaunchProcessProperty, defaultValue: false);

    /// <summary>
    /// Default true: grant Windows foreground permission to child JVMs after handoff.
    /// Set deployment.windows.grantForeground=false to disable.
    /// </summary>
    private static bool ShouldGrantForegroundToChild() =>
        ReadDeploymentBooleanProperty(WindowsGrantForegroundProperty, defaultValue: true);

    private static bool ReadDeploymentBooleanProperty(string key, bool defaultValue)
    {
        var raw = ReadDeploymentProperty(key);
        if (string.IsNullOrWhiteSpace(raw))
        {
            return defaultValue;
        }

        return raw.Equals("true", StringComparison.OrdinalIgnoreCase)
            || raw.Equals("1", StringComparison.Ordinal)
            || raw.Equals("yes", StringComparison.OrdinalIgnoreCase);
    }

    private static int LaunchJavaDetachedAndExit(
        string javaExecutable,
        IReadOnlyList<string> command,
        string launchKind)
    {
        var logPath = CreateLauncherLogPath(launchKind);

        var jvm = DescribeJvm(javaExecutable);
        WriteLaunchRecord(
            logPath,
            launchKind,
            ChildPidPlaceholder,
            javaExecutable,
            command,
            logPath,
            jvm.Vendor,
            jvm.Version);

        var childPid = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? NativeMethods.SpawnProcessWithFileStdio(javaExecutable, command, logPath)
            : SpawnProcessWithFileStdioManaged(javaExecutable, command, logPath);

        if (childPid <= 0)
        {
            throw new InvalidOperationException(
                "Unable to start detached Java process for " + launchKind + ": " + javaExecutable);
        }

        PatchLaunchRecordChildPid(logPath, childPid);
        return 0;
    }

    private static string ProbeUberJarWithDetachedPrelaunch(
        string probeExecutable,
        IReadOnlyList<string> command)
    {
        var logPath = CreateLauncherLogPath("prelaunch");

        var jvm = DescribeJvm(probeExecutable);
        WriteLaunchRecord(
            logPath,
            "prelaunch",
            ChildPidPlaceholder,
            probeExecutable,
            command,
            logPath,
            jvm.Vendor,
            jvm.Version);

        var childPid = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? NativeMethods.SpawnProcessWithFileStdio(probeExecutable, command, logPath)
            : SpawnProcessWithFileStdioManaged(probeExecutable, command, logPath);

        if (childPid <= 0)
        {
            throw new InvalidOperationException(
                "Unable to start detached Java prelaunch probe: " + probeExecutable);
        }

        PatchLaunchRecordChildPid(logPath, childPid);

        return ReadFirstStdoutLineFromLog(logPath, childPid, PrelaunchProbeTimeoutMs);
    }

    private static string ReadFirstStdoutLineFromLog(string logPath, int childPid, int timeoutMs)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        var pastHandoff = false;
        while (Environment.TickCount64 < deadline)
        {
            if (File.Exists(logPath))
            {
                var text = File.ReadAllText(logPath);
                foreach (var line in text.Split('\n', '\r'))
                {
                    var trimmed = line.Trim();
                    if (trimmed.Length == 0)
                    {
                        continue;
                    }
                    if (!pastHandoff)
                    {
                        if (trimmed.StartsWith("Handoff complete:", StringComparison.Ordinal))
                        {
                            pastHandoff = true;
                        }
                        continue;
                    }
                    return trimmed;
                }
            }

            try
            {
                using var process = Process.GetProcessById(childPid);
                if (process.HasExited)
                {
                    break;
                }
            }
            catch (ArgumentException)
            {
                break;
            }

            Thread.Sleep(50);
        }

        return string.Empty;
    }

    private static int SpawnProcessWithFileStdioManaged(
        string executable,
        IReadOnlyList<string> command,
        string logPath)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(logPath) ?? ".");

        var logTarget = new FileStream(
            logPath,
            FileMode.OpenOrCreate,
            FileAccess.Write,
            FileShare.ReadWrite);
        logTarget.Seek(0, SeekOrigin.End);
        var gate = new object();
        var remainingPumps = 2;

        var startInfo = new ProcessStartInfo
        {
            FileName = executable,
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        foreach (var arg in command)
        {
            startInfo.ArgumentList.Add(arg);
        }

        var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start process: " + executable);
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            NativeMethods.TryGrantForegroundToChild(process.Id);
        }
        process.StandardInput.Close();

        void Pump(Stream source)
        {
            try
            {
                var buffer = new byte[8192];
                int read;
                while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
                {
                    lock (gate)
                    {
                        logTarget.Write(buffer, 0, read);
                        logTarget.Flush();
                    }
                }
            }
            catch
            {
                // Detached launcher must not fail if the child closes streams early.
            }
            finally
            {
                if (Interlocked.Decrement(ref remainingPumps) == 0)
                {
                    try
                    {
                        logTarget.Flush();
                        logTarget.Dispose();
                    }
                    catch
                    {
                    }
                }
            }
        }

        _ = Task.Run(() => Pump(process.StandardOutput.BaseStream));
        _ = Task.Run(() => Pump(process.StandardError.BaseStream));
        return process.Id;
    }

    private readonly struct JvmDescription
    {
        public JvmDescription(string vendor, string version)
        {
            Vendor = vendor;
            Version = version;
        }

        public string Vendor { get; }
        public string Version { get; }
    }

    private static JvmDescription DescribeJvm(string javaExecutable)
    {
        try
        {
            var output = SpawnProcessCaptureStderrManaged(javaExecutable, new List<string> { "-version" });
            return ParseJvmDescription(output);
        }
        catch
        {
            return new JvmDescription("unknown", "unknown");
        }
    }

    private static JvmDescription ParseJvmDescription(string output)
    {
        var vendor = "unknown";
        var version = "unknown";
        foreach (var line in output.Split('\n', '\r'))
        {
            if (line.Contains("version", StringComparison.OrdinalIgnoreCase))
            {
                var quoteStart = line.IndexOf('"');
                if (quoteStart >= 0)
                {
                    var remainder = line[(quoteStart + 1)..];
                    var quoteEnd = remainder.IndexOf('"');
                    version = quoteEnd > 0 ? remainder[..quoteEnd] : remainder.Trim();
                }

                var open = line.IndexOf('(');
                var close = line.IndexOf(')');
                if (open >= 0 && close > open)
                {
                    var runtime = line[(open + 1)..close];
                    var slash = runtime.IndexOf('/');
                    vendor = slash > 0 ? runtime[..slash].Trim() : runtime.Trim();
                }
                break;
            }
        }

        return new JvmDescription(vendor, version);
    }

    private static void WriteLaunchRecord(
        string logPath,
        string launchKind,
        object childPid,
        string javaExecutable,
        IReadOnlyList<string> command,
        string combinedRedirectPath,
        string jvmVendor,
        string jvmVersion)
    {
        var launcherProcess = Process.GetCurrentProcess();
        var builder = new StringBuilder();
        builder.AppendLine("IcedTea-Web .NET launcher handoff record");
        builder.AppendLine("Handoff step: " + launchKind);
        builder.AppendLine("Handoff status: SUCCESS");
        builder.AppendLine("Parent process ID (handing off): " + launcherProcess.Id);
        builder.AppendLine("Parent process name: " + launcherProcess.ProcessName);
        builder.AppendLine("Parent executable: " + (Environment.ProcessPath ?? "unknown"));
        builder.AppendLine("Child process ID (handed to): " + childPid);
        builder.AppendLine("Child executable: " + javaExecutable);
        builder.AppendLine("Child JVM vendor: " + jvmVendor);
        builder.AppendLine("Child JVM version: " + jvmVersion);
        builder.AppendLine(".NET runtime: " + RuntimeInformation.FrameworkDescription);
        builder.AppendLine(".NET version: " + Environment.Version);
        builder.AppendLine("Standard Input stream: NUL device (no pipe from parent; child cannot block parent on stdin)");
        builder.AppendLine("Standard Output and Standard Error written to: " + Path.GetFullPath(combinedRedirectPath)
            + " (combined file redirect; parent exited; child writes directly; no pipe buffer stall risk)");
        builder.AppendLine("Working directory: " + Environment.CurrentDirectory);
        builder.AppendLine("OS: " + RuntimeInformation.OSDescription);
        builder.AppendLine("Architecture: " + RuntimeInformation.OSArchitecture);
        builder.AppendLine("Command:");
        builder.AppendLine(javaExecutable + " " + string.Join(' ', command));
        builder.AppendLine("Handoff complete: parent launcher exiting without waiting for child process.");
        builder.AppendLine();
        File.WriteAllText(logPath, builder.ToString());
    }

    private static void PatchLaunchRecordChildPid(string logPath, int childPid)
    {
        var text = File.ReadAllText(logPath);
        var patched = text.Replace(
            "Child process ID (handed to): " + ChildPidPlaceholder,
            "Child process ID (handed to): " + childPid,
            StringComparison.Ordinal);
        if (!string.Equals(text, patched, StringComparison.Ordinal))
        {
            File.WriteAllText(logPath, patched);
        }
    }

    private static void WriteLauncherFailure(Exception ex)
    {
        try
        {
            var logDirectory = ResolveLauncherLogDirectory();
            Directory.CreateDirectory(logDirectory);
            var logPath = Path.Combine(
                logDirectory,
                "itw-launcher-error-" + CreateItwLogStamp() + "-" + Environment.ProcessId + ".log");
            File.WriteAllText(logPath, "IcedTea-Web .NET launcher failed: " + ex + Environment.NewLine);
        }
        catch
        {
            // Avoid masking the original launcher failure.
        }
    }

    private static int DetectJavaMajorVersion(
        string javaExecutable,
        string uberJar,
        bool preserveStdio,
        IReadOnlyCollection<string> javawsArgs)
    {
        // Prefer ITW uber-jar --java-version (headless, no parent console). Fall back to
        // java -version when the uber-jar probe fails (e.g. javaw stdout quirks on Windows).
        try
        {
            return ProbeJavaMajorVersionFromUberJar(javaExecutable, uberJar);
        }
        catch (InvalidOperationException)
        {
            return ProbeJavaMajorVersionFromJavaExecutable(javaExecutable);
        }
    }

    private static int ProbeJavaMajorVersionFromJavaExecutable(string javaExecutable)
    {
        var probeExecutable = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? ResolveJavawExecutable(javaExecutable)
            : javaExecutable;
        var stderr = SpawnProcessCaptureStderrManaged(probeExecutable, new List<string> { "-version" });
        return ParseJavaMajorVersionFromVersionOutput(stderr);
    }

    private static int ParseJavaMajorVersionFromVersionOutput(string output)
    {
        foreach (var line in output.Split('\n', '\r'))
        {
            if (!line.Contains("version", StringComparison.Ordinal))
            {
                continue;
            }

            var quoteStart = line.IndexOf('"');
            if (quoteStart < 0)
            {
                continue;
            }

            var version = line[(quoteStart + 1)..];
            var quoteEnd = version.IndexOf('"');
            if (quoteEnd > 0)
            {
                version = version[..quoteEnd];
            }

            if (version.StartsWith("1.", StringComparison.Ordinal))
            {
                var parts = version.Split('.');
                if (parts.Length >= 2 && int.TryParse(parts[1], out var legacyMajor) && legacyMajor > 0)
                {
                    return legacyMajor;
                }
            }
            else
            {
                var dot = version.IndexOf('.');
                var majorToken = dot > 0 ? version[..dot] : version;
                if (int.TryParse(majorToken, out var major) && major > 0)
                {
                    return major;
                }
            }
        }

        throw new InvalidOperationException(
            "Unable to parse Java major version from java -version output: " + output);
    }

    private static int ProbeJavaMajorVersionFromUberJar(
        string javaExecutable,
        string uberJar)
    {
        // --java-version exits before ITW needs module opens. Default: detached prelaunch handoff
        // (launcher exits; stdout/stderr go to per-launch log files). Opt-in blocking via
        // deployment.keepjavaPrelaunchProcess=true uses piped capture instead.
        var probeCommand = new List<string> { "-Xms8m", "-cp", uberJar, JavawsMainClass, JavaVersionProbeArg };

        string stdout;
        if (ShouldKeepJavaPrelaunchProcess())
        {
            stdout = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
                ? NativeMethods.SpawnProcessCaptureStdout(javaExecutable, probeCommand)
                : SpawnProcessCaptureStdoutManaged(javaExecutable, probeCommand);
        }
        else
        {
            stdout = ProbeUberJarWithDetachedPrelaunch(javaExecutable, probeCommand);
        }

        var firstLine = stdout.Trim().Split('\n', '\r')[0].Trim();
        if (int.TryParse(firstLine, out var major) && major > 0)
        {
            return major;
        }

        throw new InvalidOperationException(
            "Unable to parse Java major version from uber jar probe output: " + stdout);
    }

    private static string SpawnProcessCaptureStdoutManaged(string executable, IReadOnlyList<string> command)
    {
        var startInfo = CreateCaptureProcessStartInfo(executable, command);
        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start process for stdout capture");
        process.StandardInput.Close();
        var stdout = process.StandardOutput.ReadToEnd();
        _ = process.StandardError.ReadToEnd();
        process.WaitForExit(10_000);
        return stdout;
    }

    private static string SpawnProcessCaptureStderrManaged(string executable, IReadOnlyList<string> command)
    {
        var startInfo = CreateCaptureProcessStartInfo(executable, command);
        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Unable to start process for stderr capture");
        process.StandardInput.Close();
        _ = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit(10_000);
        return stderr;
    }

    private static ProcessStartInfo CreateCaptureProcessStartInfo(
        string executable,
        IReadOnlyList<string> command)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = executable,
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = RuntimeInformation.IsOSPlatform(OSPlatform.Windows),
        };
        foreach (var arg in command)
        {
            startInfo.ArgumentList.Add(arg);
        }

        return startInfo;
    }

    private static string ResolveJavawExecutable(string javaExecutable)
    {
        var javaDir = Path.GetDirectoryName(javaExecutable);
        if (javaDir == null)
        {
            return javaExecutable;
        }

        var javaw = Path.Combine(javaDir, "javaw.exe");
        return File.Exists(javaw) ? javaw : javaExecutable;
    }

    /// <summary>
    /// Quote for CreateProcessW / CommandLineToArgvW so paths with spaces (and embedded quotes)
    /// survive as a single argv token — required for JNLP paths like {@code Sonata (4).jnlpx}.
    /// </summary>
    private static string QuoteCommandLineArg(string arg)
    {
        if (arg.Length == 0)
        {
            return "\"\"";
        }

        if (!arg.Any(static c => char.IsWhiteSpace(c) || c == '"'))
        {
            return arg;
        }

        var builder = new StringBuilder(arg.Length + 8);
        builder.Append('"');
        var backslashes = 0;
        foreach (var ch in arg)
        {
            if (ch == '\\')
            {
                backslashes++;
                continue;
            }

            if (ch == '"')
            {
                builder.Append('\\', backslashes * 2 + 1);
                builder.Append('"');
                backslashes = 0;
                continue;
            }

            if (backslashes > 0)
            {
                builder.Append('\\', backslashes);
                backslashes = 0;
            }
            builder.Append(ch);
        }

        // Trailing backslashes before the closing quote must be doubled.
        if (backslashes > 0)
        {
            builder.Append('\\', backslashes * 2);
        }
        builder.Append('"');
        return builder.ToString();
    }

    private static IEnumerable<string> ModularJdkArguments()
    {
        var args = new List<string>
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
            "--add-exports", "java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.swing.table=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.swing=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.swing.plaf=ALL-UNNAMED",
            "--add-exports", "java.naming/com.sun.jndi.toolkit.url=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        };

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            args.Add("--add-exports");
            args.Add("java.desktop/sun.awt.windows=ALL-UNNAMED");
            args.Add("--add-exports");
            args.Add("java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED");
        }

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            args.Add("--add-exports");
            args.Add("java.desktop/sun.awt.X11=ALL-UNNAMED");
        }

        return args;
    }

    private static bool HasSecurityManagerCompatibilityFlag(IEnumerable<string> forwardedJvmArgs) =>
        forwardedJvmArgs.Any(arg => arg.StartsWith("-Djava.security.manager=", StringComparison.Ordinal));

    /// <summary>
    /// Apply deployment.jvm.ip.type to JVM args. Removes any user prefer-IP -D flags,
    /// then injects ipv4 or ipv6 settings. auto (default) leaves prefer-IP unset.
    /// </summary>
    private static List<string> ApplyConfiguredIpStack(IEnumerable<string> forwardedJvmArgs)
    {
        var args = forwardedJvmArgs
            .Where(arg => !IsPreferIpJvmArg(arg))
            .ToList();
        switch (ResolveConfiguredIpType())
        {
            case "ipv6":
                args.Add("-D" + PreferIpv4StackProperty + "=false");
                args.Add("-D" + PreferIpv6AddressesProperty + "=true");
                break;
            case "auto":
                break;
            default:
                args.Add("-D" + PreferIpv4StackProperty + "=true");
                break;
        }
        return args;
    }

    private static bool IsPreferIpJvmArg(string arg) =>
        arg.StartsWith("-D" + PreferIpv4StackProperty, StringComparison.Ordinal)
        || arg.StartsWith("-D" + PreferIpv6AddressesProperty, StringComparison.Ordinal);

    private static string ResolveConfiguredIpType()
    {
        var raw = ReadDeploymentProperty(JvmIpTypeProperty);
        if (string.IsNullOrWhiteSpace(raw))
        {
            return "auto";
        }

        return raw.Trim().ToLowerInvariant() switch
        {
            "ipv6" => "ipv6",
            "auto" => "auto",
            "ipv4" => "ipv4",
            _ => "auto",
        };
    }

    private static string JavaExecutableName() =>
        RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? "java.exe" : "java";

    private static bool IsTruthy(string? value) =>
        value != null && (value.Equals("true", StringComparison.OrdinalIgnoreCase)
            || value.Equals("1", StringComparison.OrdinalIgnoreCase)
            || value.Equals("yes", StringComparison.OrdinalIgnoreCase));

    private static class NativeMethods
    {
        public const int ErrorAccessDenied = 5;

        private const uint CreateNoWindow = 0x08000000;
        private const uint StartfUsestdhandles = 0x00000100;
        private const uint GenericRead = 0x80000000;
        private const uint GenericWrite = 0x40000000;
        private const uint FileShareRead = 0x00000001;
        private const uint FileShareWrite = 0x00000002;
        private const uint OpenExisting = 3;
        private const uint CreateAlways = 2;
        private const uint OpenAlways = 4;
        private const uint FileEnd = 2;
        private const uint FileAttributeNormal = 0x00000080;
        private const uint Infinite = 0xFFFFFFFF;
        private const uint HandleFlagInherit = 0x00000001;

        public const int StdInputHandle = -10;
        public const int StdOutputHandle = -11;
        public const int StdErrorHandle = -12;
        public const int SwHide = 0;
        private const ushort ConsoleKeyEvent = 0x0001;
        private const ushort VkReturn = 0x000D;
        private const uint InputKeyboard = 1;
        private const uint KeyeventfKeyup = 0x0002;
        public static readonly IntPtr InvalidHandleValue = new(-1);

        private const uint FileTypeDisk = 0x00000001;
        private const uint FileTypePipe = 0x00000003;

        private static uint savedConsoleInputMode;
        private static bool savedConsoleInputModeValid;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint GetFileType(IntPtr hFile);

        public static bool HasRedirectedStdoutFromParent()
        {
            var stdout = GetStdHandle(StdOutputHandle);
            if (stdout == InvalidHandleValue || stdout == IntPtr.Zero)
            {
                return false;
            }

            return GetFileType(stdout) is FileTypeDisk or FileTypePipe;
        }

        // INPUT must be 40 bytes on x64 (union sized for MOUSEINPUT); smaller structs make SendInput fail.
        private static readonly int InputRecordSize = Marshal.SizeOf<SendInputRecord>();

        private const uint AttachParentProcess = 0xFFFFFFFF;

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool AttachConsole(uint dwProcessId);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool FreeConsole();

        public static bool TryAttachParentConsole()
        {
            if (!AttachConsole(AttachParentProcess))
            {
                return false;
            }

            RebindStandardHandlesToAttachedConsole();
            return true;
        }

        [DllImport("kernel32.dll")]
        public static extern IntPtr GetConsoleWindow();

        [DllImport("user32.dll")]
        public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        /// <summary>
        /// Enables the specified process to call SetForegroundWindow. The caller
        /// must already be allowed to set the foreground window (e.g. user-launched
        /// javaws, or a parent that granted us rights). Used so detached Java UI
        /// is not stuck behind other windows after launcher handoff.
        /// </summary>
        [DllImport("user32.dll")]
        public static extern bool AllowSetForegroundWindow(int dwProcessId);

        public const int AsfwAny = -1;

        public static void TryGrantForegroundToChild(int childPid)
        {
            if (childPid <= 0 || !ShouldGrantForegroundToChild())
            {
                return;
            }

            try
            {
                _ = AllowSetForegroundWindow(childPid);
                // Also allow a further hop (this process exits; child may spawn UI).
                _ = AllowSetForegroundWindow(AsfwAny);
            }
            catch
            {
                // Best-effort only; launch must continue.
            }
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr GetStdHandle(int nStdHandle);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetStdHandle(int nStdHandle, IntPtr hHandle);

        public static void RebindStandardHandlesToAttachedConsole()
        {
            RebindConsoleDevice(StdInputHandle, "CONIN$", GenericRead | GenericWrite,
                FileShareRead | FileShareWrite);
            RebindConsoleDevice(StdOutputHandle, "CONOUT$", GenericWrite | GenericRead,
                FileShareWrite);
            RebindConsoleDevice(StdErrorHandle, "CONOUT$", GenericWrite | GenericRead,
                FileShareWrite);
        }

        private static void RebindConsoleDevice(
            int stdHandleType,
            string deviceName,
            uint desiredAccess,
            uint shareMode)
        {
            var handle = CreateFileW(
                deviceName,
                desiredAccess,
                shareMode,
                IntPtr.Zero,
                OpenExisting,
                0,
                IntPtr.Zero);
            if (handle == InvalidHandleValue || handle == IntPtr.Zero)
            {
                throw new InvalidOperationException(
                    "Unable to open " + deviceName + " after AttachConsole: " + Marshal.GetLastWin32Error());
            }

            if (!SetStdHandle(stdHandleType, handle))
            {
                CloseHandle(handle);
                throw new InvalidOperationException(
                    "SetStdHandle failed for " + deviceName + ": " + Marshal.GetLastWin32Error());
            }
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool WriteConsole(
            IntPtr hConsoleOutput,
            string lpBuffer,
            uint nNumberOfCharsToWrite,
            out uint lpNumberOfCharsWritten,
            IntPtr lpReserved);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool WriteConsoleInput(
            IntPtr hConsoleInput,
            ConsoleInputRecord[] lpBuffer,
            uint nLength,
            out uint lpNumberOfEventsWritten);

        public static void SaveConsoleInputMode()
        {
            var stdin = GetStdHandle(StdInputHandle);
            if (stdin != InvalidHandleValue && stdin != IntPtr.Zero
                && GetConsoleMode(stdin, out var mode))
            {
                savedConsoleInputMode = mode;
                savedConsoleInputModeValid = true;
            }
        }

        public static void ReleaseAttachedConsoleForParentShell()
        {
            if (savedConsoleInputModeValid)
            {
                var stdin = GetStdHandle(StdInputHandle);
                if (stdin != InvalidHandleValue && stdin != IntPtr.Zero)
                {
                    _ = SetConsoleMode(stdin, savedConsoleInputMode);
                }
            }

            var stdout = GetStdHandle(StdOutputHandle);
            if (stdout != InvalidHandleValue && stdout != IntPtr.Zero)
            {
                _ = WriteConsole(stdout, Environment.NewLine, (uint)Environment.NewLine.Length,
                    out _, IntPtr.Zero);
            }

            SendEnterKeyToConsoleStdin();
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CreateProcessW(
            string? lpApplicationName,
            StringBuilder lpCommandLine,
            IntPtr lpProcessAttributes,
            IntPtr lpThreadAttributes,
            bool bInheritHandles,
            uint dwCreationFlags,
            IntPtr lpEnvironment,
            string? lpCurrentDirectory,
            ref StartupInfoW lpStartupInfo,
            out ProcessInformation lpProcessInformation);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr CreateFileW(
            string lpFileName,
            uint dwDesiredAccess,
            uint dwShareMode,
            IntPtr lpSecurityAttributes,
            uint dwCreationDisposition,
            uint dwFlagsAndAttributes,
            IntPtr hTemplateFile);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr hObject);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CreatePipe(
            out IntPtr hReadPipe,
            out IntPtr hWritePipe,
            ref SecurityAttributes lpPipeAttributes,
            uint nSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool ReadFile(
            IntPtr hFile,
            byte[] lpBuffer,
            uint nNumberOfBytesToRead,
            out uint lpNumberOfBytesRead,
            IntPtr lpOverlapped);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetHandleInformation(IntPtr hObject, uint dwMask, uint dwFlags);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, SendInputRecord[] pInputs, int cbSize);

        public static string SpawnProcessCaptureStdout(string executable, IReadOnlyList<string> command)
        {
            var securityAttributes = new SecurityAttributes
            {
                nLength = Marshal.SizeOf<SecurityAttributes>(),
                bInheritHandle = true,
            };
            if (!CreatePipe(out var stdoutRead, out var stdoutWrite, ref securityAttributes, 0))
            {
                throw new InvalidOperationException(
                    "CreatePipe failed for version probe: " + Marshal.GetLastWin32Error());
            }

            var nullHandle = CreateFileW(
                "NUL",
                GenericRead | GenericWrite,
                FileShareRead,
                IntPtr.Zero,
                OpenExisting,
                0,
                IntPtr.Zero);
            if (nullHandle == new IntPtr(-1) || nullHandle == IntPtr.Zero)
            {
                CloseHandle(stdoutRead);
                CloseHandle(stdoutWrite);
                throw new InvalidOperationException(
                    "Unable to open NUL device for version probe: " + Marshal.GetLastWin32Error());
            }

            try
            {
                SetHandleInformation(stdoutRead, HandleFlagInherit, 0);

                var commandLine = new StringBuilder();
                commandLine.Append('"').Append(executable).Append('"');
                foreach (var arg in command)
                {
                    commandLine.Append(' ').Append(QuoteCommandLineArg(arg));
                }

                var startupInfo = new StartupInfoW
                {
                    cb = (uint)Marshal.SizeOf<StartupInfoW>(),
                    dwFlags = StartfUsestdhandles,
                    hStdInput = nullHandle,
                    hStdOutput = stdoutWrite,
                    hStdError = nullHandle,
                };

                if (!CreateProcessW(
                    null,
                    commandLine,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    true,
                    CreateNoWindow,
                    IntPtr.Zero,
                    null,
                    ref startupInfo,
                    out var processInfo))
                {
                    throw new InvalidOperationException(
                        "CreateProcess failed for version probe: " + Marshal.GetLastWin32Error());
                }

                CloseHandle(stdoutWrite);
                stdoutWrite = IntPtr.Zero;
                CloseHandle(processInfo.hThread);

                try
                {
                    _ = WaitForSingleObject(processInfo.hProcess, Infinite);
                    return ReadPipeToString(stdoutRead);
                }
                finally
                {
                    CloseHandle(processInfo.hProcess);
                }
            }
            finally
            {
                CloseHandle(stdoutRead);
                if (stdoutWrite != IntPtr.Zero)
                {
                    CloseHandle(stdoutWrite);
                }

                CloseHandle(nullHandle);
            }
        }

        private static string ReadPipeToString(IntPtr pipeRead)
        {
            var buffer = new byte[4096];
            var builder = new StringBuilder();
            while (ReadFile(pipeRead, buffer, (uint)buffer.Length, out var bytesRead, IntPtr.Zero) && bytesRead > 0)
            {
                builder.Append(Encoding.UTF8.GetString(buffer, 0, (int)bytesRead));
            }

            return builder.ToString();
        }

        public static bool TrySpawnProcessWithInheritedStdio(
            string executable,
            IReadOnlyList<string> command,
            out int exitCode)
        {
            exitCode = 1;
            var stdin = GetStdHandle(StdInputHandle);
            var stdout = GetStdHandle(StdOutputHandle);
            var stderr = GetStdHandle(StdErrorHandle);
            if (stdin == InvalidHandleValue || stdin == IntPtr.Zero
                || stdout == InvalidHandleValue || stdout == IntPtr.Zero
                || stderr == InvalidHandleValue || stderr == IntPtr.Zero)
            {
                return false;
            }

            _ = SetHandleInformation(stdin, HandleFlagInherit, HandleFlagInherit);
            _ = SetHandleInformation(stdout, HandleFlagInherit, HandleFlagInherit);
            _ = SetHandleInformation(stderr, HandleFlagInherit, HandleFlagInherit);

            var commandLine = new StringBuilder();
            commandLine.Append('"').Append(executable).Append('"');
            foreach (var arg in command)
            {
                commandLine.Append(' ').Append(QuoteCommandLineArg(arg));
            }

            var startupInfo = new StartupInfoW
            {
                cb = (uint)Marshal.SizeOf<StartupInfoW>(),
                dwFlags = StartfUsestdhandles,
                hStdInput = stdin,
                hStdOutput = stdout,
                hStdError = stderr,
            };

            if (!CreateProcessW(
                null,
                commandLine,
                IntPtr.Zero,
                IntPtr.Zero,
                true,
                0,
                IntPtr.Zero,
                null,
                ref startupInfo,
                out var processInfo))
            {
                return false;
            }

            CloseHandle(processInfo.hThread);
            try
            {
                _ = WaitForSingleObject(processInfo.hProcess, Infinite);
                GetExitCodeProcess(processInfo.hProcess, out var rawExitCode);
                exitCode = (int)rawExitCode;
                return true;
            }
            finally
            {
                CloseHandle(processInfo.hProcess);
            }
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint SetFilePointer(IntPtr hFile, int lDistanceToMove, IntPtr lpDistanceToMoveHigh, uint dwMoveMethod);

        public static int SpawnProcessWithFileStdio(
            string executable,
            IReadOnlyList<string> command,
            string logPath)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(logPath) ?? ".");

            // One file handle for both stdout and stderr (combined log; interleaving OK).
            var logHandle = CreateFileW(
                logPath,
                GenericWrite,
                FileShareRead | FileShareWrite,
                IntPtr.Zero,
                OpenAlways,
                FileAttributeNormal,
                IntPtr.Zero);
            if (logHandle == InvalidHandleValue || logHandle == IntPtr.Zero)
            {
                throw new InvalidOperationException(
                    "Unable to open log file for detached launch: " + Marshal.GetLastWin32Error());
            }

            _ = SetFilePointer(logHandle, 0, IntPtr.Zero, FileEnd);

            var nullHandle = CreateFileW(
                "NUL",
                GenericRead | GenericWrite,
                FileShareRead,
                IntPtr.Zero,
                OpenExisting,
                0,
                IntPtr.Zero);
            if (nullHandle == InvalidHandleValue || nullHandle == IntPtr.Zero)
            {
                CloseHandle(logHandle);
                throw new InvalidOperationException(
                    "Unable to open NUL device for detached launch: " + Marshal.GetLastWin32Error());
            }

            try
            {
                var commandLine = new StringBuilder();
                commandLine.Append('"').Append(executable).Append('"');
                foreach (var arg in command)
                {
                    commandLine.Append(' ').Append(QuoteCommandLineArg(arg));
                }

                var startupInfo = new StartupInfoW
                {
                    cb = (uint)Marshal.SizeOf<StartupInfoW>(),
                    dwFlags = StartfUsestdhandles,
                    hStdInput = nullHandle,
                    hStdOutput = logHandle,
                    hStdError = logHandle,
                };

                if (!CreateProcessW(
                    null,
                    commandLine,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    true,
                    CreateNoWindow,
                    IntPtr.Zero,
                    null,
                    ref startupInfo,
                    out var processInfo))
                {
                    throw new InvalidOperationException(
                        "CreateProcess failed for detached launch: " + Marshal.GetLastWin32Error());
                }

                var childPid = (int)processInfo.dwProcessId;
                TryGrantForegroundToChild(childPid);
                CloseHandle(processInfo.hThread);
                CloseHandle(processInfo.hProcess);
                return childPid;
            }
            finally
            {
                CloseHandle(logHandle);
                CloseHandle(nullHandle);
            }
        }

        public static int SpawnProcessWithNullStdio(string executable, IReadOnlyList<string> command)
        {
            var nullHandle = CreateFileW(
                "NUL",
                GenericRead | GenericWrite,
                FileShareRead,
                IntPtr.Zero,
                OpenExisting,
                0,
                IntPtr.Zero);
            if (nullHandle == new IntPtr(-1) || nullHandle == IntPtr.Zero)
            {
                throw new InvalidOperationException(
                    "Unable to open NUL device for null stdio: " + Marshal.GetLastWin32Error());
            }

            try
            {
                var commandLine = new StringBuilder();
                commandLine.Append('"').Append(executable).Append('"');
                foreach (var arg in command)
                {
                    commandLine.Append(' ').Append(QuoteCommandLineArg(arg));
                }

                var startupInfo = new StartupInfoW
                {
                    cb = (uint)Marshal.SizeOf<StartupInfoW>(),
                    dwFlags = StartfUsestdhandles,
                    hStdInput = nullHandle,
                    hStdOutput = nullHandle,
                    hStdError = nullHandle,
                };

                if (!CreateProcessW(
                    null,
                    commandLine,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    true,
                    CreateNoWindow,
                    IntPtr.Zero,
                    null,
                    ref startupInfo,
                    out var processInfo))
                {
                    throw new InvalidOperationException(
                        "CreateProcess failed for Java launch: " + Marshal.GetLastWin32Error());
                }

                try
                {
                    TryGrantForegroundToChild((int)processInfo.dwProcessId);
                    CloseHandle(processInfo.hThread);
                    _ = WaitForSingleObject(processInfo.hProcess, Infinite);
                    GetExitCodeProcess(processInfo.hProcess, out var exitCode);
                    return (int)exitCode;
                }
                finally
                {
                    CloseHandle(processInfo.hProcess);
                }
            }
            finally
            {
                CloseHandle(nullHandle);
            }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct StartupInfoW
        {
            public uint cb;
            public string? lpReserved;
            public string? lpDesktop;
            public string? lpTitle;
            public uint dwX;
            public uint dwY;
            public uint dwXSize;
            public uint dwYSize;
            public uint dwXCountChars;
            public uint dwYCountChars;
            public uint dwFillAttribute;
            public uint dwFlags;
            public ushort wShowWindow;
            public ushort cbReserved2;
            public IntPtr lpReserved2;
            public IntPtr hStdInput;
            public IntPtr hStdOutput;
            public IntPtr hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ProcessInformation
        {
            public IntPtr hProcess;
            public IntPtr hThread;
            public uint dwProcessId;
            public uint dwThreadId;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes
        {
            public int nLength;
            public IntPtr lpSecurityDescriptor;
            public bool bInheritHandle;
        }

        public static void SendEnterKeyToConsoleStdin()
        {
            try
            {
                // Tillett pattern: both synthetic keyboard Enter and console input-buffer Enter.
                SendReturnViaSendInput();
                TryWriteReturnToConsoleInput();
            }
            catch
            {
            }
        }

        private static bool TryWriteReturnToConsoleInput()
        {
            var stdin = GetStdHandle(StdInputHandle);
            if (stdin == InvalidHandleValue || stdin == IntPtr.Zero)
            {
                return false;
            }

            var records = new ConsoleInputRecord[]
            {
                new()
                {
                    EventType = ConsoleKeyEvent,
                    KeyEvent = new ConsoleKeyEventRecord
                    {
                        bKeyDown = true,
                        wRepeatCount = 1,
                        wVirtualKeyCode = VkReturn,
                        wUnicodeChar = VkReturn,
                    },
                },
                new()
                {
                    EventType = ConsoleKeyEvent,
                    KeyEvent = new ConsoleKeyEventRecord
                    {
                        bKeyDown = false,
                        wRepeatCount = 1,
                        wVirtualKeyCode = VkReturn,
                    },
                },
            };

            return WriteConsoleInput(stdin, records, (uint)records.Length, out var written)
                && written == records.Length;
        }

        private static void SendReturnViaSendInput()
        {
            var down = new SendInputRecord
            {
                type = InputKeyboard,
                u = new InputUnion
                {
                    ki = new KeyboardInput { wVk = VkReturn },
                },
            };
            var up = new SendInputRecord
            {
                type = InputKeyboard,
                u = new InputUnion
                {
                    ki = new KeyboardInput { wVk = VkReturn, dwFlags = KeyeventfKeyup },
                },
            };

            if (SendInput(2, new[] { down, up }, InputRecordSize) == 0)
            {
                _ = Marshal.GetLastWin32Error();
            }
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct ConsoleInputRecord
        {
            [FieldOffset(0)]
            public ushort EventType;

            [FieldOffset(4)]
            public ConsoleKeyEventRecord KeyEvent;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ConsoleKeyEventRecord
        {
            [MarshalAs(UnmanagedType.Bool)]
            public bool bKeyDown;

            public ushort wRepeatCount;
            public ushort wVirtualKeyCode;
            public ushort wUnicodeChar;
            public ushort wVirtualScanCode;
            public uint dwControlKeyState;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SendInputRecord
        {
            public uint type;
            public InputUnion u;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct InputUnion
        {
            [FieldOffset(0)]
            public MouseInput mi;

            [FieldOffset(0)]
            public KeyboardInput ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KeyboardInput
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MouseInput
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }
    }
}
