package net.sourceforge.icedteaweb.autodetect.it.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Javaagent for {@code javaws} autodetect ITs.
 *
 * <p>Byte Buddy advises {@code Launcher.resolveMissingSuitableJre} <em>without skipping</em>.
 * On enter it reflects into {@link DialogClickSupport}, which presses
 * <strong>Apply</strong> / <strong>Autodetect</strong> via {@code AbstractButton.doClick()}
 * on the dialog EDT.
 */
public final class AutodetectDialogAgent {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile Instrumentation instrumentation;

    private AutodetectDialogAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        start(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(inst);
    }

    private static void start(Instrumentation inst) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        instrumentation = inst;
        log("AutodetectDialogAgent started (doClick drive)");
        try {
            File agentJar = new File(AutodetectDialogAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (agentJar.isFile()) {
                // So advice can Class.forName(DialogClickSupport) from the app CL.
                inst.appendToSystemClassLoaderSearch(new JarFile(agentJar));
                log("appended agent jar to system CL");
            }
        } catch (Throwable ex) {
            log("appendToSystemClassLoaderSearch: " + ex);
        }
        // Start clicker via system CL (same class advice will see after append).
        try {
            Class<?> support = Class.forName(
                    "net.sourceforge.icedteaweb.autodetect.it.agent.DialogClickSupport",
                    true,
                    ClassLoader.getSystemClassLoader());
            support.getMethod("init", Instrumentation.class).invoke(null, inst);
            support.getMethod("startBackgroundClicker").invoke(null);
        } catch (Throwable ex) {
            log("early clicker start: " + ex);
        }
        try {
            new AgentBuilder.Default()
                    .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new AgentBuilder.Listener() {
                        @Override
                        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                JavaModule module, boolean loaded, DynamicType dynamicType) {
                            log("transformed " + typeDescription.getName() + " loaded=" + loaded
                                    + " cl=" + classLoader);
                        }

                        @Override
                        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                boolean loaded, Throwable throwable) {
                            log("transform error " + typeName + ": " + throwable);
                        }

                        @Override
                        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module,
                                boolean loaded) {
                        }

                        @Override
                        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                                JavaModule module, boolean loaded) {
                        }

                        @Override
                        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module,
                                boolean loaded) {
                        }
                    })
                    .type(ElementMatchers.named("net.sourceforge.jnlp.Launcher"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                            .visit(Advice.to(ArmDoClickAdvice.class)
                                    .on(ElementMatchers.named("resolveMissingSuitableJre"))))
                    .installOn(inst);
            log("advice installed on Launcher.resolveMissingSuitableJre");

            Thread retransform = new Thread(() -> forceRetransformWhenPresent(), "itw-dialog-agent-retransform");
            retransform.setDaemon(true);
            retransform.start();
        } catch (Throwable ex) {
            log("install failed: " + ex);
        }
    }

    private static void forceRetransformWhenPresent() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                Class<?> launcher = null;
                try {
                    launcher = Class.forName("net.sourceforge.jnlp.Launcher", false,
                            ClassLoader.getSystemClassLoader());
                } catch (ClassNotFoundException ignored) {
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        if ("net.sourceforge.jnlp.Launcher".equals(loaded.getName())) {
                            launcher = loaded;
                            break;
                        }
                    }
                }
                if (launcher != null && instrumentation.isModifiableClass(launcher)) {
                    instrumentation.retransformClasses(launcher);
                    log("retransformed Launcher via " + launcher.getClassLoader());
                    return;
                }
            } catch (Throwable ex) {
                log("retransform attempt: " + ex);
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log("timed out waiting to retransform Launcher");
    }

    static void log(String message) {
        Path file = resolveLogFile();
        String line = System.currentTimeMillis() + " " + message + System.lineSeparator();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            System.err.println("[itw-autodetect-dialog-agent] " + message);
        }
    }

    private static Path resolveLogFile() {
        String prop = System.getProperty("itw.dialog.agent.log");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "itw-autodetect-dialog-agent.log");
    }

    /**
     * Minimal advice (no nested types): reflect into {@link DialogClickSupport}.
     * Does not skip the dialog method.
     */
    public static final class ArmDoClickAdvice {

        private ArmDoClickAdvice() {
        }

        @Advice.OnMethodEnter
        public static void enter() {
            // No lambdas here — they become private synthetic methods Launcher cannot call.
            System.err.println("[itw-autodetect-dialog-agent] resolveMissingSuitableJre — arm doClick");
            try {
                Class<?> support = Class.forName(
                        "net.sourceforge.icedteaweb.autodetect.it.agent.DialogClickSupport",
                        true,
                        ClassLoader.getSystemClassLoader());
                // Only arm — loop already started from premain.
                support.getMethod("scheduleEdtDoClickPass").invoke(null);
            } catch (Throwable t) {
                System.err.println("[itw-autodetect-dialog-agent] arm doClick failed: " + t);
                t.printStackTrace(System.err);
            }
        }
    }
}
