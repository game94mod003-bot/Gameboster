package com.abolix.gamebooster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SingBoxConfigTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    public void buildsFullDeviceTunnelWhenNoAppFilterIsSelected() throws Exception {
        ProxyNode node = ConfigImporter.parse("vless://" + UUID + "@edge.example:443?security=tls&sni=cdn.example").get(0);
        JSONObject config = new JSONObject(SingBoxConfig.build(node, Collections.emptyList(), "com.abolix.gamebooster"));
        JSONObject tun = config.getJSONArray("inbounds").getJSONObject(0);
        assertTrue(tun.getBoolean("auto_route"));
        assertFalse(tun.has("include_package"));
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
    }

    @Test
    public void modernHttpsDnsServerDoesNotUseLegacyDirectDetour() throws Exception {
        ProxyNode node = ConfigImporter.parse("vless://" + UUID + "@edge.example:443?security=tls&sni=cdn.example").get(0);
        JSONObject config = new JSONObject(SingBoxConfig.build(node, Collections.emptyList(), "com.abolix.gamebooster"));
        JSONObject dnsServer = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0);
        assertEquals("https", dnsServer.getString("type"));
        assertEquals("1.1.1.1", dnsServer.getString("server"));
        assertFalse("New DNS server must not detour to an empty direct outbound", dnsServer.has("detour"));
    }

    @Test
    public void dnsDetourFailureMapsToActionableDiagnostic() {
        AbolixDiagnostics.Issue issue = AbolixDiagnostics.classify(
                "start dns/https[cloudflare-doh]: detour to an empty direct outbound makes no sense");
        assertEquals("ABX-DNS-001", issue.code);
        assertTrue(issue.suggestion.contains("detour"));
    }

    @Test
    public void diagnosticCatalogContainsAtLeastFiftyKnownCases() {
        assertTrue(AbolixDiagnostics.knownIssueCount() >= 50);
    }

    @Test
    public void selectedAppsAlsoIncludeAbolixForDiagnostics() throws Exception {
        ProxyNode node = ConfigImporter.parse("trojan://password@edge.example:443?sni=cdn.example").get(0);
        JSONObject config = new JSONObject(SingBoxConfig.build(node,
                Arrays.asList("com.instagram.android", "org.telegram.messenger"), "com.abolix.gamebooster"));
        JSONArray include = config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("include_package");
        assertEquals(3, include.length());
        assertEquals("com.abolix.gamebooster", include.getString(2));
    }
}
