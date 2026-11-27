package com.abolix.gamebooster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A compact, multi-page Persian UI for Abolix VPN. */
public final class MainActivity extends Activity {
    private static final int REQUEST_VPN = 7201;
    private static final int REQUEST_NOTIFICATIONS = 7202;
    private static final String PREFS = "abolix_secure_store";
    private static final int[] ACCENTS = {0xff55e6c1, 0xff8c7cff, 0xffffb86b, 0xffff6b9a, 0xff54b9ff, 0xffb6e36b};
    private static final String[] APP_PACKAGES = {
            "com.instagram.android", "com.google.android.youtube", "org.telegram.messenger",
            "com.discord", "com.facebook.katana"
    };
    private static final String[] APP_LABELS = {"Instagram", "YouTube", "Telegram", "Discord", "Facebook"};
    private static final String[] CHECK_HOSTS = {"YouTube", "Telegram", "Discord", "Facebook"};
    private static final String[] CHECK_DOMAINS = {"www.youtube.com", "api.telegram.org", "discord.com", "www.facebook.com"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "Abolix-Network-Worker");
        thread.setDaemon(true);
        return thread;
    });
    private LinearLayout root;
    private LinearLayout page;
    private LinearLayout bottomBar;
    private TextView headerStatus;
    private TextView selectedNodeText;
    private TextView connectionState;
    private TextView downSpeedText;
    private TextView upSpeedText;
    private TextView totalDownText;
    private TextView totalUpText;
    private TextView exitIpText;
    private TextView servicePingText;
    private TextView pingText;
    private AbolixGraphView graph;
    private EditText subscriptionUrlField;
    private String currentSection = "home";
    private boolean connected;
    private boolean pendingConnect;
    private boolean receiverRegistered;
    private boolean refreshInFlight;
    private long lastAutoRefreshAttempt;
    private long lastRx = TrafficStats.getUidRxBytes(Process.myUid());
    private long lastTx = TrafficStats.getUidTxBytes(Process.myUid());
    private long lastTrafficSample = System.currentTimeMillis();
    private final Runnable trafficRunnable = new Runnable() {
        @Override public void run() {
            sampleTraffic();
            handler.postDelayed(this, getPreferences().getBoolean("low_data_mode", true) ? 2000 : 1000);
        }
    };
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            connected = intent.getBooleanExtra("connected", false);
            String message = intent.getStringExtra("message");
            updateConnectionLabels(message);
            if ("home".equals(currentSection)) renderPage();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff080b14);
        getWindow().setNavigationBarColor(0xff080b14);
        connected = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("vpn_connected", false);
        buildShell();
        renderPage();
        askNotificationPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        android.content.SharedPreferences vpnPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        connected = vpnPrefs.getBoolean("vpn_connected", false);
        String savedStatus = vpnPrefs.getString("vpn_status", "");
        if (connectionState != null && !savedStatus.isEmpty()) connectionState.setText(savedStatus);
        registerVpnReceiver();
        handler.removeCallbacks(trafficRunnable);
        handler.post(trafficRunnable);
        autoRefreshOnEntry();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(trafficRunnable);
        unregisterVpnReceiver();
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff080b14);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView logo = text("A", 22, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(ACCENTS[accentIndex()], 16));
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(11), 0, dp(8), 0);
        titleBox.addView(text("ABOLIX", 18, Color.WHITE, true));
        headerStatus = text("اتصال امن و هوشمند", 11, 0xff91a0b7, false);
        titleBox.addView(headerStatus);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView topState = text("●", 14, ACCENTS[accentIndex()], true);
        topState.setGravity(Gravity.CENTER);
        header.addView(topState, new LinearLayout.LayoutParams(dp(28), dp(28)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), dp(4), dp(16), dp(18));
        contentFrame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(7), dp(7), dp(7), dp(8));
        bottomBar.setBackgroundColor(0xff0d1220);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(66)));
        buildBottomBar();
    }

    private void buildBottomBar() {
        bottomBar.removeAllViews();
        String[][] items = {{"home", "⌂", "خانه"}, {"servers", "⇄", "سرورها"}, {"subscription", "↻", "اشتراک"}, {"routing", "◎", "اپ‌ها"}, {"settings", "⚙", "تنظیمات"}};
        for (String[] item : items) {
            boolean active = currentSection.equals(item[0]);
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setPadding(dp(2), dp(1), dp(2), dp(1));
            TextView icon = text(item[1], 21, active ? ACCENTS[accentIndex()] : 0xff8290a8, true);
            TextView label = text(item[2], 10, active ? Color.WHITE : 0xff8290a8, active);
            tab.addView(icon);
            tab.addView(label);
            tab.setOnClickListener(v -> { currentSection = item[0]; buildBottomBar(); renderPage(); });
            bottomBar.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private void renderPage() {
        if (page == null) return;
        page.removeAllViews();
        switch (currentSection) {
            case "servers": renderServers(); break;
            case "subscription": renderSubscription(); break;
            case "routing": renderRouting(); break;
            case "settings": renderSettings(); break;
            default: renderHome();
        }
        buildBottomBar();
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(7));
            child.animate().alpha(1f).translationY(0f).setDuration(220).setStartDelay(Math.min(i * 35L, 140L)).start();
        }
    }

    private void renderHome() {
        page.addView(sectionTitle("داشبورد", "وضعیت اتصال، سرعت و کیفیت مسیر"));
        page.addView(buildConnectionCard());
        page.addView(buildTrafficCard());
        page.addView(buildExitCard());
        page.addView(buildChecksCard());
        page.addView(noteCard("نکته", "پینگ‌ها فقط با فشردن دکمه اجرا می‌شوند. عدد سرعت از شمارنده‌ی ترافیک ثبت‌شده برای UID برنامه محاسبه می‌شود؛ ممکن است با کل ترافیک همه‌ی اپ‌ها یکسان نباشد."));
    }

    private View buildConnectionCard() {
        LinearLayout card = cardColumn();
        LinearLayout top = row();
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.addView(text(connected ? "تونل فعال است" : "آماده‌ی اتصال", 17, Color.WHITE, true));
        connectionState = text(connected ? "مسیر امن روشن است" : "یک سرور انتخاب کن و متصل شو", 12, 0xff9aa8bd, false);
        status.addView(connectionState);
        top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        TextView indicator = text(connected ? "●" : "○", 26, connected ? ACCENTS[accentIndex()] : 0xff64748b, true);
        top.addView(indicator);
        card.addView(top);
        addGap(card, 14);

        ProxyNode node = selectedNode();
        selectedNodeText = text(node == null ? "هیچ سروری انتخاب نشده" : node.getName() + "  ·  " + node.getProtocol().toUpperCase(Locale.ROOT), 13, 0xffd9e3f2, true);
        card.addView(selectedNodeText);
        addGap(card, 13);
        Button connect = button(connected ? "قطع اتصال" : "اتصال امن", connected ? 0xffe45f7e : ACCENTS[accentIndex()]);
        connect.setOnClickListener(v -> toggleConnection());
        card.addView(connect, new LinearLayout.LayoutParams(-1, dp(48)));
        return card;
    }

    private View buildTrafficCard() {
        LinearLayout card = cardColumn();
        card.addView(text("ترافیک زنده", 16, Color.WHITE, true));
        addGap(card, 12);
        LinearLayout speeds = row();
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
        d.addView(text("↓ دانلود", 11, ACCENTS[accentIndex()], true));
        downSpeedText = text("0 B/s", 21, Color.WHITE, true); d.addView(downSpeedText);
        LinearLayout u = new LinearLayout(this); u.setOrientation(LinearLayout.VERTICAL); u.setGravity(Gravity.RIGHT);
        u.addView(text("↑ آپلود", 11, 0xff9b8cff, true));
        upSpeedText = text("0 B/s", 21, Color.WHITE, true); u.addView(upSpeedText);
        speeds.addView(d, new LinearLayout.LayoutParams(0, -2, 1));
        speeds.addView(u, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(speeds);
        addGap(card, 9);
        graph = new AbolixGraphView(this);
        graph.setAccent(ACCENTS[accentIndex()]);
        card.addView(graph, new LinearLayout.LayoutParams(-1, dp(105)));
        addGap(card, 10);
        LinearLayout totals = row();
        totalDownText = text("دریافت اپ از روشن‌شدن گوشی: " + bytesText(Math.max(0, TrafficStats.getUidRxBytes(Process.myUid()))), 10, 0xffaebbd0, false);
        totalUpText = text("ارسال اپ از روشن‌شدن گوشی: " + bytesText(Math.max(0, TrafficStats.getUidTxBytes(Process.myUid()))), 10, 0xffaebbd0, false);
        totals.addView(totalDownText, new LinearLayout.LayoutParams(0, -2, 1));
        totals.addView(totalUpText, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(totals);
        return card;
    }

    private View buildExitCard() {
        LinearLayout card = cardColumn();
        LinearLayout top = row();
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("IP خروجی و کشور", 15, Color.WHITE, true));
        labels.addView(text("از مسیر شبکه‌ی فعلی برنامه", 11, 0xff9aa8bd, false));
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button test = button("بررسی IP", 0xff29384f);
        test.setOnClickListener(v -> checkExitIp());
        top.addView(test);
        card.addView(top);
        addGap(card, 10);
        exitIpText = text("برای بررسی، دکمه را بزن", 13, 0xffd6e0ef, false);
        card.addView(exitIpText);
        return card;
    }

    private View buildChecksCard() {
        LinearLayout card = cardColumn();
        LinearLayout top = row();
        top.addView(text("کیفیت سرویس‌ها", 15, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button check = button("تست پینگ", 0xff29384f);
        check.setOnClickListener(v -> checkServices());
        top.addView(check);
        card.addView(top);
        addGap(card, 9);
        pingText = text("YouTube · Telegram · Discord · Facebook\nتست دستی TCP روی پورت 443", 12, 0xffaebbd0, false);
        card.addView(pingText);
        return card;
    }

    private void renderServers() {
        page.addView(sectionTitle("سرورها", "انتخاب و تست یک‌باره‌ی اتصال"));
        LinearLayout actions = cardColumn();
        Button importButton = button("＋ افزودن لینک کانفیگ", ACCENTS[accentIndex()]);
        importButton.setOnClickListener(v -> showImportDialog());
        actions.addView(importButton, new LinearLayout.LayoutParams(-1, dp(46)));
        addGap(actions, 8);
        Button subs = button("مدیریت لینک اشتراک", 0xff29384f);
        subs.setOnClickListener(v -> { currentSection = "subscription"; renderPage(); });
        actions.addView(subs, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(actions);

        List<ProxyNode> nodes = SubscriptionManager.getNodes(this);
        String current = getSharedPreferences(PREFS, MODE_PRIVATE).getString("selected_node_id", "");
        if (nodes.isEmpty()) {
            page.addView(noteCard("هنوز سروری نداری", "از لینک تکی یا اشتراک VLESS، VMess، Trojan، Shadowsocks، Hysteria2 یا TUIC وارد کن."));
            return;
        }
        for (ProxyNode node : nodes) {
            boolean selected = node.getId().equals(current);
            LinearLayout item = cardColumn();
            LinearLayout info = row();
            LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
            names.addView(text(node.getName(), 14, Color.WHITE, true));
            names.addView(text(node.getProtocol().toUpperCase(Locale.ROOT) + "  ·  " + node.getServer() + ":" + node.getPort(), 10, 0xff91a0b7, false));
            info.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            info.addView(text(selected ? "✓" : "○", 20, selected ? ACCENTS[accentIndex()] : 0xff728198, true));
            item.addView(info);
            addGap(item, 10);
            LinearLayout controls = row();
            Button choose = button(selected ? "انتخاب شده" : "انتخاب سرور", selected ? 0xff2b4d4a : 0xff29384f);
            choose.setOnClickListener(v -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("selected_node_id", node.getId()).apply();
                Toast.makeText(this, "سرور انتخاب شد", Toast.LENGTH_SHORT).show();
                renderPage();
            });
            Button ping = button("پینگ یک‌باره", 0xff29384f);
            ping.setOnClickListener(v -> testNode(node, ping));
            controls.addView(choose, new LinearLayout.LayoutParams(0, dp(40), 1));
            addGap(controls, 7);
            controls.addView(ping, new LinearLayout.LayoutParams(0, dp(40), 1));
            item.addView(controls);
            page.addView(item);
        }
    }

    private void renderSubscription() {
        page.addView(sectionTitle("اشتراک", "مدیریت و به‌روزرسانی خودکار"));
        LinearLayout card = cardColumn();
        card.addView(text("لینک HTTPS اشتراک", 14, Color.WHITE, true));
        addGap(card, 8);
        subscriptionUrlField = editText("https://example.com/subscription", false);
        subscriptionUrlField.setText(SubscriptionManager.getUrl(this));
        card.addView(subscriptionUrlField, new LinearLayout.LayoutParams(-1, dp(48)));
        addGap(card, 8);
        Button refresh = button("به‌روزرسانی همین حالا", ACCENTS[accentIndex()]);
        refresh.setOnClickListener(v -> refreshSubscription(false));
        card.addView(refresh, new LinearLayout.LayoutParams(-1, dp(46)));
        addGap(card, 7);
        Button importText = button("وارد کردن متن / چند لینک", 0xff29384f);
        importText.setOnClickListener(v -> showImportDialog());
        card.addView(importText, new LinearLayout.LayoutParams(-1, dp(44)));
        addGap(card, 12);
        card.addView(text("آخرین به‌روزرسانی: " + SubscriptionManager.formatUpdated(this), 11, 0xff9aa8bd, false));
        page.addView(card);

        LinearLayout options = cardColumn();
        Switch automatic = makeSwitch("به‌روزرسانی خودکار هنگام ورود", getPreferences().getBoolean("auto_refresh", true));
        automatic.setOnCheckedChangeListener((button, checked) -> getPreferences().edit().putBoolean("auto_refresh", checked).apply());
        options.addView(automatic);
        options.addView(text("فقط وقتی لینک اشتراک ذخیره شده باشد اجرا می‌شود؛ هیچ کار پس‌زمینه‌ی دائمی ندارد.", 11, 0xff9aa8bd, false));
        page.addView(options);
    }

    private void renderRouting() {
        page.addView(sectionTitle("تونل برنامه‌ها", "Split tunneling با انتخاب اپلیکیشن"));
        page.addView(noteCard("نحوه‌ی کار", "وقتی هیچ برنامه‌ای انتخاب نشده باشد، کل دستگاه از VPN عبور می‌کند. با انتخاب یک یا چند برنامه، فقط همان‌ها و خود Abolix از تونل عبور خواهند کرد."));
        Set<String> selected = selectedPackages();
        for (int i = 0; i < APP_PACKAGES.length; i++) {
            final String packageName = APP_PACKAGES[i];
            final String appLabel = APP_LABELS[i];
            LinearLayout card = cardColumn();
            LinearLayout line = row();
            CheckBox check = new CheckBox(this);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENTS[accentIndex()]));
            check.setTextColor(Color.WHITE);
            check.setTextSize(14);
            check.setText(appLabel);
            check.setChecked(selected.contains(packageName));
            boolean installed = isInstalled(packageName);
            check.setEnabled(installed);
            check.setOnCheckedChangeListener((button, checked) -> {
                Set<String> now = selectedPackages();
                if (checked) now.add(packageName); else now.remove(packageName);
                saveSelectedPackages(now);
            });
            line.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = text(installed ? "نصب شده" : "نصب نیست", 10, installed ? 0xff8fa2bb : 0xffd18b74, false);
            line.addView(state);
            card.addView(line);
            page.addView(card);
        }
        Button reset = button("پاک کردن انتخاب‌ها · تونل کامل", 0xff29384f);
        reset.setOnClickListener(v -> { saveSelectedPackages(Collections.emptySet()); renderPage(); });
        page.addView(reset, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void renderSettings() {
        page.addView(sectionTitle("تنظیمات", "شخصی‌سازی تجربه‌ی Abolix"));
        LinearLayout themeCard = cardColumn();
        themeCard.addView(text("رنگ تأکیدی", 15, Color.WHITE, true));
        addGap(themeCard, 12);
        LinearLayout palette = row();
        for (int i = 0; i < ACCENTS.length; i++) {
            final int index = i;
            View swatch = new View(this);
            GradientDrawable shape = round(ACCENTS[i], 100);
            if (i == accentIndex()) shape.setStroke(dp(3), Color.WHITE);
            swatch.setBackground(shape);
            swatch.setOnClickListener(v -> { getPreferences().edit().putInt("accent", index).apply(); renderPage(); buildShellColors(); });
            palette.addView(swatch, new LinearLayout.LayoutParams(dp(34), dp(34), 1));
        }
        themeCard.addView(palette);
        page.addView(themeCard);

        LinearLayout usage = cardColumn();
        usage.addView(text("بهینه‌سازی مصرف", 15, Color.WHITE, true));
        addGap(usage, 8);
        Switch lowData = makeSwitch("حالت کم‌مصرف رابط", getPreferences().getBoolean("low_data_mode", true));
        lowData.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("low_data_mode", checked).apply();
            handler.removeCallbacks(trafficRunnable);
            handler.post(trafficRunnable);
        });
        usage.addView(lowData);
        usage.addView(text("در این حالت تست‌های خودکار اجرا نمی‌شوند و نمودار با نمونه‌برداری سبک به‌روزرسانی می‌شود. کاهش واقعی حجم اینترنت به پروتکل و سرویس مقصد وابسته است؛ فشرده‌سازی دلخواه ترافیک رمزنگاری‌شده ممکن نیست.", 11, 0xff9aa8bd, false));
        page.addView(usage);

        LinearLayout info = cardColumn();
        info.addView(text("درباره Abolix", 15, Color.WHITE, true));
        addGap(info, 7);
        info.addView(text("نسخه 1.0.0 · هسته sing-box 1.14.4\nپشتیبانی لینک: VLESS، VMess، Trojan، SS، Hysteria2، TUIC، AnyTLS، SOCKS و HTTP\nساخته‌شده برای Android 7+", 11, 0xff9aa8bd, false));
        page.addView(info);
    }

    private void buildShellColors() {
        int color = ACCENTS[accentIndex()];
        getWindow().setStatusBarColor(0xff080b14);
        getWindow().setNavigationBarColor(0xff080b14);
        if (headerStatus != null) headerStatus.setTextColor(0xff91a0b7);
        if (graph != null) graph.setAccent(color);
    }

    private void toggleConnection() {
        if (connected) {
            AbolixVpnService.stop(this);
            connected = false;
            updateConnectionLabels("در حال قطع اتصال");
            renderPage();
            return;
        }
        ProxyNode node = selectedNode();
        if (node == null) {
            Toast.makeText(this, "ابتدا یک سرور اضافه و انتخاب کن", Toast.LENGTH_LONG).show();
            currentSection = "servers"; renderPage(); return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            pendingConnect = true;
            startActivityForResult(permission, REQUEST_VPN);
        } else startSelectedNode();
    }

    private void startSelectedNode() {
        ProxyNode node = selectedNode();
        if (node == null) return;
        try {
            String config = SingBoxConfig.build(node, new ArrayList<>(selectedPackages()), getPackageName());
            AbolixVpnService.start(this, config, node.getName());
            if (connectionState != null) connectionState.setText("در حال راه‌اندازی هسته…");
            Toast.makeText(this, "درخواست اتصال ارسال شد", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "ساخت پیکربندی ناموفق: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && pendingConnect) {
            pendingConnect = false;
            if (resultCode == RESULT_OK) startSelectedNode();
            else Toast.makeText(this, "اجازه‌ی VPN داده نشد", Toast.LENGTH_LONG).show();
        }
    }

    private void testNode(ProxyNode node, Button button) {
        button.setText("در حال تست…"); button.setEnabled(false);
        executor.execute(() -> {
            long start = System.nanoTime();
            String result;
            try (Socket socket = new Socket()) {
                if (!AbolixVpnService.protectSocket(socket)) {
                    throw new java.io.IOException("امکان خارج‌کردن سوکت تست از تونل VPN فراهم نشد");
                }
                socket.connect(new InetSocketAddress(node.getServer(), node.getPort()), 5000);
                result = "TCP: " + ((System.nanoTime() - start) / 1_000_000L) + " ms";
            } catch (Exception e) { result = "ناموفق: " + concise(e); }
            String finalResult = result;
            runOnUiThread(() -> {
                button.setText("پینگ یک‌باره"); button.setEnabled(true);
                Toast.makeText(this, node.getName() + " · " + finalResult, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void checkServices() {
        if (!connected) {
            pingText.setText("برای سنجش مسیر VPN، ابتدا اتصال را برقرار کن.");
            return;
        }
        pingText.setText("در حال بررسی مسیر فعلی…");
        executor.execute(() -> {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < CHECK_HOSTS.length; i++) {
                long start = System.nanoTime();
                String value;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(CHECK_DOMAINS[i], 443), 4500);
                    value = ((System.nanoTime() - start) / 1_000_000L) + " ms";
                } catch (Exception e) { value = "خطا"; }
                if (result.length() > 0) result.append("\n");
                result.append(CHECK_HOSTS[i]).append(": ").append(value);
            }
            String text = result.toString();
            runOnUiThread(() -> { if (pingText != null) pingText.setText(text); });
        });
    }

    private void checkExitIp() {
        if (!connected) {
            exitIpText.setText("برای دیدن IP خروجی VPN، ابتدا متصل شو.");
            return;
        }
        exitIpText.setText("در حال دریافت IP خروجی…");
        executor.execute(() -> {
            String result;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://ipwho.is/").openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Abolix-Android/1.0");
                if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
                String body = readLimited(connection.getInputStream(), 32768);
                JSONObject json = new JSONObject(body);
                if (!json.optBoolean("success", false)) throw new IllegalStateException("سرویس مکان‌یابی پاسخ معتبر نداد");
                JSONObject isp = json.optJSONObject("connection");
                result = json.optString("ip", "?") + "  ·  " + json.optString("country", "نامشخص")
                        + " (" + json.optString("country_code", "--") + ")"
                        + (isp == null ? "" : "\n" + isp.optString("isp", ""));
            } catch (Exception e) { result = "بررسی IP ناموفق بود: " + concise(e); }
            finally { if (connection != null) connection.disconnect(); }
            String finalResult = result;
            runOnUiThread(() -> { if (exitIpText != null) exitIpText.setText(finalResult); });
        });
    }

    private void showImportDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(18), dp(10), dp(18), dp(4));
        box.setOrientation(LinearLayout.VERTICAL);
        TextView hint = text("لینک تکی یا چند لینک را وارد کن؛ همچنین متن کامل Clash YAML، sing-box JSON یا Xray JSON و اشتراک Base64 قابل‌خواندن است. VLESS، VMess، Trojan، SS، Hysteria2، TUIC، AnyTLS، SOCKS و HTTP پشتیبانی می‌شوند. فایل‌های دارای XHTTP، mKCP یا SSR با هسته رسمی sing-box سازگار نیستند.", 12, 0xff9aa8bd, false);
        box.addView(hint);
        EditText input = editText("vless://…", true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(150));
        inputParams.topMargin = dp(12);
        box.addView(input, inputParams);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("افزودن کانفیگ")
                .setView(box)
                .setNegativeButton("لغو", null)
                .setPositiveButton("وارد کردن", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                SubscriptionManager.RefreshResult imported = SubscriptionManager.importText(this, input.getText().toString());
                Toast.makeText(this, imported.message + " · " + imported.nodes.size() + " سرور", Toast.LENGTH_LONG).show();
                dialog.dismiss();
                currentSection = "servers";
                renderPage();
            } catch (Exception e) {
                Toast.makeText(this, concise(e), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private void refreshSubscription(boolean automatic) {
        if (refreshInFlight) return;
        String address = subscriptionUrlField != null && subscriptionUrlField.getText() != null
                ? subscriptionUrlField.getText().toString().trim() : SubscriptionManager.getUrl(this);
        if (address.isEmpty()) {
            if (!automatic) Toast.makeText(this, "ابتدا لینک اشتراک را وارد کن", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SubscriptionManager.saveUrl(this, address);
        } catch (RuntimeException secureError) {
            Toast.makeText(this, "ذخیره‌ی امن لینک اشتراک ناموفق بود: " + concise(secureError), Toast.LENGTH_LONG).show();
            return;
        }
        refreshInFlight = true;
        Toast.makeText(this, automatic ? "در حال بررسی اشتراک…" : "در حال به‌روزرسانی اشتراک…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String message;
            try {
                SubscriptionManager.RefreshResult result = SubscriptionManager.refresh(this, address);
                message = result.message + " · " + result.nodes.size() + " سرور";
            } catch (Exception e) { message = concise(e); }
            String finalMessage = message;
            runOnUiThread(() -> {
                refreshInFlight = false;
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                if ("subscription".equals(currentSection) || "servers".equals(currentSection)) renderPage();
            });
        });
    }

    private void autoRefreshOnEntry() {
        if (!getPreferences().getBoolean("auto_refresh", true)) return;
        String url = SubscriptionManager.getUrl(this);
        if (url == null || url.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoRefreshAttempt < 15000 || refreshInFlight) return;
        lastAutoRefreshAttempt = now;
        refreshSubscription(true);
    }

    private void sampleTraffic() {
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        long now = System.currentTimeMillis();
        long elapsed = Math.max(1, now - lastTrafficSample);
        float downRate = rx >= 0 && lastRx >= 0 ? Math.max(0, (rx - lastRx) * 1000f / elapsed) : 0;
        float upRate = tx >= 0 && lastTx >= 0 ? Math.max(0, (tx - lastTx) * 1000f / elapsed) : 0;
        lastRx = rx; lastTx = tx; lastTrafficSample = now;
        if (downSpeedText != null) downSpeedText.setText(rateText(downRate));
        if (upSpeedText != null) upSpeedText.setText(rateText(upRate));
        if (totalDownText != null) totalDownText.setText("دریافت اپ از روشن‌شدن گوشی: " + (rx < 0 ? "ناموجود" : bytesText(rx)));
        if (totalUpText != null) totalUpText.setText("ارسال اپ از روشن‌شدن گوشی: " + (tx < 0 ? "ناموجود" : bytesText(tx)));
        if (graph != null && "home".equals(currentSection)) graph.addSample(downRate, upRate);
    }

    private void updateConnectionLabels(String message) {
        if (connectionState != null) connectionState.setText(message == null ? (connected ? "تونل فعال است" : "قطع") : message);
        if (headerStatus != null) headerStatus.setText(connected ? "اتصال برقرار است" : "اتصال امن و هوشمند");
    }

    private ProxyNode selectedNode() {
        List<ProxyNode> nodes = SubscriptionManager.getNodes(this);
        if (nodes.isEmpty()) return null;
        String selected = getPreferences().getString("selected_node_id", "");
        for (ProxyNode node : nodes) if (node.getId().equals(selected)) return node;
        ProxyNode first = nodes.get(0);
        getPreferences().edit().putString("selected_node_id", first.getId()).apply();
        return first;
    }

    private Set<String> selectedPackages() {
        String stored = getPreferences().getString("selected_packages", "");
        Set<String> result = new HashSet<>();
        if (!stored.trim().isEmpty()) for (String value : stored.split(",")) if (!value.trim().isEmpty()) result.add(value.trim());
        return result;
    }

    private void saveSelectedPackages(Set<String> packages) {
        getPreferences().edit().putString("selected_packages", android.text.TextUtils.join(",", packages)).apply();
    }

    private boolean isInstalled(String packageName) {
        try { getPackageManager().getApplicationInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void registerVpnReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(AbolixVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(vpnReceiver, filter);
        receiverRegistered = true;
    }

    private void unregisterVpnReceiver() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(vpnReceiver); } catch (Exception ignored) { }
        receiverRegistered = false;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout cardColumn() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(round(0xff121a2a, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        card.setElevation(dp(1));
        return card;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return line;
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(2), dp(9), dp(2), dp(13));
        block.addView(text(title, 22, Color.WHITE, true));
        block.addView(text(subtitle, 11, 0xff91a0b7, false));
        return block;
    }

    private View noteCard(String title, String body) {
        LinearLayout card = cardColumn();
        card.addView(text(title, 13, ACCENTS[accentIndex()], true));
        addGap(card, 5);
        card.addView(text(body, 11, 0xffaebbd0, false));
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setGravity(Gravity.RIGHT);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setIncludeFontPadding(true);
        return view;
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(round(color, 13));
        return button;
    }

    private EditText editText(String hint, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(0xff64748b);
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(round(0xff0b1120, 11));
        input.setGravity(Gravity.RIGHT | (multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL));
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(false);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setSingleLine(true);
        }
        return input;
    }

    private Switch makeSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(13);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(4), 0, dp(4));
        return toggle;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), 0x223d4b63);
        return shape;
    }

    private void addGap(LinearLayout parent, int height) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int accentIndex() { return Math.max(0, Math.min(ACCENTS.length - 1, getPreferences().getInt("accent", 0))); }
    private android.content.SharedPreferences getPreferences() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String rateText(float bytesPerSecond) {
        if (bytesPerSecond < 1024) return new DecimalFormat("0").format(bytesPerSecond) + " B/s";
        if (bytesPerSecond < 1024 * 1024) return new DecimalFormat("0.0").format(bytesPerSecond / 1024f) + " KB/s";
        return new DecimalFormat("0.00").format(bytesPerSecond / (1024f * 1024f)) + " MB/s";
    }

    private static String bytesText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return new DecimalFormat("0.0").format(bytes / 1024d) + " KB";
        if (bytes < 1024L * 1024 * 1024) return new DecimalFormat("0.00").format(bytes / (1024d * 1024d)) + " MB";
        return new DecimalFormat("0.00").format(bytes / (1024d * 1024d * 1024d)) + " GB";
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IllegalStateException("پاسخ بیش از حد بزرگ است");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
