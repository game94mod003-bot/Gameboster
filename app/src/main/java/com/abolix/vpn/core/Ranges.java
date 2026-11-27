package com.abolix.vpn.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** IPv4 CIDR list + random sampling (weighted by range size). */
public final class Ranges {
    private final List<long[]> r = new ArrayList<>(); // {start, size}
    private long total;

    public static Ranges parse(List<String> lines) {
        Ranges x = new Ranges();
        for (String ln : lines) {
            String s = ln.trim();
            int hash = s.indexOf('#');
            if (hash >= 0) s = s.substring(0, hash).trim();
            if (s.isEmpty()) continue;
            try {
                String ip = s;
                int bits = 32;
                int sl = s.indexOf('/');
                if (sl > 0) { ip = s.substring(0, sl); bits = Integer.parseInt(s.substring(sl + 1).trim()); }
                if (bits < 8 || bits > 32) continue;
                String[] p = ip.trim().split("\\.");
                if (p.length != 4) continue;
                long v = 0;
                for (String o : p) {
                    int n = Integer.parseInt(o);
                    if (n < 0 || n > 255) throw new NumberFormatException();
                    v = (v << 8) | n;
                }
                long size = 1L << (32 - bits);
                long start = v & ~(size - 1);
                x.r.add(new long[]{start, size});
                x.total += size;
            } catch (Exception ignore) { }
        }
        return x;
    }

    public long total() { return total; }

    public static String str(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    public List<String> sample(int count, Random rnd) {
        List<String> out = new ArrayList<>();
        if (total <= 0) return out;
        long want = Math.min((long) count, total);
        Set<Long> seen = new HashSet<>();
        long attempts = 0, max = want * 30 + 100;
        while (seen.size() < want && attempts++ < max) {
            long pick = (long) (rnd.nextDouble() * total);
            for (long[] a : r) {
                if (pick < a[1]) {
                    long ip = a[0] + pick;
                    int last = (int) (ip & 255);
                    if (last != 0 && last != 255 && seen.add(ip)) out.add(str(ip));
                    break;
                }
                pick -= a[1];
            }
        }
        return out;
    }
}
