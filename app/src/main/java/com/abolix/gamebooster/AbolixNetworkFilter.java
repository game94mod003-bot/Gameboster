package com.abolix.gamebooster;

/** Filters VPN-owned interfaces out of physical-uplink discovery. */
final class AbolixNetworkFilter {
    private AbolixNetworkFilter() { }

    static boolean shouldSkip(String interfaceName, String tunInterfaceName, boolean vpnTransport) {
        if (vpnTransport) return true;
        if (interfaceName == null || interfaceName.trim().isEmpty()) return true;
        return tunInterfaceName != null && !tunInterfaceName.trim().isEmpty()
                && tunInterfaceName.equalsIgnoreCase(interfaceName);
    }
}
