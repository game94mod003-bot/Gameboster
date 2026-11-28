package com.abolix.gamebooster;

import org.junit.Test;
import static org.junit.Assert.*;

public class AbolixNetworkFilterTest {
    @Test public void excludesVpnTransport() {
        assertTrue(AbolixNetworkFilter.shouldSkip("tun0", "tun0", true));
    }
    @Test public void excludesRegisteredTunInterface() {
        assertTrue(AbolixNetworkFilter.shouldSkip("tun0", "tun0", false));
    }
    @Test public void comparisonIsCaseInsensitive() {
        assertTrue(AbolixNetworkFilter.shouldSkip("TUN0", "tun0", false));
    }
    @Test public void excludesMissingInterfaceName() {
        assertTrue(AbolixNetworkFilter.shouldSkip(null, "", false));
        assertTrue(AbolixNetworkFilter.shouldSkip("  ", "", false));
    }
    @Test public void keepsPhysicalWifiInterface() {
        assertFalse(AbolixNetworkFilter.shouldSkip("wlan0", "tun0", false));
    }
    @Test public void keepsPhysicalCellularInterface() {
        assertFalse(AbolixNetworkFilter.shouldSkip("rmnet_data0", "tun0", false));
    }
}
