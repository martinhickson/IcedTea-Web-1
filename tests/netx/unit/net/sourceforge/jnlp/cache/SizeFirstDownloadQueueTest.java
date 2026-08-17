package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SizeFirstDownloadQueueTest {

    @BeforeEach
    public void setUp() {
        SizeFirstDownloadQueue.resetForTests();
    }

    @AfterEach
    public void tearDown() {
        SizeFirstDownloadQueue.resetForTests();
    }

    @Test
    public void ordersKnownSizesLargestFirst() throws Exception {
        Resource tiny = resource("tiny.jar", 100);
        Resource huge = resource("huge.jar", 85_000_000);
        Resource mid = resource("mid.jar", 1_000_000);
        List<Resource> ordered = SizeFirstDownloadQueue.orderLargestFirst(
                Arrays.asList(tiny, huge, mid));
        assertEquals(huge, ordered.get(0));
        assertEquals(mid, ordered.get(1));
        assertEquals(tiny, ordered.get(2));
    }

    @Test
    public void unknownSizeStartsBeforeKnown() throws Exception {
        Resource known = resource("known.jar", 1_000);
        Resource unknown = resource("unknown.jar", -1);
        List<Resource> ordered = SizeFirstDownloadQueue.orderLargestFirst(
                Arrays.asList(known, unknown));
        assertEquals(unknown, ordered.get(0), "failed HEAD must not bury a jar at the tail");
        assertEquals(known, ordered.get(1));
    }

    @Test
    public void equalSizesKeepOriginalOrder() throws Exception {
        Resource a = resource("a.jar", 50);
        Resource b = resource("b.jar", 50);
        List<Resource> ordered = SizeFirstDownloadQueue.orderLargestFirst(Arrays.asList(a, b));
        assertEquals(a, ordered.get(0));
        assertEquals(b, ordered.get(1));
    }

    @Test
    public void smallLanesChurnSmallestFirstWhileGiantsStayHeld() throws Exception {
        List<Resource> batch = new ArrayList<Resource>();
        for (int i = 1; i <= 14; i++) {
            batch.add(resource("j" + i + ".jar", i * 1000L));
        }
        List<Resource> started = SizeFirstDownloadQueue.tinyChurnWhileGiantsHeld(batch);
        assertEquals(14, started.size());
        for (int i = 0; i < 10; i++) {
            assertEquals((14 - i) * 1000L, started.get(i).getSize(), "first 10 must be largest held");
        }
        assertEquals(1000L, started.get(10).getSize(), "first refill is smallest");
        assertEquals(2000L, started.get(11).getSize());
        assertEquals(3000L, started.get(12).getSize());
        assertEquals(4000L, started.get(13).getSize(), "small lane keeps taking next-smallest");
    }

    @Test
    public void flushHeadsThenSubmitsLargestFirst() throws Exception {
        Resource tiny = resource("tiny.jar", -1);
        Resource huge = resource("huge.jar", -1);
        Resource mid = resource("mid.jar", -1);
        SizeFirstDownloadQueue.headProbe = r -> {
            String path = r.getLocation().getPath();
            if (path.endsWith("huge.jar")) {
                r.setSize(9_000);
            } else if (path.endsWith("mid.jar")) {
                r.setSize(3_000);
            } else {
                r.setSize(10);
            }
        };
        List<Resource> started = new CopyOnWriteArrayList<Resource>();
        SizeFirstDownloadQueue.downloadStarter = started::add;

        SizeFirstDownloadQueue.enqueue(tiny);
        SizeFirstDownloadQueue.enqueue(huge);
        SizeFirstDownloadQueue.enqueue(mid);
        SizeFirstDownloadQueue.flush();

        assertEquals(Arrays.asList(huge, mid, tiny), new ArrayList<Resource>(started));
        assertEquals(9_000, huge.getSize());
        assertTrue(SizeFirstDownloadQueue.isEmptyForTests());
    }

    @Test
    public void recordHeadStoresMetaForReuse() throws Exception {
        Resource r = resource("meta.jar", -1);
        URL u = r.getLocation();
        SizeFirstDownloadQueue.recordHead(r, u, 12_345L, 1_700_000_000_000L);
        SizeFirstDownloadQueue.HeadMeta meta = SizeFirstDownloadQueue.headMeta(r);
        assertEquals(u, SizeFirstDownloadQueue.headWinner(r));
        assertEquals(12_345L, meta.contentLength);
        assertEquals(1_700_000_000_000L, meta.lastModified);
        assertEquals(12_345L, r.getSize());
    }

    @Test
    public void singletonFlushDoesNotHead() throws Exception {
        Resource one = resource("only.jar", -1);
        java.util.concurrent.atomic.AtomicInteger heads = new java.util.concurrent.atomic.AtomicInteger();
        SizeFirstDownloadQueue.headProbe = r -> {
            heads.incrementAndGet();
            r.setSize(99);
        };
        List<Resource> started = new CopyOnWriteArrayList<Resource>();
        SizeFirstDownloadQueue.downloadStarter = started::add;
        SizeFirstDownloadQueue.enqueue(one);
        SizeFirstDownloadQueue.flush();
        assertEquals(0, heads.get(), "single-resource wait must not HEAD");
        assertEquals(Arrays.asList(one), started);
        assertEquals(-1, one.getSize());
    }

    private static Resource resource(String name, long size) throws Exception {
        Resource r = Resource.getResource(new URL("http://size-first.test/" + name),
                null, UpdatePolicy.ALWAYS);
        r.setSize(size);
        return r;
    }
}
