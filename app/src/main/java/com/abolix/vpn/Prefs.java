package com.abolix.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    private Prefs() { }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("abolix", Context.MODE_PRIVATE); }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split("\n")) { x = x.trim(); if (!x.isEmpty()) out.add(x); }
        return out;
    }

    static List<String> links(Context c) { return lines(sp(c).getString("links", "")); }
    static void setLinks(Context c, List<String> l) { sp(c).edit().putString("links", join(l)).apply(); }
    static int active(Context c) { return sp(c).getInt("active", 0); }
    static void setActive(Context c, int i) { sp(c).edit().putInt("active", i).apply(); }

    static List<String> clean(Context c) { return lines(sp(c).getString("clean", "")); }
    static void setClean(Context c, List<String> l) { sp(c).edit().putString("clean", join(l)).apply(); }

    /** Adds IPs to the front of the clean list (deduplicated). */
    static int addClean(Context c, List<String> add) {
        Set<String> s = new LinkedHashSet<>(add);
        s.addAll(clean(c));
        setClean(c, new ArrayList<>(s));
        return add.size();
    }

    static String ranges(Context c) {
        String v = sp(c).getString("ranges", null);
        if (v != null) return v;
        StringBuilder b = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("akamai_ranges.txt"), "UTF-8"));
            String ln;
            while ((ln = r.readLine()) != null) b.append(ln).append('\n');
            r.close();
        } catch (Exception ignore) { }
        return b.toString();
    }
    static void setRanges(Context c, String s) { sp(c).edit().putString("ranges", s).apply(); }

    /** 0 auto, 1 off, 2 normal, 3 aggressive */
    static int frag(Context c) { return sp(c).getInt("frag", 0); }
    static void setFrag(Context c, int v) { sp(c).edit().putInt("frag", v).apply(); }

    /** "" = per link, otherwise chrome / firefox / safari / ios / android / randomized */
    static String fp(Context c) { return sp(c).getString("fp", ""); }
    static void setFp(Context c, String v) { sp(c).edit().putString("fp", v).apply(); }

    static boolean mux(Context c) { return sp(c).getBoolean("mux", false); }
    static void setMux(Context c, boolean v) { sp(c).edit().putBoolean("mux", v).apply(); }
    static boolean failover(Context c) { return sp(c).getBoolean("failover", true); }
    static void setFailover(Context c, boolean v) { sp(c).edit().putBoolean("failover", v).apply(); }
    static boolean autoAdd(Context c) { return sp(c).getBoolean("autoadd", true); }
    static void setAutoAdd(Context c, boolean v) { sp(c).edit().putBoolean("autoadd", v).apply(); }
    static String sni(Context c) { return sp(c).getString("sni", "www.akamai.com"); }
    static void setSni(Context c, String v) { sp(c).edit().putString("sni", v).apply(); }
    static int scanCount(Context c) { return sp(c).getInt("count", 1000); }
    static void setScanCount(Context c, int v) { sp(c).edit().putInt("count", v).apply(); }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('\n');
        return b.toString();
    }
}
