/* TinyHttpdImpl.java
 Copyright (C) 2011,2012 Red Hat, Inc.

 This file is part of IcedTea.

 IcedTea is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by
 the Free Software Foundation, version 2.

 IcedTea is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with IcedTea; see the file COPYING.  If not, write to
 the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 02110-1301 USA.

 Linking this library statically or dynamically with other modules is
 making a combined work based on this library.  Thus, the terms and
 conditions of the GNU General Public License cover the whole
 combination.

 As a special exception, the copyright holders of this library give you
 permission to link this library with independent modules to produce an
 executable, regardless of the license terms of these independent
 modules, and to copy and distribute the resulting executable under
 terms of your choice, provided that you also meet, for each linked
 independent module, the terms and conditions of the license of that
 module.  An independent module is a module which is not derived from
 or based on this library.  If you modify this library, you may extend
 this exception to your version of the library, but you are not
 obligated to do so.  If you do not wish to do so, delete this
 exception statement from your version.
 */
package net.sourceforge.jnlp;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import net.sourceforge.jnlp.cache.ResourceTracker;

/**
 * based on http://www.mcwalter.org/technology/java/httpd/tiny/index.html Very
 * small implementation of http return headers for our served resources
 * Originally Licenced under GPLv2.0
 *
 * When resource starts with XslowX prefix, then resouce (without XslowX) is
 * returned, but its delivery is delayed
 */
public class TinyHttpdImpl extends Thread {

    private static final String CRLF = "\r\n";
    private static final String HTTP_NOT_IMPLEMENTED = "HTTP/1.0 " + HttpURLConnection.HTTP_NOT_IMPLEMENTED + " Not Implemented" + CRLF;
    private static final String HTTP_NOT_FOUND = "HTTP/1.0 " + HttpURLConnection.HTTP_NOT_FOUND + " Not Found" + CRLF;
    private static final String HTTP_OK = "HTTP/1.0 " + HttpURLConnection.HTTP_OK + " OK" + CRLF;
    private static final String XSX = "/XslowX";

    private Socket socket;
    private final File testDir;
    private boolean canRun = true;
    private boolean supportingHeadRequest = true;
    private boolean supportLastModified = false;
    private boolean supportRangeRequests = false;
    private java.util.concurrent.atomic.AtomicReference<String> rangeHeaderSink = null;
    private Authentication511Requester authenticationRequester;

    public TinyHttpdImpl(Socket socket, File dir) {
        this(socket, dir, true);
    }

    public TinyHttpdImpl(Socket socket, File dir, boolean start) {
        this.socket = socket;
        this.testDir = dir;
        if (start) {
            start();
        }
    }

    public void setCanRun(boolean canRun) {
        this.canRun = canRun;
    }

    public void setSupportingHeadRequest(boolean supportsHead) {
        this.supportingHeadRequest = supportsHead;
    }

    public boolean isSupportingHeadRequest() {
        return this.supportingHeadRequest;
    }

    public void setSupportLastModified(boolean supportLastModified) {
        this.supportLastModified = supportLastModified;
    }

    public boolean isSupportingLastModified() {
        return this.supportLastModified;
    }

    /**
     * Enable HTTP Range (RFC 7233) serving: parse a {@code Range} request header and
     * answer with 206 Partial Content (or 416 when unsatisfiable). One request per
     * connection in this mode. Off by default so existing tests are unaffected.
     */
    public void setSupportRangeRequests(boolean supportRangeRequests) {
        this.supportRangeRequests = supportRangeRequests;
    }

    public boolean isSupportingRangeRequests() {
        return this.supportRangeRequests;
    }

    /**
     * Shared holder that records the last seen {@code Range} request header value, so a
     * test can assert the client actually sent a resume request. Set by ServerLauncher
     * so it survives across per-connection instances.
     */
    public void setRangeHeaderSink(java.util.concurrent.atomic.AtomicReference<String> rangeHeaderSink) {
        this.rangeHeaderSink = rangeHeaderSink;
    }

    public int getPort() {
        return this.socket.getPort();
    }

    private String extractMemory(String filePath) throws UnsupportedEncodingException {
        //the memory is last item in form, browsers generraly agree on this
        int i = filePath.lastIndexOf("=");
        filePath = filePath.substring(i);
        filePath = URLDecoder.decode(filePath, "utf-8");
        return filePath;
    }

