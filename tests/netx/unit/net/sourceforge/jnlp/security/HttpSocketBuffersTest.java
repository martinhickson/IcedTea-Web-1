package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class HttpSocketBuffersTest {

    private String savedRcv;
    private String savedSnd;

    private void save() {
        savedRcv = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE);
        savedSnd = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_HTTPCONNECTION_SEND_BUFFER_SIZE);
    }

    @AfterEach
    public void restore() {
        if (savedRcv != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE, savedRcv);
        }
        if (savedSnd != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_HTTPCONNECTION_SEND_BUFFER_SIZE, savedSnd);
        }
    }

    @Test
    public void defaultReceiveBufferIs1024KiB() {
        save();
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE, "1048576");
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_HTTPCONNECTION_SEND_BUFFER_SIZE, "0");
        assertEquals(1024 * 1024, HttpSocketBuffers.DEFAULT_RECEIVE_BUFFER);
        assertEquals(1024 * 1024, HttpSocketBuffers.receiveBufferSize());
        assertEquals(0, HttpSocketBuffers.sendBufferSize());
    }

    @Test
    public void zeroMeansDoNotSet() throws Exception {
        save();
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE, "0");
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_HTTPCONNECTION_SEND_BUFFER_SIZE, "0");
        try (ServerSocket server = new ServerSocket(0);
                Socket client = new Socket("127.0.0.1", server.getLocalPort());
                Socket accepted = server.accept()) {
            int before = client.getReceiveBufferSize();
            HttpSocketBuffers.apply(client);
            assertEquals(before, client.getReceiveBufferSize());
            HttpSocketBuffers.apply(accepted);
        }
    }

    @Test
    public void applyRaisesReceiveBuffer() throws Exception {
        save();
        int want = 1024 * 1024;
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE, String.valueOf(want));
        try (ServerSocket server = new ServerSocket(0);
                Socket client = new Socket()) {
            HttpSocketBuffers.apply(client);
            client.connect(new java.net.InetSocketAddress("127.0.0.1", server.getLocalPort()));
            try (Socket accepted = server.accept()) {
                HttpSocketBuffers.apply(accepted);
                // Kernel rmem_max may clamp below the request (seen 208 KiB for 256 KiB).
                // Must not stay at the ~8 KiB that caps a high-RTT GET at ~200 kbps.
                assertTrue(client.getReceiveBufferSize() >= 64 * 1024,
                        "rcv=" + client.getReceiveBufferSize());
                assertTrue(accepted.getReceiveBufferSize() >= 64 * 1024,
                        "peer rcv=" + accepted.getReceiveBufferSize());
            }
        }
    }
}
