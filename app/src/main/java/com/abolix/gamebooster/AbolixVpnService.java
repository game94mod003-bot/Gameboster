package com.abolix.gamebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Android VPN lifecycle and reflective bridge to the official sing-box libbox Android API. */
public final class AbolixVpnService extends VpnService {
    public static final String ACTION_START = "com.abolix.gamebooster.action.START";
    public static final String ACTION_STOP = "com.abolix.gamebooster.action.STOP";
    public static final String ACTION_STATE = "com.abolix.gamebooster.action.STATE";
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_NODE = "node";
    private static final String TAG = "AbolixVPN";
    private static final String CHANNEL = "abolix_vpn";
    private static final int NOTIFICATION_ID = 4107;
    private static volatile AbolixVpnService instance;
    private static volatile boolean libboxInitialized;
    private static final Object LIBBOX_SETUP_LOCK = new Object();
    private final Object coreLock = new Object();
    private long lifecycleGeneration;
    private boolean starting;

    private volatile Object commandServer;
    private volatile ParcelFileDescriptor tunDescriptor;
    private volatile boolean running;
    private volatile String activeConfig = "";
    private volatile Object defaultInterfaceListener;
    private volatile android.net.ConnectivityManager.NetworkCallback defaultNetworkCallback;
    private String currentNode = "";

