package com.abolix.gamebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
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
    private static final String ERROR_CHANNEL = "abolix_errors";
    private static final int NOTIFICATION_ID = 4107;
    private static final int ERROR_NOTIFICATION_ID = 4108;
    private static volatile AbolixVpnService instance;
    private static volatile boolean libboxInitialized;
    private static final Object LIBBOX_SETUP_LOCK = new Object();
    private final Object coreLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable startupWatchdog;
    private long startupWatchdogGeneration = -1L;
    private long lifecycleGeneration;
    private boolean starting;

    private volatile Object commandServer;
    private volatile ParcelFileDescriptor tunDescriptor;
    private volatile boolean running;
    private volatile String activeConfig = "";
    private volatile Object defaultInterfaceListener;
    private volatile android.net.ConnectivityManager.NetworkCallback defaultNetworkCallback;
    private volatile android.net.Network selectedUnderlyingNetwork;
    private volatile String tunInterfaceName = "";
    private volatile String startupStage = "آماده‌سازی سرویس";
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
        cancelStartupWatchdog();
        synchronized (this) {
            lifecycleGeneration++;
            starting = false;
        }
        stopEngine();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        cancelStartupWatchdog();
        synchronized (this) {
            lifecycleGeneration++;
            starting = false;
        }
        // State is written by the explicit stop/revoke/error path. Do not emit a
        // generic "disconnected" event here: that used to overwrite the useful
        // startup error immediately after stopSelf() was called.
        synchronized (coreLock) {
            closeEngineCore();
            stopForeground(true);
        }
        defaultInterfaceListener = null;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            cancelStartupWatchdog();
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
        clearPreviousFailure();
        try {
            // Promote the service immediately: Android 8+ may terminate a service
            // started with startForegroundService() if this call is delayed.
            startForegroundCompat("در حال اتصال · " + requestedNode);
        } catch (Throwable foregroundError) {
            synchronized (this) {
                if (lifecycleGeneration == startGeneration) {
                    lifecycleGeneration++;
                    starting = false;
                }
            }
            String message = "اندروید اجازه‌ی اجرای سرویس VPN در پیش‌زمینه را نداد: " + safeMessage(foregroundError);
            Log.e(TAG, message, foregroundError);
            stopWithError(message);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        broadcastConnectingStatus("در حال اتصال به " + requestedNode + "…");
        armStartupWatchdog(startGeneration);
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
                cancelStartupWatchdog(startGeneration);
                clearPreviousFailure();
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
                cancelStartupWatchdog(startGeneration);
            }
        }, "Abolix-Core-Start").start();
        return START_NOT_STICKY;
    }

    private void armStartupWatchdog(final long generation) {
        cancelStartupWatchdog();
        startupWatchdogGeneration = generation;
        startupWatchdog = () -> {
            boolean expired;
            synchronized (AbolixVpnService.this) {
                expired = lifecycleGeneration == generation && starting;
                if (expired) {
                    lifecycleGeneration++;
                    starting = false;
                }
            }
            if (expired) {
                startupWatchdog = null;
                startupWatchdogGeneration = -1L;
                String message = "راه‌اندازی بیش از 45 ثانیه در این مرحله متوقف ماند: " + startupStage
                        + ". اتصال کامل نشد؛ گزارش Abolix را بررسی کن.";
                new Thread(() -> stopWithError(message), "Abolix-Core-Timeout-Cleanup").start();
            }
        };
        mainHandler.postDelayed(startupWatchdog, 45000L);
    }

    private void cancelStartupWatchdog() {
        Runnable pending = startupWatchdog;
        startupWatchdog = null;
        startupWatchdogGeneration = -1L;
        if (pending != null) mainHandler.removeCallbacks(pending);
    }

    private void cancelStartupWatchdog(long generation) {
        if (startupWatchdogGeneration != generation) return;
        cancelStartupWatchdog();
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (this) { return lifecycleGeneration == generation; }
    }

    private void startEngine(String config) throws Exception {
        setStartupStage("بستن اتصال قبلی");
        closeEngineCore();
        setStartupStage("بارگذاری کتابخانه sing-box");
        Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
        Class<?> setupType = Class.forName("io.nekohasekai.libbox.SetupOptions");
        if (!libboxInitialized) {
            setStartupStage("راه‌اندازی اولیه libbox");
            synchronized (LIBBOX_SETUP_LOCK) {
                if (!libboxInitialized) {
                    java.io.File baseDir = new java.io.File(getFilesDir(), "libbox");
                    java.io.File workingDir = new java.io.File(getFilesDir(), "sing-box");
                    java.io.File tempDir = new java.io.File(getCacheDir(), "sing-box");
                    if ((!baseDir.isDirectory() && !baseDir.mkdirs())
                            || (!workingDir.isDirectory() && !workingDir.mkdirs())
                            || (!tempDir.isDirectory() && !tempDir.mkdirs())) {
                        throw new java.io.IOException("ساخت پوشه‌های کاری هسته ناموفق بود");
                    }
                    Object setup = setupType.getDeclaredConstructor().newInstance();
                    setPropertyRequired(setup, "BasePath", baseDir.getAbsolutePath());
                    setPropertyRequired(setup, "WorkingPath", workingDir.getAbsolutePath());
                    setPropertyRequired(setup, "TempPath", tempDir.getAbsolutePath());
                    setPropertyRequired(setup, "FixAndroidStack", true);
                    setPropertyRequired(setup, "Debug", BuildConfig.DEBUG);
                    setPropertyRequired(setup, "LogMaxLines", 1000);
                    setPropertyRequired(setup, "AppVersion", String.valueOf(BuildConfig.VERSION_CODE));
                    setPropertyRequired(setup, "AppMarketingVersion", BuildConfig.VERSION_NAME);
                    findMethod(libbox, "setup", 1).invoke(null, setup);
                    // These draft hooks are present in libbox 1.14.x and used by its Android client.
                    invokeStaticIfPresent(libbox, "promoteOOMDraft");
                    invokeStaticIfPresent(libbox, "discardPowerReportDraft");
                    libboxInitialized = true;
                }
            }
        }

        setStartupStage("بررسی ساختار کانفیگ با sing-box");
        validateLibboxConfig(libbox, config);

        setStartupStage("آماده‌سازی رابط اندروید");
        Class<?> platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface");
        Object platform = Proxy.newProxyInstance(platformType.getClassLoader(), new Class<?>[]{platformType},
                (proxy, method, args) -> handlePlatformCall(method, args));

        Class<?> handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType},
                (proxy, method, args) -> handleHandlerCall(method, args));
        Class<?> serverType = Class.forName("io.nekohasekai.libbox.CommandServer");
        Constructor<?> constructor = findTwoArgConstructor(serverType, handlerType, platformType);
        setStartupStage("ساخت CommandServer");
        commandServer = constructor.newInstance(handler, platform);
        setStartupStage("شروع CommandServer");
        invokeNoArg(commandServer, "start");
        activeConfig = config;

        Class<?> overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions");
        Object override = overrideType.getDeclaredConstructor().newInstance();
        Method reload = findCompatibleMethod(serverType, "startOrReloadService", String.class, overrideType);
        setStartupStage("بارگذاری سرویس و ساخت رابط VPN");
        Object result;
        try {
            result = reload.invoke(commandServer, config, override);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("sing-box نتوانست سرویس را بارگذاری کند: " + safeMessage(cause), cause);
        }
        if (result instanceof String && !((String) result).trim().isEmpty()) {
            throw new IllegalStateException((String) result);
        }
        if (tunDescriptor == null) {
            throw new IllegalStateException("هسته فرمان شروع را پذیرفت، اما رابط VPN اندروید (TUN) ساخته نشد. لاگ openTun را بررسی کن.");
        }
        startupStage = "اتصال برقرار شد";
        Log.i(TAG, "sing-box service started and Android TUN is ready");
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


    private void startDefaultInterfaceMonitor(Object listener) throws Exception {
        defaultInterfaceListener = listener;
        closeDefaultInterfaceMonitor();
        defaultInterfaceListener = listener;
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) throw new IllegalStateException("ConnectivityManager در دسترس نیست");
        android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        android.net.ConnectivityManager.NetworkCallback callback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network network) { notifyDefaultInterfaceCurrent(); }
            @Override public void onLost(android.net.Network network) { notifyDefaultInterfaceCurrent(); }
            @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities capabilities) {
                notifyDefaultInterfaceCurrent();
            }
            @Override public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties properties) {
                notifyDefaultInterfaceCurrent();
            }
        };
        connectivity.registerNetworkCallback(request, callback);
        defaultNetworkCallback = callback;
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

    private android.net.Network findPhysicalNetwork(android.net.ConnectivityManager connectivity) {
        android.net.Network active = connectivity.getActiveNetwork();
        android.net.NetworkCapabilities activeCaps = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (isPhysicalInternetNetwork(activeCaps)) return active;

        android.net.Network best = null;
        int bestScore = Integer.MIN_VALUE;
        android.net.Network[] all = connectivity.getAllNetworks();
        for (android.net.Network network : all) {
            android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (!isPhysicalInternetNetwork(caps)) continue;
            int score = 0;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) score += 30;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) score += 20;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10;
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 5;
            if (score > bestScore) { best = network; bestScore = score; }
        }
        return best;
    }

    private boolean isPhysicalInternetNetwork(android.net.NetworkCapabilities caps) {
        return caps != null
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
    }

    private void notifyDefaultInterfaceCurrent() {
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.Network physical = connectivity == null ? null : findPhysicalNetwork(connectivity);
        if (physical != selectedUnderlyingNetwork) {
            selectedUnderlyingNetwork = physical;
            try { setUnderlyingNetworks(physical == null ? new android.net.Network[0] : new android.net.Network[]{physical}); }
            catch (RuntimeException error) { Log.w(TAG, "Could not set underlying physical network", error); }
        }
        notifyDefaultInterface(physical);
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
                if ("updateDefaultInterface".equalsIgnoreCase(method.getName()) && method.getParameterTypes().length == 4) {
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
            setPropertyRequired(boxInterface, "Name", interfaceName);

            java.util.ArrayList<String> dnsServers = new java.util.ArrayList<>();
            for (java.net.InetAddress address : link.getDnsServers()) {
                if (address != null && address.getHostAddress() != null) dnsServers.add(address.getHostAddress());
            }
            setProperty(boxInterface, "DNSServer", iteratorProxy("io.nekohasekai.libbox.StringIterator", dnsServers));

            java.util.ArrayList<String> searchDomains = new java.util.ArrayList<>();
            String domains = link.getDomains();
            if (domains != null) {
                for (String domain : domains.split("[,\\s]+")) if (!domain.isEmpty()) searchDomains.add(domain);
            }
            setProperty(boxInterface, "DNSSearchDomain", iteratorProxy("io.nekohasekai.libbox.StringIterator", searchDomains));

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
            setPropertyRequired(boxInterface, "Addresses", iteratorProxy("io.nekohasekai.libbox.StringIterator", addresses));

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
            setPropertyRequired(boxInterface, "Index", systemInterface.getIndex());
            try { setProperty(boxInterface, "MTU", systemInterface.getMTU()); }
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

    private Object findConnectionOwner(Object[] args) throws Exception {
        if (args == null || args.length < 5) throw new IllegalArgumentException("پارامترهای FindConnectionOwner ناقص است");
        if (Build.VERSION.SDK_INT < 29) return null;
        android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return null;
        int protocol = ((Number) args[0]).intValue();
        String sourceAddress = String.valueOf(args[1]);
        int sourcePort = ((Number) args[2]).intValue();
        String destinationAddress = String.valueOf(args[3]);
        int destinationPort = ((Number) args[4]).intValue();
        java.net.InetSocketAddress local = new java.net.InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort);
        java.net.InetSocketAddress remote = new java.net.InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort);
        int uid = connectivity.getConnectionOwnerUid(protocol, local, remote);
        if (uid < 0) return null;

        Class<?> ownerType = Class.forName("io.nekohasekai.libbox.ConnectionOwner");
        Object owner = ownerType.getDeclaredConstructor().newInstance();
        setProperty(owner, "UserId", uid);
        String userName = getPackageManager().getNameForUid(uid);
        if (userName != null) setProperty(owner, "UserName", userName);
        String[] packageNames = getPackageManager().getPackagesForUid(uid);
        ArrayList<String> packageList = new ArrayList<>();
        if (packageNames != null) java.util.Collections.addAll(packageList, packageNames);
        Class<?> iteratorType = Class.forName("io.nekohasekai.libbox.StringIterator");
        Object iterator = iteratorProxy("io.nekohasekai.libbox.StringIterator", packageList);
        Method setter = null;
        for (Method method : ownerType.getMethods()) {
            if ("setAndroidPackageNames".equalsIgnoreCase(method.getName())
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isAssignableFrom(iteratorType)) {
                setter = method;
                break;
            }
        }
        if (setter == null) throw new NoSuchMethodException("ConnectionOwner.setAndroidPackageNames(StringIterator)");
        setter.invoke(owner, iterator);
        return owner;
    }

    private static void validateLibboxConfig(Class<?> libbox, String config) throws Exception {
        // FormatConfig parses and normalizes JSON without constructing a second proxy engine.
        // CheckConfig constructs a temporary box instance; doing that before the real Android
        // PlatformInterface is attached can fail for TUN inbounds and needlessly initializes
        // native services twice. The real startOrReloadService below performs full validation.
        Method formatter = findMethod(libbox, "formatConfig", 1);
        try {
            Object formatted = formatter.invoke(null, config);
            if (formatted == null) throw new IllegalArgumentException("sing-box پیکربندی را نرمال‌سازی نکرد");
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalArgumentException("ساختار کانفیگ توسط sing-box رد شد: " + safeMessage(cause), cause);
        }
    }

    private static void invokeStaticIfPresent(Class<?> type, String name) {
        try { type.getMethod(name).invoke(null); }
        catch (NoSuchMethodException ignored) { }
        catch (Exception error) { Log.w(TAG, "Optional libbox hook failed: " + name, error); }
    }

    private void setStartupStage(String stage) {
        startupStage = stage;
        Log.i(TAG, "Startup: " + stage);
        updateNotification("راه‌اندازی هسته · " + stage);
        broadcastConnectingStatus("راه‌اندازی هسته: " + stage);
    }

    private static void setPropertyRequired(Object target, String property, Object value) throws Exception {
        String setter = "set" + property;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equalsIgnoreCase(setter) || method.getParameterTypes().length != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            Object converted = value;
            if (value instanceof Number) {
                Number number = (Number) value;
                if (parameter == long.class || parameter == Long.class) converted = number.longValue();
                else if (parameter == int.class || parameter == Integer.class) converted = number.intValue();
                else if (parameter == short.class || parameter == Short.class) converted = number.shortValue();
                else if (parameter == byte.class || parameter == Byte.class) converted = number.byteValue();
            }
            try { method.invoke(target, converted); return; }
            catch (java.lang.reflect.InvocationTargetException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IllegalStateException("تنظیم " + property + " در libbox ناموفق بود: " + safeMessage(cause), cause);
            }
        }
        throw new NoSuchMethodException("Libbox setter پیدا نشد: " + setter + " در " + target.getClass().getName());
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
        if ("getInterfaces".equalsIgnoreCase(name)) return loadNetworkInterfaces();
        if ("findConnectionOwner".equalsIgnoreCase(name)) return findConnectionOwner(args);
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
        if ("registerMyInterface".equalsIgnoreCase(name)) {
            tunInterfaceName = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
            Log.i(TAG, "Registered sing-box TUN interface: " + tunInterfaceName);
            return defaultValue(method.getReturnType());
        }
        if ("cancelNotification".equalsIgnoreCase(name) || "clearDNSCache".equalsIgnoreCase(name)
                || "closeNeighborMonitor".equalsIgnoreCase(name) || "startNeighborMonitor".equalsIgnoreCase(name)) {
            return defaultValue(method.getReturnType());
        }
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
        } else if ("setSystemProxyEnabled".equalsIgnoreCase(name)) {
            // Android's VPN TUN already owns routing; the separate system HTTP proxy is disabled.
            return null;
        } else if ("triggerNativeCrash".equalsIgnoreCase(name)) {
            Log.w(TAG, "libbox requested a native crash trigger; ignored by the production client");
            return null;
        } else if ("connectSSHAgent".equalsIgnoreCase(name)) {
            return -1;
        } else if ("writeDebugMessage".equalsIgnoreCase(name) && args != null && args.length > 0) {
            Log.d(TAG, String.valueOf(args[0]));
        }
        return defaultValue(method.getReturnType());
    }

    private int openTun(Object options) throws Exception {
        setStartupStage("ساخت رابط VPN اندروید");
        Log.i(TAG, "openTun callback received from libbox");
        if (Build.VERSION.SDK_INT < 21) throw new IllegalStateException("Android 5 یا بالاتر لازم است");
        if (prepare(this) != null) throw new SecurityException("مجوز VPN صادر نشده است؛ دوباره روی اتصال بزن و اجازه VPN را تأیید کن.");

        VpnService.Builder builder = new VpnService.Builder();
        int mtu = readInt(options, "Mtu", 1400);
        if (mtu < 576 || mtu > 9000) mtu = 1400;
        builder.setSession("Abolix · " + currentNode).setMtu(mtu);
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);

        Object inet4Addresses = readProperty(options, "Inet4Address");
        Object inet6Addresses = readProperty(options, "Inet6Address");
        boolean hasInet4 = iteratorHasNext(inet4Addresses);
        boolean hasInet6 = iteratorHasNext(inet6Addresses);
        int addressCount = addPrefixIterator(builder, inet4Addresses, true)
                + addPrefixIterator(builder, inet6Addresses, true);
        if (addressCount == 0) {
            throw new IllegalStateException("هسته هیچ آدرس IPv4/IPv6 برای رابط VPN نداد؛ تنظیمات TUN را بررسی کن.");
        }

        boolean autoRoute = readBoolean(options, "AutoRoute", true);
        String dnsMode = unwrapStringBox(readProperty(options, "DnsMode"));
        String disabledDnsMode = readLibboxStringConstant("DNSModeDisabled", "disabled");
        boolean dnsEnabled = !dnsMode.equalsIgnoreCase(disabledDnsMode)
                && !"disabled".equalsIgnoreCase(dnsMode)
                && (autoRoute || !dnsMode.isEmpty());

        if (dnsEnabled) {
            Object dnsIterator = readProperty(options, "DnsServerAddress");
            boolean hasDns = addDnsIterator(builder, dnsIterator, !autoRoute);
            if (!hasDns && autoRoute) {
                Log.w(TAG, "sing-box enabled DNS but provided no DNS server addresses");
            }
        }

        if (autoRoute) {
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+: sing-box provides calculated RouteAddress and RouteExcludeAddress prefixes.
                int routeCount4 = addPrefixIterator(builder, readProperty(options, "Inet4RouteAddress"), false);
                int routeCount6 = addPrefixIterator(builder, readProperty(options, "Inet6RouteAddress"), false);
                if (routeCount4 == 0 && hasInet4) builder.addRoute("0.0.0.0", 0);
                if (routeCount6 == 0 && hasInet6) builder.addRoute("::", 0);
                addExcludedRoutes(builder, readProperty(options, "Inet4RouteExcludeAddress"));
                addExcludedRoutes(builder, readProperty(options, "Inet6RouteExcludeAddress"));
            } else {
                // Before Android 13, use the route ranges generated by sing-box.
                addPrefixIterator(builder, readProperty(options, "Inet4RouteRange"), false);
                addPrefixIterator(builder, readProperty(options, "Inet6RouteRange"), false);
            }
            addPackages(builder, readProperty(options, "IncludePackage"), true);
            addPackages(builder, readProperty(options, "ExcludePackage"), false);
        }

        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) throw new IllegalStateException("سیستم تونل VPN را نساخت. مجوز VPN، محدودیت باتری یا تنظیمات امنیتی گوشی را بررسی کن.");
        closeTunDescriptor();
        tunDescriptor = descriptor;
        Log.i(TAG, "Android TUN established (fd=" + descriptor.getFd() + ", mtu=" + mtu
                + ", autoRoute=" + autoRoute + ", dnsMode=" + dnsMode + ")");
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

    private boolean addDnsIterator(VpnService.Builder builder, Object iterator, boolean addHostRoute) throws Exception {
        if (iterator == null) return false;
        boolean added = false;
        while (booleanCall(iterator, "hasNext")) {
            Object address = call(iterator, "next");
            if (address == null) continue;
            String value = String.valueOf(address).trim();
            if (value.isEmpty()) continue;
            builder.addDnsServer(value);
            if (addHostRoute) builder.addRoute(value, value.contains(":") ? 128 : 32);
            added = true;
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
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ exposes IpPrefix(InetAddress, int), not IpPrefix(String, int).
                InetAddress inetAddress = InetAddress.getByName(address);
                builder.excludeRoute(new android.net.IpPrefix(inetAddress, bits));
            }
        }
    }

    private boolean iteratorHasNext(Object iterator) throws Exception {
        return iterator != null && booleanCall(iterator, "hasNext");
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

    private Object readProperty(Object target, String property) throws Exception {
        if (target == null) return null;
        String expected = "get" + property;
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || !method.getName().equalsIgnoreCase(expected)) continue;
            try {
                return method.invoke(target);
            } catch (java.lang.reflect.InvocationTargetException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IllegalStateException("خواندن " + property + " از libbox ناموفق بود: " + safeMessage(cause), cause);
            }
        }
        Log.w(TAG, "Optional libbox getter not found: " + expected + " on " + target.getClass().getName());
        return null;
    }

    private String stringProperty(Object target, String property) throws Exception {
        Object value = readProperty(target, property);
        return value == null ? "" : String.valueOf(value);
    }

    private int readInt(Object target, String property, int fallback) throws Exception {
        Object value = readProperty(target, property);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private boolean readBoolean(Object target, String property, boolean fallback) throws Exception {
        Object value = readProperty(target, property);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private String unwrapStringBox(Object box) throws Exception {
        if (box == null) return "";
        Object value = readProperty(box, "Value");
        if (value instanceof String) return ((String) value).trim();
        // Go mobile's StringBox exposes its value as a field in some generated bindings.
        try {
            java.lang.reflect.Field field;
            try { field = box.getClass().getField("value"); }
            catch (NoSuchFieldException missingPublicField) {
                field = box.getClass().getDeclaredField("value");
                field.setAccessible(true);
            }
            Object fieldValue = field.get(box);
            if (fieldValue instanceof String) return ((String) fieldValue).trim();
        } catch (NoSuchFieldException ignored) { }
        String rendered = String.valueOf(box);
        return rendered == null ? "" : rendered.trim();
    }

    private static String readLibboxStringConstant(String fieldName, String fallback) {
        try {
            Class<?> libbox = Class.forName("io.nekohasekai.libbox.Libbox");
            Object value = libbox.getField(fieldName).get(null);
            return value == null ? fallback : String.valueOf(value);
        } catch (Exception ignored) { return fallback; }
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
            if (!method.getName().equalsIgnoreCase(setter) || method.getParameterTypes().length != 1) continue;
            try {
                Class<?> parameter = method.getParameterTypes()[0];
                Object converted = value;
                if (value instanceof Number) {
                    Number number = (Number) value;
                    if (parameter == long.class || parameter == Long.class) converted = number.longValue();
                    else if (parameter == int.class || parameter == Integer.class) converted = number.intValue();
                    else if (parameter == short.class || parameter == Short.class) converted = number.shortValue();
                    else if (parameter == byte.class || parameter == Byte.class) converted = number.byteValue();
                }
                method.invoke(target, converted);
                return;
            } catch (Exception error) {
                Log.w(TAG, "Could not set libbox property " + property, error);
            }
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
        tunInterfaceName = "";
        Object server = commandServer;
        commandServer = null;
        activeConfig = "";
        // Release Android's TUN fd before asking libbox to tear down the service,
        // matching the official Android client's lifecycle and avoiding a stale TUN fd.
        closeTunDescriptor();
        if (server != null) {
            try { invokeNoArg(server, "closeService"); } catch (Exception e) { Log.w(TAG, "closeService", e); }
            try { invokeNoArg(server, "close"); } catch (Exception e) { Log.w(TAG, "close", e); }
        }
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
        cancelStartupWatchdog();
        synchronized (coreLock) {
            closeEngineCore();
            stopForeground(true);
        }
        synchronized (this) { starting = false; }

        AbolixDiagnostics.Issue issue = AbolixDiagnostics.classify(message);
        String displayMessage = issue.code + " · " + message;
        String report = AbolixDiagnostics.formatReport(this, startupStage, message);
        android.content.SharedPreferences prefs = getSharedPreferences("abolix_secure_store", MODE_PRIVATE);
        prefs.edit()
                .putString("last_vpn_error_code", issue.code)
                .putString("last_vpn_error_message", message)
                .putString("last_vpn_error_report", report)
                .putString("last_vpn_error_id", String.valueOf(System.currentTimeMillis()))
                .putBoolean("last_vpn_error_pending", true)
                .apply();

        broadcastState(false, displayMessage);
        postErrorNotification(issue);
        stopSelf();
    }

    private void clearPreviousFailure() {
        getSharedPreferences("abolix_secure_store", MODE_PRIVATE).edit()
                .putBoolean("last_vpn_error_pending", false).apply();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ERROR_NOTIFICATION_ID);
    }

    private void postErrorNotification(AbolixDiagnostics.Issue issue) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(ERROR_CHANNEL,
                        "خطاهای اتصال Abolix", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("هشدارهای خطای اتصال و گزارش تشخیصی Abolix");
                channel.enableVibration(true);
                getSystemService(NotificationManager.class).createNotificationChannel(channel);
            }
            Intent launch = new Intent(this, MainActivity.class);
            launch.setAction("com.abolix.gamebooster.action.SHOW_ERROR_REPORT");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(this, ERROR_NOTIFICATION_ID, launch,
                    Build.VERSION.SDK_INT >= 23
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ERROR_CHANNEL) : new Notification.Builder(this);
            builder.setSmallIcon(R.drawable.ic_stat_abolix)
                    .setContentTitle("Abolix · اتصال ناموفق")
                    .setContentText("برای رفع مشکل در به‌روزرسانی بعدی، گزارش را برای سازنده بفرستید.")
                    .setStyle(new Notification.BigTextStyle().bigText(issue.code + " · " + issue.title
                            + "\nبرای کمک به رفع مشکل در به‌روزرسانی بعدی، گزارش تشخیصی را برای سازنده Abolix بفرستید."))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setOnlyAlertOnce(false);
            if (Build.VERSION.SDK_INT < 26) builder.setPriority(Notification.PRIORITY_HIGH);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(ERROR_NOTIFICATION_ID, builder.build());
        } catch (SecurityException denied) {
            Log.w(TAG, "Notification permission denied; in-app error dialog will remain available", denied);
        } catch (RuntimeException notificationError) {
            Log.w(TAG, "Could not display connection error notification", notificationError);
        }
    }

    private void broadcastState(boolean connected, String message) {
        broadcastStatus(connected, false, message);
    }

    private void broadcastConnectingStatus(String message) {
        broadcastStatus(false, true, message);
    }

    private void broadcastStatus(boolean connected, boolean connecting, String message) {
        android.content.SharedPreferences prefs = getSharedPreferences("abolix_secure_store", MODE_PRIVATE);
        prefs.edit()
                .putBoolean("vpn_connected", connected)
                .putBoolean("vpn_connecting", connecting)
                .putString("vpn_status", message == null ? "" : message)
                .apply();
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra("connected", connected)
                .putExtra("connecting", connecting)
                .putExtra("message", message);
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
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
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
