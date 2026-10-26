package com.mesafing.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Datos crudos de un dispositivo; la clasificación (impresora, router, etc.) se hace en TS. */
final class DeviceInfo {
    final String ip;
    private final Set<Integer> ports = new TreeSet<>();
    private final Set<String> services = new LinkedHashSet<>();
    private final Set<String> sources = new LinkedHashSet<>();
    private final Map<String, String> attrs = new LinkedHashMap<>();
    volatile boolean alive;
    volatile boolean isSelf;
    volatile boolean isGateway;

    DeviceInfo(String ip) {
        this.ip = ip;
    }

    synchronized void addPort(int port) {
        ports.add(port);
    }

    synchronized boolean hasPort(int port) {
        return ports.contains(port);
    }

    synchronized void addService(String type) {
        if (type != null && !type.isEmpty()) services.add(type);
    }

    synchronized void addSource(String source) {
        sources.add(source);
    }

    synchronized void put(String key, String value) {
        if (value == null) return;
        String v = value.trim();
        if (!v.isEmpty()) attrs.put(key, v);
    }

    synchronized void putIfAbsent(String key, String value) {
        if (!attrs.containsKey(key)) put(key, value);
    }

    synchronized JSObject toJson() {
        JSObject o = new JSObject();
        o.put("ip", ip);
        o.put("alive", alive);
        o.put("isSelf", isSelf);
        o.put("isGateway", isGateway);
        JSArray p = new JSArray();
        for (Integer port : ports) p.put(port);
        o.put("ports", p);
        JSArray s = new JSArray();
        for (String svc : services) s.put(svc);
        o.put("services", s);
        JSArray src = new JSArray();
        for (String x : sources) src.put(x);
        o.put("sources", src);
        for (Map.Entry<String, String> e : attrs.entrySet()) o.put(e.getKey(), e.getValue());
        return o;
    }
}