    public static void start(android.content.Context context, String config, String node) {
        Intent intent = new Intent(context, AbolixVpnService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_CONFIG, config);
        intent.putExtra(EXTRA_NODE, node == null ? "Abolix" : node);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(android.content.Context context) {
        Intent intent = new Intent(context, AbolixVpnService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    /** Keeps one-off proxy-server TCP tests outside the active TUN route. */
    public static boolean protectSocket(java.net.Socket socket) {
        AbolixVpnService service = instance;
        if (service == null || service.tunDescriptor == null) return true;
        try { return service.protect(socket); }
        catch (RuntimeException error) { Log.w(TAG, "Could not protect probe socket", error); return false; }
    }

    @Override public void onRevoke() {
        synchronized (this) {
            lifecycleGeneration++;
            starting = false;
        }
        stopEngine();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        synchronized (this) {
            lifecycleGeneration++;
            starting = false;
        }
        stopEngine();
        defaultInterfaceListener = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            final long stopGeneration;
            synchronized (this) {
                stopGeneration = ++lifecycleGeneration;
                starting = false;
            }
            new Thread(() -> {
                synchronized (coreLock) {
                    if (isCurrentGeneration(stopGeneration)) stopEngineLocked();
                }
                if (isCurrentGeneration(stopGeneration)) stopSelf(startId);
            }, "Abolix-Core-Stop").start();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        String requestedNode = intent.getStringExtra(EXTRA_NODE);
        if (requestedNode == null) requestedNode = "Abolix";
        String config = intent.getStringExtra(EXTRA_CONFIG);
        if (config == null || config.trim().isEmpty()) {
            stopWithError("پیکربندی اتصال خالی است");
            return START_NOT_STICKY;
        }
        final long startGeneration;
        synchronized (this) {
            if (starting) return START_NOT_STICKY;
            currentNode = requestedNode;
            starting = true;
            startGeneration = ++lifecycleGeneration;
        }
        startForegroundCompat("در حال اتصال · " + requestedNode);
        new Thread(() -> {
            try {
                synchronized (coreLock) {
                    if (!isCurrentGeneration(startGeneration)) return;
                    startEngine(config);
                    if (!isCurrentGeneration(startGeneration)) {
                        closeEngineCore();
                        return;
                    }
                    running = true;
                }
                if (!isCurrentGeneration(startGeneration)) return;
                broadcastState(true, "متصل به " + currentNode);
                updateNotification("متصل · " + currentNode);
            } catch (Throwable error) {
                if (isCurrentGeneration(startGeneration)) {
                    Log.e(TAG, "Unable to start sing-box", error);
                    stopWithError("اتصال شروع نشد: " + safeMessage(error));
                }
            } finally {
                synchronized (AbolixVpnService.this) {
                    if (lifecycleGeneration == startGeneration) starting = false;
                }
            }
        }, "Abolix-Core-Start").start();
        return START_NOT_STICKY;
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (this) { return lifecycleGeneration == generation; }
    }

    private void startEngine(String config) throws Exception {
        closeEngineCore();
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        Class<?> setupType = Class.forName("io.nekohasekai.libbox.SetupOptions");
        if (!libboxInitialized) {
            synchronized (LIBBOX_SETUP_LOCK) {
                if (!libboxInitialized) {
                    Object setup = setupType.getDeclaredConstructor().newInstance();
                    setProperty(setup, "BasePath", getFilesDir().getAbsolutePath());
                    setProperty(setup, "WorkingPath", getFilesDir().getAbsolutePath());
                    setProperty(setup, "TempPath", getCacheDir().getAbsolutePath());
                    setProperty(setup, "Debug", false);
                    setProperty(setup, "LogMaxLines", 300);
                    setProperty(setup, "AppVersion", "1.0.0");
                    setProperty(setup, "AppMarketingVersion", "1.0.0");
                    Method setupMethod = findMethod(libbox, "setup", 1);
                    setupMethod.invoke(null, setup);
                    libboxInitialized = true;
                }
            }
        }

        Class<?> platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface");
        Object platform = Proxy.newProxyInstance(platformType.getClassLoader(), new Class<?>[]{platformType},
                (proxy, method, args) -> handlePlatformCall(method, args));

        Class<?> handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType},
                (proxy, method, args) -> handleHandlerCall(method, args));
        Class<?> serverType = Class.forName("io.nekohasekai.libbox.CommandServer");
        Constructor<?> constructor = findTwoArgConstructor(serverType, handlerType, platformType);
        commandServer = constructor.newInstance(handler, platform);
        invokeNoArg(commandServer, "start");
        activeConfig = config;

        Class<?> overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions");
        Object override = overrideType.getDeclaredConstructor().newInstance();
        Method reload = findCompatibleMethod(serverType, "startOrReloadService", String.class, overrideType);
        Object result = reload.invoke(commandServer, config, override);
        if (result instanceof String && !((String) result).trim().isEmpty()) {
            throw new IllegalStateException((String) result);
        }
    }

    private Object createSystemProxyStatus() {
        try {
            Class<?> type = Class.forName("io.nekohasekai.libbox.SystemProxyStatus");
            Object status = type.getDeclaredConstructor().newInstance();
            setProperty(status, "Available", false);
            setProperty(status, "Enabled", false);
            return status;
        } catch (Exception e) {
            Log.w(TAG, "Could not create SystemProxyStatus", e);
            return null;
        }
    }

    private void reloadActiveConfig() {
        synchronized (coreLock) {
            Object server = commandServer;
            String config = activeConfig;
            if (server == null || config == null || config.isEmpty()) return;
            try {
                Class<?> overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions");
                Object override = overrideType.getDeclaredConstructor().newInstance();
                findCompatibleMethod(server.getClass(), "startOrReloadService", String.class, overrideType)
                        .invoke(server, config, override);
            } catch (Exception e) {
                Log.e(TAG, "Failed to reload current config", e);
                stopWithError("بارگذاری مجدد اتصال ناموفق بود: " + safeMessage(e));
            }
        }
    }


    private void startDefaultInterfaceMonitor(Object listener) {
        defaultInterfaceListener = listener;
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            notifyDefaultInterface(null);
            return;
        }
        closeDefaultInterfaceMonitor();
        defaultInterfaceListener = listener;
        android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network network) { notifyDefaultInterfaceCurrent(); }
            @Override public void onLost(android.net.Network network) { notifyDefaultInterfaceCurrent(); }
            @Override public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties properties) {
                notifyDefaultInterfaceCurrent();
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(callback);
            defaultNetworkCallback = callback;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not register default-network callback", error);
        }
        notifyDefaultInterfaceCurrent();
    }

    private void closeDefaultInterfaceMonitor() {
        android.net.ConnectivityManager.NetworkCallback callback = defaultNetworkCallback;
        defaultNetworkCallback = null;
        if (callback == null) return;
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            try { connectivity.unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
        }
    }

    private void notifyDefaultInterfaceCurrent() {
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        notifyDefaultInterface(connectivity == null ? null : connectivity.getActiveNetwork());
    }

    private void notifyDefaultInterface(android.net.Network network) {
        Object listener = defaultInterfaceListener;
        if (listener == null) return;
        String interfaceName = "";
        int interfaceIndex = -1;
        if (network != null) {
            android.net.ConnectivityManager connectivity =
                    (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.LinkProperties properties = connectivity == null ? null : connectivity.getLinkProperties(network);
            if (properties != null && properties.getInterfaceName() != null) {
                interfaceName = properties.getInterfaceName();
                try {
                    java.net.NetworkInterface systemInterface = java.net.NetworkInterface.getByName(interfaceName);
                    if (systemInterface != null) interfaceIndex = systemInterface.getIndex();
                } catch (Exception error) {
                    Log.d(TAG, "Default interface index is not available", error);
                }
            }
        }
        try {
            for (Method method : listener.getClass().getMethods()) {
                if ("updateDefaultInterface".equals(method.getName()) && method.getParameterTypes().length == 4) {
                    method.invoke(listener, interfaceName, interfaceIndex, false, false);
                    return;
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not notify sing-box about the default interface", error);
        }
    }

    private Object iteratorProxy(String interfaceName, List<?> values) throws Exception {
        Class<?> iteratorType = Class.forName(interfaceName);
        java.util.Iterator<?> iterator = values.iterator();
        return Proxy.newProxyInstance(iteratorType.getClassLoader(), new Class<?>[]{iteratorType}, (proxy, method, args) -> {
            String name = method.getName();
            if ("hasNext".equalsIgnoreCase(name)) return iterator.hasNext();
            if ("next".equalsIgnoreCase(name)) return iterator.hasNext() ? iterator.next() : null;
            if ("len".equalsIgnoreCase(name)) return values.size();
            return defaultValue(method.getReturnType());
        });
    }

    /** Mirrors the network metadata supplied by the official Android sing-box client. */
    private Object loadNetworkInterfaces() throws Exception {
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return iteratorProxy("io.nekohasekai.libbox.NetworkInterfaceIterator", java.util.Collections.emptyList());
        }

        java.util.Map<String, java.net.NetworkInterface> systemInterfaces = new java.util.HashMap<>();
        java.util.Enumeration<java.net.NetworkInterface> enumeration = java.net.NetworkInterface.getNetworkInterfaces();
        if (enumeration != null) {
            while (enumeration.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = enumeration.nextElement();
                systemInterfaces.put(networkInterface.getName(), networkInterface);
            }
        }

        Class<?> libboxInterfaceType = Class.forName("io.nekohasekai.libbox.NetworkInterface");
        Class<?> libboxType = Class.forName("io.nekohasekai.libbox.Libbox");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        android.net.Network[] networks = connectivity.getAllNetworks();
        for (android.net.Network network : networks) {
            android.net.LinkProperties link = connectivity.getLinkProperties(network);
            android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (link == null || capabilities == null) continue;
            String interfaceName = link.getInterfaceName();
            java.net.NetworkInterface systemInterface = systemInterfaces.get(interfaceName);
            if (interfaceName == null || interfaceName.isEmpty() || systemInterface == null) continue;

            Object boxInterface = libboxInterfaceType.getDeclaredConstructor().newInstance();
            setProperty(boxInterface, "Name", interfaceName);

            java.util.ArrayList<String> dnsServers = new java.util.ArrayList<>();
            for (java.net.InetAddress address : link.getDnsServers()) {
                if (address != null && address.getHostAddress() != null) dnsServers.add(address.getHostAddress());
            }
            setProperty(boxInterface, "DnsServer", iteratorProxy("io.nekohasekai.libbox.StringIterator", dnsServers));

            java.util.ArrayList<String> searchDomains = new java.util.ArrayList<>();
            String domains = link.getDomains();
            if (domains != null) {
                for (String domain : domains.split("[,\\s]+")) if (!domain.isEmpty()) searchDomains.add(domain);
            }
            setProperty(boxInterface, "DnsSearchDomain", iteratorProxy("io.nekohasekai.libbox.StringIterator", searchDomains));

            java.util.ArrayList<String> gateways = new java.util.ArrayList<>();
            for (android.net.RouteInfo route : link.getRoutes()) {
                java.net.InetAddress gateway = route.getGateway();
                if (route.getDestination().getPrefixLength() == 0 && gateway != null && !gateway.isAnyLocalAddress()) {
                    gateways.add(gateway.getHostAddress());
                }
            }
            setProperty(boxInterface, "Gateway", iteratorProxy("io.nekohasekai.libbox.StringIterator", gateways));

            java.util.ArrayList<String> addresses = new java.util.ArrayList<>();
            for (java.net.InterfaceAddress address : systemInterface.getInterfaceAddresses()) {
                java.net.InetAddress ip = address.getAddress();
                if (ip == null) continue;
                java.net.InetAddress unscoped = java.net.InetAddress.getByAddress(ip.getAddress());
                addresses.add(unscoped.getHostAddress() + "/" + address.getNetworkPrefixLength());
            }
            setProperty(boxInterface, "Addresses", iteratorProxy("io.nekohasekai.libbox.StringIterator", addresses));

            int otherType = readStaticInt(libboxType, "InterfaceTypeOther", -1);
            int interfaceType = otherType;
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                interfaceType = readStaticInt(libboxType, "InterfaceTypeWIFI", otherType);
            } else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                interfaceType = readStaticInt(libboxType, "InterfaceTypeCellular", otherType);
            } else if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                interfaceType = readStaticInt(libboxType, "InterfaceTypeEthernet", otherType);
            }
            if (interfaceType >= 0) setProperty(boxInterface, "Type", interfaceType);
            setProperty(boxInterface, "Index", systemInterface.getIndex());
            try { setProperty(boxInterface, "Mtu", systemInterface.getMTU()); }
            catch (Exception ignored) { }

            int flags = 0;
            if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags |= android.system.OsConstants.IFF_UP | android.system.OsConstants.IFF_RUNNING;
            }
            if (systemInterface.isLoopback()) flags |= android.system.OsConstants.IFF_LOOPBACK;
            if (systemInterface.isPointToPoint()) flags |= android.system.OsConstants.IFF_POINTOPOINT;
            if (systemInterface.supportsMulticast()) flags |= android.system.OsConstants.IFF_MULTICAST;
            setProperty(boxInterface, "Flags", flags);
            setProperty(boxInterface, "Metered", !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            result.add(boxInterface);
        }
        return iteratorProxy("io.nekohasekai.libbox.NetworkInterfaceIterator", result);
    }

    private static int readStaticInt(Class<?> type, String fieldName, int fallback) {
        try { return type.getField(fieldName).getInt(null); }
        catch (Exception ignored) { return fallback; }
    }

    private Object handlePlatformCall(Method method, Object[] args) throws Exception {
        String name = method.getName();
        if ("openTun".equalsIgnoreCase(name)) {
            if (args == null || args.length == 0) throw new IllegalArgumentException("TunOptions دریافت نشد");
            return openTun(args[0]);
        }
        if ("autoDetectInterfaceControl".equalsIgnoreCase(name)) {
            if (args == null || args.length == 0 || !(args[0] instanceof Number)) {
                throw new IllegalArgumentException("File descriptor برای محافظت سوکت دریافت نشد");
            }
            if (!protect(((Number) args[0]).intValue())) {
                throw new IllegalStateException("VpnService نتوانست سوکت خروجی هسته را از تونل محافظت کند");
            }
            // libbox declares this callback with an error return; null represents success.
            return null;
        }
        if ("usePlatformAutoDetectInterfaceControl".equalsIgnoreCase(name)) return true;
        if ("startDefaultInterfaceMonitor".equalsIgnoreCase(name)) {
            if (args != null && args.length > 0) startDefaultInterfaceMonitor(args[0]);
            return defaultValue(method.getReturnType());
        }
        if ("closeDefaultInterfaceMonitor".equalsIgnoreCase(name)) {
            closeDefaultInterfaceMonitor();
            return defaultValue(method.getReturnType());
        }
        if ("getInterfaces".equalsIgnoreCase(name)) {
            try { return loadNetworkInterfaces(); }
            catch (Exception error) {
                Log.w(TAG, "Could not enumerate Android network interfaces", error);
                return iteratorProxy("io.nekohasekai.libbox.NetworkInterfaceIterator", java.util.Collections.emptyList());
            }
        }
        if ("underNetworkExtension".equalsIgnoreCase(name)
                || "includeAllNetworks".equalsIgnoreCase(name)
                || "usePlatformShell".equalsIgnoreCase(name)
                || "usePlatformBridge".equalsIgnoreCase(name)
                || "usePlatformAutoRedirect".equalsIgnoreCase(name)) return false;
        if ("sendNotification".equalsIgnoreCase(name)) {
            String title = args != null && args.length > 0 ? stringProperty(args[0], "Title") : "Abolix";
            String body = args != null && args.length > 0 ? stringProperty(args[0], "Body") : "اتصال فعال";
            updateNotification((title + " · " + body).trim());
            return defaultValue(method.getReturnType());
        }
        if ("cancelNotification".equalsIgnoreCase(name) || "clearDNSCache".equalsIgnoreCase(name)
                || "closeNeighborMonitor".equalsIgnoreCase(name) || "startNeighborMonitor".equalsIgnoreCase(name)
                || "registerMyInterface".equalsIgnoreCase(name)) return defaultValue(method.getReturnType());
        return defaultValue(method.getReturnType());
    }

    private Object handleHandlerCall(Method method, Object[] args) {
        String name = method.getName();
        if ("serviceStop".equalsIgnoreCase(name)) {
            final long stopGeneration;
            synchronized (this) {
                stopGeneration = ++lifecycleGeneration;
                starting = false;
            }
            new Thread(() -> {
                synchronized (coreLock) {
                    if (isCurrentGeneration(stopGeneration)) stopEngineLocked();
                }
            }, "Abolix-Core-Stop").start();
        } else if ("serviceReload".equalsIgnoreCase(name)) {
            new Thread(this::reloadActiveConfig, "Abolix-Core-Reload").start();
        } else if ("getSystemProxyStatus".equalsIgnoreCase(name)) {
            return createSystemProxyStatus();
        } else if ("connectSSHAgent".equalsIgnoreCase(name)) {
            return -1;
        } else if ("writeDebugMessage".equalsIgnoreCase(name) && args != null && args.length > 0) {
            Log.d(TAG, String.valueOf(args[0]));
        }
        return defaultValue(method.getReturnType());
    }

    private int openTun(Object options) throws Exception {
        if (Build.VERSION.SDK_INT < 21) throw new IllegalStateException("Android 5 یا بالاتر لازم است");
        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession("Abolix · " + currentNode).setMtu(readInt(options, "Mtu", 1400));
        if (Build.VERSION.SDK_INT >= 29) {
            boolean metered = true;
            android.net.ConnectivityManager connectivity =
                    (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.Network activeNetwork = connectivity == null ? null : connectivity.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = activeNetwork == null || connectivity == null
                    ? null : connectivity.getNetworkCapabilities(activeNetwork);
            if (capabilities != null) {
                metered = !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
            builder.setMetered(metered);
        }

        Object inet4Addresses = readProperty(options, "Inet4Address");
        Object inet6Addresses = readProperty(options, "Inet6Address");
        boolean hasInet4 = iteratorHasNext(inet4Addresses);
        boolean hasInet6 = iteratorHasNext(inet6Addresses);
        addPrefixIterator(builder, inet4Addresses, true);
        addPrefixIterator(builder, inet6Addresses, true);

        Object dnsIterator = readProperty(options, "DnsServerAddress");
        boolean hasDns = addDnsIterator(builder, dnsIterator);
        if (!hasDns && readBoolean(options, "AutoRoute", true)) builder.addDnsServer("1.1.1.1");

        boolean autoRoute = readBoolean(options, "AutoRoute", true);
        if (autoRoute) {
            Object inet4Routes = firstAvailableIterator(options, "Inet4RouteAddress", "Inet4RouteRange");
            Object inet6Routes = firstAvailableIterator(options, "Inet6RouteAddress", "Inet6RouteRange");
            int routeCount4 = addPrefixIterator(builder, inet4Routes, false);
            int routeCount6 = addPrefixIterator(builder, inet6Routes, false);
            // Some sing-box configurations expose only route prefixes on API 33+ when
            // route calculation has no explicit ranges; use the address family as fallback.
            if (routeCount4 == 0 && hasInet4) builder.addRoute("0.0.0.0", 0);
            if (routeCount6 == 0 && hasInet6) builder.addRoute("::", 0);
            if (Build.VERSION.SDK_INT >= 33) {
                addExcludedRoutes(builder, readProperty(options, "Inet4RouteExcludeAddress"));
                addExcludedRoutes(builder, readProperty(options, "Inet6RouteExcludeAddress"));
            }
            addPackages(builder, readProperty(options, "IncludePackage"), true);
            addPackages(builder, readProperty(options, "ExcludePackage"), false);
        }
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("سیستم اجازه ساخت تونل VPN را نداد");
        closeTunDescriptor();
        tunDescriptor = descriptor;
        return descriptor.getFd();
    }

    private int addPrefixIterator(VpnService.Builder builder, Object iterator, boolean addAddress) throws Exception {
        if (iterator == null) return 0;
        int count = 0;
        while (booleanCall(iterator, "hasNext")) {
            Object prefix = call(iterator, "next");
            if (prefix == null) continue;
            String address = String.valueOf(call(prefix, "address"));
            int bits = ((Number) call(prefix, "prefix")).intValue();
            if (addAddress) builder.addAddress(address, bits);
            else builder.addRoute(address, bits);
            count++;
        }
        return count;
    }

    private boolean addDnsIterator(VpnService.Builder builder, Object iterator) throws Exception {
        if (iterator == null) return false;
        boolean added = false;
        while (booleanCall(iterator, "hasNext")) {
            Object address = call(iterator, "next");
            if (address != null && !String.valueOf(address).isEmpty()) {
                builder.addDnsServer(String.valueOf(address));
                added = true;
            }
        }
        return added;
    }

    private void addExcludedRoutes(VpnService.Builder builder, Object iterator) throws Exception {
        if (iterator == null) return;
        while (booleanCall(iterator, "hasNext")) {
            Object prefix = call(iterator, "next");
            if (prefix == null) continue;
            String address = String.valueOf(call(prefix, "address"));
            int bits = ((Number) call(prefix, "prefix")).intValue();
            if (Build.VERSION.SDK_INT >= 33) builder.excludeRoute(new android.net.IpPrefix(address, bits));
        }
    }

    private Object firstAvailableIterator(Object options, String primary, String fallback) {
        Object first = readProperty(options, primary);
        if (iteratorHasNext(first)) return first;
        Object alternate = readProperty(options, fallback);
        return alternate != null ? alternate : first;
    }

    private boolean iteratorHasNext(Object iterator) {
        if (iterator == null) return false;
        try { return booleanCall(iterator, "hasNext"); } catch (Exception ignored) { return false; }
    }

    private void addPackages(VpnService.Builder builder, Object iterator, boolean allowed) throws Exception {
        for (String packageName : readStringIterator(iterator)) {
            if (packageName.isEmpty()) continue;
            try {
                if (allowed) builder.addAllowedApplication(packageName);
                else builder.addDisallowedApplication(packageName);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                Log.w(TAG, "Skipped missing package in VPN profile: " + packageName);
            }
        }
    }

    private List<String> readStringIterator(Object iterator) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        if (iterator == null) return values;
        while (booleanCall(iterator, "hasNext")) {
            Object next = call(iterator, "next");
            if (next != null) values.add(String.valueOf(next));
        }
        return values;
    }

    private Object readProperty(Object target, String property) {
        if (target == null) return null;
        String expected = "get" + property;
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getName().equalsIgnoreCase(expected)) {
                try { return method.invoke(target); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private String stringProperty(Object target, String property) {
        Object value = readProperty(target, property);
        return value == null ? "" : String.valueOf(value);
    }

    private int readInt(Object target, String property, int fallback) {
        Object value = readProperty(target, property);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private boolean readBoolean(Object target, String property, boolean fallback) {
        Object value = readProperty(target, property);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }
    private static boolean booleanCall(Object object, String method) throws Exception {
        Object value = call(object, method);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Constructor<?> findTwoArgConstructor(Class<?> type, Class<?> first, Class<?> second) throws Exception {
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 2 && params[0].isAssignableFrom(first) && params[1].isAssignableFrom(second)) return constructor;
            if (params.length == 2 && params[1].isAssignableFrom(first) && params[0].isAssignableFrom(second)) {
                throw new IllegalStateException("ترتیب پارامترهای CommandServer با API شناخته‌شده متفاوت است");
            }
        }
        throw new NoSuchMethodException("CommandServer(handler, platform) constructor not found");
    }

    private static Method findMethod(Class<?> type, String name, int params) throws Exception {
        for (Method method : type.getMethods()) if (method.getName().equals(name) && method.getParameterTypes().length == params) return method;
        throw new NoSuchMethodException(name);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> first, Class<?> second) throws Exception {
        for (Method method : type.getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals(name) && p.length == 2 && p[0].isAssignableFrom(first) && p[1].isAssignableFrom(second)) return method;
        }
        throw new NoSuchMethodException(name + "(String, OverrideOptions)");
    }

    private static void invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        Object result = method.invoke(target);
        if (result instanceof String && !((String) result).trim().isEmpty()) throw new IllegalStateException((String) result);
    }

    private static void setProperty(Object target, String property, Object value) {
        String setter = "set" + property;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(setter) || method.getParameterTypes().length != 1) continue;
            try {
                Class<?> parameter = method.getParameterTypes()[0];
                Object converted = value;
                if (value instanceof Integer && (parameter == long.class || parameter == Long.class)) converted = ((Integer) value).longValue();
                method.invoke(target, converted);
                return;
            } catch (Exception ignored) { }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private void closeEngineCore() {
        running = false;
        closeDefaultInterfaceMonitor();
        defaultInterfaceListener = null;
        Object server = commandServer;
        commandServer = null;
        activeConfig = "";
        if (server != null) {
            try { invokeNoArg(server, "closeService"); } catch (Exception e) { Log.w(TAG, "closeService", e); }
            try { invokeNoArg(server, "close"); } catch (Exception e) { Log.w(TAG, "close", e); }
        }
        closeTunDescriptor();
    }

    private void stopEngine() {
        synchronized (coreLock) { stopEngineLocked(); }
    }

    private void stopEngineLocked() {
        closeEngineCore();
        broadcastState(false, "قطع شد");
        stopForeground(true);
    }

    private void closeTunDescriptor() {
        ParcelFileDescriptor descriptor = tunDescriptor;
        tunDescriptor = null;
        if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
    }

    private void stopWithError(String message) {
        Log.e(TAG, message);
        stopEngine();
        broadcastState(false, message);
        stopSelf();
    }

    private void broadcastState(boolean connected, String message) {
        android.content.SharedPreferences prefs = getSharedPreferences("abolix_secure_store", MODE_PRIVATE);
        prefs.edit().putBoolean("vpn_connected", connected).putString("vpn_status", message).apply();
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra("connected", connected).putExtra("message", message);
        sendBroadcast(state);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "اتصال Abolix", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("وضعیت اتصال امن Abolix");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat(String message) {
        Notification notification = makeNotification(message);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, makeNotification(message));
    }

    private Notification makeNotification(String message) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_abolix)
                .setContentTitle("Abolix VPN")
                .setContentText(message)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return builder.build();
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

}
