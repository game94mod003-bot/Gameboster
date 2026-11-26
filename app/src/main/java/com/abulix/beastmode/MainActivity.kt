package com.abulix.beastmode

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.StringReader

class MainActivity : AppCompatActivity() {
    private lateinit var backend: GoBackend
    private var up = false
    private val tunnel = object : Tunnel {
        override fun getName() = "abulix"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread { render(newState == Tunnel.State.UP) }
        }
    }
    private val prepare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) setState(true)
        else toast("مجوز VPN داده نشد")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        backend = GoBackend(applicationContext)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val et = findViewById<EditText>(R.id.config)
        et.setText(prefs.getString("wg", ""))
        findViewById<Button>(R.id.toggle).setOnClickListener {
            prefs.edit().putString("wg", et.text.toString()).apply()
            if (up) setState(false) else {
                val i: Intent? = VpnService.prepare(this)
                if (i != null) prepare.launch(i) else setState(true)
            }
        }
    }

    private fun setState(on: Boolean) {
        val text = findViewById<EditText>(R.id.config).text.toString()
        Thread {
            try {
                val cfg = if (on) Config.parse(StringReader(text).buffered()) else null
                backend.setState(tunnel, if (on) Tunnel.State.UP else Tunnel.State.DOWN, cfg)
                runOnUiThread { render(on) }
            } catch (e: Exception) {
                runOnUiThread { toast("خطا: ${e.message}") ; render(false) }
            }
        }.start()
    }

    private fun render(on: Boolean) {
        up = on
        findViewById<TextView>(R.id.status).setText(if (on) R.string.st_on else R.string.st_off)
        findViewById<Button>(R.id.toggle).setText(if (on) R.string.disconnect else R.string.connect)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
