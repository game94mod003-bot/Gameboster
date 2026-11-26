package ir.abulix.psiphone

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext

val BG = Color(0xFF0B1220); val CARD = Color(0xFF131C2E); val TEAL = Color(0xFF2DD4BF)
val GOLD = Color(0xFFF5B83D); val RED = Color(0xFFEF4444); val MUTED = Color(0xFF8A97B0)
val VAZIR = FontFamily(Font(R.font.vazirmatn))

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this); handle(intent)
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { App() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    private fun handle(i: Intent?) {
        val d = i?.data?.toString() ?: return
        Toast.makeText(this, if (Store.importText(d)) "کانفیگ اضافه شد ✅" else "کانفیگ نامعتبره", Toast.LENGTH_SHORT).show()
    }
}

@Composable fun T(s: String, size: Int = 14, color: Color = Color.White, bold: Boolean = false, align: TextAlign? = null, mod: Modifier = Modifier) =
    Text(s, fontSize = size.sp, color = color, fontFamily = VAZIR, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align, modifier = mod)

fun fmt(b: Long): String = when { b > 1_073_741_824 -> "%.2f GB".format(b / 1073741824.0); b > 1_048_576 -> "%.1f MB".format(b / 1048576.0); b > 1024 -> "%.0f KB".format(b / 1024.0); else -> "$b B" }
fun flag(cc: String) = if (cc.length == 2) String(Character.toChars(0x1F1E6 + cc[0] - 'A')) + String(Character.toChars(0x1F1E6 + cc[1] - 'A')) else "🌍"

@Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(BG).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f)) { when (tab) { 0 -> Home(); 1 -> Configs(); else -> ScannerScreen() } }
        Row(Modifier.fillMaxWidth().background(CARD).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("🛡️ اتصال", "📦 کانفیگ‌ها", "📡 اسکنر").forEachIndexed { i, s ->
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (tab == i) TEAL.copy(alpha = .18f) else Color.Transparent)
                    .clickable { tab = i }.padding(horizontal = 18.dp, vertical = 10.dp)) { T(s, 13, if (tab == i) TEAL else MUTED, tab == i) }
            }
        }
    }
}

