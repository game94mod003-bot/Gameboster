package com.abolix.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.abolix.vpn.core.Link;
import com.abolix.vpn.core.XrayConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import hev.htproxy.TProxyService;

public class TunnelService extends VpnService {
    static final String ACTION_START = "com.abolix.vpn.START";
    static final String ACTION_STOP = "com.abolix.vpn.STOP";
    static final int OFF = 0, CONNECTING = 1, ON = 2, ERROR = 3;
    static final int SOCKS = 10808, HTTP = 10809, HIST = 60;

    // ---- shared live state (read by the UI)
    static volatile int state = OFF;
    static volatile String status = "";
    static volatile String method = "—";
    static volatile String exitIp = "—";
    static volatile int ping = -1;
    static volatile int stability = 100;
    static volatile long downBps, upBps;
    static volatile boolean fullTunnel;
    private static final float[] dh = new float[HIST], uh = new float[HIST];

    static synchronized void push(float d, float u) {
        System.arraycopy(dh, 1, dh, 0, HIST - 1);
        System.arraycopy(uh, 1, uh, 0, HIST - 1);
        dh[HIST - 1] = d;
        uh[HIST - 1] = u;
    }

    static synchronized void snapshot(float[] d, float[] u) {
        System.arraycopy(dh, 0, d, 0, HIST);
        System.arraycopy(uh, 0, u, 0, HIST);
    }

    static synchronized void clearHist() {
        java.util.Arrays.fill(dh, 0f);
        java.util.Arrays.fill(uh, 0f);
    }

    private static final class Strat {
        String ip;
        int frag;
        String fp;
        Strat(String ip, int frag, String fp) { this.ip = ip; this.frag = frag; this.fp = fp; }

        String describe() {
            String f = frag == 0 ? "بدون فرگمنت" : frag == 1 ? "فرگمنت معمولی" : "فرگمنت شدید";
            String p = fp.isEmpty() ? "" : " · " + fp;
            return (ip.isEmpty() ? "IP اصلی" : ip) + " · " + f + p;
        }
    }

    private volatile boolean running;
    private Thread worker;
    private Process xray;
    private ParcelFileDescriptor tun;
    private boolean tunStarted;
    private final boolean[] win = new boolean[30];
    private int winN, winPos;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        foreground("در حال اتصال…");
        running = true;
        worker = new Thread(new Runnable() { public void run() { work(); } }, "abolix-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() { stopAll(); stopSelf(); }

    @Override
    public void onDestroy() { stopAll(); super.onDestroy(); }

    // ------------------------------------------------------------------ main flow
    private void work() {
        state = CONNECTING;
        status = "در حال آماده‌سازی…";
        method = "—";
        exitIp = "—";
        ping = -1;
        stability = 100;
        downBps = upBps = 0;
        winN = winPos = 0;
        clearHist();

        List<String> links = Prefs.links(this);
        int ai = Prefs.active(this);
        Link link = ai >= 0 && ai < links.size() ? Link.parse(links.get(ai)) : null;
        if (link == null) { fail("کانفیگ فعال معتبر نیست. از تب «کانفیگ» یک لینک اضافه کنید."); return; }

        List<Strat> list = strategies(link);
        int cur = -1;
        for (int i = 0; i < list.size() && running; i++) {
            Strat s = list.get(i);
            status = "تست روش " + (i + 1) + " از " + list.size();
            method = s.describe();
            if (!startXray(link, s)) continue;
            int p = Net.ping(SOCKS);
            if (p >= 0) { cur = i; ping = p; break; }
        }
        if (!running) return;
        if (cur < 0) { fail("هیچ‌کدام از روش‌ها وصل نشد. IP تمیز جدید اسکن کنید یا کانفیگ دیگری انتخاب کنید."); return; }

        if (!establish()) { fail("مجوز VPN داده نشد یا قطع شد."); return; }
        state = ON;
        status = "متصل";
        foreground("متصل · " + method);
        fetchExitIp();

        long lastRx = -1, lastTx = -1;
        int tick = 0, failStreak = 0;
        boolean failover = Prefs.failover(this);
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            long rx, tx;
            if (TProxyService.loaded && tunStarted) {
                long[] st = null;
                try { st = TProxyService.TProxyGetStats(); } catch (Throwable ignore) { }
                rx = st != null && st.length >= 4 ? st[3] : 0;
                tx = st != null && st.length >= 4 ? st[1] : 0;
            } else {
                rx = TrafficStats.getTotalRxBytes();
                tx = TrafficStats.getTotalTxBytes();
            }
            if (lastRx >= 0) {
                downBps = Math.max(0, rx - lastRx);
                upBps = Math.max(0, tx - lastTx);
                push(downBps, upBps);
            }
            lastRx = rx;
            lastTx = tx;

            if (++tick % 5 == 0) {
                int p = Net.ping(SOCKS);
                record(p >= 0);
                if (p >= 0) { ping = p; failStreak = 0; }
                else failStreak++;
                if (failStreak >= 3 && failover && running) {
                    failStreak = 0;
                    cur = (cur + 1) % list.size();
                    Strat s = list.get(cur);
                    status = "تعویض خودکار روش…";
                    method = s.describe();
                    if (startXray(link, s) && Net.ping(SOCKS) >= 0) {
                        status = "متصل";
                        foreground("متصل · " + method);
                        fetchExitIp();
                    }
                }
            }
        }
    }

