package net.sourceforge.jnlp.util.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;

public class ItwSlf4jLoggerTest {

    @Test
    public void binderIsItwFactoryNotNop() {
        assertEquals(ItwSlf4jLoggerFactory.class.getName(),
                StaticLoggerBinder.getSingleton().getLoggerFactoryClassStr());
        assertSame(ItwSlf4jLoggerFactory.INSTANCE,
                LoggerFactory.getILoggerFactory());
    }

    @Test
    public void apacheHcLoggersStayQuietWithoutItwDebug() {
        Logger log = LoggerFactory.getLogger("org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager");
        assertTrue(log instanceof ItwSlf4jLogger);
        assertFalse(log.isTraceEnabled());
        assertFalse(log.isDebugEnabled());
        assertFalse(log.isErrorEnabled());
        log.error("handshake failed", new javax.net.ssl.SSLProtocolException(
                "Received close_notify during handshake"));
    }

    @Test
    public void factoryCachesLoggers() {
        Logger a = ItwSlf4jLoggerFactory.INSTANCE.getLogger("org.apache.hc.core5");
        Logger b = ItwSlf4jLoggerFactory.INSTANCE.getLogger("org.apache.hc.core5");
        assertSame(a, b);
    }
}
