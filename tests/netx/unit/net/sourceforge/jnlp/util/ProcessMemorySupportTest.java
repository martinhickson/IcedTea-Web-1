package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProcessMemorySupportTest {

    @Test
    public void parseHeapFromHeapInfoReadsG1Output() {
        String heapInfo = "17848:\n"
                + " garbage-first heap   total 57344K, used 28849K [0x0000000601c00000, 0x0000000800000000)\n"
                + "  region size 4096K, 5 young (20480K), 1 survivors (4096K)\n";

        long[] heap = ProcessMemorySupport.parseHeapFromHeapInfo(heapInfo);

        assertEquals(28849L * 1024L, heap[0]);
        assertEquals(57344L * 1024L, heap[1]);
    }

    @Test
    public void parseMaxHeapFromFlagsReadsConfiguredLimit() {
        String flags = "-XX:MaxHeapSize=8560574464 -XX:+UseG1GC";

        assertEquals(8560574464L, ProcessMemorySupport.parseMaxHeapFromFlags(flags));
    }

    @Test
    public void formatMegabytesShowsOneDecimal() {
        assertEquals("28.2 MB", ProcessMemorySupport.formatMegabytes(28849L * 1024L));
        assertEquals("0 MB", ProcessMemorySupport.formatMegabytes(0));
    }

    @Test
    public void memoryInfoUnavailableWhenAllZero() {
        ProcessMemorySupport.MemoryInfo info = new ProcessMemorySupport.MemoryInfo(0, 0, 0, 0);
        assertTrue(!info.isAvailable());
    }

    @Test
    public void jcmdSystemPropertiesNameAnOpenJdk() {
        String output = "4123:\n"
                + "java.home=/usr/lib/jvm/java-17-openjdk-amd64\n"
                + "java.runtime.name=OpenJDK Runtime Environment\n"
                + "java.runtime.version=17.0.20.1+1-1-24.04-Ubuntu\n"
                + "java.vendor=Ubuntu\n"
                + "java.version=17.0.20.1\n"
                + "java.vm.name=OpenJDK 64-Bit Server VM\n"
                + "java.vm.vendor=Ubuntu\n"
                + "java.vm.version=17.0.20.1+1-1-24.04-Ubuntu\n";

        ProcessMemorySupport.ProcessJvmContext identity = ProcessMemorySupport.parseJcmdSystemProperties(output);

        assertEquals("/usr/lib/jvm/java-17-openjdk-amd64", identity.getJavaHome());
        assertEquals("OpenJDK", identity.getVendor());
        assertEquals("17.0.20.1", identity.getJvmVersion());
    }

    @Test
    public void jcmdSystemPropertiesUnescapeWindowsHomeAndNameTemurin() {
        String output = "99:\n"
                + "java.home=C\\:\\\\Program Files\\\\Eclipse Adoptium\\\\jdk-17.0.12.7-hotspot\n"
                + "java.runtime.name=OpenJDK Runtime Environment\n"
                + "java.vendor=Eclipse Adoptium\n"
                + "java.vendor.version=Temurin-17.0.12+7\n"
                + "java.version=17.0.12\n"
                + "java.vm.name=OpenJDK 64-Bit Server VM\n";

        ProcessMemorySupport.ProcessJvmContext identity = ProcessMemorySupport.parseJcmdSystemProperties(output);

        assertEquals("C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.12.7-hotspot", identity.getJavaHome());
        assertEquals("Eclipse Temurin", identity.getVendor());
        assertEquals("17.0.12", identity.getJvmVersion());
    }

    @Test
    public void jcmdSystemPropertiesWithoutIdentityStayUnnamed() {
        ProcessMemorySupport.ProcessJvmContext identity = ProcessMemorySupport.parseJcmdSystemProperties("4123:\n");

        assertEquals(null, identity.getJavaHome());
        assertEquals("", identity.getVendor());
        assertEquals("", identity.getJvmVersion());
    }

}
