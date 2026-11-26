package com.aether.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Time-boxed scanner for Cloudflare WARP endpoints (TCP latency + TLS/h2 check). */
object Scanner {
    data class Res(val ip: String, val ms: Int, val h2: Boolean)

    @Volatile var tested = 0
    @Volatile var alive = 0
    @Volatile var h2Count = 0
    @Volatile var best: Res? = null
    @Volatile var stop = false

    private val results = ArrayList<Res>()

    private fun candidates(): List<String> {
        val l = ArrayList<String>()
        for (p in 192..199) for (o in 1..254) l.add("162.159.$p.$o")
        for (p in 96..99) for (o in 1..254) l.add("188.114.$p.$o")
        l.shuffle()
        return l
    }

    private fun better(a: Res, b: Res?): Boolean {
        if (b == null) return true
        if (a.h2 != b.h2) return a.h2
        return a.ms < b.ms
    }

    private fun probe(ip: String, ctx: SSLContext): Res? {
        val s = Socket()
        try {
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(ip, 443), 2000)
            val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
            var h2 = false
            try {
                val ssl = ctx.socketFactory.createSocket(s, "consumer-masque.cloudflareclient.com", 443, true) as SSLSocket
                val sp = ssl.sslParameters
                sp.applicationProtocols = arrayOf("h2")
                sp.setServerNames(listOf<SNIServerName>(SNIHostName("consumer-masque.cloudflareclient.com")))
                ssl.sslParameters = sp
                ssl.soTimeout = 3000
                ssl.startHandshake()
                h2 = ssl.applicationProtocol == "h2"
            } catch (e: Exception) {
            }
            return Res(ip, ms, h2)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Blocks until the time is up, the list is exhausted or [stop] is set. Returns best results first. */
    fun scan(durationSec: Int): List<Res> {
        stop = false
        tested = 0
        alive = 0
        h2Count = 0
        best = null
        synchronized(results) { results.clear() }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), null)

        val list = candidates()
        val idx = AtomicInteger(0)
        val deadline = System.currentTimeMillis() + durationSec * 1000L
        val workers = ArrayList<Thread>()
        for (w in 0 until 24) {
            val t = Thread {
                while (!stop && System.currentTimeMillis() < deadline) {
                    val i = idx.getAndIncrement()
                    if (i >= list.size) break
                    val r = probe(list[i], ctx)
                    tested++
                    if (r != null) {
                        alive++
                        if (r.h2) h2Count++
                        synchronized(results) {
                            results.add(r)
                            if (better(r, best)) best = r
                        }
                    }
                }
            }
            t.start()
            workers.add(t)
        }
        for (t in workers) {
            try {
                t.join()
            } catch (e: Exception) {
            }
        }
        val sorted = synchronized(results) { results.sortedWith(compareBy<Res>({ !it.h2 }, { it.ms })) }
        return sorted
    }
}
