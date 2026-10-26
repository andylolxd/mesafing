package com.mesafing.app;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lista de clientes del router Starlink vía su API local gRPC-web (puerto 9001, sin contraseña):
 * el mismo nombre que muestra la app de Starlink, la IP y si está por cable o WiFi.
 */
final class Starlink {
    static final class Client {
        String ip;
        String name;
        String mac;
        String link;
    }

    private static final int PORT = 9001;
    private static final int MAX_BYTES = 1 << 20;
    /** SpaceX.API.Device.Request { wifi_get_clients (campo 3002) = {} } */
    private static final byte[] WIFI_GET_CLIENTS = {(byte) 0xD2, (byte) 0xBB, 0x01, 0x00};

    /** null si el router no es Starlink o no respondió. */
    static List<Client> clients(Network net, String router) {
        if (router == null) return null;
        byte[] frame = new byte[5 + WIFI_GET_CLIENTS.length];
        frame[4] = (byte) WIFI_GET_CLIENTS.length;
        System.arraycopy(WIFI_GET_CLIENTS, 0, frame, 5, WIFI_GET_CLIENTS.length);
        byte[] body = post(net, router, "/SpaceX.API.Device.Device/Handle", frame);
        if (body == null) return null;
        byte[] msg = firstDataFrame(body);
        if (msg == null) return null;
        try {
            return parseResponse(msg);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] post(Network net, String host, String path, byte[] payload) {
        Socket s = null;
        try {
            s = net != null ? net.getSocketFactory().createSocket() : new Socket();
            s.connect(new InetSocketAddress(host, PORT), 1500);
            s.setSoTimeout(3000);
            String head = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + PORT + "\r\n"
                    + "Content-Type: application/grpc-web+proto\r\n"
                    + "Accept: application/grpc-web+proto\r\n"
                    + "X-Grpc-Web: 1\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (buf.size() < MAX_BYTES) {
                int n;
                try {
                    n = in.read(chunk);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (n < 0) break;
                buf.write(chunk, 0, n);
            }
            byte[] raw = buf.toByteArray();
            int sep = indexOf(raw, new byte[] {'\r', '\n', '\r', '\n'});
            if (sep < 0) return null;
            String headers = new String(raw, 0, sep, StandardCharsets.US_ASCII);
            if (!headers.startsWith("HTTP/1.1 200") && !headers.startsWith("HTTP/1.0 200")) return null;
            byte[] body = Arrays.copyOfRange(raw, sep + 4, raw.length);
            String te = Http.header(headers, "Transfer-Encoding");
            return te != null && te.toLowerCase().contains("chunked") ? dechunk(body) : body;
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

    private static byte[] dechunk(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int p = 0;
        while (p < b.length) {
            int eol = indexOf(b, new byte[] {'\r', '\n'}, p);
            if (eol < 0) break;
            String sizeLine = new String(b, p, eol - p, StandardCharsets.US_ASCII).split(";")[0].trim();
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                break;
            }
            p = eol + 2;
            if (size == 0) break;
            int n = Math.min(size, b.length - p);
            out.write(b, p, n);
            p += n + 2;
        }
        return out.toByteArray();
    }

    /** gRPC-web: [flag 1 byte][largo 4 bytes][mensaje]; flag 0x80 son los trailers. */
    private static byte[] firstDataFrame(byte[] b) {
        int p = 0;
        while (p + 5 <= b.length) {
            int flag = b[p] & 0xFF;
            int len = ((b[p + 1] & 0xFF) << 24) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 8) | (b[p + 4] & 0xFF);
            p += 5;
            if (len < 0 || p + len > b.length) return null;
            if ((flag & 0x80) == 0) return Arrays.copyOfRange(b, p, p + len);
            p += len;
        }
        return null;
    }

    private static List<Client> parseResponse(byte[] msg) {
        Pb r = new Pb(msg, 0, msg.length);
        while (r.has()) {
            long tag = r.varint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 7);
            if (field == 3002 && wt == 2) {
                List<Client> list = new ArrayList<>();
                Pb c = r.sub();
                while (c.has()) {
                    long t = c.varint();
                    if ((t >>> 3) == 1 && (t & 7) == 2) {
                        Client cl = parseClient(c.sub());
                        if (cl.ip != null) list.add(cl);
                    } else {
                        c.skip((int) (t & 7));
                    }
                }
                return list;
            }
            r.skip(wt);
        }
        return null;
    }

    private static Client parseClient(Pb r) {
        Client c = new Client();
        String name = null;
        String given = null;
        while (r.has()) {
            long tag = r.varint();
            int field = (int) (tag >>> 3);
            int wt = (int) (tag & 7);
            if (wt == 2 && (field == 1 || field == 2 || field == 3 || field == 31)) {
                String v = r.string().trim();
                if (v.isEmpty()) continue;
                if (field == 1) name = v;
                else if (field == 31) given = v;
                else if (field == 3) c.ip = v;
                else if (v.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) c.mac = v.toUpperCase();
            } else if (field == 9 && wt == 0) {
                c.link = linkLabel((int) r.varint());
            } else {
                r.skip(wt);
            }
        }
        c.name = given != null ? given : name;
        return c;
    }

    private static String linkLabel(int iface) {
        switch (iface) {
            case 1:
                return "Cable (Ethernet)";
            case 2:
                return "WiFi 2.4 GHz";
            case 3:
            case 4:
                return "WiFi 5 GHz";
            default:
                return null;
        }
    }

    private static int indexOf(byte[] b, byte[] pat) {
        return indexOf(b, pat, 0);
    }

    private static int indexOf(byte[] b, byte[] pat, int from) {
        outer:
        for (int i = from; i <= b.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (b[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Lector protobuf mínimo (solo lo necesario para recorrer campos). */
    private static final class Pb {
        private final byte[] b;
        private int p;
        private final int end;

        Pb(byte[] b, int start, int end) {
            this.b = b;
            this.p = start;
            this.end = end;
        }

        boolean has() {
            return p < end;
        }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (p >= end) throw new IllegalStateException("varint");
                int x = b[p++] & 0xFF;
                v |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalStateException("varint");
        }

        Pb sub() {
            int len = (int) varint();
            if (len < 0 || p + len > end) throw new IllegalStateException("len");
            Pb s = new Pb(b, p, p + len);
            p += len;
            return s;
        }

        String string() {
            Pb s = sub();
            return new String(b, s.p, s.end - s.p, StandardCharsets.UTF_8);
        }

        void skip(int wt) {
            switch (wt) {
                case 0:
                    varint();
                    break;
                case 1:
                    p += 8;
                    break;
                case 2:
                    sub();
                    break;
                case 5:
                    p += 4;
                    break;
                default:
                    throw new IllegalStateException("wire type " + wt);
            }
            if (p > end) throw new IllegalStateException("skip");
        }
    }
}
