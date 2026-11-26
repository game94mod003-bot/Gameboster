package ir.abulix.psiphone

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Cfg(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val proto: String, val secret: String,
    val addr: String, val port: Int, val host: String, val sni: String,
    val path: String, val fp: String = "chrome"
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("proto", proto).put("secret", secret)
        .put("addr", addr).put("port", port).put("host", host).put("sni", sni).put("path", path).put("fp", fp)
    companion object {
        fun from(o: JSONObject) = Cfg(o.optString("id", UUID.randomUUID().toString()), o.getString("name"),
            o.getString("proto"), o.getString("secret"), o.getString("addr"), o.getInt("port"),
            o.getString("host"), o.getString("sni"), o.getString("path"), o.optString("fp", "chrome"))
    }
}

object Store {
    private lateinit var sp: android.content.SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("abulix", Context.MODE_PRIVATE) }

    fun list(): List<Cfg> = try {
        val a = JSONArray(sp.getString("cfgs", "[]")); (0 until a.length()).map { Cfg.from(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    private fun write(l: List<Cfg>) { sp.edit().putString("cfgs", JSONArray(l.map { it.toJson() }).toString()).apply() }
    fun add(c: Cfg) { write(list() + c); if (selectedId.isEmpty()) selectedId = c.id }
    fun delete(id: String) { write(list().filter { it.id != id }) }
    var selectedId: String
        get() = sp.getString("sel", "") ?: ""
        set(v) { sp.edit().putString("sel", v).apply() }
    fun selected(): Cfg? = list().let { l -> l.firstOrNull { it.id == selectedId } ?: l.firstOrNull() }
    var beast: Boolean
        get() = sp.getBoolean("beast", true)
        set(v) { sp.edit().putBoolean("beast", v).apply() }

    // ---- Share: encrypted so ONLY this app can read it ----
    private fun key() = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("abulix::psiphone::v1::lion-sun".toByteArray()), "AES")
    fun share(c: Cfg): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val enc = ci.doFinal(c.toJson().remove("id").toString().toByteArray())
        return "abulix://" + Base64.encodeToString(iv + enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun unshare(s: String): Cfg? = try {
        val raw = Base64.decode(s.removePrefix("abulix://").trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        Cfg.from(JSONObject(String(ci.doFinal(raw.copyOfRange(12, raw.size)))).put("id", UUID.randomUUID().toString()))
    } catch (e: Exception) { null }

    // vless:// or trojan:// over ws+tls (to convert your server link once)
    private fun parseLink(s: String): Cfg? = try {
        val u = Uri.parse(s.trim())
        val host = u.getQueryParameter("host") ?: u.host!!
        Cfg(name = u.fragment ?: "Config", proto = u.scheme!!, secret = u.userInfo!!, addr = u.host!!,
            port = if (u.port > 0) u.port else 443, host = host,
            sni = u.getQueryParameter("sni") ?: host, path = u.getQueryParameter("path") ?: "/")
    } catch (e: Exception) { null }

    fun importText(t: String): Boolean {
        val s = t.trim()
        val c = when {
            s.startsWith("abulix://") -> unshare(s)
            s.startsWith("vless://") || s.startsWith("trojan://") -> parseLink(s)
            else -> null
        } ?: return false
        add(c); return true
    }
}
