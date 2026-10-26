package com.mesafing.app;

import android.content.Context;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Barrido de la red local + identificación de cada equipo (SNMP, NetBIOS, mDNS, UPnP, HTTP). */
final class NetScanner {
    interface Listener {
        void onDevice(JSObject device);

        void onProgress(JSObject progress);
    }

    static final int[] SWEEP_PORTS = {
        80, 443, 9100, 22, 445, 139, 62078, 8080, 554, 53, 515, 631, 8008, 5000, 7000, 49152,
    };
    static final int[] EXTRA_PORTS = {
        21, 23, 8009, 8443, 3389, 5900, 1883, 8000, 8081, 9000, 1400, 5357, 548, 3000, 8888,
    };
    private static final int[] HTTP_PORTS = {80, 8080, 8000, 8081};
    private static final int SWEEP_TIMEOUT_MS = 280;
    private static final int DETAIL_TIMEOUT_MS = 500;

    private final Context ctx;
    private final NetworkEnv env;
    private final Network net;
    private final Listener listener;
    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();
    private volatile boolean cancelled;
    private volatile long lastProgressAt;

    NetScanner(Context ctx, NetworkEnv env, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.env = env;
        this.net = env.network;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
    }

    boolean isCancelled() {
        return cancelled;
    }

    JSArray results() {
        JSArray arr = new JSArray();
        for (DeviceInfo d : devices.values()) {
            if (d.alive) arr.put(d.toJson());
        }
        return arr;
    }

