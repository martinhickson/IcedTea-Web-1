using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
namespace IcedTeaWeb.Launcher;

internal static class Program
{
    private const string JavawsMainClass = "net.sourceforge.jnlp.runtime.JavawsUberLauncher";
    private const string SettingsMainClass = "net.sourceforge.jnlp.controlpanel.CommandLine";
    private const string PolicyEditorMainClass = "net.sourceforge.jnlp.security.policyeditor.PolicyEditor";
    private const string JavaVersionProbeArg = "--java-version";

    // GUI / non-console launches: Rust used Stdio::null() (discard). Set true to capture Java
    // stdout/stderr into per-launch log files under LocalApplicationData/IcedTea-Web/logs instead.
    private const bool CaptureGuiStdioToLogFiles = false;

    // Console-preserving launches: Rust blocked on child.wait() with inherited stdio after AttachConsole.
    // true  = blocking stream drain for non-Windows / redirected fallback paths.
    // false = CopyToAsync relay; still waits at end, but stream pumping uses async I/O.
    private const bool WaitForJavaStdioSynchronously = true;

    private static int Main(string[] args)
    {
        var exitCode = 1;
        try
        {
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
            var javaExecutable = ResolveJavaExecutable(installRoot, javawsArgs);
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

            exitCode = RunJava(javaExecutable, command, preserveStdio);
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

        if (launcherName.Equals("itweb-settings", StringComparison.OrdinalIgnoreCase)
            || launcherName.Equals("itweb_settings", StringComparison.OrdinalIgnoreCase))
        {
            return SettingsMainClass;
        }

        if (launcherName.Equals("policyeditor", StringComparison.OrdinalIgnoreCase))
        {
            return PolicyEditorMainClass;
        }

        return JavawsMainClass;
    }

    private static int RunJava(string javaExecutable, IReadOnlyList<string> command, bool preserveStdio)
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

    private static bool IsConsoleOutputArg(string arg)
    {
        if (arg.StartsWith("-J", StringComparison.Ordinal))
        {
            return false;
        }

        var key = arg.Split(':', 2)[0];
        return key.Equals("-version", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--version", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-about", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--about", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-verbose", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-help", StringComparison.OrdinalIgnoreCase)
            || key.Equals("--help", StringComparison.OrdinalIgnoreCase)
            || key.Equals("-?", StringComparison.OrdinalIgnoreCase);
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
            Stream.Null,
            Stream.Null);
    }

    private static int RunJavaWithRedirectedOutput(string javaExecutable, IReadOnlyList<string> command)
    {
        var logBasePath = CreateLauncherLogBasePath();
        var stdoutPath = logBasePath + ".out.log";
        var stderrPath = logBasePath + ".err.log";
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
        using var stdout = File.Create(stdoutPath);
        using var stderr = File.Create(stderrPath);
        return WaitForProcessWithDrainedStreams(
            process,
            process.StandardOutput.BaseStream,
            process.StandardError.BaseStream,
            stdout,
            stderr);
    }

    private static int WaitForProcessWithDrainedStreams(
        Process process,
        Stream stdoutSource,
        Stream stderrSource,
        Stream stdoutTarget,
        Stream stderrTarget)
    {
        if (WaitForJavaStdioSynchronously)
        {
            var stdoutDrain = Task.Run(() => CopyStreamSynchronously(stdoutSource, stdoutTarget));
            var stderrDrain = Task.Run(() => CopyStreamSynchronously(stderrSource, stderrTarget));
            process.WaitForExit();
            // Task.GetAwaiter().GetResult() is the .NET equivalent of Thread.Join on each drain thread.
            stdoutDrain.GetAwaiter().GetResult();
            stderrDrain.GetAwaiter().GetResult();
            return process.ExitCode;
        }

#pragma warning disable CS0162 // Unreachable when WaitForJavaStdioSynchronously is true (default).
        var stdoutRelay = stdoutSource.CopyToAsync(stdoutTarget);
        var stderrRelay = stderrSource.CopyToAsync(stderrTarget);
        process.WaitForExit();
        stdoutRelay.GetAwaiter().GetResult();
        stderrRelay.GetAwaiter().GetResult();
        return process.ExitCode;
#pragma warning restore CS0162
    }

    private static void CopyStreamSynchronously(Stream source, Stream target)
    {
        source.CopyTo(target);
        target.Flush();
    }

    private static string CreateLauncherLogBasePath()
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(root))
        {
            root = Path.GetTempPath();
        }
        var logDirectory = Path.Combine(root, "IcedTea-Web", "logs");
        Directory.CreateDirectory(logDirectory);
        var stamp = DateTime.UtcNow.ToString("yyyyMMdd-HHmmss-fff");
        return Path.Combine(logDirectory, "javaws-" + stamp + "-" + Environment.ProcessId);
    }

    private static void WriteLauncherFailure(Exception ex)
    {
        try
        {
            var logPath = CreateLauncherLogBasePath() + ".launcher-error.log";
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
        if (preserveStdio && IsConsoleOnlyLaunch(javawsArgs))
        {
            return ProbeJavaMajorVersionFromJavaExecutable(javaExecutable);
        }

        return ProbeJavaMajorVersionFromUberJar(javaExecutable, uberJar, preserveStdio);
    }

    private static bool IsConsoleOnlyLaunch(IReadOnlyCollection<string> javawsArgs) =>
        javawsArgs.Count > 0 && javawsArgs.All(IsConsoleOutputArg);

    private static int ProbeJavaMajorVersionFromJavaExecutable(string javaExecutable)
    {
        var stderr = SpawnProcessCaptureStderrManaged(javaExecutable, new List<string> { "-version" });
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
        string uberJar,
        bool preserveStdio)
    {
        // Uber jar --java-version prints java.version major (system property) to stdout and exits.
        // Always via JavawsUberLauncher/Boot — not the launcher-specific main (e.g. CommandLine).
        // GUI only: javaw.exe — Windows GUI JVM, no console subsystem.
        var probeExecutable = RuntimeInformation.IsOSPlatform(OSPlatform.Windows) && !preserveStdio
            ? ResolveJavawExecutable(javaExecutable)
            : javaExecutable;

        var probeCommand = new List<string>
        {
            "-Xms8m",
            "-cp",
            uberJar,
            JavawsMainClass,
            JavaVersionProbeArg,
        };

        var stdout = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? NativeMethods.SpawnProcessCaptureStdout(probeExecutable, probeCommand)
            : SpawnProcessCaptureStdoutManaged(probeExecutable, probeCommand);

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

        return "\"" + arg.Replace("\"", "\\\"", StringComparison.Ordinal) + "\"";
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
