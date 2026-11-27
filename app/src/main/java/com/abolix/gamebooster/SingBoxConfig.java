package com.abolix.gamebooster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Produces a compact sing-box 1.14-compatible client configuration. */
public final class SingBoxConfig {
    private SingBoxConfig() { }

    public static String build(ProxyNode node, List<String> selectedPackages, String ownPackage) throws Exception {
        if (node == null) throw new IllegalArgumentException("ابتدا یک سرور انتخاب کن");

        JSONObject tun = new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", new JSONArray()
                        .put("172.19.0.1/30")
                        .put("fdfe:dcba:9876::1/126"))
                .put("mtu", 1400)
                .put("auto_route", true)
                .put("strict_route", false);

        Set<String> packages = new LinkedHashSet<>();
        if (selectedPackages != null) packages.addAll(selectedPackages);
        // An empty selection means the normal full-device VPN. When the user selects apps,
        // include Abolix itself so in-app diagnostics also travel through the active tunnel.
        if (!packages.isEmpty() && ownPackage != null && !ownPackage.trim().isEmpty()) packages.add(ownPackage);
        if (!packages.isEmpty()) {
            JSONArray include = new JSONArray();
            for (String packageName : packages) include.put(packageName);
            tun.put("include_package", include);
        }

        JSONObject outbound = node.getOutbound().put("tag", "proxy");
        JSONArray outbounds = new JSONArray()
                .put(outbound)
                .put(new JSONObject().put("type", "direct").put("tag", "direct"))
                .put(new JSONObject().put("type", "block").put("tag", "block"));

        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray().put(new JSONObject()
                        .put("type", "https")
                        .put("tag", "cloudflare-doh")
                        .put("server", "1.1.1.1")
                        .put("server_port", 443)
                        .put("path", "/dns-query")
                        .put("tls", new JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
                        .put("detour", "direct")))
                .put("final", "cloudflare-doh")
                .put("strategy", "prefer_ipv4")
                .put("optimistic", new JSONObject().put("enabled", true).put("timeout", "3h"));

        JSONObject route = new JSONObject()
                .put("final", "proxy")
                .put("rules", new JSONArray().put(new JSONObject()
                        .put("protocol", "dns")
                        .put("action", "hijack-dns")));

        JSONObject config = new JSONObject()
                .put("log", new JSONObject().put("level", "warn").put("timestamp", true))
                .put("dns", dns)
                .put("inbounds", new JSONArray().put(tun))
                .put("outbounds", outbounds)
                .put("route", route);
        return config.toString();
    }
}
