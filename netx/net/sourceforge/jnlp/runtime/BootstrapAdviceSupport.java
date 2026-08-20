/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Injects advice helper classes into the bootstrap class loader before instrumenting
 * JDK classes ({@code ZipFile}, {@code URLJarFile}, etc.).
 * <p>
 * Without bootstrap injection, advice woven into {@code java.base} types references
 * ITW classes that are invisible from the bootstrap loader, causing
 * {@code NoClassDefFoundError} when JDK code calls the instrumented method (e.g. SLF4J
 * {@code ServiceLoader} closing a {@code jar:} {@code ZipFile} on JDK 21).
 */
final class BootstrapAdviceSupport {

    private BootstrapAdviceSupport() {
    }

    static void injectIntoBootstrap(Class<?>... classes) throws Exception {
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(
                BootstrapAdviceSupport.class.getClassLoader());
        Map<TypeDescription, byte[]> types = new HashMap<>();
        for (Class<?> type : classes) {
            types.put(new TypeDescription.ForLoadedType(type),
                    locator.locate(type.getName()).resolve());
        }
        ClassInjector.UsingInstrumentation.of(
                bootstrapStorageDir(),
                ClassInjector.UsingInstrumentation.Target.BOOTSTRAP,
                net.bytebuddy.agent.ByteBuddyAgent.getInstrumentation())
                .inject(types);
    }

    private static File bootstrapStorageDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "itw-bytebuddy-bootstrap");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static void adviseBootstrapMethod(Class<?> targetClass, Class<?> adviceClass, String methodName)
            throws Exception {
        adviseBootstrapMethods(targetClass, adviceClass, methodName);
    }

    /**
     * One redefine applying the same advice to every method of each name
     * (all overloads). Uses the already-installed ByteBuddy javaagent.
     */
    static void adviseBootstrapMethods(Class<?> targetClass, Class<?> adviceClass,
            String... methodNames) throws Exception {
        injectIntoBootstrap(adviceClass);
        DynamicType.Builder<?> builder = new ByteBuddy().redefine(targetClass);
        for (String methodName : methodNames) {
            builder = builder.visit(Advice.to(adviceClass).on(ElementMatchers.named(methodName)));
        }
        builder.make().load(targetClass.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }
}
