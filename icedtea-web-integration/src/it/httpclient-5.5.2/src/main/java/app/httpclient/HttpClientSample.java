package app.httpclient;

import org.apache.hc.client5.http.impl.classic.ContentCompressionExec;

/**
 * Sample application compiled against httpclient 5.5.2.
 * {@code ContentCompressionExec(boolean)} exists there and was removed in 5.6.4.
 */
public final class HttpClientSample {

    public static void main(String[] args) {
        ContentCompressionExec exec = new ContentCompressionExec(true);
        System.out.println("ITW_LIBRARY_SAMPLE_OK httpclient " + exec.getClass().getName());
    }
}
