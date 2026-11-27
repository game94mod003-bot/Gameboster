package com.abolix.vpn.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed vless:// trojan:// vmess:// link. Pure Java (no Android deps). */
public final class Link {
    public String proto = "vless", id = "", server = "", remark = "", net = "tcp", security = "none";
    public String sni = "", host = "", path = "/", alpn = "", fp = "chrome", flow = "", serviceName = "", mode = "";
    public String pbk = "", sid = "", spx = "", aid = "0";
    public int port = 443;
    public String raw = "";

    public String label() {
        String r = remark.isEmpty() ? server : remark;
        return proto.toUpperCase() + " · " + net + " · " + r;
    }

    public String frontHost() {
        if (!sni.isEmpty()) return sni;
        if (!host.isEmpty()) return host;
        return server;
    }

    public static Link parse(String s) {
        try {
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            Link l = s.startsWith("vmess://") ? vmess(s) : std(s);
            if (l != null) l.raw = s;
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    private static Link std(String s) {
        Link l = new Link();
        String rest;
        if (s.startsWith("vless://")) { l.proto = "vless"; rest = s.substring(8); }
        else if (s.startsWith("trojan://")) { l.proto = "trojan"; rest = s.substring(9); }
        else return null;
        int h = rest.indexOf('#');
        if (h >= 0) { l.remark = dec(rest.substring(h + 1)); rest = rest.substring(0, h); }
        String query = "";
        int qm = rest.indexOf('?');
        if (qm >= 0) { query = rest.substring(qm + 1); rest = rest.substring(0, qm); }
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        l.id = dec(rest.substring(0, at));
        String hp = rest.substring(at + 1);
        while (hp.endsWith("/")) hp = hp.substring(0, hp.length() - 1);
        if (hp.startsWith("[")) {
            int e = hp.indexOf(']');
            if (e < 0) return null;
            l.server = hp.substring(1, e);
            String pp = hp.substring(e + 1);
            l.port = pp.startsWith(":") ? Integer.parseInt(pp.substring(1)) : 443;
        } else {
            int c = hp.lastIndexOf(':');
            if (c < 0) { l.server = hp; l.port = 443; }
            else { l.server = hp.substring(0, c); l.port = Integer.parseInt(hp.substring(c + 1)); }
        }
        if (l.server.isEmpty() || l.id.isEmpty()) return null;
        Map<String, String> q = new HashMap<>();
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) q.put(kv.substring(0, e), dec(kv.substring(e + 1)));
        }
        l.net = get(q, "type", "tcp").toLowerCase();
        l.security = get(q, "security", l.proto.equals("trojan") ? "tls" : "none").toLowerCase();
        l.sni = get(q, "sni", get(q, "peer", ""));
        l.host = get(q, "host", "");
        l.path = get(q, "path", "/");
        l.alpn = get(q, "alpn", "");
        l.fp = get(q, "fp", "chrome");
        l.flow = get(q, "flow", "");
        l.serviceName = get(q, "serviceName", "");
        l.mode = get(q, "mode", "");
        l.pbk = get(q, "pbk", "");
        l.sid = get(q, "sid", "");
        l.spx = get(q, "spx", "");
        if (l.net.equals("splithttp")) l.net = "xhttp";
        return l;
    }

    private static Link vmess(String s) throws Exception {
        String b = s.substring(8).trim();
        int h = b.indexOf('#');
        if (h >= 0) b = b.substring(0, h);
        b = b.replace('-', '+').replace('_', '/');
        while (b.length() % 4 != 0) b += "=";
        String j = new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8);
        Link l = new Link();
        l.proto = "vmess";
        l.id = jget(j, "id");
        l.server = jget(j, "add");
        String p = jget(j, "port");
        l.port = p.isEmpty() ? 443 : Integer.parseInt(p);
        l.remark = jget(j, "ps");
        l.aid = jget(j, "aid").isEmpty() ? "0" : jget(j, "aid");
        l.net = jget(j, "net").isEmpty() ? "tcp" : jget(j, "net").toLowerCase();
        l.host = jget(j, "host");
        l.path = jget(j, "path").isEmpty() ? "/" : jget(j, "path");
        l.security = jget(j, "tls").equalsIgnoreCase("tls") ? "tls" : "none";
        l.sni = jget(j, "sni");
        l.alpn = jget(j, "alpn");
        l.fp = jget(j, "fp").isEmpty() ? "chrome" : jget(j, "fp");
        if (l.net.equals("grpc")) l.serviceName = l.path;
        if (l.net.equals("splithttp")) l.net = "xhttp";
        if (l.server.isEmpty() || l.id.isEmpty()) return null;
        return l;
    }

    private static String jget(String j, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*(?:\"([^\"]*)\"|([^,}\\s]+))").matcher(j);
        if (!m.find()) return "";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static String get(Map<String, String> q, String k, String d) {
        String v = q.get(k);
        return v == null || v.isEmpty() ? d : v;
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
