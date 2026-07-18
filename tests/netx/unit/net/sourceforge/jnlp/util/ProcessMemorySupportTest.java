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

}
