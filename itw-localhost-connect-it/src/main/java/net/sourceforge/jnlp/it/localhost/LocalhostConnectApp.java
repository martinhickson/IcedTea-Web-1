package net.sourceforge.jnlp.it.localhost;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Trusted JNLP app that opens {@code itw.test.localhost.url} using the
 * hostname {@code localhost} (not {@code 127.0.0.1}).
 */
public final class LocalhostConnectApp {

    public static void main(String[] args) {
        String url = System.getProperty("itw.test.localhost.url");
        if (url == null || url.trim().isEmpty()) {
            System.out.println("ITW_LOCALHOST_CONNECT_FAIL missing itw.test.localhost.url");
            return;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url.trim()).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            System.out.println("ITW_LOCALHOST_CONNECT_OK " + code);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("ITW_LOCALHOST_CONNECT_FAIL " + t);
        }
        System.out.flush();
    }

    private LocalhostConnectApp() {
    }
}
