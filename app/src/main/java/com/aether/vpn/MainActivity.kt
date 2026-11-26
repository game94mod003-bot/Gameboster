package com.aether.vpn

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val cBg = Color.parseColor("#0B0F1A")
    private val cCard = Color.parseColor("#151B2B")
    private val cAccent = Color.parseColor("#00E5A8")
    private val cRed = Color.parseColor("#FF5C7A")
    private val cYellow = Color.parseColor("#FFC857")
    private val cText = Color.parseColor("#E8ECF6")
    private val cSub = Color.parseColor("#8A94AD")

    private lateinit var dot: View
    private lateinit var tvState: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvStab: TextView
    private lateinit var pbStab: ProgressBar
    private lateinit var tvIp: TextView
    private lateinit var tvIpInfo: TextView
    private lateinit var tvCfg: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnScan: Button
    private lateinit var btnKey: Button
    private lateinit var btnConn: Button

    private var scanning = false
    private var gettingKey = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private var tick = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val x = TextView(this)
        x.text = t
        x.textSize = size
        x.setTextColor(color)
        if (bold) x.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return x
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        val g = GradientDrawable()
        g.setColor(cCard)
        g.cornerRadius = dp(18).toFloat()
        l.background = g
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        l.layoutParams = lp
        return l
    }

    private fun button(t: String, color: Int, textColor: Int): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(textColor)
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(16).toFloat()
        b.background = g
        val lp = LinearLayout.LayoutParams(-1, dp(54))
        lp.bottomMargin = dp(10)
        b.layoutParams = lp
        return b
    }

    private fun metric(label: String, valueView: TextView): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        valueView.gravity = Gravity.CENTER
        col.addView(valueView)
        val l = tv(label, 12f, cSub)
        l.gravity = Gravity.CENTER
        col.addView(l)
        return col
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(20), dp(16), dp(24))

        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.fitsSystemWindows = true
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        sv.addView(root)
        setContentView(sv)

        root.addView(tv("Aether", 32f, cAccent, true))
        val sub = tv("MASQUE · HTTP/2 · IPv4 + IPv6", 13f, cSub)
        (sub.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(16)
        root.addView(sub)

        // status card
        val c1 = card()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        dot = View(this)
        val dg = GradientDrawable()
        dg.shape = GradientDrawable.OVAL
        dg.setColor(cSub)
        dot.background = dg
        dot.layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        row.addView(dot)
        tvState = tv("قطع", 20f, cText, true)
        tvState.setPadding(dp(10), 0, 0, 0)
        tvState.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(tvState)
        tvTime = tv("00:00:00", 16f, cSub)
        row.addView(tvTime)
        c1.addView(row)
        root.addView(c1)

        // metrics
        val c2 = card()
        val mrow = LinearLayout(this)
        mrow.orientation = LinearLayout.HORIZONTAL
        tvPing = tv("—", 18f, cText, true)
        tvDown = tv("—", 18f, cText, true)
        tvUp = tv("—", 18f, cText, true)
        mrow.addView(metric("پینگ", tvPing))
        mrow.addView(metric("دانلود", tvDown))
        mrow.addView(metric("آپلود", tvUp))
        c2.addView(mrow)
        root.addView(c2)

        // stability
        val c3 = card()
        tvStab = tv("پایداری: —", 15f, cText, true)
        c3.addView(tvStab)
        pbStab = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pbStab.max = 100
        pbStab.progressTintList = ColorStateList.valueOf(cAccent)
        val plp = LinearLayout.LayoutParams(-1, dp(10))
        plp.topMargin = dp(10)
        pbStab.layoutParams = plp
        c3.addView(pbStab)
        root.addView(c3)

        // exit ip
        val c4 = card()
        c4.addView(tv("IP خروجی", 12f, cSub))
        tvIp = tv("—", 22f, cText, true)
        c4.addView(tvIp)
        tvIpInfo = tv("", 12f, cSub)
        c4.addView(tvIpInfo)
        c4.setOnClickListener {
            if (AetherVpnService.state == "connected") AetherVpnService.refreshIp()
        }
        root.addView(c4)

        // config
        val c5 = card()
        c5.addView(tv("کانفیگ", 12f, cSub))
        tvCfg = tv("—", 13f, cText)
        c5.addView(tvCfg)
        root.addView(c5)

        btnScan = button("اسکن", cCard, cText)
        btnKey = button("دریافت کلید WARP", cCard, cText)
        btnConn = button("اتصال", cAccent, cBg)
        root.addView(btnScan)
        root.addView(btnKey)
        root.addView(btnConn)

        val c6 = card()
        tvLog = tv("", 11f, cSub)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.layoutDirection = View.LAYOUT_DIRECTION_LTR
        c6.addView(tvLog)
        root.addView(c6)

        btnScan.setOnClickListener { doScan() }
        btnKey.setOnClickListener { doKey() }
        btnConn.setOnClickListener { toggle() }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
        lastT = SystemClock.elapsedRealtime()
        h.post(object : Runnable {
            override fun run() {
                update()
                h.postDelayed(this, 500)
            }
        })
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun busyVpn(): Boolean {
        val st = AetherVpnService.state
        return st == "connected" || st == "connecting"
    }

    private fun doScan() {
        if (scanning) return
        if (busyVpn()) {
            toast("اول اتصال را قطع کنید")
            return
        }
        scanning = true
        btnScan.isEnabled = false
        thread {
            AetherVpnService.log("شروع اسکن...")
            val res = Scanner.scan(48) { d, t -> h.post { btnScan.text = "اسکن... $d/$t" } }
            if (res.isEmpty()) {
                AetherVpnService.log("هیچ آی‌پی پاسخ نداد")
            } else {
                val best = res[0]
                Store.put(this, "ep_host", best.ip)
                Store.put(this, "ep_port", "443")
                Store.put(this, "ep_ms", best.ms.toString())
                AetherVpnService.log("بهترین: ${best.ip} (${best.ms}ms) از ${res.size} آی‌پی سالم")
            }
            h.post {
                btnScan.text = "اسکن"
                btnScan.isEnabled = true
                scanning = false
            }
        }
    }

    private fun doKey() {
        if (gettingKey) return
        if (busyVpn()) {
            toast("اول اتصال را قطع کنید")
            return
        }
        gettingKey = true
        btnKey.isEnabled = false
        btnKey.text = "در حال دریافت..."
        thread {
            val ok = WarpApi.register(this) { AetherVpnService.log(it) }
            AetherVpnService.log(if (ok) "کلید WARP دریافت شد" else "دریافت کلید ناموفق بود")
            h.post {
                btnKey.text = "دریافت کلید WARP"
                btnKey.isEnabled = true
                gettingKey = false
            }
        }
    }

    private fun toggle() {
        if (busyVpn()) {
            startService(Intent(this, AetherVpnService::class.java).setAction(AetherVpnService.ACTION_STOP))
            return
        }
        if (Store.get(this, "ep_host").isEmpty()) {
            toast("اول اسکن کنید")
            return
        }
        if (Store.get(this, "addr4").isEmpty() && Store.get(this, "addr6").isEmpty()) {
            toast("اول کلید WARP بگیرید")
            return
        }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 77) else startVpn()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        val i = Intent(this, AetherVpnService::class.java).setAction(AetherVpnService.ACTION_START)
        startForegroundService(i)
    }

    private fun fmtSpeed(bps: Double): String {
        return when {
            bps >= 1048576.0 -> String.format(Locale.US, "%.1f MB/s", bps / 1048576.0)
            bps >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", bps / 1024.0)
            else -> String.format(Locale.US, "%.0f B/s", bps)
        }
    }

    private fun setDot(color: Int) {
        (dot.background as? GradientDrawable)?.setColor(color)
    }

    private fun update() {
        tick++
        val st = AetherVpnService.state
        when (st) {
            "connected" -> {
                setDot(cAccent)
                tvState.text = "متصل"
                tvState.setTextColor(cAccent)
            }
            "connecting" -> {
                setDot(cYellow)
                dot.alpha = if (tick % 2 == 0) 0.3f else 1f
                tvState.text = "در حال اتصال..."
                tvState.setTextColor(cYellow)
            }
            "error" -> {
                setDot(cRed)
                tvState.text = AetherVpnService.message.ifEmpty { "خطا" }
                tvState.setTextColor(cRed)
            }
            else -> {
                setDot(cSub)
                tvState.text = if (AetherVpnService.message.isEmpty()) "قطع" else AetherVpnService.message
                tvState.setTextColor(cText)
            }
        }
        if (st != "connecting") dot.alpha = 1f
        btnConn.text = if (busyVpn()) "قطع اتصال" else "اتصال"

        if (st == "connected") {
            val s = (System.currentTimeMillis() - AetherVpnService.connectedAt) / 1000
            tvTime.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)

            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastT).coerceAtLeast(1) / 1000.0
            val rx = AetherVpnService.rx
            val tx = AetherVpnService.tx
            tvDown.text = fmtSpeed((rx - lastRx) / dt)
            tvUp.text = fmtSpeed((tx - lastTx) / dt)
            lastRx = rx
            lastTx = tx
            lastT = now

            val r = AetherVpnService.rtt
            tvPing.text = if (r >= 0) "$r ms" else "—"
            val sb = AetherVpnService.stability
            val lbl = when {
                sb >= 90 -> "عالی"
                sb >= 70 -> "خوب"
                sb >= 40 -> "ضعیف"
                else -> "ناپایدار"
            }
            tvStab.text = "پایداری: $sb% ($lbl)"
            pbStab.progress = sb
            pbStab.progressTintList = ColorStateList.valueOf(
                if (sb >= 70) cAccent else if (sb >= 40) cYellow else cRed
            )
            tvIp.text = AetherVpnService.exitIp.ifEmpty { "..." }
            tvIpInfo.text = AetherVpnService.exitInfo
        } else {
            tvTime.text = "00:00:00"
            tvDown.text = "—"
            tvUp.text = "—"
            tvPing.text = "—"
            tvStab.text = "پایداری: —"
            pbStab.progress = 0
            tvIp.text = "—"
            tvIpInfo.text = ""
            lastRx = 0
            lastTx = 0
            lastT = SystemClock.elapsedRealtime()
        }

        val host = Store.get(this, "ep_host")
        val a4 = Store.get(this, "addr4")
        val a6 = Store.get(this, "addr6")
        tvCfg.text = "Endpoint: " + (if (host.isEmpty()) "— (اسکن نشده)" else "$host:${Store.get(this, "ep_port", "443")}") +
            "\nپروتکل: MASQUE (CONNECT-IP) روی HTTP/2" +
            "\nIPv4 تونل: " + (if (a4.isEmpty()) "—" else a4) +
            "\nIPv6 تونل: " + (if (a6.isEmpty()) "—" else a6)

        val lines = AetherVpnService.logText().trimEnd().lines()
        tvLog.text = lines.takeLast(14).joinToString("\n")
    }
}
