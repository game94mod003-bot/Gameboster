package com.abolix.vpn.core;

/** Builds an Xray-core JSON config. No Cloudflare endpoints are used anywhere. */
public final class XrayConfig {
    public static final class Opts {
        public int socksPort = 10808, httpPort = 10809;
        /** 0 = off, 1 = normal TLS-hello fragment, 2 = aggressive */
        public int fragment = 0;
        public boolean mux = false;
        public String fp = "";   // override fingerprint ("" = use link's)
        public String alpn = ""; // override alpn  ("" = use link's)
    }

    private XrayConfig() {}

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String alpnArr(String a) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (String s : a.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            if (!first) b.append(',');
            b.append(q(s));
            first = false;
        }
        return b.append(']').toString();
    }

    /** @param ip clean IP that replaces the server address ("" = keep original). */
    public static String build(Link l, String ip, Opts o) {
        boolean reality = l.security.equals("reality");
        String addr = (ip == null || ip.isEmpty() || reality) ? l.server : ip;
        StringBuilder out = new StringBuilder();

        // ---- main outbound
        out.append("{\"tag\":\"proxy\",\"protocol\":").append(q(l.proto)).append(",\"settings\":");
        if (l.proto.equals("trojan")) {
            out.append("{\"servers\":[{\"address\":").append(q(addr)).append(",\"port\":").append(l.port)
               .append(",\"password\":").append(q(l.id)).append("}]}");
        } else if (l.proto.equals("vmess")) {
            out.append("{\"vnext\":[{\"address\":").append(q(addr)).append(",\"port\":").append(l.port)
               .append(",\"users\":[{\"id\":").append(q(l.id)).append(",\"alterId\":")
               .append(l.aid.matches("\\d+") ? l.aid : "0").append(",\"security\":\"auto\"}]}]}");
        } else {
            out.append("{\"vnext\":[{\"address\":").append(q(addr)).append(",\"port\":").append(l.port)
               .append(",\"users\":[{\"id\":").append(q(l.id)).append(",\"encryption\":\"none\"");
            if (!l.flow.isEmpty()) out.append(",\"flow\":").append(q(l.flow));
            out.append("}]}]}");
        }

        out.append(",\"streamSettings\":{\"network\":").append(q(l.net));
        String fp = o.fp.isEmpty() ? l.fp : o.fp;
        String alpn = o.alpn.isEmpty() ? l.alpn : o.alpn;
        if (l.security.equals("tls")) {
            out.append(",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":").append(q(l.frontHost()))
               .append(",\"fingerprint\":").append(q(fp)).append(",\"allowInsecure\":false");
            if (!alpn.isEmpty()) out.append(",\"alpn\":").append(alpnArr(alpn));
            out.append("}");
        } else if (reality) {
            out.append(",\"security\":\"reality\",\"realitySettings\":{\"serverName\":").append(q(l.frontHost()))
               .append(",\"fingerprint\":").append(q(fp)).append(",\"publicKey\":").append(q(l.pbk))
               .append(",\"shortId\":").append(q(l.sid)).append(",\"spiderX\":").append(q(l.spx)).append("}");
        }
        switch (l.net) {
            case "ws":
                out.append(",\"wsSettings\":{\"path\":").append(q(l.path)).append(",\"headers\":{\"Host\":")
                   .append(q(l.host.isEmpty() ? l.frontHost() : l.host)).append("}}");
                break;
            case "grpc":
                out.append(",\"grpcSettings\":{\"serviceName\":").append(q(l.serviceName.isEmpty() ? l.path : l.serviceName)).append("}");
                break;
            case "httpupgrade":
                out.append(",\"httpupgradeSettings\":{\"path\":").append(q(l.path)).append(",\"host\":")
                   .append(q(l.host.isEmpty() ? l.frontHost() : l.host)).append("}");
                break;
            case "xhttp":
                out.append(",\"xhttpSettings\":{\"path\":").append(q(l.path)).append(",\"host\":")
                   .append(q(l.host.isEmpty() ? l.frontHost() : l.host)).append(",\"mode\":")
                   .append(q(l.mode.isEmpty() ? "auto" : l.mode)).append("}");
                break;
            default:
                break;
        }
        if (o.fragment > 0) out.append(",\"sockopt\":{\"dialerProxy\":\"fragment\"}");
        out.append("}");
        if (o.mux && l.flow.isEmpty() && !l.net.equals("grpc") && !l.net.equals("xhttp"))
            out.append(",\"mux\":{\"enabled\":true,\"concurrency\":8}");
        out.append("}");
        String proxyOut = out.toString();

        String frag = o.fragment == 2 ? "{\"packets\":\"tlshello\",\"length\":\"1-5\",\"interval\":\"1-3\"}"
                                       : "{\"packets\":\"tlshello\",\"length\":\"100-200\",\"interval\":\"10-20\"}";

        StringBuilder c = new StringBuilder();
        c.append("{\"log\":{\"loglevel\":\"warning\"},");
        c.append("\"dns\":{\"servers\":[\"8.8.8.8\",\"9.9.9.9\",\"94.140.14.14\"],\"queryStrategy\":\"UseIPv4\"},");
        c.append("\"inbounds\":[");
        c.append("{\"tag\":\"socks-in\",\"listen\":\"127.0.0.1\",\"port\":").append(o.socksPort)
         .append(",\"protocol\":\"socks\",\"settings\":{\"auth\":\"noauth\",\"udp\":true,\"ip\":\"127.0.0.1\"},")
         .append("\"sniffing\":{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"],\"routeOnly\":true}},");
        c.append("{\"tag\":\"http-in\",\"listen\":\"127.0.0.1\",\"port\":").append(o.httpPort)
         .append(",\"protocol\":\"http\",\"settings\":{}}],");
        c.append("\"outbounds\":[").append(proxyOut);
        c.append(",{\"tag\":\"direct\",\"protocol\":\"freedom\"},{\"tag\":\"block\",\"protocol\":\"blackhole\"}");
        if (o.fragment > 0)
            c.append(",{\"tag\":\"fragment\",\"protocol\":\"freedom\",\"settings\":{\"fragment\":").append(frag).append("}}");
        c.append("],");
        c.append("\"routing\":{\"domainStrategy\":\"AsIs\",\"rules\":[{\"type\":\"field\",\"ip\":[")
         .append("\"10.0.0.0/8\",\"172.16.0.0/12\",\"192.168.0.0/16\",\"127.0.0.0/8\",\"169.254.0.0/16\"],\"outboundTag\":\"direct\"},")
         .append("{\"type\":\"field\",\"network\":\"tcp,udp\",\"outboundTag\":\"proxy\"}]}}");
        return c.toString();
    }
}