    void run() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("mesafing");
            lock.setReferenceCounted(false);
            try {
                lock.acquire();
            } catch (Exception ignored) {
            }
        }

        MdnsDiscovery mdns = new MdnsDiscovery(ctx, this::onMdnsService);
        mdns.start();
        Thread ssdp = new Thread(() -> SsdpDiscovery.run(net, this::onSsdpDevice, () -> cancelled), "mesafing-ssdp");
        ssdp.start();

        try {
            DeviceInfo self = device(env.localIp);
            self.isSelf = true;
            self.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            markAlive(env.localIp, "self");
            if (env.gateway != null) device(env.gateway).isGateway = true;

            loadRouterClients(null);
            sweep();
            readArpTable();
            try {
                ssdp.join(8000);
            } catch (InterruptedException ignored) {
            }
            details();
            mdns.awaitResolves(3000);
        } finally {
            mdns.stop();
            if (lock != null) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }
        progress("done", 1, 1, true);
    }

    /** Escaneo puntual de una IP (por si el barrido no la encontró o está en otra subred). */
    JSObject probeSingle(String host) {
        DeviceInfo d = device(host);
        if (env.localIp.equals(host)) d.isSelf = true;
        if (host.equals(env.gateway)) d.isGateway = true;
        loadRouterClients(host);
        probePorts(d, SWEEP_PORTS, DETAIL_TIMEOUT_MS);
        if (!d.alive) {
            try {
                if (InetAddress.getByName(host).isReachable(800)) markAlive(host, "ping");
            } catch (Exception ignored) {
            }
        }
        enrich(d);
        return d.toJson();
    }

    /** Nombres que conoce el router (hoy solo Starlink); onlyIp limita a un equipo. */
    private void loadRouterClients(String onlyIp) {
        List<Starlink.Client> clients = Starlink.clients(net, env.gateway);
        if (clients == null) return;
        device(env.gateway).putIfAbsent("deviceName", "Router Starlink");
        for (Starlink.Client c : clients) {
            if (onlyIp != null && !onlyIp.equals(c.ip)) continue;
            DeviceInfo d = device(c.ip);
            d.put("routerName", c.name);
            d.put("link", c.link);
            d.putIfAbsent("mac", c.mac);
            markAlive(c.ip, "starlink");
            emit(d);
        }
    }

    private void sweep() {
        List<String> hosts = env.hosts();
        int total = hosts.size();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(96);
        for (String ip : hosts) {
            pool.execute(() -> {
                try {
                    if (!cancelled && !ip.equals(env.localIp)) {
                        DeviceInfo d = device(ip);
                        probePorts(d, SWEEP_PORTS, SWEEP_TIMEOUT_MS);
                        if (!d.alive && !cancelled) {
                            try {
                                if (InetAddress.getByName(ip).isReachable(300)) markAlive(ip, "ping");
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } finally {
                    progress("sweep", done.incrementAndGet(), total, false);
                }
            });
        }
        shutdownAndWait(pool, 120);
    }

    private void details() {
        List<DeviceInfo> alive = new ArrayList<>();
        for (DeviceInfo d : devices.values()) {
            if (d.alive) alive.add(d);
        }
        int total = alive.size();
        AtomicInteger done = new AtomicInteger();
        progress("details", 0, total, true);
        ExecutorService pool = Executors.newFixedThreadPool(24);
        for (DeviceInfo d : alive) {
            pool.execute(() -> {
                try {
                    if (cancelled) return;
                    if (!d.isSelf) probePorts(d, EXTRA_PORTS, DETAIL_TIMEOUT_MS);
                    enrich(d);
                } finally {
                    progress("details", done.incrementAndGet(), total, false);
                }
            });
        }
        shutdownAndWait(pool, 90);
    }

    /** Nombre DNS, SNMP (modelo de impresoras/routers), NetBIOS (PCs Windows) y título web. */
    private void enrich(DeviceInfo d) {
        if (d.isSelf) {
            emit(d);
            return;
        }
        String host = d.ip;
        String rdns = reverseDns(host);
        if (rdns != null) d.putIfAbsent("hostname", rdns);
        if (cancelled) return;

        Snmp.Result snmp = Snmp.query(net, host);
        if (snmp != null) {
            d.put("snmpDescr", snmp.sysDescr);
            d.put("snmpName", snmp.sysName);
            d.put("snmpDevice", snmp.deviceDescr);
            d.addSource("snmp");
            markAlive(host, "snmp");
        }
        if (cancelled) return;

        NetBios.Result nb = NetBios.query(net, host);
        if (nb != null) {
            d.put("netbiosName", nb.name);
            d.putIfAbsent("mac", nb.mac);
            d.addSource("netbios");
            markAlive(host, "netbios");
        }
        if (cancelled) return;

        for (int port : HTTP_PORTS) {
            if (!d.hasPort(port)) continue;
            Http.Response r = Http.get(net, host, port, "/", 1500, 32768);
            if (r == null) continue;
            d.putIfAbsent("httpServer", Http.header(r.headers, "Server"));
            String title = Http.title(r.body);
            if (title != null) {
                d.putIfAbsent("httpTitle", title);
                d.putIfAbsent("httpPort", String.valueOf(port));
                break;
            }
        }
        emit(d);
    }

    private String reverseDns(String ip) {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<String> f = one.submit(() -> InetAddress.getByName(ip).getCanonicalHostName());
            String name = f.get(1500, TimeUnit.MILLISECONDS);
            if (name == null || name.equals(ip)) return null;
            return name;
        } catch (Exception e) {
            return null;
        } finally {
            one.shutdownNow();
        }
    }

    private void probePorts(DeviceInfo d, int[] ports, int timeoutMs) {
        for (int port : ports) {
            if (cancelled) return;
            int r = tcpProbe(d.ip, port, timeoutMs);
            if (r == 1) {
                d.addPort(port);
                markAlive(d.ip, "tcp");
            } else if (r == 0 && !d.alive) {
                markAlive(d.ip, "tcp");
            }
        }
    }

    /** 1 = puerto abierto, 0 = rechazado (el equipo existe), -1 = sin respuesta. */
    private int tcpProbe(String ip, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = net != null ? net.getSocketFactory().createSocket() : new Socket();
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return 1;
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage());
            return (m.contains("ECONNREFUSED") || m.contains("refused")) ? 0 : -1;
        } catch (SocketTimeoutException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** /proc/net/arp está bloqueado desde Android 10; se intenta igual por si el equipo lo permite. */
    private void readArpTable() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                parseNeighbor(parts, 0, 3, "0x2".equals(safe(parts, 2)));
            }
            return;
        } catch (Exception ignored) {
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"ip", "neigh", "show"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    int macIdx = -1;
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("lladdr")) macIdx = i + 1;
                    }
                    boolean ok = macIdx > 0 && !line.contains("FAILED") && !line.contains("INCOMPLETE");
                    parseNeighbor(parts, 0, macIdx, ok);
                }
            }
            p.destroy();
        } catch (Exception ignored) {
        }
    }

    private void parseNeighbor(String[] parts, int ipIdx, int macIdx, boolean complete) {
        if (!complete || macIdx < 0 || parts.length <= Math.max(ipIdx, macIdx)) return;
        String ip = parts[ipIdx];
        String mac = parts[macIdx];
        if (!ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || mac.equals("00:00:00:00:00:00")) return;
        device(ip).putIfAbsent("mac", mac.toUpperCase());
        markAlive(ip, "arp");
    }

    private static String safe(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }

    private void onMdnsService(String ip, String type, String name, int port, Map<String, String> attrs) {
        DeviceInfo d = device(ip);
        d.addService(type);
        if (port > 0) d.addPort(port);
        d.addSource("mdns");
        String friendly = attrs.get("fn");
        d.putIfAbsent("mdnsName", friendly != null && !friendly.isEmpty() ? friendly : name);

        String mfg = attrs.get("usb_MFG");
        String mdl = attrs.get("usb_MDL");
        String usb = mdl == null ? null : (mfg != null && !mdl.toLowerCase().startsWith(mfg.toLowerCase()) ? mfg + " " + mdl : mdl);
        String ty = attrs.get("ty");
        if (type.startsWith("_ipp") || type.startsWith("_printer") || type.startsWith("_pdl")) {
            d.putIfAbsent("printerModel", ty != null ? ty : usb);
        }
        String[] modelKeys = {"ty", "md", "model", "am", "rpMd", "product"};
        for (String k : modelKeys) {
            String v = attrs.get(k);
            if (v != null && !v.isEmpty()) {
                d.putIfAbsent("mdnsModel", v.replaceAll("^\\(|\\)$", ""));
                break;
            }
        }
        if (usb != null) d.putIfAbsent("mdnsModel", usb);
        markAlive(ip, "mdns");
        emit(d);
    }

    private void onSsdpDevice(String ip, String name, String manufacturer, String model, String server) {
        DeviceInfo d = device(ip);
        d.addSource("upnp");
        d.put("upnpName", name);
        d.put("upnpManufacturer", manufacturer);
        d.put("upnpModel", model);
        d.put("upnpServer", server);
        markAlive(ip, "upnp");
        emit(d);
    }

    private DeviceInfo device(String ip) {
        return devices.computeIfAbsent(ip, DeviceInfo::new);
    }

    private void markAlive(String ip, String source) {
        DeviceInfo d = device(ip);
        d.addSource(source);
        if (!d.alive) {
            d.alive = true;
            emit(d);
        }
    }

    private void emit(DeviceInfo d) {
        if (d.alive) listener.onDevice(d.toJson());
    }

    private void progress(String phase, int done, int total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && done < total && now - lastProgressAt < 150) return;
        lastProgressAt = now;
        int found = 0;
        for (DeviceInfo d : devices.values()) {
            if (d.alive) found++;
        }
        JSObject p = new JSObject();
        p.put("phase", phase);
        p.put("done", done);
        p.put("total", total);
        p.put("found", found);
        listener.onProgress(p);
    }

    private static void shutdownAndWait(ExecutorService pool, int seconds) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(seconds, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }
    }
}