@Composable fun Card(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(mod.clip(RoundedCornerShape(20.dp)).background(CARD).padding(14.dp), content = content)

@Composable fun Stat(label: String, value: String, color: Color, mod: Modifier) = Card(mod) {
    T(label, 11, MUTED); Spacer(Modifier.height(4.dp)); T(value, 16, color, true)
}

@Composable fun Home() {
    val ctx = LocalContext.current
    val st by Vpn.state.collectAsState(); val ping by Vpn.ping.collectAsState(); val stable by Vpn.stable.collectAsState()
    val cc by Vpn.countryCode.collectAsState(); val country by Vpn.country.collectAsState(); val ip by Vpn.ip.collectAsState()
    val down by Vpn.down.collectAsState(); val up by Vpn.up.collectAsState(); val hist by Vpn.hist.collectAsState()
    val trx by Vpn.totalRx.collectAsState(); val ttx by Vpn.totalTx.collectAsState(); val err by Vpn.error.collectAsState()
    var beast by remember { mutableStateOf(Store.beast) }

    fun launchSvc() { ctx.startForegroundService(Intent(ctx, AbulixVpnService::class.java)) }
    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == Activity.RESULT_OK) launchSvc() }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    val on = st == Vpn.S.CONNECTED; val busy = st == Vpn.S.CONNECTING
    val col = when { on -> TEAL; busy -> GOLD; st == Vpn.S.ERROR -> RED; else -> MUTED }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            T("Psiphone abulix", 26, GOLD, true, TextAlign.Center, Modifier.fillMaxWidth())
            T("🦁☀️  فرانتینگ CDN • سرعت و پایداری", 12, MUTED, false, TextAlign.Center, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp).scale(if (busy || on) pulse else 1f).clip(CircleShape).background(col.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(160.dp).clip(CircleShape).background(Brush.radialGradient(listOf(col.copy(alpha = .9f), col.copy(alpha = .45f))))
                        .clickable {
                            if (on || busy) ctx.startService(Intent(ctx, AbulixVpnService::class.java).setAction("stop"))
                            else if (Store.selected() == null) Toast.makeText(ctx, "اول یه کانفیگ اضافه کن 📦", Toast.LENGTH_LONG).show()
                            else { Store.beast = beast; val i = VpnService.prepare(ctx); if (i != null) vpnPerm.launch(i) else launchSvc() }
                        }, contentAlignment = Alignment.Center) {
                        T(when { on -> "قطع کن"; busy -> "در حال اتصال…"; else -> "وصل شو" }, 20, BG, true)
                    }
                }
            }
            if (err.isNotEmpty() && st == Vpn.S.ERROR) T(err, 12, RED, false, TextAlign.Center, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T("🔥 Beast Mode", 15, GOLD, true); T("Fragment ضد DPI + اثرانگشت TLS رندوم", 11, MUTED) }
                    Switch(beast, { beast = it; Store.beast = it }, enabled = !on && !busy)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("پینگ", if (ping > 0) "$ping ms" else "—", if (ping in 1..300) TEAL else GOLD, Modifier.weight(1f))
                Stat("پایداری", if (on) "$stable%" else "—", if (stable >= 90) TEAL else GOLD, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("کشور خروجی", if (country.isNotEmpty()) "${flag(cc)} $country" else "—", Color.White, Modifier.weight(1f))
                Stat("IP خروجی", ip.ifEmpty { "—" }, Color.White, Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    T("⬇ ${fmt(down)}/s", 15, TEAL, true); T("⬆ ${fmt(up)}/s", 15, GOLD, true)
                }
                Spacer(Modifier.height(10.dp))
                Chart(hist)
                Spacer(Modifier.height(8.dp))
                T("مصرف این نشست: ⬇ ${fmt(trx)}   ⬆ ${fmt(ttx)}", 12, MUTED)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable fun Chart(data: List<Long>) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        if (data.size < 2) return@Canvas
        val mx = (data.max()).coerceAtLeast(1024).toFloat(); val dx = size.width / 59f
        val path = Path(); val fill = Path()
        data.forEachIndexed { i, v ->
            val x = size.width - (data.size - 1 - i) * dx; val y = size.height - (v / mx) * size.height * .92f
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, size.height); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(TEAL.copy(alpha = .35f), Color.Transparent)))
        drawPath(path, TEAL, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round))
        drawLine(Color.White.copy(alpha = .08f), Offset(0f, size.height), Offset(size.width, size.height))
    }
}

@Composable fun Configs() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Store.list()) }; var sel by remember { mutableStateOf(Store.selected()?.id ?: "") }
    var dlg by remember { mutableStateOf(false) }
    fun refresh() { list = Store.list(); sel = Store.selected()?.id ?: "" }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("کانفیگ‌ها", 22, GOLD, true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                Toast.makeText(ctx, if (Store.importText(t)) "اضافه شد ✅" else "کلیپ‌بورد کانفیگ معتبر نداره", Toast.LENGTH_SHORT).show(); refresh()
            }, colors = ButtonDefaults.buttonColors(TEAL, BG)) { T("📋 از کلیپ‌بورد", 12, BG, true) }
            OutlinedButton({ dlg = true }) { T("➕ دستی", 12, TEAL) }
        }
        T("لینک vless:// یا trojan:// (ws+tls) یا لینک abulix:// قبول میشه.", 11, MUTED)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { c ->
                Card(Modifier.fillMaxWidth().clickable { Store.selectedId = c.id; refresh() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(c.id == sel, { Store.selectedId = c.id; refresh() })
                        Column(Modifier.weight(1f)) { T(c.name, 15, Color.White, true); T("${c.proto} • ${c.addr}:${c.port} • SNI ${c.sni}", 11, MUTED) }
                        TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Store.share(c)), "اشتراک")) }) { T("📤", 18) }
                        TextButton({ Store.delete(c.id); refresh() }) { T("🗑", 18) }
                    }
                }
            }
        }
    }
    if (dlg) AddDialog({ dlg = false }) { Store.add(it); refresh(); dlg = false }
}