    @Override
    public void run() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
            DataOutputStream writer = new DataOutputStream(this.socket.getOutputStream());
            try {
                while (canRun) {
                    String line = reader.readLine();
                    if (line.length() < 1) {
                        break;
                    }

                    StringTokenizer t = new StringTokenizer(line, " ");
                    String request = t.nextToken();
                    String filePath = t.nextToken();

                    boolean isHeadRequest = request.equals(ResourceTracker.RequestMethods.HEAD.toString());
                    boolean isGetRequest = request.equals(ResourceTracker.RequestMethods.GET.toString());

                    if (isHeadRequest && !isSupportingHeadRequest()) {
                        ServerAccess.logOutputReprint("Received HEAD request but not supported");
                        writer.writeBytes(HTTP_NOT_IMPLEMENTED);
                        continue;
                    }

                    if (!isHeadRequest && !isGetRequest) {
                        ServerAccess.logOutputReprint("Received unknown request type " + request);
                        continue;
                    }

                    if (authenticationRequester != null) {
                        if (authenticationRequester.isNeedsAuthentication511()) {
                            if (filePath.startsWith("/" + ServerLauncher.login501_1)) {
                                //requeested login dialog
                                writer.writeBytes(HTTP_OK + CRLF);
                                if (authenticationRequester.isRememberOrigianlUrl()) {
                                    writer.writeBytes(authenticationRequester.createReply2(extractMemory(filePath)));
                                } else {
                                    writer.writeBytes(authenticationRequester.createReply2(null));
                                }

                                continue;
                            } else if (filePath.startsWith("/" + ServerLauncher.login501_2) && filePath.contains("name=itw") && filePath.contains("passwd=itw")) {
                                //verifying password
                                authenticationRequester.setWasuthenticated511(true);
                                if (authenticationRequester.isRememberOrigianlUrl()) {
                                    filePath = extractMemory(filePath);
                                    filePath = filePath.replaceAll("=", "");
                                } else {
                                     writer.writeBytes("HTTP/1.1 200 OK" + CRLF + "Content-Type: text/html" + CRLF + CRLF);
                                     writer.writeBytes("Authentication ok, get back to your resource");
                                     continue;
                                }
                            } else if (!authenticationRequester.isWasuthenticated011()) {
                                //request authentication - redirect to 501_1
                                writer.writeBytes("HTTP/1.1 511 Network Authentication Required" + CRLF + "Content-Type: text/html" + CRLF + CRLF);
                                if (authenticationRequester.isRememberOrigianlUrl()) {
                                    writer.writeBytes(authenticationRequester.createReply1(filePath));
                                } else {
                                    writer.writeBytes(authenticationRequester.createReply1(null));
                                }
                                continue;
                            }
                        }
                    }

                    boolean slowSend = filePath.startsWith(XSX);

                    if (requestsCounter != null) {
                        String resource = filePath.replace(XSX, "/");
                        resource = urlToFilePath(resource);
                        Map<String, Integer> reosurceRecord = requestsCounter.get(resource);
                        if (reosurceRecord == null) {
                            reosurceRecord = new HashMap<>();
                            requestsCounter.put(resource, reosurceRecord);
                        }
                        Integer i = reosurceRecord.get(request);
                        if (i == null) {
                            i = 0;
                        }
                        i++;
                        reosurceRecord.put(request, i);
                    }

                    if (redirect != null) {
                        String where = redirect.getUrl(filePath).toExternalForm();
                        ServerAccess.logOutputReprint("Redirecting " + request + "as " + redirectCode + " to " + where);
                        writer.writeBytes("HTTP/1.0 " + redirectCode + " Moved" + CRLF);
                        writer.writeBytes("Location: " + where + CRLF);
                        writer.writeBytes(CRLF);
                    } else {

                        if (slowSend) {
                            filePath = filePath.replace(XSX, "/");
                        }

                        ServerAccess.logOutputReprint("Getting- " + request + ": " + filePath);
                        filePath = urlToFilePath(filePath);

                        File resource = new File(this.testDir, filePath);

                        if (!(resource.isFile() && resource.canRead())) {
                            ServerAccess.logOutputReprint("Could not open file " + filePath);
                            writer.writeBytes(HTTP_NOT_FOUND);
                            continue;
                        }
                        ServerAccess.logOutputReprint("Serving- " + request + ": " + filePath);

                        int resourceLength = (int) resource.length();
                        byte[] buff = new byte[resourceLength];
                        FileInputStream fis = new FileInputStream(resource);
                        fis.read(buff);
                        fis.close();

                        // When Range support is on, consume the request headers to find a Range:
                        // header and answer with 206 Partial Content (RFC 7233). One request per
                        // connection in this mode (the headers are drained here, not after serving).
                        String rangeHeader = null;
                        if (supportRangeRequests) {
                            String h;
                            while ((h = reader.readLine()) != null && h.length() > 0) {
                                if (rangeHeader == null && h.regionMatches(true, 0, "Range:", 0, 6)) {
                                    rangeHeader = h.substring(6).trim();
                                }
                            }
                            if (rangeHeaderSink != null && rangeHeader != null) {
                                rangeHeaderSink.set(rangeHeader);
                            }
                        }
                        RangeSlice slice = supportRangeRequests ? parseRange(rangeHeader, resourceLength) : null;

                        String contentType = "Content-Type: ";
                        if (filePath.toLowerCase().endsWith(".jnlp")) {
                            contentType += "application/x-java-jnlp-file";
                        } else if (filePath.toLowerCase().endsWith(".jar")) {
                            contentType += "application/x-jar";
                        } else {
                            contentType += "text/html";
                        }
                        String lastModified = "";
                        if (supportLastModified) {
                            lastModified = "Last-Modified: " + new Date(resource.lastModified()) + CRLF;
                        }

                        if (slice != null && slice.unsatisfiable) {
                            writer.writeBytes("HTTP/1.0 416 Range Not Satisfiable" + CRLF);
                            writer.writeBytes("Content-Range: bytes */" + resourceLength + CRLF);
                            writer.writeBytes("Accept-Ranges: bytes" + CRLF + CRLF);
                        } else if (slice != null) {
                            writer.writeBytes("HTTP/1.0 206 Partial Content" + CRLF);
                            writer.writeBytes("Content-Length:" + slice.length + CRLF);
                            writer.writeBytes("Content-Range: bytes " + slice.start + "-" + slice.end
                                    + "/" + resourceLength + CRLF);
                            writer.writeBytes("Accept-Ranges: bytes" + CRLF + lastModified + contentType + CRLF + CRLF);
                            if (isGetRequest) {
                                writer.write(buff, (int) slice.start, slice.length);
                            }
                        } else {
                            writer.writeBytes(HTTP_OK + "Content-Length:" + resourceLength + CRLF + lastModified + contentType + CRLF + CRLF);

                            if (isGetRequest) {
                                if (slowSend) {
                                    byte[][] bb = splitArray(buff, 10);
                                    for (int j = 0; j < bb.length; j++) {
                                        Thread.sleep(2000);
                                        byte[] bs = bb[j];
                                        writer.write(bs, 0, bs.length);
                                    }
                                } else {
                                    writer.write(buff, 0, resourceLength);
                                }
                            }
                        }

                        if (supportRangeRequests) {
                            // Headers were drained above; this connection carries a single request.
                            break;
                        }
                    }
                }
            } catch (SocketException e) {
                ServerAccess.logException(e, false);
            } catch (Exception e) {
                writer.writeBytes(HTTP_NOT_FOUND);
                ServerAccess.logException(e, false);
            } finally {
                reader.close();
                writer.close();
            }
        } catch (Exception e) {
            ServerAccess.logException(e, false);
        }
    }

    /**
     * Parse a single {@code Range: bytes=start-end|start-|-suffix} request value into the
     * slice to serve. Returns {@code null} when there is no Range header (serve full 200),
     * a satisfiable {@link RangeSlice} for a valid range, or an {@code unsatisfiable} slice
     * (416) when the range is malformed/out of bounds.
     */
    static RangeSlice parseRange(String rangeHeader, int resourceLength) {
        if (rangeHeader == null) {
            return null;
        }
        String h = rangeHeader.trim();
        int eq = h.indexOf('=');
        if (eq == -1 || !h.substring(0, eq).trim().equalsIgnoreCase("bytes")) {
            return null;
        }
        String spec = h.substring(eq + 1).trim();
        int comma = spec.indexOf(',');
        if (comma != -1) {
            spec = spec.substring(0, comma).trim();
        }
        int dash = spec.indexOf('-');
        if (dash == -1) {
            return new RangeSlice(true, 0, 0, 0); // malformed -> treat as 416
        }
        String s = spec.substring(0, dash).trim();
        String e = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (s.isEmpty()) {
                if (e.isEmpty()) {
                    return new RangeSlice(true, 0, 0, 0);
                }
                long suffix = Long.parseLong(e);
                if (suffix <= 0) {
                    return new RangeSlice(true, 0, 0, 0);
                }
                start = Math.max(0L, resourceLength - suffix);
                end = resourceLength - 1L;
            } else {
                start = Long.parseLong(s);
                end = e.isEmpty() ? resourceLength - 1L : Long.parseLong(e);
            }
            if (resourceLength <= 0 || start >= resourceLength || start > end) {
                return new RangeSlice(true, 0, 0, 0);
            }
            if (end >= resourceLength) {
                end = resourceLength - 1L;
            }
            return new RangeSlice(false, start, end, (int) (end - start + 1));
        } catch (NumberFormatException ex) {
            return new RangeSlice(true, 0, 0, 0);
        }
    }

    static final class RangeSlice {
        final boolean unsatisfiable;
        final long start;
        final long end;
        final int length;

        RangeSlice(boolean unsatisfiable, long start, long end, int length) {
            this.unsatisfiable = unsatisfiable;
            this.start = start;
            this.end = end;
            this.length = length;
        }
    }

    /**
     * This function splits input array to severasl pieces from byte[length]
     * splitt to n pieces s is retrunrd byte[n][length/n], except last piece
     * which contains length%n
     *
     * @param input - array to be splitted
     * @param pieces - to how many pieces it should be broken
     * @return inidividual pices of original array, which concatet again givs
     * original array
     */
    public static byte[][] splitArray(byte[] input, int pieces) {
        int rest = input.length;
        int rowLength = rest / pieces;
        if (rest % pieces > 0) {
            rowLength++;
        }
        if (pieces * rowLength >= rest + rowLength) {
            pieces--;
        }
        int i = 0, j = 0;
        byte[][] array = new byte[pieces][];
        array[0] = new byte[rowLength];
        for (byte b : input) {
            if (i >= rowLength) {
                i = 0;
                array[++j] = new byte[Math.min(rowLength, rest)];
            }
            array[j][i++] = b;
            rest--;
        }
        return array;
    }

    /**
     * This function transforms a request URL into a path to a file which the
     * server will return to the requester.
     *
     * @param url - the request URL
     * @return a String representation of the local path to the file
     * @throws UnsupportedEncodingException
     */
    public static String urlToFilePath(String url) throws UnsupportedEncodingException {
        url = URLDecoder.decode(url, "UTF-8"); // Decode URL encoded charaters, eg "%3B" becomes ';'
        if (url.startsWith(XSX)) {
            url = url.replace(XSX, "/");
        }
        url = url.replaceAll("\\?.*", ""); // Remove query string from URL
        url = ".".concat(url); // Change path into relative path
        if (url.endsWith("/")) {
            url += "index.html";
        }
        url = url.replace('/', File.separatorChar); // If running on Windows, replace '/' in path with "\\"
        url = stripHttpPathParams(url);
        return url;
    }

    /**
     * This function removes the HTTP Path Parameter from a given JAR URL,
     * assuming that the path param delimiter is a semicolon
     *
     * @param url - the URL from which to remove the path parameter
     * @return the URL with the path parameter removed
     */
    public static String stripHttpPathParams(String url) {
        if (url == null) {
            return null;
        }

        // If JNLP specifies JAR URL with .JAR extension (as it should), then look for any semicolons
        // after this position. If one is found, remove it and any following characters.
        int fileExtension = url.toUpperCase().lastIndexOf(".JAR");
        if (fileExtension != -1) {
            int firstSemiColon = url.indexOf(';', fileExtension);
            if (firstSemiColon != -1) {
                url = url.substring(0, firstSemiColon);
            }
        }
        return url;
    }

    /**
     * When redirect is set, the requests to this server will just redirect to
     * the underlying ServerLauncher
     */
    private ServerLauncher redirect = null;

    void setRedirect(ServerLauncher redirect) {
        this.redirect = redirect;
    }

    /**
     * one of: 301, 302,303, 307, 308,
     */
    private int redirectCode = 302;

    void setRedirectCode(int redirectPort) {
        this.redirectCode = redirectPort;
    }

    //resoource -> request -> number of requests on of this rsource on this server
    // eg   simpletest1.jnlp -> GET -> 3
    private Map<String, Map<String, Integer>> requestsCounter;

    public void setRequestsCounter(Map<String, Map<String, Integer>> requestsCounter) {
        this.requestsCounter = requestsCounter;
    }

    void setAuthenticator(Authentication511Requester ar) {
        this.authenticationRequester = ar;
    }

}
