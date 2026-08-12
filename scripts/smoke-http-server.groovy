#!/usr/bin/env groovy
// Minimal static-file HTTP server for the packaged-distribution smoke tests
// (replaces python -m http.server; Python is banned in this environment).
//
// Usage: groovy smoke-http-server.groovy <port> <root-dir>
// Binds 127.0.0.1:<port>, serves files under <root-dir>. Compatible with
// Groovy 2.4 (this repo/toolchain) and 4.x (brew/choco on runners).
import java.nio.file.Files

def port = args.length > 0 ? Integer.parseInt(args[0]) : 8123
def root = args.length > 1 ? new File(args[1]) : new File('.')
def rootCanonical = root.canonicalPath

def contentTypes = [
    jnlp:       'application/x-java-jnlp-file',
    jar:        'application/java-archive',
    html:       'text/html',
    xml:        'text/xml',
    txt:        'text/plain',
    properties: 'text/plain',
]

def server = new ServerSocket(port, 50, InetAddress.getByName('127.0.0.1'))
println "serving ${rootCanonical} on 127.0.0.1:${port}"

while (true) {
    def sock = server.accept()
    Thread.start {
        try {
            sock.withStreams { input, output ->
                def reader = new BufferedReader(new InputStreamReader(input, 'ISO-8859-1'))
                def requestLine = reader.readLine()
                if (requestLine == null) {
                    return
                }
                def parts = requestLine.split(' ')
                def method = parts.length > 0 ? parts[0] : 'GET'
                def rawPath = parts.length > 1 ? parts[1] : '/'
                def rel = new URI(rawPath).path
                if (rel == null || rel.isEmpty()) {
                    rel = '/'
                }
                def relFile = (rel.startsWith('/') ? rel.substring(1) : rel).replace('\\', '/')
                def file = new File(root, relFile)
                def fileCanonical = file.canonicalPath
                def valid = file.isFile() && fileCanonical.startsWith(rootCanonical)
                if (!valid) {
                    def notFound = '404 Not Found\n'.bytes
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${notFound.length}\r\nConnection: close\r\n\r\n".bytes)
                    output.write(notFound)
                    return
                }
                def name = file.name.toLowerCase()
                def dot = name.lastIndexOf('.')
                def ext = dot >= 0 ? name.substring(dot + 1) : ''
                def contentType = contentTypes.containsKey(ext) ? contentTypes[ext] : 'application/octet-stream'
                def data = Files.readAllBytes(file.toPath())
                def headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${contentType}\r\n" +
                    "Content-Length: ${data.length}\r\n" +
                    "Last-Modified: ${file.lastModified()}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(headers.bytes)
                if (method == 'GET') {
                    output.write(data)
                }
            }
        } catch (Exception ignored) {
            // best-effort single connection; keep serving
        } finally {
            try { sock.close() } catch (Exception ignored) { }
        }
    }
}
