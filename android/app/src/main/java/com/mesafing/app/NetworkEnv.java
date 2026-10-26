package com.mesafing.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.getcapacitor.JSObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Red WiFi/Ethernet local: IP propia, máscara, router y rango a escanear. */
final class NetworkEnv {
    /** Más grande que /24 son demasiados hosts para un barrido rápido; se escanea el /24 propio. */
    private static final int MIN_SCAN_PREFIX = 24;

    Network network;
    String transport;
    String localIp;
    int prefixLength;
    String gateway;
    String ssid;
    int scanPrefix;
    long networkBase;
    int hostCount;

    static NetworkEnv detect(Context ctx) throws Exception {
        ConnectivityManager cm =
                (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) throw new Exception("No se pudo leer el estado de red.");

        Network chosen = null;
        LinkProperties chosenLp = null;
        LinkAddress chosenAddr = null;
        String transport = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) continue;
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (!wifi && !eth) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            LinkAddress la = findIpv4(lp);
            if (la == null) continue;
            chosen = n;
            chosenLp = lp;
            chosenAddr = la;
            transport = wifi ? "wifi" : "ethernet";
            if (wifi) break;
        }
        if (chosen == null) {
            throw new Exception("No estás conectado a un WiFi. Conectate al WiFi donde está la impresora y volvé a intentar.");
        }

        NetworkEnv env = new NetworkEnv();
        env.network = chosen;
        env.transport = transport;
        env.localIp = chosenAddr.getAddress().getHostAddress();
        env.prefixLength = chosenAddr.getPrefixLength();
        for (RouteInfo r : chosenLp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                env.gateway = r.getGateway().getHostAddress();
                break;
            }
        }
        env.ssid = readSsid(ctx);
        env.scanPrefix = Math.max(env.prefixLength, MIN_SCAN_PREFIX);
        if (env.scanPrefix > 30) env.scanPrefix = 30;
        long ip = ipToLong(env.localIp);
        long mask = (0xFFFFFFFFL << (32 - env.scanPrefix)) & 0xFFFFFFFFL;
        env.networkBase = ip & mask;
        env.hostCount = (int) ((1L << (32 - env.scanPrefix)) - 2);
        return env;
    }

    private static LinkAddress findIpv4(LinkProperties lp) {
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return la;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String readSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String s = info.getSSID();
            if (s == null || s.contains("unknown ssid")) return null;
            return s.replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }

    List<String> hosts() {
        List<String> out = new ArrayList<>(hostCount);
        for (long i = 1; i <= hostCount; i++) out.add(longToIp(networkBase + i));
        return out;
    }

    static long ipToLong(String ip) {
        String[] p = ip.split("\\.");
        long v = 0;
        for (String part : p) v = (v << 8) | (Long.parseLong(part) & 0xFF);
        return v & 0xFFFFFFFFL;
    }

    static String longToIp(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    JSObject toJson() {
        JSObject o = new JSObject();
        o.put("transport", transport);
        o.put("ip", localIp);
        o.put("prefixLength", prefixLength);
        o.put("gateway", gateway);
        o.put("ssid", ssid);
        o.put("networkAddress", longToIp(networkBase));
        o.put("scanPrefix", scanPrefix);
        o.put("hostCount", hostCount);
        o.put("firstHost", longToIp(networkBase + 1));
        o.put("lastHost", longToIp(networkBase + hostCount));
        return o;
    }
}
