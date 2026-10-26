package com.mesafing.app;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** SNMP v1 GET mínimo (community "public"): casi todas las impresoras de red informan su modelo así. */
final class Snmp {
    private static final byte[] OID_SYS_DESCR = {0x2B, 6, 1, 2, 1, 1, 1, 0};
    private static final byte[] OID_SYS_NAME = {0x2B, 6, 1, 2, 1, 1, 5, 0};
    /** HOST-RESOURCES-MIB hrDeviceDescr.1: en impresoras suele ser "Marca Modelo". */
    private static final byte[] OID_HR_DEVICE_DESCR_1 = {0x2B, 6, 1, 2, 1, 0x19, 3, 2, 1, 3, 1};
    private static final Random RND = new Random();

    static final class Result {
        String sysDescr;
        String sysName;
        String deviceDescr;
    }

    static Result query(Network net, String ip) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            if (net != null) net.bindSocket(ds);
            ds.setSoTimeout(600);
            InetAddress addr = InetAddress.getByName(ip);
            String descr = get(ds, addr, OID_SYS_DESCR);
            if (descr == null) return null;
            Result r = new Result();
            r.sysDescr = descr;
            r.sysName = get(ds, addr, OID_SYS_NAME);
            r.deviceDescr = get(ds, addr, OID_HR_DEVICE_DESCR_1);
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (ds != null) ds.close();
        }
    }

    private static String get(DatagramSocket ds, InetAddress addr, byte[] oid) throws Exception {
        int reqId = RND.nextInt(0x7000) + 1;
        byte[] pkt = buildGet(oid, reqId);
        ds.send(new DatagramPacket(pkt, pkt.length, addr, 161));
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + 700;
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                ds.receive(resp);
            } catch (SocketTimeoutException e) {
                return null;
            }
            String v = parse(Arrays.copyOf(buf, resp.getLength()), reqId);
            if (v != null) return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static byte[] buildGet(byte[] oid, int reqId) throws Exception {
        byte[] varbind = tlv(0x30, concat(tlv(0x06, oid), new byte[] {0x05, 0x00}));
        byte[] varbindList = tlv(0x30, varbind);
        byte[] pdu = tlv(0xA0, concat(
                tlv(0x02, intBytes(reqId)),
                tlv(0x02, new byte[] {0}),
                tlv(0x02, new byte[] {0}),
                varbindList));
        return tlv(0x30, concat(
                tlv(0x02, new byte[] {0}),
                tlv(0x04, "public".getBytes(StandardCharsets.US_ASCII)),
                pdu));
    }

    private static byte[] intBytes(int v) {
        if (v < 0x80) return new byte[] {(byte) v};
        return new byte[] {(byte) (v >> 8), (byte) (v & 0xFF)};
    }

    private static byte[] tlv(int tag, byte[] value) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = value.length;
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len & 0xFF);
        }
        o.write(value);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p);
        return o.toByteArray();
    }

    /** null = no es nuestra respuesta; "" = respuesta sin valor útil. */
    private static String parse(byte[] b, int reqId) {
        try {
            Ber r = new Ber(b);
            r.enter(0x30);
            r.skip();
            r.skip();
            r.enter(0xA2);
            if (r.readInt() != reqId) return null;
            if (r.readInt() != 0) return "";
            r.skip();
            r.enter(0x30);
            r.enter(0x30);
            r.skip();
            int tag = r.peekTag();
            byte[] val = r.readValue();
            if (tag != 0x04) return "";
            return clean(new String(val, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        String out = s.replaceAll("[\\x00-\\x1F\\x7F]+", " ").trim();
        if (out.length() > 200) out = out.substring(0, 200);
        return out;
    }

    private static final class Ber {
        private final byte[] b;
        private int p;

        Ber(byte[] b) {
            this.b = b;
        }

        int peekTag() {
            return b[p] & 0xFF;
        }

        private int header(int expectTag) throws Exception {
            int t = b[p++] & 0xFF;
            if (expectTag >= 0 && t != expectTag) throw new Exception("tag " + t);
            int len = b[p++] & 0xFF;
            if ((len & 0x80) != 0) {
                int n = len & 0x7F;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (b[p++] & 0xFF);
            }
            return len;
        }

        void enter(int tag) throws Exception {
            header(tag);
        }

        void skip() throws Exception {
            p += header(-1);
        }

        byte[] readValue() throws Exception {
            int len = header(-1);
            byte[] v = Arrays.copyOfRange(b, p, p + len);
            p += len;
            return v;
        }

        int readInt() throws Exception {
            int x = 0;
            for (byte bb : readValue()) x = (x << 8) | (bb & 0xFF);
            return x;
        }
    }
}
