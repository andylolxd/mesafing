package com.mesafing.app;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP/1.0 sobre socket ligado a la red WiFi (evita VPN y la restricción de texto plano). */
final class Http {
    static final class Response {
        int status;
        String headers = "";
        String body = "";
    }

    private static final Pattern TITLE =
            Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);

    static Response get(Network net, String host, int port, String path, int timeoutMs, int maxBytes) {
        Response r = getOnce(net, host, port, path, timeoutMs, maxBytes);
        if (r == null) return null;
        if (r.status >= 300 && r.status < 400) {
            String loc = header(r.headers, "Location");
            if (loc != null) {
                String nextPath = null;
                if (loc.startsWith("/")) {
                    nextPath = loc;
                } else if (loc.startsWith("http://")) {
                    try {
                        URL u = new URL(loc);
                        if (u.getHost().equals(host)) {
                            nextPath = u.getFile().isEmpty() ? "/" : u.getFile();
                            if (u.getPort() > 0) port = u.getPort();
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (nextPath != null) {
                    Response r2 = getOnce(net, host, port, nextPath, timeoutMs, maxBytes);
                    if (r2 != null) return r2;
                }
            }
        }
        return r;
    }

    private static Response getOnce(Network net, String host, int port, String path, int timeoutMs, int maxBytes) {
        Socket s = null;
        try {
            s = net != null ? net.getSocketFactory().createSocket() : new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            String hostHeader = port == 80 ? host : host + ":" + port;
            String req = "GET " + path + " HTTP/1.0\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: mesafing/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs * 2L;
            while (buf.size() < maxBytes && System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = in.read(chunk);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (n < 0) break;
                buf.write(chunk, 0, n);
            }
            String raw = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (!raw.startsWith("HTTP/")) return null;
            Response r = new Response();
            int idx = raw.indexOf("\r\n\r\n");
            r.headers = idx >= 0 ? raw.substring(0, idx) : raw;
            r.body = idx >= 0 ? raw.substring(idx + 4) : "";
            String[] first = r.headers.split("\r\n", 2)[0].split(" ");
            if (first.length >= 2) {
                try {
                    r.status = Integer.parseInt(first[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String header(String headers, String name) {
        String prefix = name.toLowerCase() + ":";
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return null;
    }

    static String title(String body) {
        Matcher m = TITLE.matcher(body);
        if (!m.find()) return null;
        String t = decode(m.group(1)).replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    static String xmlTag(String body, String tag) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*>([^<]*)</" + tag + ">", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) return null;
        String v = decode(m.group(1)).trim();
        return v.isEmpty() ? null : v;
    }

    private static String decode(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
