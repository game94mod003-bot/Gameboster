package com.abolix.vpn.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Two-stage clean-IP scanner:
 *  1) TCP connect + TLS handshake to every candidate with the chosen front SNI.
 *  2) The best candidates are re-probed 3x (jitter/loss) and, if a backend host is given,
 *     a real fronted request (WebSocket upgrade or plain GET) is sent to confirm the IP reaches the backend.
 * Trust-all is used ONLY here: no credentials are ever sent by the scanner.
 */
public final class ProbeScanner {
    public static final class Res {
        public String ip;
        public int ms;          // average handshake time
        public int loss;        // 0..3 failed refinement probes
        public boolean backendOk;
        public int score() { return ms + loss * 500 + (backendOk ? 0 : 150); }
    }

    public interface Cb {
        void progress(int done, int total, int found);
        void phase(String text);
    }

    private volatile boolean stop;
    private SSLSocketFactory factory;

    public void cancel() { stop = true; }

    private SSLSocketFactory factory() throws Exception {
        if (factory == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            factory = ctx.getSocketFactory();
        }
        return factory;
    }

    /** @param wsPath if non-null the backend check is a WebSocket upgrade on that path. */
    public List<Res> run(List<String> ips, final String sni, final String backendHost, final String wsPath,
                         final int port, final int timeoutMs, int threads, final Cb cb) throws Exception {
        stop = false;
        factory();
        final int total = ips.size();
        final AtomicInteger done = new AtomicInteger(), found = new AtomicInteger();
        final ConcurrentLinkedQueue<Res> ok = new ConcurrentLinkedQueue<>();
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(4, Math.min(threads, 256)));
        if (cb != null) cb.phase("scan");
        for (final String ip : ips) {
            ex.execute(new Runnable() {
                public void run() {
                    if (!stop) {
                        int ms = probe(ip, sni, port, timeoutMs, null, null);
                        if (ms >= 0) {
                            Res r = new Res();
                            r.ip = ip;
                            r.ms = ms;
                            ok.add(r);
                            found.incrementAndGet();
                        }
                    }
                    int d = done.incrementAndGet();
                    if (cb != null && (d % 8 == 0 || d == total)) cb.progress(d, total, found.get());
                }
            });
        }
        ex.shutdown();
        ex.awaitTermination(1, TimeUnit.HOURS);
        List<Res> all = new ArrayList<>(ok);
        Collections.sort(all, new Comparator<Res>() {
            public int compare(Res a, Res b) { return a.ms - b.ms; }
        });
        if (stop || all.isEmpty()) return all;

        // ---- refinement
        if (cb != null) cb.phase("refine");
        final List<Res> top = new ArrayList<>(all.subList(0, Math.min(24, all.size())));
        ExecutorService ex2 = Executors.newFixedThreadPool(8);
        for (final Res r : top) {
            ex2.execute(new Runnable() {
                public void run() {
                    int sum = r.ms, n = 1, loss = 0;
                    for (int i = 0; i < 3 && !stop; i++) {
                        int m = probe(r.ip, sni, port, timeoutMs, null, null);
                        if (m < 0) loss++; else { sum += m; n++; }
                    }
                    r.ms = sum / n;
                    r.loss = loss;
                    if (!stop && backendHost != null && !backendHost.isEmpty())
                        r.backendOk = probe(r.ip, sni, port, timeoutMs + 1500, backendHost, wsPath) >= 0;
                }
            });
        }
        ex2.shutdown();
        ex2.awaitTermination(10, TimeUnit.MINUTES);
        Collections.sort(top, new Comparator<Res>() {
            public int compare(Res a, Res b) { return a.score() - b.score(); }
        });
        return top;
    }

    /** @return handshake ms, or -1. If backendHost != null also verifies a fronted request. */
    int probe(String ip, String sni, int port, int timeout, String backendHost, String wsPath) {
        Socket raw = new Socket();
        try {
            long t0 = System.nanoTime();
            raw.connect(new InetSocketAddress(ip, port), timeout);
            raw.setSoTimeout(timeout);
            SSLSocket ss = (SSLSocket) factory.createSocket(raw, sni, port, true);
            SSLParameters p = ss.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(sni)));
            ss.setSSLParameters(p);
            ss.startHandshake();
            int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
            if (backendHost == null) return ms;
            StringBuilder rq = new StringBuilder();
            if (wsPath != null) {
                rq.append("GET ").append(wsPath).append(" HTTP/1.1\r\nHost: ").append(backendHost)
                  .append("\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n")
                  .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nUser-Agent: Mozilla/5.0\r\n\r\n");
            } else {
                rq.append("GET / HTTP/1.1\r\nHost: ").append(backendHost)
                  .append("\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n");
            }
            OutputStream os = ss.getOutputStream();
            os.write(rq.toString().getBytes("US-ASCII"));
            os.flush();
            String line = new BufferedReader(new InputStreamReader(ss.getInputStream(), "US-ASCII")).readLine();
            if (line == null || !line.startsWith("HTTP/")) return -1;
            String[] sp = line.split(" ");
            int code = sp.length > 1 ? Integer.parseInt(sp[1]) : 0;
            boolean good = wsPath != null ? code == 101 : (code >= 200 && code < 400);
            return good ? ms : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { raw.close(); } catch (Exception ignore) { }
        }
    }
}
