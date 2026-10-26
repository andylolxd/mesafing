package com.mesafing.app;

import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Consulta NBSTAT (UDP 137): nombre y MAC de PCs Windows y algunos NAS/impresoras. */
final class NetBios {
    static final class Result {
        String name;
        String mac;
    }

    static Result query(Network net, String ip) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            if (net != null) net.bindSocket(ds);
            ds.setSoTimeout(500);
            byte[] q = new byte[50];
            q[0] = 0x4D;
            q[1] = 0x46;
            q[5] = 1;
            q[12] = 0x20;
            q[13] = 'C';
            q[14] = 'K';
            for (int i = 15; i < 45; i++) q[i] = 'A';
            q[47] = 0x21;
            q[49] = 0x01;
            ds.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            int len = resp.getLength();
            int p = 12;
            if ((buf[p] & 0xC0) == 0xC0) {
                p += 2;
            } else {
                while (p < len && buf[p] != 0) p += (buf[p] & 0xFF) + 1;
                p++;
            }
            p += 10;
            if (p >= len) return null;
            int count = buf[p] & 0xFF;
            p++;
            Result r = new Result();
            for (int i = 0; i < count; i++) {
                if (p + 18 > len) break;
                String n = new String(buf, p, 15, StandardCharsets.US_ASCII).trim();
                int suffix = buf[p + 15] & 0xFF;
                int flags = ((buf[p + 16] & 0xFF) << 8) | (buf[p + 17] & 0xFF);
                if (r.name == null && suffix == 0 && (flags & 0x8000) == 0 && !n.isEmpty()) r.name = n;
                p += 18;
            }
            if (p + 6 <= len) {
                StringBuilder sb = new StringBuilder();
                boolean allZero = true;
                for (int i = 0; i < 6; i++) {
                    int v = buf[p + i] & 0xFF;
                    if (v != 0) allZero = false;
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02X", v));
                }
                if (!allZero) r.mac = sb.toString();
            }
            return (r.name == null && r.mac == null) ? null : r;
        } catch (Exception e) {
            return null;
        } finally {
            if (ds != null) ds.close();
        }
    }
}
