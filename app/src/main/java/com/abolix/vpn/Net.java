package com.abolix.vpn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/** Measurements that go through the local Xray SOCKS port (never Cloudflare endpoints). */
final class Net {
    private Net() { }

    private static HttpURLConnection open(String url, int port, int timeout) throws Exception {
        Proxy p = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(p);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setInstanceFollowRedirects(false);
        return c;
    }

    /** @return round-trip ms or -1 */
    static int time(String url, int port, int timeout) {
        HttpURLConnection c = null;
        try {
            long t0 = System.nanoTime();
            c = open(url, port, timeout);
            int code = c.getResponseCode();
            if (code <= 0) return -1;
            return (int) ((System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int ping(int port) {
        int a = time("http://connectivitycheck.gstatic.com/generate_204", port, 6000);
        return a;
    }

    static String exitIp(int port) {
        String[] urls = {"https://checkip.amazonaws.com", "https://ifconfig.me/ip", "https://ipinfo.io/ip"};
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = open(u, port, 7000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                String s = r.readLine();
                if (s != null && s.trim().matches("[0-9a-fA-F:.]{7,45}")) return s.trim();
            } catch (Exception ignore) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }
}