@Composable fun AddDialog(onDismiss: () -> Unit, onAdd: (Cfg) -> Unit) {
    var name by remember { mutableStateOf("") }; var proto by remember { mutableStateOf("vless") }; var secret by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }; var port by remember { mutableStateOf("443") }; var host by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }; var path by remember { mutableStateOf("/") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = CARD,
        confirmButton = { TextButton({
            if (secret.isNotBlank() && addr.isNotBlank() && host.isNotBlank())
                onAdd(Cfg(name = name.ifBlank { host }, proto = proto, secret = secret.trim(), addr = addr.trim(), port = port.toIntOrNull() ?: 443,
                    host = host.trim(), sni = sni.ifBlank { host }.trim(), path = path.ifBlank { "/" }))
        }) { T("ذخیره", 14, TEAL, true) } },
        dismissButton = { TextButton(onDismiss) { T("لغو", 14, MUTED) } },
        title = { T("کانفیگ جدید", 16, GOLD, true) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { listOf("vless", "trojan").forEach { p -> FilterChip(proto == p, { proto = p }, { T(p, 12) }, Modifier.padding(end = 6.dp)) } }
                @Composable fun F(v: String, l: String, set: (String) -> Unit) = OutlinedTextField(v, set, label = { T(l, 11, MUTED) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = VAZIR, fontSize = 13.sp))
                F(name, "نام") { name = it }; F(secret, if (proto == "vless") "UUID" else "Password") { secret = it }
                F(addr, "آدرس (IP تمیز یا دامنه)") { addr = it }; F(port, "پورت") { port = it }
                F(host, "Host (دامنه‌ی پشت CDN)") { host = it }; F(sni, "SNI (خالی = Host)") { sni = it }; F(path, "Path وب‌سوکت") { path = it }
            }
        })
}

@Composable fun ScannerScreen() {
    val run by Scanner.running.collectAsState(); val done by Scanner.done.collectAsState()
    val total by Scanner.total.collectAsState(); val hits by Scanner.hits.collectAsState()
    val cfg = Store.selected(); val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("اسکنر IP تمیز", 22, GOLD, true)
        T("روی رنج‌های رسمی Cloudflare با اینترنت همین لحظه‌ی خودت تست میکنه: TCP + TLS با SNI کانفیگ + درخواست واقعی HTTP (نه فقط پینگ).", 11, MUTED)
        Button({ if (run) Scanner.stop() else if (cfg == null) Toast.makeText(ctx, "اول کانفیگ اضافه کن", Toast.LENGTH_SHORT).show() else Scanner.start(cfg.sni, cfg.host, 40) },
            colors = ButtonDefaults.buttonColors(if (run) RED else TEAL, BG), modifier = Modifier.fillMaxWidth()) { T(if (run) "توقف" else "🚀 شروع اسکن", 14, BG, true) }
        if (total > 0) { LinearProgressIndicator({ done / total.toFloat() }, Modifier.fillMaxWidth(), color = TEAL); T("$done / $total — سالم: ${hits.size}", 12, MUTED) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits.take(60)) { h ->
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { T(h.ip, 15, Color.White, true); T("${h.ms} ms • ${h.colo} • loss ${h.loss}%", 11, if (h.ms < 400) TEAL else GOLD) }
                        Button({
                            if (cfg != null) { Store.add(cfg.copy(id = java.util.UUID.randomUUID().toString(), name = "${cfg.name} · ${h.ip}", addr = h.ip)); Store.selectedId = Store.list().last().id
                                Toast.makeText(ctx, "کانفیگ جدید با این IP ساخته و انتخاب شد ✅", Toast.LENGTH_SHORT).show() }
                        }, colors = ButtonDefaults.buttonColors(GOLD, BG)) { T("استفاده", 12, BG, true) }
                    }
                }
            }
        }
    }
}
