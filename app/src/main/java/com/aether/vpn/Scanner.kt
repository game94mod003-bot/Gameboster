package com.aether.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Scanner {
    data class Res(val ip: String, val ms: Int)

    private val prefixes = listOf(
        "162.159.192.", "162.159.193.", "162.159.195.", "162.159.198.",
        "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99."
    )

    private fun probe(ip: String): Res? {
        var best = Int.MAX_VALUE
        for (i in 0 until 2) {
            try {
                val s = Socket()
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(ip, 443), 2500)
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                s.close()
                if (ms < best) best = ms
            } catch (e: Exception) {
            }
        }
        return if (best == Int.MAX_VALUE) null else Res(ip, best)
    }

    fun scan(count: Int, onProgress: (Int, Int) -> Unit): List<Res> {
        val rnd = Random()
        val set = LinkedHashSet<String>()
        set.add("162.159.198.1")
        set.add("162.159.192.1")
        set.add("162.159.193.1")
        while (set.size < count) {
            set.add(prefixes[rnd.nextInt(prefixes.size)] + (1 + rnd.nextInt(254)))
        }
        val ips = set.toList()
        val pool = Executors.newFixedThreadPool(24)
        val done = AtomicInteger(0)
        val tasks = ips.map { ip ->
            Callable<Res?> {
                val r = probe(ip)
                onProgress(done.incrementAndGet(), ips.size)
                r
            }
        }
        val results = ArrayList<Res>()
        for (f in pool.invokeAll(tasks)) {
            try {
                val r = f.get()
                if (r != null) results.add(r)
            } catch (e: Exception) {
            }
        }
        pool.shutdown()
        return results.sortedBy { it.ms }
    }
}
