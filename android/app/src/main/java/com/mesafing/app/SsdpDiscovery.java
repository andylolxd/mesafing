package com.mesafing.app;

import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** UPnP/SSDP: routers, TVs, NAS e impresoras anuncian nombre, fabricante y modelo. */
final class SsdpDiscovery {
    interface Callback {
        void onDevice(String ip, String name, String manufacturer, String model, String server);
    }

    interface Cancelled {
        boolean get();
    }

    private static final String[] SEARCH_TARGETS = {"ssdp:all", "upnp:rootdevice"};

    static void run(Network net, Callback cb, Cancelled cancelled) {
        DatagramSocket ds = null;
        Map<String, String> locations = new LinkedHashMap<>();
        Map<String, String> servers = new LinkedHashMap<>();
        try {
            ds = new DatagramSocket();
            if (net != null) net.bindSocket(ds);
            ds.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int round = 0; round < 2; round++) {
                for (String st : SEARCH_TARGETS) {
                    String msg = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 2\r\n"
                            + "ST: " + st + "\r\n\r\n";
                    byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                    ds.send(new DatagramPacket(b, b.length, group, 1900));
                }
            }
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline && !cancelled.get()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (!(p.getAddress() instanceof Inet4Address)) continue;
                String ip = p.getAddress().getHostAddress();
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String loc = Http.header(text, "LOCATION");
                String server = Http.header(text, "SERVER");
                if (loc != null && !locations.containsKey(ip)) locations.put(ip, loc);
                if (server != null && !servers.containsKey(ip)) servers.put(ip, server);
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }

        for (Map.Entry<String, String> e : servers.entrySet()) {
            if (cancelled.get()) return;
            String ip = e.getKey();
            String loc = locations.get(ip);
            String name = null;
            String manufacturer = null;
            String model = null;
            if (loc != null && loc.startsWith("http://")) {
                try {
                    URL u = new URL(loc);
                    int port = u.getPort() > 0 ? u.getPort() : 80;
                    String path = u.getFile().isEmpty() ? "/" : u.getFile();
                    Http.Response r = Http.get(net, u.getHost(), port, path, 2000, 65536);
                    if (r != null) {
                        name = Http.xmlTag(r.body, "friendlyName");
                        manufacturer = Http.xmlTag(r.body, "manufacturer");
                        String modelName = Http.xmlTag(r.body, "modelName");
                        String modelNumber = Http.xmlTag(r.body, "modelNumber");
                        model = modelName;
                        if (modelNumber != null && (model == null || !model.contains(modelNumber))) {
                            model = model == null ? modelNumber : model + " " + modelNumber;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            cb.onDevice(ip, name, manufacturer, model, e.getValue());
        }
        for (Map.Entry<String, String> e : locations.entrySet()) {
            if (!servers.containsKey(e.getKey())) cb.onDevice(e.getKey(), null, null, null, null);
        }
    }
}
