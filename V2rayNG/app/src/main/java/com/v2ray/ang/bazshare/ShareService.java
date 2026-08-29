package com.v2ray.ang.bazshare;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;

/**
 * Bazcode hotspot VPN sharing daemon for rooted Android 4.4.2+ devices.
 * It watches the hotspot and VPN tunnel and installs/removes routing rules
 * automatically. Sharing is enabled by default.
 */
public final class ShareService extends Service {
    private static final String TAG = "BazcodeVpnShare";
    private static final long TICK_MS = 10000L;
    private static final String CLIENT_NET = "192.168.43.0/24";
    private static final String TABLE = "61";
    private static final String PREFS = "baz_vpn_share";

    public static final String ACTION_STATE = "com.v2ray.ang.BAZ_SHARE_STATE";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_APPLIED = "applied";
    public static final String EXTRA_VPN = "vpn";
    public static final String EXTRA_AP = "ap";

    public static boolean isEnabled(Context ctx) {
        return ctx != null && ctx.getSharedPreferences(PREFS, 0).getBoolean("enabled", true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply();
        }
    }

    public static void ensureRunning(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.startService(new Intent(ctx, ShareService.class));
        } catch (Exception ignored) {}
    }

    private final Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private String appliedVpn = "";
    private String appliedAp = "";
    private boolean applied;
    private boolean running;
    private long lastAttempt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    reconcile();
                    handler.postDelayed(tick, nextDelay());
                }
            }, "baz-vpn-share-tick").start();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":worker");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
        handler.post(tick);
        broadcastState();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.post(tick);
        }
        // Allow explicit refresh after toggling.
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        if (applied) cleanup();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        broadcastState();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private long nextDelay() {
        if (!isEnabled(this)) return 60000L;
        return applied ? TICK_MS : 5000L;
    }

    private void reconcile() {
        if (!isEnabled(this)) {
            if (applied) cleanup();
            broadcastState();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAttempt < 900L) return;
        lastAttempt = now;

        String ap = SystemState.hotspotInterface();
        String vpn = SystemState.vpnInterface();

        if (vpn.length() == 0 || !SystemState.isUp(vpn)) vpn = "";

        if (ap.length() == 0 || vpn.length() == 0) {
            if (applied) cleanup();
            broadcastState();
            return;
        }

        if (applied && (!ap.equals(appliedAp) || !vpn.equals(appliedVpn))) {
            cleanup();
        }

        if (!applied) {
            if (apply(vpn, ap)) {
                applied = true;
                appliedVpn = vpn;
                appliedAp = ap;
            }
        }
        broadcastState();
    }

    private boolean apply(String vpn, String ap) {
        if (!safe(vpn) || !safe(ap)) return false;

        String cmd =
            "iptables -w -t nat -N BAZ_VS_POST 2>/dev/null || true; " +
            "iptables -w -N BAZ_VS_FWD 2>/dev/null || true; " +
            "iptables -w -t mangle -N BAZ_VS_MSS 2>/dev/null || true; " +

            "while iptables -w -t nat -D POSTROUTING -j BAZ_VS_POST 2>/dev/null; do :; done; " +
            "iptables -w -t nat -I POSTROUTING 1 -j BAZ_VS_POST; " +

            "while iptables -w -D FORWARD -j BAZ_VS_FWD 2>/dev/null; do :; done; " +
            "iptables -w -I FORWARD 1 -j BAZ_VS_FWD; " +

            "iptables -w -t mangle -C FORWARD -j BAZ_VS_MSS 2>/dev/null || " +
            "iptables -w -t mangle -A FORWARD -j BAZ_VS_MSS; " +

            "iptables -w -t nat -F BAZ_VS_POST; " +
            "iptables -w -F BAZ_VS_FWD; " +
            "iptables -w -t mangle -F BAZ_VS_MSS; " +

            "echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true; " +

            "iptables -w -A BAZ_VS_FWD -i " + ap + " -o " + vpn + " -j ACCEPT; " +
            "iptables -w -A BAZ_VS_FWD -i " + vpn + " -o " + ap +
            " -m state --state ESTABLISHED,RELATED -j ACCEPT; " +

            // Reject QUIC so clients fall back to TCP through older tunnels.
            "iptables -w -I BAZ_VS_FWD 1 -i " + ap +
            " -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable; " +

            "iptables -w -t mangle -A BAZ_VS_MSS -i " + ap +
            " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350; " +
            "iptables -w -t mangle -A BAZ_VS_MSS -o " + ap +
            " -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350; " +

            "iptables -w -t nat -A BAZ_VS_POST -s " + CLIENT_NET +
            " -o " + vpn + " -j MASQUERADE; " +

            "while ip rule del from " + CLIENT_NET + " lookup " + TABLE +
            " 2>/dev/null; do :; done; " +
            "ip rule add from " + CLIENT_NET + " lookup " + TABLE +
            " pref 50 2>/dev/null || true; " +
            "ip route flush table " + TABLE + " 2>/dev/null || true; " +
            "ip route add " + CLIENT_NET + " dev " + ap +
            " table " + TABLE + " 2>/dev/null || true; " +
            "ip route add default dev " + vpn +
            " table " + TABLE + " 2>/dev/null || true; " +
            "echo BAZ_VPN_SHARE_OK";

        String out = root(cmd, 9);
        return out.indexOf("BAZ_VPN_SHARE_OK") >= 0;
    }

    private void cleanup() {
        String cmd =
            "iptables -w -t nat -F BAZ_VS_POST 2>/dev/null || true; " +
            "iptables -w -F BAZ_VS_FWD 2>/dev/null || true; " +
            "iptables -w -t mangle -F BAZ_VS_MSS 2>/dev/null || true; " +
            "while iptables -w -t nat -D POSTROUTING -j BAZ_VS_POST 2>/dev/null; do :; done; " +
            "while iptables -w -D FORWARD -j BAZ_VS_FWD 2>/dev/null; do :; done; " +
            "while iptables -w -t mangle -D FORWARD -j BAZ_VS_MSS 2>/dev/null; do :; done; " +
            "ip rule del from " + CLIENT_NET + " lookup " + TABLE + " 2>/dev/null || true; " +
            "ip route flush table " + TABLE + " 2>/dev/null || true; " +
            "echo BAZ_VPN_SHARE_OFF";

        root(cmd, 6);
        applied = false;
        appliedVpn = "";
        appliedAp = "";
    }

    private void broadcastState() {
        try {
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_ENABLED, isEnabled(this));
            i.putExtra(EXTRA_APPLIED, applied);
            i.putExtra(EXTRA_VPN, appliedVpn);
            i.putExtra(EXTRA_AP, appliedAp);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private static boolean safe(String s) {
        return s != null && s.matches("[A-Za-z0-9_.:-]{2,20}");
    }

    private static String root(String command, int timeoutSec) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] {"su", "-c", command});
            final Process fp = p;
            final StringBuilder out = new StringBuilder();

            Thread stdout = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                            new InputStreamReader(fp.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (out.length() < 4096) out.append(line).append('\n');
                        }
                    } catch (Exception ignored) {}
                }
            }, "baz-share-out");

            Thread stderr = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                            new InputStreamReader(fp.getErrorStream()));
                        while (br.readLine() != null) {}
                    } catch (Exception ignored) {}
                }
            }, "baz-share-err");

            stdout.start();
            stderr.start();

            long end = System.currentTimeMillis() + timeoutSec * 1000L;
            while (System.currentTimeMillis() < end) {
                try { p.exitValue(); break; }
                catch (IllegalThreadStateException e) { Thread.sleep(100L); }
            }

            try { p.destroy(); } catch (Exception ignored) {}
            stdout.join(300L);
            stderr.join(300L);
            return out.toString();
        } catch (Exception e) {
            try { if (p != null) p.destroy(); } catch (Exception ignored) {}
            return "";
        }
    }

    private static final class SystemState {
        static String hotspotInterface() {
            String out = shell("ip -o -4 addr show 2>/dev/null", 2);
            String[] lines = out.split("\\n");

            for (String line : lines) {
                if (line.indexOf("192.168.43.1/") >= 0 ||
                    line.indexOf("192.168.43.0/") >= 0) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length > 1 && safe(p[1])) return p[1];
                }
            }

            for (String candidate : new String[] {
                "wlan0", "ap0", "softap0", "wlan1"
            }) {
                if (new File("/sys/class/net/" + candidate).exists()) {
                    String addr = shell(
                        "ip -4 addr show " + candidate + " 2>/dev/null", 1);
                    if (addr.indexOf("192.168.43.") >= 0) return candidate;
                }
            }
            return "";
        }

        static boolean isUp(String name) {
            try {
                String s = shell(
                    "cat /sys/class/net/" + name + "/operstate 2>/dev/null", 1
                ).trim();
                return "up".equals(s) || "unknown".equals(s) ||
                       "dormant".equals(s);
            } catch (Exception e) {
                return false;
            }
        }

        static String vpnInterface() {
            File dir = new File("/sys/class/net");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if ((n.matches("tun[0-9]+") ||
                         n.matches("ppp[0-9]+") ||
                         n.matches("wg[0-9]+") ||
                         n.matches("tap[0-9]+")) &&
                         !"lo".equals(n)) {
                        return n;
                    }
                }
            }
            return "";
        }

        static String shell(String cmd, int timeoutSec) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[] {"sh", "-c", cmd});
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                long end = System.currentTimeMillis() + timeoutSec * 1000L;
                String line;
                while (System.currentTimeMillis() < end &&
                       (line = br.readLine()) != null) {
                    out.append(line).append('\n');
                }
                try { p.destroy(); } catch (Exception ignored) {}
                return out.toString();
            } catch (Exception e) {
                try { if (p != null) p.destroy(); } catch (Exception ignored) {}
                return "";
            }
        }
    }
}
