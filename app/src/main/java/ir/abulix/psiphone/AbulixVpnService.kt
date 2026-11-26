package ir.abulix.psiphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class AbulixVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopAll(); return START_NOT_STICKY }
        startFg()
        scope.launch { connect() }
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "vpn").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Psiphone abulix").setContentText("متصل است").setContentIntent(pi).setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private suspend fun connect() {
        Vpn.reset(); Vpn.error.value = ""; Vpn.state.value = Vpn.S.CONNECTING
        val cfg = Store.selected()
        if (cfg == null) { fail("کانفیگی انتخاب نشده"); return }
        try {
            val b = Builder().setSession("Psiphone abulix").setMtu(1500)
                .addAddress("10.10.14.1", 30).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            b.addDisallowedApplication(packageName) // our own sockets bypass the tunnel
            pfd = b.establish() ?: run { fail("VPN ساخته نشد"); return }
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val handler = object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(l: Long, s: String?): Long = 0
            }
            core = Libv2ray.newCoreController(handler)
            core!!.startLoop(XrayConfig.build(cfg, Store.beast), pfd!!.fd)
        } catch (e: Throwable) { fail("خطا: ${e.message}"); return }

        var ok = false
        repeat(8) { if (!ok) { delay(1000); ok = measure() > 0 } }
        if (!ok) { fail("سرور جواب نداد. کانفیگ یا IP رو عوض کن (اسکنر)"); return }
        Vpn.state.value = Vpn.S.CONNECTED
        scope.launch { geo() }
        monitor()
    }

    private fun measure(): Long = try { core?.measureDelay("https://www.gstatic.com/generate_204") ?: -1 } catch (e: Throwable) { -1 }

    private suspend fun monitor() = coroutineScope {
        val uid = Process.myUid()
        var lrx = TrafficStats.getUidRxBytes(uid); var ltx = TrafficStats.getUidTxBytes(uid)
        val srx = lrx; val stx = ltx
        val samples = ArrayDeque<Boolean>(); var tick = 0
        while (isActive) {
            delay(1000)
            val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
            Vpn.down.value = (rx - lrx).coerceAtLeast(0); Vpn.up.value = (tx - ltx).coerceAtLeast(0)
            lrx = rx; ltx = tx
            Vpn.totalRx.value = rx - srx; Vpn.totalTx.value = tx - stx
            Vpn.hist.value = (Vpn.hist.value + Vpn.down.value).takeLast(60)
            if (tick++ % 5 == 0) launch {
                val ms = measure(); Vpn.ping.value = ms
                samples.addLast(ms > 0); if (samples.size > 20) samples.removeFirst()
                Vpn.stable.value = samples.count { it } * 100 / samples.size
            }
        }
    }

    private fun geo() {
        repeat(10) {
            try {
                val c = URL("http://ip-api.com/json/?fields=status,country,countryCode,query")
                    .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT))) as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                if (j.optString("status") == "success") {
                    Vpn.country.value = j.getString("country"); Vpn.countryCode.value = j.getString("countryCode"); Vpn.ip.value = j.getString("query"); return
                }
            } catch (_: Throwable) {}
            Thread.sleep(2000)
        }
    }

    private fun fail(msg: String) { Vpn.error.value = msg; Vpn.state.value = Vpn.S.ERROR; stopAll(keepState = true) }

    private fun stopAll(keepState: Boolean = false) {
        scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { core?.stopLoop() } catch (_: Throwable) {}
        core = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        if (!keepState) Vpn.state.value = Vpn.S.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { stopAll() }
    override fun onDestroy() { stopAll(); super.onDestroy() }
}
