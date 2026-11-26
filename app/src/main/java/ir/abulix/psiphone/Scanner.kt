package ir.abulix.psiphone

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class Hit(val ip: String, val ms: Long, val colo: String, val loss: Int)

object Scanner {
    // Official public Cloudflare IPv4 ranges (cloudflare.com/ips-v4)
    val RANGES = listOf("173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
        "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22")

    val running = MutableStateFlow(false)
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val hits = MutableStateFlow<List<Hit>>(emptyList())
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stop() { job?.cancel(); running.value = false }

    fun start(sni: String, host: String, perRange: Int) {
        job?.cancel(); hits.value = emptyList(); done.value = 0
        job = scope.launch {
            running.value = true
            val ips = sample(perRange); total.value = ips.size
            val sem = Semaphore(80)
            ips.map { ip -> launch { sem.withPermit { probe(ip, sni, host)?.let { h -> hits.update { (it + h).sortedBy { x -> x.ms + x.loss * 500 } } } }; done.update { it + 1 } } }.joinAll()
            running.value = false
        }
    }

    private fun sample(n: Int): List<String> = RANGES.flatMap { r ->
        val (b, p) = r.split("/"); val o = b.split(".").map { it.toLong() }
        val base = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
        val size = 1L shl (32 - p.toInt())
        (1..n).map { val v = base + (2 + (Math.random() * (size - 4)).toLong()); "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}" }
    }.distinct()

    // Real probe: TCP -> TLS handshake with your SNI -> real HTTP request through the CDN edge.
    private fun probeOnce(ip: String, sni: String, host: String): Pair<Long, String>? = try {
        val t0 = System.nanoTime()
        val raw = Socket(); raw.connect(InetSocketAddress(ip, 443), 1500)
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, 443, true) as SSLSocket
        val p = ssl.sslParameters; p.serverNames = listOf(SNIHostName(sni)); ssl.sslParameters = p
        ssl.soTimeout = 2000; ssl.startHandshake()
        val ms = (System.nanoTime() - t0) / 1_000_000
        ssl.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()); ssl.outputStream.flush()
        val buf = ByteArray(2048); val n = ssl.inputStream.read(buf)
        val txt = if (n > 0) String(buf, 0, n) else ""
        ssl.close()
        if (txt.startsWith("HTTP/1.")) ms to (Regex("colo=(\\w+)").find(txt)?.groupValues?.get(1) ?: "-") else null
    } catch (e: Throwable) { null }

    private fun probe(ip: String, sni: String, host: String): Hit? {
        val r = (1..3).map { probeOnce(ip, sni, host) }
        val ok = r.filterNotNull(); if (ok.size < 2) return null
        return Hit(ip, ok.map { it.first }.average().toLong(), ok.first().second, (3 - ok.size) * 33)
    }
}
