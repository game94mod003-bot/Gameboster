package ir.abulix.psiphone

import org.json.JSONArray
import org.json.JSONObject

object XrayConfig {
    const val SOCKS_PORT = 10808

    fun build(c: Cfg, beast: Boolean): String {
        val stream = JSONObject()
            .put("network", "ws").put("security", "tls")
            .put("tlsSettings", JSONObject().put("serverName", c.sni)
                .put("fingerprint", if (beast) "randomized" else c.fp)
                .put("alpn", JSONArray().put("http/1.1")))
            .put("wsSettings", JSONObject().put("path", c.path).put("headers", JSONObject().put("Host", c.host)))
        if (beast) stream.put("sockopt", JSONObject().put("dialerProxy", "frag").put("tcpNoDelay", true))

        val settings = if (c.proto == "vless")
            JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", c.addr).put("port", c.port)
                .put("users", JSONArray().put(JSONObject().put("id", c.secret).put("encryption", "none")))))
        else
            JSONObject().put("servers", JSONArray().put(JSONObject().put("address", c.addr).put("port", c.port).put("password", c.secret)))

        val proxy = JSONObject().put("tag", "proxy").put("protocol", c.proto).put("settings", settings).put("streamSettings", stream)
        val outs = JSONArray().put(proxy)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        if (beast) outs.put(JSONObject().put("tag", "frag").put("protocol", "freedom").put("settings",
            JSONObject().put("fragment", JSONObject().put("packets", "tlshello").put("length", "10-30").put("interval", "10-20"))))

        val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        val ins = JSONArray()
            .put(JSONObject().put("tag", "tun-in").put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500)).put("sniffing", sniff))
            .put(JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1").put("port", SOCKS_PORT)
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true)))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")).put("queryStrategy", "UseIPv4"))
            .put("inbounds", ins).put("outbounds", outs)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("ip", JSONArray().put("geoip:private")).put("outboundTag", "direct"))))
            .toString()
    }
}