    private List<Strat> strategies(Link link) {
        int fm = Prefs.frag(this);
        List<String> ips = Prefs.clean(this);
        List<String> use = new ArrayList<>();
        if (ips.isEmpty()) use.add("");
        else for (int i = 0; i < ips.size() && i < 4; i++) use.add(ips.get(i));
        int[] fr = fm == 0 ? new int[]{0, 1, 2} : fm == 1 ? new int[]{0} : fm == 2 ? new int[]{1} : new int[]{2};
        String fp = Prefs.fp(this);
        List<Strat> out = new ArrayList<>();
        for (String ip : use) for (int f : fr) out.add(new Strat(ip, f, fp));
        if (fp.isEmpty()) {
            for (String ip : use) out.add(new Strat(ip, fr[fr.length - 1], "randomized"));
            for (String ip : use) out.add(new Strat(ip, fr[0], "firefox"));
        }
        return out;
    }

    private boolean startXray(Link link, Strat s) {
        killXray();
        try {
            XrayConfig.Opts o = new XrayConfig.Opts();
            o.socksPort = SOCKS;
            o.httpPort = HTTP;
            o.fragment = s.frag;
            o.fp = s.fp;
            o.mux = Prefs.mux(this);
            File cfg = new File(getFilesDir(), "xray.json");
            FileOutputStream fo = new FileOutputStream(cfg);
            fo.write(XrayConfig.build(link, s.ip, o).getBytes("UTF-8"));
            fo.close();
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libxray.so");
            if (!bin.exists()) { status = "هسته Xray داخل برنامه نیست (بیلد ناقص)"; return false; }
            ProcessBuilder pb = new ProcessBuilder(bin.getPath(), "run", "-c", cfg.getPath());
            pb.environment().put("XRAY_LOCATION_ASSET", getFilesDir().getPath());
            pb.redirectErrorStream(true);
            xray = pb.start();
            final Process pr = xray;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                        while (r.readLine() != null) { /* drain */ }
                    } catch (Exception ignore) { }
                }
            }).start();
            for (int i = 0; i < 40 && running; i++) {
                try {
                    Socket sk = new Socket();
                    sk.connect(new InetSocketAddress("127.0.0.1", SOCKS), 150);
                    sk.close();
                    return true;
                } catch (Exception e) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { return false; }
                }
            }
        } catch (Exception e) {
            status = "خطا در اجرای هسته";
        }
        return false;
    }

    private void killXray() {
        Process p = xray;
        xray = null;
        if (p != null) {
            p.destroy();
            try { p.waitFor(); } catch (Exception ignore) { }
        }
    }

    private boolean establish() {
        try {
            Builder b = new Builder();
            b.setSession("Abolix");
            b.setMtu(1500);
            b.addAddress("198.18.0.1", 32);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("8.8.8.8");
            b.addDnsServer("9.9.9.9");
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignore) { }
            fullTunnel = TProxyService.loaded;
            if (!fullTunnel && Build.VERSION.SDK_INT >= 29)
                b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", HTTP));
            tun = b.establish();
            if (tun == null) return false;
            if (TProxyService.loaded) {
                File f = new File(getFilesDir(), "hev.yml");
                FileOutputStream fo = new FileOutputStream(f);
                String y = "tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\nsocks5:\n  port: " + SOCKS
                        + "\n  address: 127.0.0.1\n  udp: 'udp'\nmisc:\n  log-level: warn\n";
                fo.write(y.getBytes("UTF-8"));
                fo.close();
                TProxyService.TProxyStartService(f.getPath(), tun.getFd());
                tunStarted = true;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void record(boolean ok) {
        win[winPos] = ok;
        winPos = (winPos + 1) % win.length;
        if (winN < win.length) winN++;
        int good = 0;
        for (int i = 0; i < winN; i++) if (win[i]) good++;
        stability = winN == 0 ? 100 : good * 100 / winN;
    }

    private void fetchExitIp() {
        new Thread(new Runnable() {
            public void run() {
                String ip = Net.exitIp(SOCKS);
                if (ip != null && running) exitIp = ip;
            }
        }).start();
    }

    private void fail(String msg) {
        status = msg;
        state = ERROR;
        running = false;
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    private void stopAll() {
        running = false;
        if (worker != null) worker.interrupt();
        cleanup();
        state = OFF;
        status = "";
        downBps = upBps = 0;
        stopForeground(true);
    }

    private void cleanup() {
        if (tunStarted) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignore) { }
            tunStarted = false;
        }
        killXray();
        if (tun != null) {
            try { tun.close(); } catch (Exception ignore) { }
            tun = null;
        }
    }

    private void foreground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("abolix", "Abolix", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "abolix")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("ابولیکس")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(1, n);
    }
}
