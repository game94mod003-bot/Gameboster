package hev.htproxy;

/** JNI bridge to hev-socks5-tunnel (built in CI). Package/class name must match the native library. */
public final class TProxyService {
    public static boolean loaded;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private TProxyService() { }

    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    /** {tx_packets, tx_bytes, rx_packets, rx_bytes} */
    public static native long[] TProxyGetStats();
}
