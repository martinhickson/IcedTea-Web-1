/* JnlpFileParseCacheTest.java
   Copyright (C) 2026 IcedTea contributors

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.
*/

package net.sourceforge.jnlp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.net.URL;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;

public class JnlpFileParseCacheTest extends NoStdOutErrTest {

    private static final String SAMPLE_JNLP =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jnlp spec=\"1.0+\">\n"
            + "  <information>\n"
            + "    <title>Parse Cache Sample</title>\n"
            + "    <vendor>IcedTea</vendor>\n"
            + "  </information>\n"
            + "  <security>\n"
            + "    <all-permissions/>\n"
            + "  </security>\n"
            + "  <resources>\n"
            + "    <j2se version=\"17+\"/>\n"
            + "    <jar href=\"app.jar\" main=\"true\"/>\n"
            + "  </resources>\n"
            + "  <application-desc main-class=\"com.example.Main\"/>\n"
            + "</jnlp>\n";

    @Before
    public void clearCacheBefore() {
        JnlpFileParseCache.clear();
    }

    @After
    public void clearCacheAfter() {
        JnlpFileParseCache.clear();
    }

    @Test
    public void secondLoadOfSameDiskJnlpHitsCacheAndReturnsDistinctCopy() throws Exception {
        File jnlpFile = File.createTempFile("parse-cache-", ".jnlp");
        jnlpFile.deleteOnExit();
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(jnlpFile), StandardCharsets.UTF_8)) {
            writer.write(SAMPLE_JNLP);
        }
        URL location = jnlpFile.toURI().toURL();
        ParserSettings settings = new ParserSettings();

        JNLPFile first = new JNLPFile(location, settings);
        Assert.assertEquals(1, JnlpFileParseCache.stores());
        Assert.assertEquals(1, JnlpFileParseCache.misses());
        Assert.assertEquals(0, JnlpFileParseCache.hits());
        Assert.assertEquals(1, JnlpFileParseCache.size());

        JNLPFile second = new JNLPFile(location, settings);
        Assert.assertEquals(1, JnlpFileParseCache.stores());
        Assert.assertEquals(1, JnlpFileParseCache.misses());
        Assert.assertEquals(1, JnlpFileParseCache.hits());
        Assert.assertNotSame(first, second);
        Assert.assertEquals(first.getTitle(), second.getTitle());
        Assert.assertEquals("Parse Cache Sample", second.getTitle());
        Assert.assertEquals(first.getResources().getJREs()[0].getVersion().toString(),
                second.getResources().getJREs()[0].getVersion().toString());
        Assert.assertNotSame(first.getSecurity(), second.getSecurity());
        Assert.assertNotSame(first.getResources().getJREs()[0], second.getResources().getJREs()[0]);
        Assert.assertSame(first, first.getResources().getJNLPFile());
        Assert.assertSame(second, second.getResources().getJNLPFile());
        Assert.assertNotEquals(first.getUniqueKey(), second.getUniqueKey());
        Assert.assertNotSame(first.getManifestsAttributes(), second.getManifestsAttributes());
    }
}
