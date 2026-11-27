package com.abolix.gamebooster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Base64;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConfigImporterTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    public void importsVlessRealityUriWithCommonParameterNames() throws Exception {
        String link = "vless://" + UUID + "@edge.example:443?encryption=none&security=reality"
                + "&sni=cover.example&fp=chrome&pbk=PUBLIC_KEY&sid=ABCD&type=tcp#Reality";
        ProxyNode node = only(ConfigImporter.parse(link));
        JSONObject outbound = node.getOutbound();
        assertEquals("vless", outbound.getString("type"));
        assertEquals("edge.example", outbound.getString("server"));
        assertEquals("PUBLIC_KEY", outbound.getJSONObject("tls").getJSONObject("reality").getString("public_key"));
        assertEquals("ABCD", outbound.getJSONObject("tls").getJSONObject("reality").getString("short_id"));
    }

    @Test
    public void importsVlessWebSocketTlsUri() throws Exception {
        String link = "vless://" + UUID + "@edge.example:443?type=ws&security=tls&sni=cdn.example"
                + "&host=cdn.example&path=%2Fsocket#WS";
        ProxyNode node = only(ConfigImporter.parse(link));
        JSONObject outbound = node.getOutbound();
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"));
        assertEquals("/socket", outbound.getJSONObject("transport").getString("path"));
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"));
    }

    @Test
    public void importsVmessBase64WithoutPadding() throws Exception {
        String json = "{\"v\":\"2\",\"ps\":\"vmess-node\",\"add\":\"vmess.example\","
                + "\"port\":443,\"id\":\"" + UUID + "\",\"aid\":0,\"scy\":\"auto\","
                + "\"net\":\"ws\",\"type\":\"none\",\"host\":\"cdn.example\",\"path\":\"/ws\",\"tls\":\"tls\"}";
        String encoded = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                .replaceAll("=+$", "");
        ProxyNode node = only(ConfigImporter.parse("vmess://" + encoded));
        assertEquals("vmess", node.getProtocol());
        assertEquals("vmess.example", node.getServer());
        assertEquals("ws", node.getOutbound().getJSONObject("transport").getString("type"));
    }

    @Test
    public void importsUriStyleVmessWithWebSocketTls() throws Exception {
        String link = "vmess://" + UUID + "@vmess-uri.example:443?encryption=auto&security=tls"
                + "&type=ws&host=cdn.example&path=%2Fvmess&sni=cdn.example#VMess-URI";
        ProxyNode node = only(ConfigImporter.parse(link));
        assertEquals("vmess", node.getProtocol());
        assertEquals(UUID, node.getOutbound().getString("uuid"));
        assertEquals("ws", node.getOutbound().getJSONObject("transport").getString("type"));
        assertTrue(node.getOutbound().getJSONObject("tls").getBoolean("enabled"));
    }

    @Test
    public void importsShadowsocksSchemeAlias() throws Exception {
        String credentials = Base64.encodeToString("aes-128-gcm:secret".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        ProxyNode node = only(ConfigImporter.parse("shadowsocks://" + credentials + "@ss.example:8388#SS"));
        assertEquals("shadowsocks", node.getProtocol());
        assertEquals("ss.example", node.getServer());
        assertEquals("aes-128-gcm", node.getOutbound().getString("method"));
    }

    @Test
    public void importsTrojanAndMultipleLinksWithCopyPastePunctuation() throws Exception {
        String links = "First: vless://" + UUID + "@one.example:443?security=tls&sni=one.example#one,\n"
                + "Second: trojan://secret@two.example:443?sni=two.example#two).";
        List<ProxyNode> nodes = ConfigImporter.parse(links);
        assertEquals(2, nodes.size());
        assertTrue(nodes.stream().anyMatch(node -> "vless".equals(node.getProtocol())));
        assertTrue(nodes.stream().anyMatch(node -> "trojan".equals(node.getProtocol())));
    }

    @Test
    public void importsClashYamlEvenWhenCommentPrecedesRootKey() throws Exception {
        String yaml = "# Exported by Clash\nproxies:\n"
                + "  - name: clash-vless\n"
                + "    type: vless\n"
                + "    server: clash.example\n"
                + "    port: 443\n"
                + "    uuid: " + UUID + "\n"
                + "    tls: true\n"
                + "    servername: cdn.example\n"
                + "    network: ws\n"
                + "    ws-opts:\n"
                + "      path: /clash\n";
        ProxyNode node = only(ConfigImporter.parse(yaml));
        assertEquals("vless", node.getProtocol());
        assertEquals("/clash", node.getOutbound().getJSONObject("transport").getString("path"));
    }

    @Test
    public void importsSingBoxJsonAndIgnoresNonProxyOutbounds() throws Exception {
        String json = "{\"outbounds\":["
                + "{\"type\":\"selector\",\"tag\":\"select\",\"outbounds\":[\"node\"]},"
                + "{\"type\":\"vless\",\"tag\":\"node\",\"server\":\"sb.example\",\"server_port\":443,"
                + "\"uuid\":\"" + UUID + "\",\"tls\":{\"enabled\":true,\"server_name\":\"cdn.example\"}}]}";
        ProxyNode node = only(ConfigImporter.parse(json));
        assertEquals("vless", node.getProtocol());
        assertEquals("sb.example", node.getServer());
    }

    @Test
    public void jsonCommentStripperPreservesQuotedStringsAndEscapes() throws Exception {
        String json = "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"node\","
                + "\"server\":\"json.example\",\"server_port\":443,"
                + "\"uuid\":\"" + UUID + "\",\"tls\":{\"enabled\":true,"
                + "\"server_name\":\"cdn.example\"},\"transport\":{\"type\":\"ws\","
                + "\"path\":\"/a\\\"b//c\"}}],} // trailing comment";
        ProxyNode node = only(ConfigImporter.parse(json));
        assertEquals("json.example", node.getServer());
        assertEquals("/a\"b//c", node.getOutbound().getJSONObject("transport").getString("path"));
    }

    @Test
    public void importsXrayJsonWithRealityTransport() throws Exception {
        String json = "{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"xray\","
                + "\"settings\":{\"vnext\":[{\"address\":\"xray.example\",\"port\":443,"
                + "\"users\":[{\"id\":\"" + UUID + "\",\"encryption\":\"none\"}]}]},"
                + "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\","
                + "\"realitySettings\":{\"serverName\":\"cover.example\",\"publicKey\":\"PUB\",\"shortId\":\"01\"}}}]}";
        ProxyNode node = only(ConfigImporter.parse(json));
        JSONObject reality = node.getOutbound().getJSONObject("tls").getJSONObject("reality");
        assertEquals("PUB", reality.getString("public_key"));
        assertEquals("01", reality.getString("short_id"));
    }

    @Test
    public void importsBase64SubscriptionWithMultipleProfiles() throws Exception {
        String clear = "vless://" + UUID + "@one.example:443?security=tls&sni=one.example\n"
                + "trojan://secret@two.example:443?sni=two.example";
        String encoded = Base64.encodeToString(clear.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        assertEquals(2, ConfigImporter.parse(encoded).size());
    }

    @Test
    public void importsExplicitUrlSafeBase64Prefix() throws Exception {
        String clear = "vless://" + UUID + "@base64.example:443?security=tls&sni=cdn.example";
        String encoded = Base64.encodeToString(clear.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
        ProxyNode node = only(ConfigImporter.parse("base64://" + encoded));
        assertEquals("base64.example", node.getServer());
    }

    @Test
    public void rejectsUnsupportedFormatWithActionableMessage() throws Exception {
        try {
            ConfigImporter.parse("ssr://not-a-supported-node");
            fail("SSR should not be silently accepted by sing-box");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ShadowsocksR"));
        }
    }

    @Test
    public void rejectsEmptyInput() throws Exception {
        try {
            ConfigImporter.parse("  ");
            fail("Empty input should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("خالی"));
        }
    }

    private static ProxyNode only(List<ProxyNode> nodes) {
        assertNotNull(nodes);
        assertEquals(1, nodes.size());
        ProxyNode node = nodes.get(0);
        assertFalse(node.getServer().isEmpty());
        return node;
    }
}
