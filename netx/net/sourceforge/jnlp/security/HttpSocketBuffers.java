package net.sourceforge.jnlp.security;

import java.net.Socket;
import java.net.SocketException;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Applies {@code SO_RCVBUF} / {@code SO_SNDBUF} from deployment properties so
 * one HTTP GET can advertise a window that covers a high-RTT path. Parallel
 * download slots are unchanged. {@code 0} means do not call set (OS autotune).
 */
public final class HttpSocketBuffers {

    public static final int DEFAULT_RECEIVE_BUFFER = 256 * 1024;

    private HttpSocketBuffers() {
    }

    public static int receiveBufferSize() {
        return sized(DeploymentConfiguration.KEY_HTTPCONNECTION_RECEIVE_BUFFER_SIZE, DEFAULT_RECEIVE_BUFFER);
    }

    public static int sendBufferSize() {
        return sized(DeploymentConfiguration.KEY_HTTPCONNECTION_SEND_BUFFER_SIZE, 0);
    }

    public static void apply(Socket socket) {
        if (socket == null) {
            return;
        }
        int rcv = receiveBufferSize();
        if (rcv > 0) {
            try {
                socket.setReceiveBufferSize(rcv);
            } catch (SocketException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, e);
            }
        }
        int snd = sendBufferSize();
        if (snd > 0) {
            try {
                socket.setSendBufferSize(snd);
            } catch (SocketException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, e);
            }
        }
    }

    private static int sized(String key, int fallback) {
        try {
            int n = Integer.parseInt(JNLPRuntime.getConfiguration().getProperty(key).trim());
            return n < 0 ? fallback : n;
        } catch (Exception e) {
            return fallback;
        }
    }
}
