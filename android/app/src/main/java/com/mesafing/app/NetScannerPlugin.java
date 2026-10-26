package com.mesafing.app;

import android.content.Intent;
import android.net.Uri;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "NetScanner")
public class NetScannerPlugin extends Plugin {
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile NetScanner current;

    @PluginMethod
    public void getNetworkInfo(PluginCall call) {
        worker.execute(() -> {
            try {
                call.resolve(NetworkEnv.detect(getContext()).toJson());
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (!scanning.compareAndSet(false, true)) {
            call.reject("Ya hay un escaneo en curso.");
            return;
        }
        worker.execute(() -> {
            try {
                NetworkEnv env;
                try {
                    env = NetworkEnv.detect(getContext());
                } catch (Exception e) {
                    call.reject(e.getMessage());
                    return;
                }
                NetScanner scanner = new NetScanner(getContext(), env, new NetScanner.Listener() {
                    @Override
                    public void onDevice(JSObject device) {
                        notifyListeners("device", device);
                    }

                    @Override
                    public void onProgress(JSObject progress) {
                        notifyListeners("scanProgress", progress);
                    }
                });
                current = scanner;
                try {
                    scanner.run();
                    JSObject res = new JSObject();
                    res.put("network", env.toJson());
                    res.put("devices", scanner.results());
                    res.put("cancelled", scanner.isCancelled());
                    call.resolve(res);
                } catch (Exception e) {
                    call.reject("Error al escanear: " + e.getMessage());
                } finally {
                    current = null;
                }
            } finally {
                scanning.set(false);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        NetScanner s = current;
        if (s != null) s.cancel();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        NetScanner s = current;
        if (s != null) s.cancel();
        call.resolve();
    }

    @PluginMethod
    public void probeHost(PluginCall call) {
        String host = call.getString("host");
        if (host == null || !host.trim().matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            call.reject("IP inválida.");
            return;
        }
        worker.execute(() -> {
            try {
                NetworkEnv env = NetworkEnv.detect(getContext());
                NetScanner scanner = new NetScanner(getContext(), env, new NetScanner.Listener() {
                    @Override public void onDevice(JSObject device) {}
                    @Override public void onProgress(JSObject progress) {}
                });
                call.resolve(scanner.probeSingle(host.trim()));
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void testPrint(PluginCall call) {
        String host = call.getString("host");
        int port = call.getInt("port", 9100);
        if (host == null || host.trim().isEmpty()) {
            call.reject("Falta la IP.");
            return;
        }
        worker.execute(() -> {
            NetworkEnv env = null;
            try {
                env = NetworkEnv.detect(getContext());
            } catch (Exception ignored) {
            }
            try {
                EscPos.testPrint(env != null ? env.network : null, host.trim(), port, env != null ? env.localIp : null);
                call.resolve();
            } catch (Exception e) {
                call.reject("No se pudo imprimir en " + host + ":" + port + " (" + e.getMessage() + ")");
            }
        });
    }

    @PluginMethod
    public void identifyEscPos(PluginCall call) {
        String host = call.getString("host");
        int port = call.getInt("port", 9100);
        if (host == null || host.trim().isEmpty()) {
            call.reject("Falta la IP.");
            return;
        }
        worker.execute(() -> {
            NetworkEnv env = null;
            try {
                env = NetworkEnv.detect(getContext());
            } catch (Exception ignored) {
            }
            try {
                call.resolve(EscPos.identify(env != null ? env.network : null, host.trim(), port));
            } catch (Exception e) {
                call.reject("No respondió " + host + ":" + port + " (" + e.getMessage() + ")");
            }
        });
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            call.reject("URL inválida.");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No hay navegador para abrir " + url);
        }
    }
}
