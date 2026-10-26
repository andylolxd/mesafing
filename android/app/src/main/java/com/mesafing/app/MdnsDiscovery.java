package com.mesafing.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** mDNS/Bonjour: impresoras (IPP/RAW), Chromecast, AirPlay, PCs, etc. con modelo en los TXT. */
final class MdnsDiscovery {
    interface Callback {
        void onService(String ip, String type, String name, int port, Map<String, String> attrs);
    }

    private static final String[] TYPES = {
        "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp",
        "_scanner._tcp", "_uscan._tcp", "_http._tcp", "_googlecast._tcp",
        "_airplay._tcp", "_raop._tcp", "_smb._tcp", "_workstation._tcp",
        "_device-info._tcp", "_companion-link._tcp", "_hap._tcp", "_spotify-connect._tcp",
        "_androidtvremote2._tcp", "_sonos._tcp", "_ssh._tcp", "_rfb._tcp",
    };

    private final NsdManager nsd;
    private final Callback cb;
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    /** resolveService no admite resoluciones simultáneas en Android < 14. */
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    MdnsDiscovery(Context ctx, Callback cb) {
        this.nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.cb = cb;
    }

    void start() {
        if (nsd == null) return;
        for (String type : TYPES) {
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
                @Override public void onDiscoveryStarted(String serviceType) {}
                @Override public void onDiscoveryStopped(String serviceType) {}
                @Override public void onServiceLost(NsdServiceInfo info) {}

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    String key = info.getServiceType() + "|" + info.getServiceName();
                    if (seen.add(key)) enqueue(info);
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
                listeners.add(l);
            } catch (Exception ignored) {
            }
        }
    }

    private void enqueue(NsdServiceInfo info) {
        pending.incrementAndGet();
        try {
            resolver.execute(() -> {
                try {
                    resolveBlocking(info);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pending.decrementAndGet();
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveBlocking(NsdServiceInfo info) {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo si, int errorCode) {
                    latch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo si) {
                    try {
                        deliver(si);
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(2500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void deliver(NsdServiceInfo si) {
        InetAddress host = si.getHost();
        if (!(host instanceof Inet4Address)) return;
        Map<String, String> attrs = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, byte[]> e : si.getAttributes().entrySet()) {
                byte[] v = e.getValue();
                attrs.put(e.getKey(), v == null ? "" : new String(v, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        String type = si.getServiceType() == null ? "" : si.getServiceType().replaceAll("^\\.+|\\.+$", "");
        cb.onService(host.getHostAddress(), type, si.getServiceName(), si.getPort(), attrs);
    }

    void awaitResolves(long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    void stop() {
        for (NsdManager.DiscoveryListener l : listeners) {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (Exception ignored) {
            }
        }
        listeners.clear();
        resolver.shutdownNow();
    }
}
