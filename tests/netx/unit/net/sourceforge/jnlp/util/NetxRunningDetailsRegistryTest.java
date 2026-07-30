/* NetxRunningDetailsRegistryTest.java
   Copyright (C) 2026 IcedTea contributors

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.
*/

package net.sourceforge.jnlp.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;

public class NetxRunningDetailsRegistryTest extends NoStdOutErrTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void pointLocksAtTempDir() throws Exception {
        File locksDir = temp.newFolder("locks");
        File mainLock = new File(locksDir, "netx_running");
        DeploymentConfiguration config = JNLPRuntime.getConfiguration();
        Assert.assertNotNull(config);
        config.setProperty(DeploymentConfiguration.KEY_USER_LOCKS_DIR, locksDir.getAbsolutePath());
        config.setProperty(DeploymentConfiguration.KEY_USER_NETX_RUNNING_FILE, mainLock.getAbsolutePath());
        Assert.assertEquals(mainLock.getAbsolutePath(), PathsAndFiles.MAIN_LOCK.getFullPath());
    }

    @Test
    public void registerProcessPersistsEntryWhileFileIsLockedForUpdate() throws Exception {
        File details = NetxRunningDetailsRegistry.getDetailsFile();
        Files.createDirectories(details.getParentFile().toPath());
        if (!details.exists()) {
            Assert.assertTrue(details.createNewFile());
        }

        JnlpLockMetadata.ProcessEntry entry = new JnlpLockMetadata.ProcessEntry(
                424242, "file:/tmp/sample.jnlp", "Sample App", "1.0",
                null, "C:/jdk", "Temurin", "17.0.1");
        NetxRunningDetailsRegistry.registerProcess(entry);

        Assert.assertTrue(details.isFile());
        Assert.assertTrue(details.length() > 0);
        String text = new String(Files.readAllBytes(details.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("processID=424242"));
        Assert.assertTrue(text.contains("Sample App"));

        List<JnlpLockMetadata.ProcessEntry> listed = NetxRunningDetailsRegistry.listRegisteredProcesses();
        Assert.assertEquals(1, listed.size());
        Assert.assertEquals(424242, listed.get(0).getProcessId());
        Assert.assertEquals("Sample App", listed.get(0).getAppTitle());

        NetxRunningDetailsRegistry.unregisterProcess(424242);
        Assert.assertTrue(NetxRunningDetailsRegistry.listRegisteredProcesses().isEmpty());
    }
}
