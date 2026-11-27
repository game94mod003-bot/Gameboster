package com.abolix.gamebooster;

import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A parsed proxy share-link and its sing-box outbound definition. */
public final class ProxyNode {
    private final String id;
    private final String name;
    private final String protocol;
    private final String server;
    private final int port;
    private final JSONObject outbound;

    private ProxyNode(String name, String protocol, String server, int port, JSONObject outbound) {
        this.id = UUID.nameUUIDFromBytes((protocol + "|" + server + "|" + port + "|" + outbound).getBytes(StandardCharsets.UTF_8)).toString();
        this.name = name == null || name.trim().isEmpty() ? server + ":" + port : name.trim();
        this.protocol = protocol;
        this.server = server;
        this.port = port;
        this.outbound = outbound;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProtocol() { return protocol; }
    public String getServer() { return server; }
    public int getPort() { return port; }
    public JSONObject getOutbound() { return new JSONObject(outbound.toString()); }

    public JSONObject toStorageJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            out.put("name", name);
            out.put("protocol", protocol);
            out.put("server", server);
            out.put("port", port);
            out.put("outbound", outbound);
        } catch (Exception ignored) { }
        return out;
    }

    public static ProxyNode fromStorageJson(JSONObject saved) throws Exception {
        JSONObject outbound = saved.getJSONObject("outbound");
        return new ProxyNode(saved.optString("name"), saved.optString("protocol"),
                saved.getString("server"), saved.getInt("port"), outbound);
    }

    public static ProxyNode parse(String input) throws Exception {
        if (input == null) throw new IllegalArgumentException("لینک خالی است");
        String raw = input.trim().replace("\uFEFF", "");
        int hash = raw.indexOf('#');
        String fragment = hash >= 0 ? decode(raw.substring(hash + 1)) : "";
        String scheme = raw.contains(":") ? raw.substring(0, raw.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        switch (scheme) {
            case "vmess": return parseVmess(raw, fragment);
            case "vless":
            case "trojan":
            case "hysteria2":
            case "hy2":
            case "tuic":
                return parseUriNode(raw, scheme.equals("hy2") ? "hysteria2" : scheme, fragment);
            case "ss": return parseShadowsocks(raw, fragment);
            default: throw new IllegalArgumentException("پروتکل پشتیبانی‌نشده: " + (scheme.isEmpty() ? "نامشخص" : scheme));
        }
    }

    private static ProxyNode parseVmess(String raw, String fragment) throws Exception {
        String payload = raw.substring(raw.indexOf(':') + 1);
        while (payload.startsWith("/")) payload = payload.substring(1);
        int fragmentStart = payload.indexOf('#');
        if (fragmentStart >= 0) payload = payload.substring(0, fragmentStart);
        JSONObject vmess = new JSONObject(decodeBase64(payload));
        String host = normalizeHost(required(vmess.optString("add"), "آدرس سرور VMess"));
        int port = parsePort(vmess.opt("port"));
        String uuid = required(vmess.optString("id"), "UUID");
        String name = fragment.isEmpty() ? vmess.optString("ps", host + ":" + port) : fragment;

        JSONObject out = baseOutbound("vmess", host, port);
        out.put("uuid", uuid);
        out.put("security", nonEmpty(vmess.optString("scy"), "auto"));
        out.put("alter_id", vmess.optInt("aid", 0));
        String network = nonEmpty(vmess.optString("net"), "tcp").toLowerCase(Locale.ROOT);
        String tls = vmess.optString("tls", "");
        if ("tls".equalsIgnoreCase(tls) || "true".equalsIgnoreCase(tls) || "1".equals(tls)) {
            JSONObject tlsObject = new JSONObject().put("enabled", true);
            String sni = firstNonEmpty(vmess.optString("sni"), vmess.optString("host"));
            if (!sni.isEmpty()) tlsObject.put("server_name", sni);
            out.put("tls", tlsObject);
        }
        addVmessTransport(out, network, vmess);
        return new ProxyNode(name, "vmess", host, port, out);
    }

    private static void addVmessTransport(JSONObject out, String network, JSONObject vmess) throws Exception {
        if ("ws".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "ws");
            String path = vmess.optString("path", "");
            if (!path.isEmpty()) transport.put("path", path);
            String host = vmess.optString("host", "");
            if (!host.isEmpty()) transport.put("headers", new JSONObject().put("Host", host));
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            out.put("transport", new JSONObject().put("type", "grpc")
                    .put("service_name", firstNonEmpty(vmess.optString("path"), vmess.optString("serviceName"))));
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "http");
            String path = vmess.optString("path", "");
            if (!path.isEmpty()) transport.put("path", path);
            String host = vmess.optString("host", "");
            if (!host.isEmpty()) transport.put("host", new org.json.JSONArray().put(host));
            out.put("transport", transport);
        } else if ("httpupgrade".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "httpupgrade");
            String path = vmess.optString("path", "");
            String host = vmess.optString("host", "");
            if (!path.isEmpty()) transport.put("path", path);
            if (!host.isEmpty()) transport.put("host", host);
            out.put("transport", transport);
        } else if (!"tcp".equals(network)) {
            throw new IllegalArgumentException("ترنسپورت VMess در این نسخه پشتیبانی نمی‌شود: " + network);
        }
    }

    private static ProxyNode parseUriNode(String raw, String protocol, String fragment) throws Exception {
        URI uri = new URI(raw);
        String host = normalizeHost(required(uri.getHost(), "آدرس سرور"));
        int port = uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت نامعتبر است");
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String userInfo = decode(uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo());
        JSONObject out = baseOutbound(protocol, host, port);

        if ("vless".equals(protocol) || "vmess".equals(protocol)) {
            out.put("uuid", required(userInfo, "شناسه کاربر"));
            if ("vless".equals(protocol)) {
                String flow = query.get("flow");
                if (flow != null && !flow.isEmpty()) out.put("flow", flow);
            }
        } else if ("trojan".equals(protocol) || "hysteria2".equals(protocol)) {
            out.put("password", required(userInfo, "رمز عبور"));
        } else if ("tuic".equals(protocol)) {
            String[] parts = userInfo.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("لینک TUIC باید UUID و password داشته باشد");
            out.put("uuid", required(parts[0], "UUID"));
            out.put("password", required(parts[1], "رمز عبور"));
        }

        if ("ss".equals(protocol)) {
            throw new IllegalArgumentException("فرمت Shadowsocks باید با ss:// وارد شود");
        }

        if ("hysteria2".equals(protocol) || "trojan".equals(protocol)) {
            addTls(out, query, true);
            if ("trojan".equals(protocol)) {
                String network = query.getOrDefault("type", query.getOrDefault("network", "tcp")).toLowerCase(Locale.ROOT);
                addUriTransport(out, network, query);
            }
            String obfs = query.get("obfs");
            if (obfs != null && !obfs.isEmpty()) {
                JSONObject obfsJson = new JSONObject().put("type", obfs);
                if (query.containsKey("obfs-password")) obfsJson.put("password", query.get("obfs-password"));
                out.put("obfs", obfsJson);
            }
        } else if ("tuic".equals(protocol)) {
            addTls(out, query, true);
            String cc = query.get("congestion_control");
            if (cc != null) out.put("congestion_control", cc);
            String udp = query.get("udp_relay_mode");
            if (udp != null) out.put("udp_relay_mode", udp);
        } else {
            String security = query.getOrDefault("security", "").toLowerCase(Locale.ROOT);
            if ("tls".equals(security) || "reality".equals(security) || "1".equals(query.get("tls"))) {
                addTls(out, query, false);
            }
            String network = query.getOrDefault("type", query.getOrDefault("network", "tcp")).toLowerCase(Locale.ROOT);
            addUriTransport(out, network, query);
        }
        String name = fragment.isEmpty() ? host + ":" + port : fragment;
        return new ProxyNode(name, protocol, host, port, out);
    }

    private static void addTls(JSONObject out, Map<String, String> query, boolean always) throws Exception {
        JSONObject tls = new JSONObject().put("enabled", true);
        String sni = firstNonEmpty(query.get("sni"), query.get("peer"), query.get("serverName"));
        if (!sni.isEmpty()) tls.put("server_name", sni);
        String insecure = query.get("allowInsecure");
        if (insecure == null) insecure = query.get("insecure");
        if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) tls.put("insecure", true);
        String alpn = query.get("alpn");
        if (alpn != null && !alpn.isEmpty()) {
            org.json.JSONArray protocols = new org.json.JSONArray();
            for (String item : alpn.split(",")) if (!item.trim().isEmpty()) protocols.put(item.trim());
            if (protocols.length() > 0) tls.put("alpn", protocols);
        }
        String fp = query.get("fp");
        if (fp != null && !fp.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fp));
        String security = query.getOrDefault("security", "");
        if ("reality".equalsIgnoreCase(security) || query.containsKey("pbk")) {
            String publicKey = required(query.get("pbk"), "Reality public key (pbk)");
            JSONObject reality = new JSONObject().put("enabled", true).put("public_key", publicKey);
            String shortId = query.get("sid");
            if (shortId != null) reality.put("short_id", shortId);
            tls.put("reality", reality);
        }
        out.put("tls", tls);
    }

    private static void addUriTransport(JSONObject out, String network, Map<String, String> query) throws Exception {
        if ("ws".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "ws");
            String path = query.get("path");
            if (path != null && !path.isEmpty()) transport.put("path", path);
            String host = firstNonEmpty(query.get("host"), query.get("authority"));
            if (!host.isEmpty()) transport.put("headers", new JSONObject().put("Host", host));
            String early = query.get("ed");
            if (early != null) {
                try { transport.put("max_early_data", Integer.parseInt(early)); } catch (NumberFormatException ignored) { }
            }
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            String service = firstNonEmpty(query.get("serviceName"), query.get("service_name"), query.get("path"));
            out.put("transport", new JSONObject().put("type", "grpc").put("service_name", service));
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "http");
            String path = query.get("path");
            if (path != null) transport.put("path", path);
            String host = query.get("host");
            if (host != null && !host.isEmpty()) transport.put("host", new org.json.JSONArray().put(host));
            out.put("transport", transport);
        } else if ("httpupgrade".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "httpupgrade");
            String path = query.get("path");
            if (path != null) transport.put("path", path);
            String host = query.get("host");
            if (host != null) transport.put("host", host);
            out.put("transport", transport);
        } else if (!"tcp".equals(network)) {
            throw new IllegalArgumentException("ترنسپورت V2Ray در این نسخه پشتیبانی نمی‌شود: " + network);
        }
    }

    private static ProxyNode parseShadowsocks(String raw, String fragment) throws Exception {
        String body = raw.substring("ss://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        String methodPassword;
        String endpoint;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            methodPassword = body.substring(0, at);
            endpoint = body.substring(at + 1);
            if (!methodPassword.contains(":")) methodPassword = decodeBase64(methodPassword);
        } else {
            String decoded = decodeBase64(body);
            int decodedAt = decoded.lastIndexOf('@');
            if (decodedAt < 0) throw new IllegalArgumentException("فرمت Shadowsocks نامعتبر است");
            methodPassword = decoded.substring(0, decodedAt);
            endpoint = decoded.substring(decodedAt + 1);
        }
        int split = methodPassword.indexOf(':');
        if (split <= 0) throw new IllegalArgumentException("روش رمزنگاری یا password در Shadowsocks پیدا نشد");
        String method = decode(methodPassword.substring(0, split));
        String password = decode(methodPassword.substring(split + 1));
        URI endpointUri = new URI("ss://placeholder@" + endpoint);
        if (endpointUri.getRawQuery() != null && endpointUri.getRawQuery().toLowerCase(Locale.ROOT).contains("plugin=")) {
            throw new IllegalArgumentException("افزونه‌های SIP003 در این نسخه‌ی Abolix پشتیبانی نمی‌شوند");
        }
        String host = normalizeHost(required(endpointUri.getHost(), "آدرس سرور"));
        int port = endpointUri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت نامعتبر است");
        JSONObject out = baseOutbound("shadowsocks", host, port);
        out.put("method", method).put("password", password);
        String name = fragment.isEmpty() ? host + ":" + port : fragment;
        return new ProxyNode(name, "shadowsocks", host, port, out);
    }

    private static JSONObject baseOutbound(String protocol, String host, int port) throws Exception {
        return new JSONObject().put("type", protocol).put("tag", "proxy")
                .put("server", host).put("server_port", port);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return values;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String decode(String value) {
        if (value == null) return "";
        try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static String decodeBase64(String value) {
        String compact = value == null ? "" : value.trim().replaceAll("\\s", "");
        try {
            byte[] decoded = Base64.decode(compact, Base64.URL_SAFE | Base64.NO_WRAP);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException first) {
            byte[] decoded = Base64.decode(compact, Base64.DEFAULT);
            return new String(decoded, StandardCharsets.UTF_8);
        }
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.length() > 2 && value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " خالی است");
        return value.trim();
    }

    private static int parsePort(Object value) {
        int port;
        try { port = Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { throw new IllegalArgumentException("پورت نامعتبر است"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت باید بین 1 تا 65535 باشد");
        return port;
    }

    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
