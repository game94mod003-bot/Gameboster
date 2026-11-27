package com.abolix.gamebooster;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** A proxy node normalized to a single sing-box outbound. */
public final class ProxyNode {
    private static final String[] IMPORTABLE_TYPES = {
            "vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "tuic", "anytls", "socks", "http"
    };

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

    public JSONObject getOutbound() throws JSONException {
        return new JSONObject(outbound.toString());
    }

    public JSONObject toStorageJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            out.put("name", name);
            out.put("protocol", protocol);
            out.put("server", server);
            out.put("port", port);
            out.put("outbound", outbound);
        } catch (JSONException ignored) { }
        return out;
    }

    public static ProxyNode fromStorageJson(JSONObject saved) throws Exception {
        JSONObject outbound = saved.getJSONObject("outbound");
        return fromOutbound(outbound, saved.optString("name", ""));
    }

    /** Converts a supported sing-box outbound into the app's single-node model. */
    public static ProxyNode fromOutbound(JSONObject source, String fallbackName) throws Exception {
        if (source == null) throw new IllegalArgumentException("خروجی پراکسی خالی است");
        JSONObject copy = new JSONObject(source.toString());
        String originalTag = copy.optString("tag", "");
        String type = required(copy.optString("type", ""), "نوع پروکسی").toLowerCase(Locale.ROOT);
        if (!isImportableType(type)) throw new IllegalArgumentException("پروتکل sing-box پشتیبانی‌نشده است: " + type);
        String host = normalizeHost(required(copy.optString("server", ""), "آدرس سرور"));
        int port = parsePort(copy.opt("server_port"));
        copy.put("type", type);
        copy.put("tag", "proxy");
        // Preserve the human-readable label; the engine tag is always normalized to "proxy".
        String displayName = firstNonEmpty(fallbackName, originalTag, host + ":" + port);
        return new ProxyNode(displayName, type, host, port, copy);
    }

    public static boolean isImportableType(String type) {
        if (type == null) return false;
        for (String supported : IMPORTABLE_TYPES) if (supported.equalsIgnoreCase(type)) return true;
        return false;
    }

    public static ProxyNode parse(String input) throws Exception {
        if (input == null) throw new IllegalArgumentException("لینک خالی است");
        String raw = input.trim().replace("\uFEFF", "");
        int hash = raw.indexOf('#');
        String fragment = hash >= 0 ? decode(raw.substring(hash + 1)) : "";
        int colon = raw.indexOf(':');
        String scheme = colon > 0 ? raw.substring(0, colon).toLowerCase(Locale.ROOT) : "";
        switch (scheme) {
            case "vmess":
                // Accept both the standard base64-JSON form and URI-style VMess links
                // emitted by several clients/panels.
                return raw.substring(raw.indexOf(':') + 1).contains("@")
                        ? parseUriNode(raw, "vmess", fragment) : parseVmess(raw, fragment);
            case "vless":
            case "trojan":
            case "hysteria":
            case "hysteria2":
            case "hy2":
            case "tuic":
            case "anytls":
            case "socks":
            case "socks4":
            case "socks4a":
            case "socks5":
            case "socks5h":
            case "http":
            case "https":
                return parseUriNode(raw, "hy2".equals(scheme) ? "hysteria2" : scheme, fragment);
            case "ss":
            case "shadowsocks": return parseShadowsocks(raw, fragment);
            case "ssr":
                throw new IllegalArgumentException("ShadowsocksR (ssr://) در هسته رسمی sing-box پشتیبانی نمی‌شود؛ لینک VLESS/VMess/Trojan یا SS معمولی بده");
            default:
                throw new IllegalArgumentException("نوع لینک شناخته نشد: " + (scheme.isEmpty() ? "نامشخص" : scheme));
        }
    }

    private static ProxyNode parseVmess(String raw, String fragment) throws Exception {
        String payload = raw.substring(raw.indexOf(':') + 1);
        while (payload.startsWith("/")) payload = payload.substring(1);
        int fragmentStart = payload.indexOf('#');
        if (fragmentStart >= 0) payload = payload.substring(0, fragmentStart);
        JSONObject vmess = new JSONObject(decodeBase64(payload));
        String host = normalizeHost(required(vmess.optString("add", vmess.optString("address", "")), "آدرس سرور VMess"));
        int port = parsePort(vmess.opt("port"));
        String uuid = required(vmess.optString("id", ""), "UUID");
        String name = firstNonEmpty(fragment, vmess.optString("ps", ""), host + ":" + port);

        JSONObject out = baseOutbound("vmess", host, port)
                .put("uuid", uuid)
                .put("security", nonEmpty(vmess.optString("scy", ""), "auto"))
                .put("alter_id", vmess.optInt("aid", 0));
        String network = nonEmpty(vmess.optString("net", ""), "tcp").toLowerCase(Locale.ROOT);
        String tlsValue = vmess.optString("tls", "");
        if ("tls".equalsIgnoreCase(tlsValue) || "true".equalsIgnoreCase(tlsValue) || "1".equals(tlsValue)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = firstNonEmpty(vmess.optString("sni", ""), vmess.optString("host", ""));
            if (!sni.isEmpty()) tls.put("server_name", sni);
            if (vmess.optBoolean("allowInsecure", false)) tls.put("insecure", true);
            String fingerprint = vmess.optString("fp", "");
            if (!fingerprint.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
            out.put("tls", tls);
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
            String early = vmess.optString("ed", "");
            if (!early.isEmpty()) transport.put("max_early_data", parseNonNegative(early, "VMess early data"));
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            out.put("transport", new JSONObject().put("type", "grpc")
                    .put("service_name", firstNonEmpty(vmess.optString("path", ""), vmess.optString("serviceName", ""))));
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "http");
            String path = vmess.optString("path", "");
            if (!path.isEmpty()) transport.put("path", path);
            String host = vmess.optString("host", "");
            if (!host.isEmpty()) transport.put("host", new JSONArray().put(host));
            out.put("transport", transport);
        } else if ("httpupgrade".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "httpupgrade");
            String path = vmess.optString("path", "");
            String host = vmess.optString("host", "");
            if (!path.isEmpty()) transport.put("path", path);
            if (!host.isEmpty()) transport.put("host", host);
            out.put("transport", transport);
        } else if (!"tcp".equals(network)) {
            throw new IllegalArgumentException("ترنسپورت VMess پشتیبانی نمی‌شود: " + network + " (TCP/WS/gRPC/HTTP/HTTPUpgrade مجاز است)");
        }
    }

    private static ProxyNode parseUriNode(String raw, String protocol, String fragment) throws Exception {
        URI uri = new URI(raw);
        String host = normalizeHost(required(uri.getHost(), "آدرس سرور"));
        int port = uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت نامعتبر است؛ باید بین 1 و 65535 باشد");
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String userInfo = decode(uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo());
        JSONObject out;

        if ("socks".equals(protocol) || protocol.startsWith("socks4") || "socks5".equals(protocol) || "socks5h".equals(protocol)) {
            out = baseOutbound("socks", host, port);
            String version = protocol.startsWith("socks4") ? ("socks4a".equals(protocol) ? "4a" : "4") : "5";
            out.put("version", version);
            addUsernamePassword(out, userInfo);
        } else if ("http".equals(protocol) || "https".equals(protocol)) {
            out = baseOutbound("http", host, port);
            addUsernamePassword(out, userInfo);
            if ("https".equals(protocol) || "tls".equalsIgnoreCase(query.get("security"))) addTls(out, query, true);
        } else if ("anytls".equals(protocol)) {
            out = baseOutbound("anytls", host, port);
            out.put("password", required(userInfo, "رمز عبور AnyTLS"));
            addTls(out, query, true);
        } else if ("hysteria".equals(protocol)) {
            out = baseOutbound("hysteria", host, port);
            String auth = firstNonEmpty(query.get("auth"), query.get("auth_str"), userInfo);
            if (!auth.isEmpty()) out.put("auth_str", auth);
            String up = firstNonEmpty(query.get("upmbps"), query.get("up_mbps"));
            String down = firstNonEmpty(query.get("downmbps"), query.get("down_mbps"));
            if (!up.isEmpty()) out.put("up_mbps", parsePositive(up, "سرعت آپلود Hysteria"));
            if (!down.isEmpty()) out.put("down_mbps", parsePositive(down, "سرعت دانلود Hysteria"));
            String obfs = query.get("obfs");
            if (obfs != null && !obfs.isEmpty()) out.put("obfs", obfs);
            addTls(out, query, true);
        } else {
            out = baseOutbound(protocol, host, port);
            if ("vless".equals(protocol)) {
                out.put("uuid", required(userInfo, "شناسه کاربر VLESS"));
                String flow = query.get("flow");
                if (flow != null && !flow.isEmpty()) out.put("flow", flow);
            } else if ("vmess".equals(protocol)) {
                out.put("uuid", required(userInfo, "UUID VMess"));
                out.put("security", nonEmpty(query.get("encryption"), "auto"));
                String alterId = firstNonEmpty(query.get("aid"), query.get("alterid"));
                if (!alterId.isEmpty()) out.put("alter_id", parseNonNegative(alterId, "VMess alterId"));
            } else if ("trojan".equals(protocol) || "hysteria2".equals(protocol)) {
                out.put("password", required(userInfo, "رمز عبور"));
            } else if ("tuic".equals(protocol)) {
                String[] parts = userInfo.split(":", 2);
                if (parts.length != 2) throw new IllegalArgumentException("لینک TUIC باید UUID و password داشته باشد");
                out.put("uuid", required(parts[0], "UUID"));
                out.put("password", required(parts[1], "رمز عبور"));
            }

            if ("hysteria2".equals(protocol) || "trojan".equals(protocol) || "tuic".equals(protocol)) {
                addTls(out, query, true);
            } else {
                String security = query.getOrDefault("security", "").toLowerCase(Locale.ROOT);
                String tlsFlag = query.get("tls");
                if ("tls".equals(security) || "reality".equals(security)
                        || "1".equals(tlsFlag) || "true".equalsIgnoreCase(tlsFlag)) addTls(out, query, false);
            }

            if ("trojan".equals(protocol) || "vless".equals(protocol) || "vmess".equals(protocol)) {
                String network = query.getOrDefault("type", query.getOrDefault("network", "tcp")).toLowerCase(Locale.ROOT);
                addUriTransport(out, network, query);
            }
            if ("tuic".equals(protocol)) {
                String cc = query.get("congestion_control");
                if (cc != null) out.put("congestion_control", cc);
                String udp = query.get("udp_relay_mode");
                if (udp != null) out.put("udp_relay_mode", udp);
            }
            if ("hysteria2".equals(protocol)) addHysteria2Obfs(out, query);
        }
        String name = firstNonEmpty(fragment, query.get("remarks"), query.get("name"), host + ":" + port);
        return new ProxyNode(name, protocol, host, port, out);
    }

    private static void addUsernamePassword(JSONObject out, String userInfo) throws Exception {
        if (userInfo == null || userInfo.isEmpty()) return;
        int split = userInfo.indexOf(':');
        if (split < 0) out.put("username", userInfo);
        else {
            out.put("username", userInfo.substring(0, split));
            out.put("password", userInfo.substring(split + 1));
        }
    }

    private static void addHysteria2Obfs(JSONObject out, Map<String, String> query) throws Exception {
        String obfs = query.get("obfs");
        if (obfs == null || obfs.isEmpty()) return;
        JSONObject obfsJson = new JSONObject().put("type", obfs);
        String password = query.get("obfs-password");
        if (password != null) obfsJson.put("password", password);
        out.put("obfs", obfsJson);
    }

    private static void addTls(JSONObject out, Map<String, String> query, boolean always) throws Exception {
        JSONObject tls = new JSONObject().put("enabled", true);
        String sni = firstNonEmpty(query.get("sni"), query.get("peer"), query.get("servername"));
        if (!sni.isEmpty()) tls.put("server_name", sni);
        String insecure = query.get("allowinsecure");
        if (insecure == null) insecure = query.get("insecure");
        if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) tls.put("insecure", true);
        String alpn = query.get("alpn");
        if (alpn != null && !alpn.isEmpty()) {
            JSONArray protocols = new JSONArray();
            for (String item : alpn.split(",")) if (!item.trim().isEmpty()) protocols.put(item.trim());
            if (protocols.length() > 0) tls.put("alpn", protocols);
        }
        String fp = query.get("fp");
        if (fp != null && !fp.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fp));
        String security = query.getOrDefault("security", "");
        if ("reality".equalsIgnoreCase(security) || query.containsKey("pbk")
                || query.containsKey("publickey") || query.containsKey("public_key")) {
            String publicKey = firstNonEmpty(query.get("pbk"), query.get("publickey"), query.get("public_key"));
            JSONObject reality = new JSONObject().put("enabled", true)
                    .put("public_key", required(publicKey, "کلید عمومی Reality (pbk/publicKey)"));
            String shortId = firstNonEmpty(query.get("sid"), query.get("shortid"), query.get("short_id"));
            if (!shortId.isEmpty()) reality.put("short_id", shortId);
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
            if (early != null && !early.isEmpty()) transport.put("max_early_data", parseNonNegative(early, "early data"));
            String earlyHeader = query.get("eh");
            if (earlyHeader != null && !earlyHeader.isEmpty()) transport.put("early_data_header_name", earlyHeader);
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            String service = firstNonEmpty(query.get("servicename"), query.get("service_name"), query.get("path"));
            out.put("transport", new JSONObject().put("type", "grpc").put("service_name", service));
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "http");
            String path = query.get("path");
            if (path != null) transport.put("path", path);
            String host = query.get("host");
            if (host != null && !host.isEmpty()) transport.put("host", new JSONArray().put(host));
            out.put("transport", transport);
        } else if ("httpupgrade".equals(network)) {
            JSONObject transport = new JSONObject().put("type", "httpupgrade");
            String path = query.get("path");
            if (path != null) transport.put("path", path);
            String host = query.get("host");
            if (host != null) transport.put("host", host);
            out.put("transport", transport);
        } else if (!"tcp".equals(network)) {
            throw new IllegalArgumentException("ترنسپورت V2Ray پشتیبانی نمی‌شود: " + network + "؛ TCP، WS، gRPC، HTTP و HTTPUpgrade پشتیبانی می‌شوند");
        }
    }

    private static ProxyNode parseShadowsocks(String raw, String fragment) throws Exception {
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd < 0) throw new IllegalArgumentException("لینک Shadowsocks نامعتبر است");
        String body = raw.substring(schemeEnd + 3);
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
        String host = normalizeHost(required(endpointUri.getHost(), "آدرس سرور"));
        int port = endpointUri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت نامعتبر است");
        JSONObject out = baseOutbound("shadowsocks", host, port).put("method", method).put("password", password);
        Map<String, String> query = parseQuery(endpointUri.getRawQuery());
        String plugin = query.get("plugin");
        if (plugin != null && !plugin.isEmpty()) {
            int separator = plugin.indexOf(';');
            String pluginName = separator >= 0 ? plugin.substring(0, separator) : plugin;
            String pluginOptions = separator >= 0 ? plugin.substring(separator + 1) : "";
            if (!("obfs-local".equals(pluginName) || "v2ray-plugin".equals(pluginName))) {
                throw new IllegalArgumentException("افزونه SS پشتیبانی نمی‌شود: " + pluginName + "؛ فقط obfs-local و v2ray-plugin مجازند");
            }
            out.put("plugin", pluginName);
            if (!pluginOptions.isEmpty()) out.put("plugin_opts", pluginOptions);
        }
        String name = firstNonEmpty(fragment, host + ":" + port);
        return new ProxyNode(name, "shadowsocks", host, port, out);
    }

    private static JSONObject baseOutbound(String protocol, String host, int port) throws JSONException {
        return new JSONObject().put("type", protocol).put("tag", "proxy").put("server", host).put("server_port", port);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return values;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
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
        if (compact.isEmpty()) throw new IllegalArgumentException("متن Base64 خالی است");
        String padded = compact;
        while (padded.length() % 4 != 0) padded += "=";
        int flags = (compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0)
                ? Base64.URL_SAFE | Base64.NO_WRAP : Base64.NO_WRAP;
        try {
            return new String(Base64.decode(padded, flags), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException first) {
            int fallbackFlags = flags == (Base64.URL_SAFE | Base64.NO_WRAP)
                    ? Base64.NO_WRAP : Base64.URL_SAFE | Base64.NO_WRAP;
            return new String(Base64.decode(padded, fallbackFlags), StandardCharsets.UTF_8);
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

    private static int parsePositive(String value, String label) {
        int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " نامعتبر است"); }
        if (parsed <= 0) throw new IllegalArgumentException(label + " باید بزرگ‌تر از صفر باشد");
        return parsed;
    }

    private static int parseNonNegative(String value, String label) {
        int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " نامعتبر است"); }
        if (parsed < 0) throw new IllegalArgumentException(label + " نمی‌تواند منفی باشد");
        return parsed;
    }

    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
