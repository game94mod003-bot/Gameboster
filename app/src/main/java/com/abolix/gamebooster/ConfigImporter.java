package com.abolix.gamebooster;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports common proxy inputs into one-node profiles understood by sing-box:
 * share links, base64 subscriptions, Clash YAML, sing-box JSON and Xray JSON.
 * Unsafe YAML object constructors are intentionally disabled.
 */
public final class ConfigImporter {
    private static final int MAX_INPUT_CHARS = 3 * 1024 * 1024;
    private ConfigImporter() { }

    public static List<ProxyNode> parse(String input) throws Exception {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("متن یا فایل کانفیگ خالی است");
        if (input.length() > MAX_INPUT_CHARS) throw new IllegalArgumentException("حجم کانفیگ بیشتر از 3 مگابایت است");
        String candidate = input.trim().replace("\uFEFF", "");
        String decoded = decodeSubscriptionIfNeeded(candidate);
        if (!decoded.equals(candidate)) candidate = decoded;

        String trimmed = candidate.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            List<ProxyNode> jsonNodes = parseJson(trimmed);
            if (!jsonNodes.isEmpty()) return jsonNodes;
            throw new IllegalArgumentException("JSON خوانده شد اما هیچ سرور قابل‌استفاده‌ای داخل آن نبود. فایل باید outbound از نوع VLESS/VMess/Trojan/SS/Hysteria2/TUIC یا موارد پشتیبانی‌شده داشته باشد.");
        }
        if (looksLikeYaml(trimmed)) {
            List<ProxyNode> yamlNodes = parseClashYaml(trimmed);
            if (!yamlNodes.isEmpty()) return yamlNodes;
        }
        return parseShareLinks(trimmed);
    }

    private static String decodeSubscriptionIfNeeded(String input) {
        String value = input.trim();
        boolean explicitBase64 = value.regionMatches(true, 0, "base64://", 0, 9)
                || value.regionMatches(true, 0, "base64:", 0, 7);
        if (!explicitBase64 && (value.contains("://") || value.startsWith("{") || value.startsWith("[") || looksLikeYaml(value))) {
            return value;
        }
        String compact;
        if (explicitBase64) {
            int prefixLength = value.regionMatches(true, 0, "base64://", 0, 9) ? 9 : 7;
            compact = value.substring(prefixLength).replaceAll("\\s", "");
        } else {
            compact = value.replaceAll("\\s", "");
        }
        if (compact.length() < 12 || !compact.matches("[A-Za-z0-9_+/=-]+")) return value;
        String padded = compact;
        while (padded.length() % 4 != 0) padded += "=";
        try {
            int flags = (compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0)
                    ? Base64.URL_SAFE | Base64.NO_WRAP : Base64.NO_WRAP;
            byte[] bytes;
            try {
                bytes = Base64.decode(padded, flags);
            } catch (IllegalArgumentException ex) {
                int fallbackFlags = flags == (Base64.URL_SAFE | Base64.NO_WRAP)
                        ? Base64.NO_WRAP : Base64.URL_SAFE | Base64.NO_WRAP;
                bytes = Base64.decode(padded, fallbackFlags);
            }
            String result = new String(bytes, StandardCharsets.UTF_8).trim().replace("\uFEFF", "");
            if (result.isEmpty()) return value;
            int printable = 0;
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t' || (c >= 32 && c != 127)) printable++;
            }
            if (printable < result.length() * 0.92) return value;
            if (result.contains("://") || result.startsWith("{") || result.startsWith("[") || looksLikeYaml(result)) return result;
        } catch (Exception ignored) { }
        return value;
    }

    private static boolean looksLikeYaml(String value) {
        if (value == null) return false;
        String candidate = value.replace("\uFEFF", "").trim();
        if (candidate.startsWith("---")) return true;
        // Accept valid Clash/sing-box YAML even when comments or blank lines precede the root keys.
        return Pattern.compile("(?im)^\\s*(?:proxies|proxy-providers|outbounds|inbounds|dns|route|mixed-port|port)\\s*:")
                .matcher(candidate).find();
    }

    private static List<ProxyNode> parseJson(String text) throws Exception {
        ArrayList<ProxyNode> nodes = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        String cleanJson = stripJsonComments(text);
        if (cleanJson.startsWith("[")) {
            JSONArray array = new JSONArray(cleanJson);
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof JSONObject) collectJsonObject((JSONObject) value, nodes, errors);
            }
        } else {
            JSONObject root = new JSONObject(cleanJson);
            collectJsonObject(root, nodes, errors);
        }
        if (nodes.isEmpty() && !errors.isEmpty()) {
            throw new IllegalArgumentException("JSON دریافت شد، اما هیچ outbound قابل‌استفاده‌ای پیدا نشد: " + joinErrors(errors));
        }
        return distinct(nodes);
    }

    private static void collectJsonObject(JSONObject object, List<ProxyNode> nodes, List<String> errors) {
        if (object.has("type") && object.has("server")) {
            try { nodes.add(ProxyNode.fromOutbound(object, object.optString("tag", ""))); }
            catch (Exception e) { addError(errors, e.getMessage()); }
            return;
        }
        JSONArray outbounds = object.optJSONArray("outbounds");
        if (outbounds == null) outbounds = object.optJSONArray("proxies");
        if (outbounds == null) outbounds = object.optJSONArray("nodes");
        if (outbounds == null) {
            // Xray and sing-box single-outbound snippets may wrap the item under "outbound".
            JSONObject outbound = object.optJSONObject("outbound");
            if (outbound != null) collectXrayOrSingBoxOutbound(outbound, nodes, errors);
            else if (object.has("protocol") || (object.has("type") && object.has("server"))) {
                collectXrayOrSingBoxOutbound(object, nodes, errors);
            } else addError(errors, "فیلد outbounds/proxies یا یک outbound تکی در JSON پیدا نشد");
            return;
        }
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.optJSONObject(i);
            if (outbound != null) collectXrayOrSingBoxOutbound(outbound, nodes, errors);
        }
        if (outbounds.length() == 0) addError(errors, "آرایه‌ی outbounds در JSON خالی است");
    }

    private static void collectXrayOrSingBoxOutbound(JSONObject outbound, List<ProxyNode> nodes, List<String> errors) {
        if (outbound.has("type")) {
            String type = outbound.optString("type", "");
            if (!ProxyNode.isImportableType(type)) return; // direct, block, selector and urltest are not servers.
            try { nodes.add(ProxyNode.fromOutbound(outbound, outbound.optString("tag", ""))); }
            catch (Exception e) { addError(errors, e.getMessage()); }
            return;
        }
        if (outbound.has("protocol")) {
            try { nodes.add(convertXrayOutbound(outbound)); }
            catch (Exception e) { addError(errors, e.getMessage()); }
        }
    }

    private static ProxyNode convertXrayOutbound(JSONObject source) throws Exception {
        String protocol = source.optString("protocol", "").toLowerCase(Locale.ROOT);
        JSONObject settings = source.optJSONObject("settings");
        if (settings == null) throw new IllegalArgumentException("settings در outbound مربوط به Xray خالی است");
        String label = first(source.optString("tag", ""), source.optString("remarks", ""));
        JSONObject out;

        if ("vless".equals(protocol) || "vmess".equals(protocol)) {
            JSONArray vnext = settings.optJSONArray("vnext");
            JSONObject serverEntry = vnext == null ? null : vnext.optJSONObject(0);
            JSONArray users = serverEntry == null ? null : serverEntry.optJSONArray("users");
            JSONObject user = users == null ? null : users.optJSONObject(0);
            if (serverEntry == null || user == null) throw new IllegalArgumentException(protocol + ": settings.vnext[0].users[0] پیدا نشد");
            String host = required(serverEntry.optString("address", ""), "آدرس Xray");
            int port = serverEntry.optInt("port", 0);
            out = base(protocol, host, port);
            out.put("uuid", required(user.optString("id", ""), "UUID Xray"));
            if ("vless".equals(protocol)) {
                String flow = user.optString("flow", "");
                if (!flow.isEmpty()) out.put("flow", flow);
            } else {
                out.put("security", first(user.optString("security", ""), "auto"));
                out.put("alter_id", user.optInt("alterId", 0));
            }
            applyXrayStream(out, source.optJSONObject("streamSettings"));
            return ProxyNode.fromOutbound(out, label);
        }

        JSONArray servers = settings.optJSONArray("servers");
        JSONObject server = servers == null ? null : servers.optJSONObject(0);
        if (server == null) throw new IllegalArgumentException(protocol + ": settings.servers[0] پیدا نشد");
        String host = required(server.optString("address", ""), "آدرس سرور Xray");
        int port = server.optInt("port", 0);
        if ("trojan".equals(protocol)) {
            out = base("trojan", host, port).put("password", required(server.optString("password", ""), "رمز Trojan"));
            applyXrayStream(out, source.optJSONObject("streamSettings"));
        } else if ("shadowsocks".equals(protocol)) {
            out = base("shadowsocks", host, port)
                    .put("method", required(server.optString("method", ""), "روش رمزنگاری Shadowsocks"))
                    .put("password", required(server.optString("password", ""), "رمز Shadowsocks"));
        } else if ("socks".equals(protocol)) {
            out = base("socks", host, port).put("version", first(server.optString("version", ""), "5"));
            JSONArray accounts = server.optJSONArray("users");
            JSONObject account = accounts == null ? null : accounts.optJSONObject(0);
            if (account != null) {
                if (account.has("user")) out.put("username", account.optString("user"));
                if (account.has("pass")) out.put("password", account.optString("pass"));
            }
        } else if ("http".equals(protocol)) {
            out = base("http", host, port);
            JSONArray accounts = server.optJSONArray("users");
            JSONObject account = accounts == null ? null : accounts.optJSONObject(0);
            if (account != null) {
                out.put("username", account.optString("user", ""));
                out.put("password", account.optString("pass", ""));
            }
            applyXrayStream(out, source.optJSONObject("streamSettings"));
        } else {
            throw new IllegalArgumentException("پروتکل Xray پشتیبانی‌نشده است: " + protocol);
        }
        return ProxyNode.fromOutbound(out, label);
    }

    private static void applyXrayStream(JSONObject out, JSONObject stream) throws Exception {
        if (stream == null) return;
        String security = stream.optString("security", "none");
        JSONObject tlsSettings = stream.optJSONObject("tlsSettings");
        JSONObject realitySettings = stream.optJSONObject("realitySettings");
        if ("tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = first(
                    tlsSettings == null ? "" : tlsSettings.optString("serverName", ""),
                    realitySettings == null ? "" : realitySettings.optString("serverName", ""));
            if (!sni.isEmpty()) tls.put("server_name", sni);
            if (tlsSettings != null) {
                if (tlsSettings.optBoolean("allowInsecure", false)) tls.put("insecure", true);
                JSONArray alpn = tlsSettings.optJSONArray("alpn");
                if (alpn != null && alpn.length() > 0) tls.put("alpn", alpn);
            }
            String fingerprint = first(
                    tlsSettings == null ? "" : tlsSettings.optString("fingerprint", ""),
                    realitySettings == null ? "" : realitySettings.optString("fingerprint", ""));
            if (!fingerprint.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
            if ("reality".equalsIgnoreCase(security)) {
                if (realitySettings == null) throw new IllegalArgumentException("security=reality تنظیمات realitySettings ندارد");
                String publicKey = first(realitySettings.optString("publicKey", ""),
                        realitySettings.optString("public_key", ""));
                JSONObject reality = new JSONObject().put("enabled", true)
                        .put("public_key", required(publicKey, "Reality publicKey"));
                String shortId = first(realitySettings.optString("shortId", ""),
                        realitySettings.optString("short_id", ""));
                if (!shortId.isEmpty()) reality.put("short_id", shortId);
                tls.put("reality", reality);
            }
            out.put("tls", tls);
        }

        String network = stream.optString("network", "tcp").toLowerCase(Locale.ROOT);
        if ("ws".equals(network)) {
            JSONObject ws = stream.optJSONObject("wsSettings");
            JSONObject transport = new JSONObject().put("type", "ws");
            if (ws != null) {
                String path = ws.optString("path", "");
                if (!path.isEmpty()) transport.put("path", path);
                JSONObject headers = ws.optJSONObject("headers");
                if (headers != null && headers.length() > 0) transport.put("headers", headers);
            }
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = stream.optJSONObject("grpcSettings");
            out.put("transport", new JSONObject().put("type", "grpc")
                    .put("service_name", grpc == null ? "" : grpc.optString("serviceName", "")));
        } else if ("http".equals(network) || "h2".equals(network)) {
            JSONObject http = stream.optJSONObject("httpSettings");
            JSONObject transport = new JSONObject().put("type", "http");
            if (http != null) {
                String path = http.optString("path", "");
                if (!path.isEmpty()) transport.put("path", path);
                JSONArray host = http.optJSONArray("host");
                if (host != null && host.length() > 0) transport.put("host", host);
            }
            out.put("transport", transport);
        } else if ("tcp".equals(network)) {
            JSONObject tcp = stream.optJSONObject("tcpSettings");
            JSONObject header = tcp == null ? null : tcp.optJSONObject("header");
            if (header != null && "http".equalsIgnoreCase(header.optString("type", ""))) {
                JSONObject request = header.optJSONObject("request");
                JSONObject transport = new JSONObject().put("type", "http");
                if (request != null) {
                    JSONArray paths = request.optJSONArray("path");
                    if (paths != null && paths.length() > 0) transport.put("path", paths.optString(0));
                    JSONObject headers = request.optJSONObject("headers");
                    JSONArray hosts = headers == null ? null : headers.optJSONArray("Host");
                    if (hosts != null && hosts.length() > 0) transport.put("host", hosts);
                }
                out.put("transport", transport);
            }
        } else if ("tcp".equals(network) || "raw".equals(network)) {
            // Xray's raw is the current name for the plain TCP transport.
        } else if ("kcp".equals(network)) {
            throw new IllegalArgumentException("mKCP در هسته رسمی sing-box پشتیبانی نمی‌شود");
        } else {
            throw new IllegalArgumentException("ترنسپورت Xray به نام " + network + " در این نسخه پشتیبانی نمی‌شود");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ProxyNode> parseClashYaml(String text) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(25);
        options.setCodePointLimit(MAX_INPUT_CHARS);
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded = yaml.load(text);
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("فایل YAML باید یک کانفیگ Clash با کلید proxies باشد");
        Map<?, ?> root = (Map<?, ?>) loaded;
        Object rawProxies = root.get("proxies");
        Object rawOutbounds = root.get("outbounds");
        if (!(rawProxies instanceof List) && !(rawOutbounds instanceof List)) {
            throw new IllegalArgumentException("کلید proxies (Clash) یا outbounds (sing-box) در فایل YAML پیدا نشد");
        }

        ArrayList<ProxyNode> nodes = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        boolean hasClashProxies = rawProxies instanceof List && !((List<?>) rawProxies).isEmpty();
        if (hasClashProxies) {
            for (Object value : (List<?>) rawProxies) {
                if (!(value instanceof Map)) continue;
                try {
                    Map<String, Object> proxy = normalizeMap((Map<?, ?>) value);
                    JSONObject outbound = convertClashProxy(proxy);
                    nodes.add(ProxyNode.fromOutbound(outbound, str(proxy.get("name"))));
                } catch (Exception e) { addError(errors, e.getMessage()); }
            }
        } else if (rawOutbounds instanceof List) {
            for (Object value : (List<?>) rawOutbounds) {
                if (!(value instanceof Map)) continue;
                try {
                    Map<String, Object> outboundMap = normalizeMap((Map<?, ?>) value);
                    if (outboundMap.containsKey("type")) {
                        JSONObject outbound = new JSONObject(outboundMap);
                        String type = outbound.optString("type", "");
                        if (ProxyNode.isImportableType(type)) nodes.add(ProxyNode.fromOutbound(outbound, outbound.optString("tag", "")));
                    } else if (outboundMap.containsKey("protocol")) {
                        nodes.add(convertXrayOutbound(new JSONObject(outboundMap)));
                    }
                } catch (Exception e) { addError(errors, e.getMessage()); }
            }
        }
        if (nodes.isEmpty() && !errors.isEmpty()) throw new IllegalArgumentException("فایل Clash خوانده شد اما هیچ سرور قابل‌استفاده‌ای نداشت: " + joinErrors(errors));
        if (nodes.isEmpty()) throw new IllegalArgumentException("در لیست proxies سرور قابل‌استفاده‌ای پیدا نشد");
        return distinct(nodes);
    }

    private static JSONObject convertClashProxy(Map<String, Object> proxy) throws Exception {
        String type = required(str(proxy.get("type")), "نوع پروکسی Clash").toLowerCase(Locale.ROOT);
        String host = required(str(proxy.get("server")), "آدرس سرور Clash");
        int port = toInt(proxy.get("port"), "پورت Clash");
        JSONObject out;
        if ("vless".equals(type)) {
            out = base("vless", host, port).put("uuid", required(str(proxy.get("uuid")), "UUID VLESS"));
            String flow = str(proxy.get("flow"));
            if (!flow.isEmpty()) out.put("flow", flow);
            applyClashTls(out, proxy, true);
            applyClashTransport(out, proxy);
        } else if ("vmess".equals(type)) {
            out = base("vmess", host, port)
                    .put("uuid", required(str(proxy.get("uuid")), "UUID VMess"))
                    .put("security", first(str(proxy.get("cipher")), "auto"))
                    .put("alter_id", toIntDefault(proxy.get("alterId"), 0));
            applyClashTls(out, proxy, false);
            applyClashTransport(out, proxy);
        } else if ("trojan".equals(type)) {
            out = base("trojan", host, port).put("password", required(str(proxy.get("password")), "رمز Trojan"));
            applyClashTls(out, proxy, true);
            applyClashTransport(out, proxy);
        } else if ("ss".equals(type) || "shadowsocks".equals(type)) {
            out = base("shadowsocks", host, port)
                    .put("method", required(str(proxy.get("cipher")), "روش رمزنگاری Shadowsocks"))
                    .put("password", required(str(proxy.get("password")), "رمز Shadowsocks"));
            String plugin = str(proxy.get("plugin"));
            if (!plugin.isEmpty()) {
                if (!("obfs".equals(plugin) || "v2ray-plugin".equals(plugin) || "obfs-local".equals(plugin))) {
                    throw new IllegalArgumentException("افزونه Shadowsocks پشتیبانی‌نشده است: " + plugin);
                }
                out.put("plugin", "obfs".equals(plugin) ? "obfs-local" : plugin);
                String opts = pluginOptions(proxy.get("plugin-opts"));
                if (!opts.isEmpty()) out.put("plugin_opts", opts);
            }
        } else if ("hysteria2".equals(type) || "hy2".equals(type)) {
            out = base("hysteria2", host, port).put("password", required(str(proxy.get("password")), "رمز Hysteria2"));
            applyClashTls(out, proxy, true);
            Map<String, Object> obfs = asMap(proxy.get("obfs"));
            if (!obfs.isEmpty()) {
                JSONObject obfsJson = new JSONObject().put("type", first(str(obfs.get("type")), "salamander"));
                String obfsPassword = str(obfs.get("password"));
                if (!obfsPassword.isEmpty()) obfsJson.put("password", obfsPassword);
                out.put("obfs", obfsJson);
            }
        } else if ("hysteria".equals(type)) {
            out = base("hysteria", host, port);
            String auth = str(proxy.get("auth"));
            if (!auth.isEmpty()) out.put("auth_str", auth);
            String up = first(str(proxy.get("up-mbps")), str(proxy.get("up_mbps")), str(proxy.get("up")));
            String down = first(str(proxy.get("down-mbps")), str(proxy.get("down_mbps")), str(proxy.get("down")));
            if (!up.isEmpty()) out.put("up_mbps", toInt(up.replaceAll("(?i)\\s*mbps$", "").trim(), "سرعت آپلود Hysteria"));
            if (!down.isEmpty()) out.put("down_mbps", toInt(down.replaceAll("(?i)\\s*mbps$", "").trim(), "سرعت دانلود Hysteria"));
            String obfs = str(proxy.get("obfs"));
            if (!obfs.isEmpty()) out.put("obfs", obfs);
            applyClashTls(out, proxy, true);
        } else if ("tuic".equals(type)) {
            out = base("tuic", host, port)
                    .put("uuid", required(str(proxy.get("uuid")), "UUID TUIC"))
                    .put("password", required(str(proxy.get("password")), "رمز TUIC"));
            String cc = str(proxy.get("congestion-controller"));
            if (!cc.isEmpty()) out.put("congestion_control", cc);
            String relay = str(proxy.get("udp-relay-mode"));
            if (!relay.isEmpty()) out.put("udp_relay_mode", relay);
            applyClashTls(out, proxy, true);
        } else if ("socks5".equals(type) || "socks".equals(type) || "socks4".equals(type)) {
            out = base("socks", host, port).put("version", type.contains("4") ? "4" : "5");
            String username = str(proxy.get("username"));
            String password = str(proxy.get("password"));
            if (!username.isEmpty()) out.put("username", username);
            if (!password.isEmpty()) out.put("password", password);
        } else if ("http".equals(type)) {
            out = base("http", host, port);
            String username = str(proxy.get("username"));
            String password = str(proxy.get("password"));
            if (!username.isEmpty()) out.put("username", username);
            if (!password.isEmpty()) out.put("password", password);
            applyClashTls(out, proxy, false);
        } else if ("anytls".equals(type)) {
            out = base("anytls", host, port).put("password", required(str(proxy.get("password")), "رمز AnyTLS"));
            applyClashTls(out, proxy, true);
        } else {
            throw new IllegalArgumentException("نوع پروکسی Clash پشتیبانی نمی‌شود: " + type);
        }
        return out;
    }

    private static void applyClashTls(JSONObject out, Map<String, Object> proxy, boolean defaultEnabled) throws Exception {
        boolean enabled = bool(proxy.get("tls"), defaultEnabled);
        Map<String, Object> realityOpts = asMap(proxy.get("reality-opts"));
        if (!realityOpts.isEmpty()) enabled = true;
        if (!enabled) return;
        JSONObject tls = new JSONObject().put("enabled", true);
        String serverName = first(str(proxy.get("servername")), str(proxy.get("sni")));
        if (!serverName.isEmpty()) tls.put("server_name", serverName);
        if (bool(proxy.get("skip-cert-verify"), false)) tls.put("insecure", true);
        Object alpn = proxy.get("alpn");
        if (alpn instanceof List) {
            JSONArray values = new JSONArray();
            for (Object value : (List<?>) alpn) if (value != null) values.put(String.valueOf(value));
            if (values.length() > 0) tls.put("alpn", values);
        }
        String fingerprint = str(proxy.get("client-fingerprint"));
        if (!fingerprint.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
        if (!realityOpts.isEmpty()) {
            JSONObject reality = new JSONObject().put("enabled", true)
                    .put("public_key", required(str(realityOpts.get("public-key")), "Reality public-key"));
            String shortId = str(realityOpts.get("short-id"));
            if (!shortId.isEmpty()) reality.put("short_id", shortId);
            tls.put("reality", reality);
        }
        out.put("tls", tls);
    }

    private static void applyClashTransport(JSONObject out, Map<String, Object> proxy) throws Exception {
        String network = first(str(proxy.get("network")), "tcp").toLowerCase(Locale.ROOT);
        if ("tcp".equals(network)) return;
        if ("ws".equals(network)) {
            Map<String, Object> options = asMap(proxy.get("ws-opts"));
            JSONObject transport = new JSONObject().put("type", "ws");
            String path = str(options.get("path"));
            if (!path.isEmpty()) transport.put("path", path);
            Map<String, Object> headers = asMap(options.get("headers"));
            if (!headers.isEmpty()) transport.put("headers", new JSONObject(headers));
            out.put("transport", transport);
        } else if ("grpc".equals(network)) {
            Map<String, Object> options = asMap(proxy.get("grpc-opts"));
            String service = first(str(options.get("grpc-service-name")), str(options.get("serviceName")));
            out.put("transport", new JSONObject().put("type", "grpc").put("service_name", service));
        } else if ("http".equals(network) || "h2".equals(network)) {
            Map<String, Object> options = asMap(proxy.get("http-opts"));
            JSONObject transport = new JSONObject().put("type", "http");
            String path = str(options.get("path"));
            if (!path.isEmpty()) transport.put("path", path);
            Object hosts = options.get("headers");
            if (hosts instanceof Map) {
                Object host = ((Map<?, ?>) hosts).get("Host");
                if (host instanceof List) transport.put("host", new JSONArray((List<?>) host));
                else if (host != null) transport.put("host", new JSONArray().put(String.valueOf(host)));
            }
            out.put("transport", transport);
        } else if ("httpupgrade".equals(network)) {
            Map<String, Object> options = asMap(proxy.get("http-upgrade-opts"));
            JSONObject transport = new JSONObject().put("type", "httpupgrade");
            String path = str(options.get("path"));
            String host = str(options.get("host"));
            if (!path.isEmpty()) transport.put("path", path);
            if (!host.isEmpty()) transport.put("host", host);
            out.put("transport", transport);
        } else {
            throw new IllegalArgumentException("شبکه Clash پشتیبانی نمی‌شود: " + network + "؛ mKCP و XHTTP در sing-box رسمی پشتیبانی نمی‌شوند");
        }
    }

    private static String pluginOptions(Object raw) {
        if (!(raw instanceof Map)) return raw == null ? "" : String.valueOf(raw);
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if ("tls".equals(key) && bool(entry.getValue(), false)) values.add("tls");
            else if ("mode".equals(key) && "websocket".equals(value)) values.add("mode=websocket");
            else values.add(key + "=" + value);
        }
        return android.text.TextUtils.join(";", values);
    }

    /** Removes JSONC comments and trailing commas without touching quoted strings. */
    private static String stripJsonComments(String input) {
        StringBuilder output = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                output.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; output.append(c); continue; }
            if (c == '/' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < input.length() && input.charAt(i) != '\n' && input.charAt(i) != '\r') i++;
                    if (i < input.length()) output.append(input.charAt(i));
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < input.length() && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) i++;
                    i++;
                    continue;
                }
            }
            output.append(c);
        }
        StringBuilder compact = new StringBuilder(output.length());
        inString = false;
        escaped = false;
        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            if (inString) {
                compact.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                compact.append(c);
            } else if (c == ',') {
                int look = i + 1;
                while (look < output.length() && Character.isWhitespace(output.charAt(look))) look++;
                if (look < output.length() && (output.charAt(look) == '}' || output.charAt(look) == ']')) continue;
                compact.append(c);
            } else compact.append(c);
        }
        return compact.toString().trim();
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? normalizeMap((Map<?, ?>) value) : Collections.emptyMap();
    }

    private static JSONObject base(String type, String host, int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("پورت باید بین 1 و 65535 باشد");
        return new JSONObject().put("type", type).put("tag", "proxy").put("server", host).put("server_port", port);
    }

    private static List<ProxyNode> parseShareLinks(String text) throws Exception {
        ArrayList<ProxyNode> nodes = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        Pattern linkPattern = Pattern.compile("(?i)(?:vless|vmess|trojan|ss|shadowsocks|ssr|hysteria2?|hy2|tuic|anytls|socks(?:4a?|5h?)?|https?)://[^\\s\"'<>]+");
        Matcher matcher = linkPattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            // Subscription text is often copied with punctuation or Markdown around each URI.
            while (!value.isEmpty() && ",;)]}>`.".indexOf(value.charAt(value.length() - 1)) >= 0) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isEmpty()) continue;
            try { nodes.add(ProxyNode.parse(value)); }
            catch (Exception e) { addError(errors, e.getMessage()); }
        }
        if (nodes.isEmpty()) {
            String suffix = errors.isEmpty() ? "" : "\n" + joinErrors(errors);
            if (text.trim().matches("(?is)^https?://\\S+$")) {
                throw new IllegalArgumentException("این یک لینک وب/اشتراک است، نه لینک مستقیم سرور. آن را در بخش «اشتراک» وارد کن تا محتوایش دانلود و خوانده شود.");
            }
            throw new IllegalArgumentException("فرمت ورودی قابل‌شناسایی نیست. لینک‌های VLESS، VMess، Trojan، SS، Hysteria2، TUIC، AnyTLS، SOCKS و HTTP یا فایل Clash YAML / sing-box JSON / Xray JSON را وارد کن." + suffix);
        }
        return distinct(nodes);
    }

    private static List<ProxyNode> distinct(List<ProxyNode> input) {
        LinkedHashMap<String, ProxyNode> unique = new LinkedHashMap<>();
        for (ProxyNode node : input) unique.put(node.getId(), node);
        return new ArrayList<>(unique.values());
    }

    private static void addError(List<String> errors, String message) {
        if (errors.size() < 5 && message != null && !message.trim().isEmpty()) errors.add(message.trim());
    }

    private static String joinErrors(List<String> errors) { return android.text.TextUtils.join(" | ", errors); }
    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " خالی است");
        return value.trim();
    }
    private static String str(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return fallback;
    }
    private static int toInt(Object value, String label) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { throw new IllegalArgumentException(label + " نامعتبر است"); }
    }
    private static int toIntDefault(Object value, int fallback) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }
}
