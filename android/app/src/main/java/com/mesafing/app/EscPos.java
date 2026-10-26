package com.mesafing.app;

import android.net.Network;

import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Prueba de impresión e identificación de térmicas ESC/POS por TCP (puerto 9100). */
final class EscPos {
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;

    private static Socket connect(Network net, String host, int port) throws Exception {
        Socket s = net != null ? net.getSocketFactory().createSocket() : new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(1200);
        return s;
    }

    static void testPrint(Network net, String host, int port, String fromIp) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        b.write(new byte[] {ESC, '@'});
        b.write(new byte[] {ESC, 'a', 1});
        b.write(new byte[] {ESC, 'E', 1});
        b.write("MESAFING\n".getBytes(StandardCharsets.US_ASCII));
        b.write(new byte[] {ESC, 'E', 0});
        b.write("Prueba de impresion\n".getBytes(StandardCharsets.US_ASCII));
        b.write(new byte[] {ESC, 'a', 0});
        String text = "\n"
                + "IP impresora: " + host + "\n"
                + "Puerto: " + port + "\n"
                + (fromIp != null ? "Desde: " + fromIp + "\n" : "")
                + "Fecha: " + when + "\n\n"
                + "Si lees esto, la impresora\n"
                + "recibe por red. Usa esta IP\n"
                + "y puerto en bole-kds.\n\n\n\n";
        b.write(text.getBytes(StandardCharsets.US_ASCII));
        b.write(new byte[] {GS, 'V', 'A', 3});

        try (Socket s = connect(net, host, port)) {
            OutputStream out = s.getOutputStream();
            out.write(b.toByteArray());
            out.flush();
        }
    }

    /** GS I: id de modelo (n=1), fabricante (66), modelo (67), firmware (65). Muchas térmicas lo soportan. */
    static JSObject identify(Network net, String host, int port) throws Exception {
        JSObject o = new JSObject();
        try (Socket s = connect(net, host, port)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(new byte[] {GS, 'I', 1});
            out.flush();
            byte[] id = readRaw(in, 1);
            o.put("modelId", id.length > 0 ? String.format("0x%02X", id[0] & 0xFF) : null);

            o.put("maker", query(out, in, 66));
            o.put("model", query(out, in, 67));
            o.put("firmware", query(out, in, 65));
        }
        return o;
    }

    private static String query(OutputStream out, InputStream in, int n) throws Exception {
        out.write(new byte[] {GS, 'I', (byte) n});
        out.flush();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < deadline && buf.size() < 80) {
            int v;
            try {
                v = in.read();
            } catch (SocketTimeoutException e) {
                break;
            }
            if (v < 0 || v == 0) break;
            buf.write(v);
        }
        String s = new String(buf.toByteArray(), StandardCharsets.US_ASCII)
                .replaceAll("[^\\x20-\\x7E]", "")
                .trim();
        if (s.startsWith("_")) s = s.substring(1).trim();
        return s.isEmpty() ? null : s;
    }

    private static byte[] readRaw(InputStream in, int max) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < deadline && buf.size() < max) {
            int v;
            try {
                v = in.read();
            } catch (SocketTimeoutException e) {
                break;
            }
            if (v < 0) break;
            buf.write(v);
        }
        return buf.toByteArray();
    }
}
