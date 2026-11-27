package com.abolix.vpn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.abolix.vpn.core.Link;
import com.abolix.vpn.core.ProbeScanner;
import com.abolix.vpn.core.Ranges;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];

    // home
    private ConnectView cv;
    private TextView status, method, vPing, vIp, vDown, vUp, vStab, svcYt, svcTg, svcGg;
    private ChartView chart;
    private final float[] dH = new float[TunnelService.HIST], uH = new float[TunnelService.HIST];

    // scanner
    private EditText etCount, etSni;
    private Switch swAuto, swBackend;
    private Button btnScan;
    private ProgressBar pb;
    private TextView scanInfo, cleanInfo;
    private LinearLayout resultsBox;
    private ProbeScanner scanner;
    private volatile boolean scanning;
    private List<ProbeScanner.Res> lastRes = new ArrayList<>();

    // configs
    private LinearLayout cfgList;
    private EditText etPaste, etClean;
    private Button btnFrag, btnFp;

    private final Runnable tick = new Runnable() {
        public void run() { refreshHome(); h.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        FrameLayout root = new FrameLayout(this);
        root.addView(new FxView(this), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFitsSystemWindows(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 4));
        head.addView(Ui.tv(this, "ابولیکس", 24, Ui.TEXT, true));
        head.addView(Ui.tv(this, "عبور هوشمند با Domain Fronting · کانال تلگرام @Abolix", 11, Ui.DIM, false));
        col.addView(head);

        FrameLayout content = new FrameLayout(this);
        pages[0] = homePage();
        pages[1] = scanPage();
        pages[2] = cfgPage();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        col.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.parseColor("#0D1426"));
        String[] names = {"اتصال", "اسکنر IP", "کانفیگ"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.tv(this, names[i], 14, Ui.DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { show(idx); } });
            tabs[i] = t;
            bar.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        col.addView(bar);
        root.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setContentView(root);
        show(Prefs.links(this).isEmpty() ? 2 : 0);
        refreshCfg();
    }

    private void show(int i) {
        for (int k = 0; k < 3; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(k == i ? Ui.TEAL : Ui.DIM);
        }
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { h.removeCallbacks(tick); super.onPause(); }

    // =============================================================== HOME
    private ScrollView scroll(LinearLayout l) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        l.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 20));
        sv.addView(l, new ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP));
        return sv;
    }

    private TextView stat(LinearLayout row, String title, int color) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.tv(this, title, 11, Ui.DIM, false));
        TextView v = Ui.tv(this, "—", 17, color, true);
        v.setSingleLine(true);
        c.addView(v);
        row.addView(c, Ui.lp(0, Ui.WRAP, this, 4, 4, 4, 4));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).weight = 1f;
        return v;
    }

    private View homePage() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        cv = new ConnectView(this);
        cv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggle(); } });
        l.addView(cv, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 250)));

        status = Ui.tv(this, "آماده اتصال", 17, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER);
        l.addView(status, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        method = Ui.tv(this, "", 12, Ui.DIM, false);
        method.setGravity(Gravity.CENTER);
        l.addView(method, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        LinearLayout r1 = Ui.row(this), r2 = Ui.row(this), r3 = Ui.row(this);
        vPing = stat(r1, "پینگ", Ui.TEAL);
        vStab = stat(r1, "پایداری اتصال", Ui.AMBER);
        vDown = stat(r2, "دانلود لحظه‌ای", Ui.TEAL);
        vUp = stat(r2, "آپلود لحظه‌ای", Ui.BLUE);
        vIp = stat(r3, "IP خروجی", Ui.TEXT);
        l.addView(r1, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 12, 0, 0));
        l.addView(r2);
        l.addView(r3);

        LinearLayout cc = Ui.card(this);
        LinearLayout lg = Ui.row(this);
        lg.addView(Ui.tv(this, "● دانلود  ", 12, Ui.TEAL, true));
        lg.addView(Ui.tv(this, "● آپلود", 12, Ui.BLUE, true));
        cc.addView(lg);
        chart = new ChartView(this);
        cc.addView(chart, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 140)));
        l.addView(cc, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 8, 4, 4));

        LinearLayout sc = Ui.card(this);
        sc.addView(Ui.tv(this, "تست سرویس‌ها از داخل تونل", 13, Ui.TEXT, true));
        LinearLayout sr = Ui.row(this);
        svcYt = Ui.tv(this, "یوتیوب: —", 12, Ui.DIM, false);
        svcTg = Ui.tv(this, "تلگرام: —", 12, Ui.DIM, false);
        svcGg = Ui.tv(this, "گوگل: —", 12, Ui.DIM, false);
        for (TextView t : new TextView[]{svcYt, svcTg, svcGg}) sr.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        sc.addView(sr, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 8));
        sc.addView(Ui.btn(this, "تست یوتیوب / تلگرام / گوگل", Ui.BLUE, new View.OnClickListener() {
            public void onClick(View v) { testServices(); }
        }));
        l.addView(sc, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));
        return scroll(l);
    }

    private void refreshHome() {
        int st = TunnelService.state;
        cv.setState(st);
        if (st == TunnelService.OFF) { status.setText("آماده اتصال"); status.setTextColor(Ui.TEXT); }
        else if (st == TunnelService.CONNECTING) { status.setText(TunnelService.status); status.setTextColor(Ui.AMBER); }
        else if (st == TunnelService.ON) { status.setText("متصل ✓"); status.setTextColor(Ui.TEAL); }
        else { status.setText(TunnelService.status); status.setTextColor(Ui.RED); }
        boolean live = st == TunnelService.ON || st == TunnelService.CONNECTING;
        method.setText(live ? "روش: " + TunnelService.method + (st == TunnelService.ON && !TunnelService.fullTunnel ? "  (حالت پروکسی)" : "") : "");
        vPing.setText(TunnelService.ping < 0 ? "—" : TunnelService.ping + " ms");
        vStab.setText(st == TunnelService.ON ? TunnelService.stability + "%" : "—");
        vDown.setText(Ui.speed(TunnelService.downBps));
        vUp.setText(Ui.speed(TunnelService.upBps));
        vIp.setText(st == TunnelService.ON ? TunnelService.exitIp : "—");
        TunnelService.snapshot(dH, uH);
        chart.set(dH, uH);
    }

    private void toggle() {
        int st = TunnelService.state;
        if (st == TunnelService.ON || st == TunnelService.CONNECTING) {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_STOP));
            return;
        }
        if (Prefs.links(this).isEmpty()) {
            Toast.makeText(this, "اول یک کانفیگ اضافه کنید", Toast.LENGTH_LONG).show();
            show(2);
            return;
        }
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, 1);
        else startVpn();
    }

    private void startVpn() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_START));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) startVpn();
    }

    private void testServices() {
        if (TunnelService.state != TunnelService.ON) {
            Toast.makeText(this, "اول متصل شوید", Toast.LENGTH_SHORT).show();
            return;
        }
        svcYt.setText("یوتیوب: …"); svcTg.setText("تلگرام: …"); svcGg.setText("گوگل: …");
        final String[] urls = {"https://www.youtube.com/generate_204", "https://web.telegram.org/", "https://www.google.com/generate_204"};
        final TextView[] tv = {svcYt, svcTg, svcGg};
        final String[] nm = {"یوتیوب", "تلگرام", "گوگل"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            new Thread(new Runnable() {
                public void run() {
                    final int ms = Net.time(urls[k], TunnelService.SOCKS, 8000);
                    h.post(new Runnable() {
                        public void run() {
                            tv[k].setText(nm[k] + ": " + (ms < 0 ? "ناموفق" : ms + "ms"));
                            tv[k].setTextColor(ms < 0 ? Ui.RED : Ui.TEAL);
                        }
                    });
                }
            }).start();
        }
    }

    // =============================================================== SCANNER
    private View scanPage() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        LinearLayout c = Ui.card(this);
        c.addView(Ui.tv(this, "اسکن رنج IP آکامای", 15, Ui.TEXT, true));
        c.addView(Ui.tv(this, "IPهای تصادفی از رنج‌ها انتخاب می‌شوند؛ هرکدام با SNI فرانتینگ تست می‌شوند و بهترین‌ها دقیق‌تر سنجیده می‌شوند.", 11, Ui.DIM, false));
        c.addView(Ui.tv(this, "تعداد IP برای اسکن (مثلاً 1000 یا 3000)", 12, Ui.DIM, false), Ui.lp(Ui.WRAP, Ui.WRAP, this, 0, 10, 0, 2));
        etCount = Ui.edit(this, "1000", String.valueOf(Prefs.scanCount(this)));
        etCount.setInputType(InputType.TYPE_CLASS_NUMBER);
        c.addView(etCount);
        c.addView(Ui.tv(this, "دامنه فرانت (SNI)", 12, Ui.DIM, false), Ui.lp(Ui.WRAP, Ui.WRAP, this, 0, 10, 0, 2));
        etSni = Ui.edit(this, "www.akamai.com", Prefs.sni(this));
        etSni.setSingleLine(true);
        etSni.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        c.addView(etSni);
        swBackend = sw("تأیید با سرور کانفیگ فعال (دقیق‌تر)", true, null);
        swAuto = sw("بعد از اسکن، بهترین‌ها خودکار به لیست تمیز اضافه شوند", Prefs.autoAdd(this), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { Prefs.setAutoAdd(MainActivity.this, v); }
        });
        c.addView(swBackend, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        c.addView(swAuto);
        btnScan = Ui.btn(this, "شروع اسکن", Ui.TEAL, new View.OnClickListener() { public void onClick(View v) { toggleScan(); } });
        c.addView(btnScan, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 10, 0, 0));
        c.addView(Ui.btn(this, "ویرایش رنج‌های IP", Ui.CARD2, new View.OnClickListener() { public void onClick(View v) { editRanges(); } }),
                Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 6, 0, 0));
        pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.TEAL));
        c.addView(pb, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 10, 0, 0));
        scanInfo = Ui.tv(this, "", 12, Ui.DIM, false);
        c.addView(scanInfo);
        l.addView(c, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));

        LinearLayout rc = Ui.card(this);
        rc.addView(Ui.tv(this, "بهترین نتایج", 15, Ui.TEXT, true));
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        rc.addView(resultsBox);
        LinearLayout br = Ui.row(this);
        br.addView(Ui.btn(this, "افزودن ۵ برتر", Ui.TEAL, new View.OnClickListener() { public void onClick(View v) { addTop(5); } }),
                new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        br.addView(Ui.btn(this, "افزودن همه", Ui.BLUE, new View.OnClickListener() { public void onClick(View v) { addTop(1000); } }),
                new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        rc.addView(br, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        cleanInfo = Ui.tv(this, "", 12, Ui.DIM, false);
        rc.addView(cleanInfo, Ui.lp(Ui.WRAP, Ui.WRAP, this, 0, 6, 0, 0));
        l.addView(rc, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));
        updateCleanInfo();
        return scroll(l);
    }

    private Switch sw(String text, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(13);
        s.setChecked(on);
        if (l != null) s.setOnCheckedChangeListener(l);
        return s;
    }

    private void updateCleanInfo() {
        if (cleanInfo != null) cleanInfo.setText("تعداد IP در لیست تمیز: " + Prefs.clean(this).size());
    }

    private void toggleScan() {
        if (scanning) { if (scanner != null) scanner.cancel(); return; }
        int count;
        try { count = Integer.parseInt(etCount.getText().toString().trim()); } catch (Exception e) { count = 1000; }
        count = Math.max(50, Math.min(count, 20000));
        Prefs.setScanCount(this, count);
        final String sni = etSni.getText().toString().trim().isEmpty() ? "www.akamai.com" : etSni.getText().toString().trim();
        Prefs.setSni(this, sni);
        Ranges r = Ranges.parse(Prefs.lines(Prefs.ranges(this)));
        if (r.total() == 0) { Toast.makeText(this, "رنج معتبری وجود ندارد", Toast.LENGTH_LONG).show(); return; }
        final List<String> ips = r.sample(count, new Random());

        String backend = null, wsPath = null;
        int port = 443;
        List<String> links = Prefs.links(this);
        int ai = Prefs.active(this);
        Link act = ai >= 0 && ai < links.size() ? Link.parse(links.get(ai)) : null;
        if (swBackend.isChecked() && act != null && act.net.equals("ws")) {
            backend = act.host.isEmpty() ? act.frontHost() : act.host;
            wsPath = act.path;
            port = act.port;
        }
        final String fb = backend, fw = wsPath;
        final int fp = port;
        scanner = new ProbeScanner();
        scanning = true;
        btnScan.setText("توقف اسکن");
        pb.setMax(ips.size());
        pb.setProgress(0);
        resultsBox.removeAllViews();
        scanInfo.setText("در حال اسکن " + ips.size() + " آی‌پی…");
        new Thread(new Runnable() {
            public void run() {
                List<ProbeScanner.Res> res = new ArrayList<>();
                try {
                    res = scanner.run(ips, sni, fb, fw, fp, 2500, 128, new ProbeScanner.Cb() {
                        public void progress(final int d, final int t, final int f) {
                            h.post(new Runnable() { public void run() {
                                pb.setProgress(d);
                                scanInfo.setText("اسکن: " + d + " از " + t + " · پاسخ‌دهنده: " + f);
                            } });
                        }
                        public void phase(final String p) {
                            if (p.equals("refine")) h.post(new Runnable() { public void run() { scanInfo.setText("سنجش دقیق بهترین‌ها (پایداری + سرور)…"); } });
                        }
                    });
                } catch (Exception ignore) { }
                final List<ProbeScanner.Res> fr = res;
                h.post(new Runnable() { public void run() { scanDone(fr); } });
            }
        }).start();
    }

    private void scanDone(List<ProbeScanner.Res> res) {
        scanning = false;
        btnScan.setText("شروع اسکن");
        lastRes = res;
        scanInfo.setText(res.isEmpty() ? "هیچ IP سالمی پیدا نشد. SNI یا رنج را عوض کنید." : "اسکن تمام شد · " + res.size() + " نتیجه برتر");
        resultsBox.removeAllViews();
        int n = Math.min(12, res.size());
        for (int i = 0; i < n; i++) {
            final ProbeScanner.Res r = res.get(i);
            TextView t = Ui.tv(this, (i + 1) + ".  " + r.ip + "   " + r.ms + "ms"
                    + (r.backendOk ? "   ✓سرور" : "") + (r.loss > 0 ? "   افت " + r.loss + "/3" : ""), 13,
                    r.backendOk ? Ui.TEAL : Ui.TEXT, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setBackground(Ui.round(Ui.CARD2, 10, this));
            int p = Ui.dp(this, 9);
            t.setPadding(p, p, p, p);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    List<String> one = new ArrayList<>();
                    one.add(r.ip);
                    Prefs.addClean(MainActivity.this, one);
                    updateCleanInfo();
                    refreshCfg();
                    Toast.makeText(MainActivity.this, r.ip + " به لیست تمیز اضافه شد", Toast.LENGTH_SHORT).show();
                }
            });
            resultsBox.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 4, 0, 0));
        }
        if (!res.isEmpty() && Prefs.autoAdd(this)) addTop(5);
    }

    private void addTop(int n) {
        if (lastRes.isEmpty()) { Toast.makeText(this, "نتیجه‌ای برای افزودن نیست", Toast.LENGTH_SHORT).show(); return; }
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < lastRes.size() && i < n; i++) ips.add(lastRes.get(i).ip);
        Prefs.addClean(this, ips);
        updateCleanInfo();
        refreshCfg();
        Toast.makeText(this, ips.size() + " آی‌پی به لیست تمیز اضافه شد", Toast.LENGTH_SHORT).show();
    }

    private void editRanges() {
        final EditText e = Ui.edit(this, "CIDR…", Prefs.ranges(this));
        e.setMinLines(8);
        e.setGravity(Gravity.TOP);
        e.setTypeface(Typeface.MONOSPACE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("رنج‌های IP (هر خط یک CIDR)")
                .setView(e)
                .setPositiveButton("ذخیره", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { Prefs.setRanges(MainActivity.this, e.getText().toString()); }
                })
                .setNeutralButton("پیش‌فرض", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { Prefs.sp(MainActivity.this).edit().remove("ranges").apply(); }
                })
                .setNegativeButton("بستن", null)
                .show();
    }

    // =============================================================== CONFIGS
    private View cfgPage() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        LinearLayout a = Ui.card(this);
        a.addView(Ui.tv(this, "افزودن کانفیگ", 15, Ui.TEXT, true));
        a.addView(Ui.tv(this, "لینک vless:// ، trojan:// یا vmess:// (از کانال @Abolix). چند خط هم قبول است.", 11, Ui.DIM, false));
        etPaste = Ui.edit(this, "vless://…", "");
        etPaste.setMinLines(3);
        etPaste.setGravity(Gravity.TOP);
        etPaste.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        a.addView(etPaste, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        LinearLayout r = Ui.row(this);
        r.addView(Ui.btn(this, "افزودن", Ui.TEAL, new View.OnClickListener() { public void onClick(View v) { addLinks(etPaste.getText().toString()); etPaste.setText(""); } }),
                new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        r.addView(Ui.btn(this, "چسباندن از کلیپ‌بورد", Ui.BLUE, new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                if (d != null && d.getItemCount() > 0) addLinks(String.valueOf(d.getItemAt(0).coerceToText(MainActivity.this)));
            }
        }), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        a.addView(r);
        l.addView(a, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));

        LinearLayout b = Ui.card(this);
        b.addView(Ui.tv(this, "کانفیگ‌ها (برای انتخاب لمس کنید)", 14, Ui.TEXT, true));
        cfgList = new LinearLayout(this);
        cfgList.setOrientation(LinearLayout.VERTICAL);
        b.addView(cfgList);
        l.addView(b, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));

        LinearLayout c = Ui.card(this);
        c.addView(Ui.tv(this, "لیست IP تمیز", 14, Ui.TEXT, true));
        c.addView(Ui.tv(this, "هر خط یک IP. برنامه IPهای بالای لیست را به ترتیب امتحان می‌کند.", 11, Ui.DIM, false));
        etClean = Ui.edit(this, "23.x.x.x", Prefs.join(Prefs.clean(this)));
        etClean.setMinLines(4);
        etClean.setGravity(Gravity.TOP);
        etClean.setTypeface(Typeface.MONOSPACE);
        etClean.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        c.addView(etClean, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        c.addView(Ui.btn(this, "ذخیره لیست", Ui.TEAL, new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setClean(MainActivity.this, Prefs.lines(etClean.getText().toString()));
                updateCleanInfo();
                Toast.makeText(MainActivity.this, "ذخیره شد", Toast.LENGTH_SHORT).show();
            }
        }), Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        l.addView(c, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));

        LinearLayout o = Ui.card(this);
        o.addView(Ui.tv(this, "روش‌های اتصال", 14, Ui.TEXT, true));
        o.addView(Ui.tv(this, "در حالت «خودکار» برنامه به‌ترتیب IPهای تمیز × فرگمنت × فینگرپرینت را امتحان می‌کند و در صورت افت، خودش روش را عوض می‌کند.", 11, Ui.DIM, false));
        btnFrag = Ui.btn(this, "", Ui.CARD2, new View.OnClickListener() {
            public void onClick(View v) { Prefs.setFrag(MainActivity.this, (Prefs.frag(MainActivity.this) + 1) % 4); refreshOpts(); }
        });
        btnFp = Ui.btn(this, "", Ui.CARD2, new View.OnClickListener() {
            public void onClick(View v) {
                String[] f = {"", "chrome", "firefox", "safari", "ios", "android", "randomized"};
                String cur = Prefs.fp(MainActivity.this);
                int i = 0;
                for (int k = 0; k < f.length; k++) if (f[k].equals(cur)) i = k;
                Prefs.setFp(MainActivity.this, f[(i + 1) % f.length]);
                refreshOpts();
            }
        });
        o.addView(btnFrag, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        o.addView(btnFp, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 4, 0, 0));
        o.addView(sw("Mux (چند اتصال روی یک کانال)", Prefs.mux(this), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean c2) { Prefs.setMux(MainActivity.this, c2); }
        }), Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 8, 0, 0));
        o.addView(sw("تعویض خودکار روش هنگام قطعی", Prefs.failover(this), new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean c2) { Prefs.setFailover(MainActivity.this, c2); }
        }));
        l.addView(o, Ui.lp(Ui.MATCH, Ui.WRAP, this, 4, 4, 4, 4));
        refreshOpts();
        return scroll(l);
    }

    private void refreshOpts() {
        String[] fn = {"خودکار", "خاموش", "معمولی", "شدید"};
        btnFrag.setText("فرگمنت TLS: " + fn[Prefs.frag(this)] + "  (لمس برای تغییر)");
        String fp = Prefs.fp(this);
        btnFp.setText("فینگرپرینت TLS: " + (fp.isEmpty() ? "طبق کانفیگ" : fp) + "  (لمس برای تغییر)");
    }

    private void addLinks(String text) {
        List<String> cur = Prefs.links(this);
        int added = 0;
        for (String ln : Prefs.lines(text)) {
            if (Link.parse(ln) != null && !cur.contains(ln)) { cur.add(ln); added++; }
        }
        Prefs.setLinks(this, cur);
        refreshCfg();
        Toast.makeText(this, added == 0 ? "لینک معتبری پیدا نشد" : added + " کانفیگ اضافه شد", Toast.LENGTH_SHORT).show();
    }

    private void refreshCfg() {
        if (cfgList == null) return;
        cfgList.removeAllViews();
        final List<String> links = Prefs.links(this);
        int act = Prefs.active(this);
        if (links.isEmpty()) cfgList.addView(Ui.tv(this, "هنوز کانفیگی اضافه نشده.", 12, Ui.DIM, false));
        for (int i = 0; i < links.size(); i++) {
            final int idx = i;
            Link k = Link.parse(links.get(i));
            LinearLayout row = Ui.row(this);
            row.setBackground(Ui.round(Ui.CARD2, 10, this));
            int p = Ui.dp(this, 10);
            row.setPadding(p, p, p, p);
            TextView t = Ui.tv(this, (i == act ? "◉  " : "○  ") + (k == null ? "نامعتبر" : k.label()), 13, i == act ? Ui.TEAL : Ui.TEXT, false);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            TextView x = Ui.tv(this, "  ✕  ", 16, Ui.RED, true);
            x.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    List<String> c = Prefs.links(MainActivity.this);
                    if (idx < c.size()) c.remove(idx);
                    Prefs.setLinks(MainActivity.this, c);
                    if (Prefs.active(MainActivity.this) >= c.size()) Prefs.setActive(MainActivity.this, 0);
                    refreshCfg();
                }
            });
            row.addView(x);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Prefs.setActive(MainActivity.this, idx); refreshCfg(); }
            });
            cfgList.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, this, 0, 4, 0, 0));
        }
        if (etClean != null) etClean.setText(Prefs.join(Prefs.clean(this)));
    }
}
