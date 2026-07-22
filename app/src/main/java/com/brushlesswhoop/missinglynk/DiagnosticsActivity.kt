package com.brushlesswhoop.missinglynk

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar

/**
 * Shows the on-device diagnostics log so a bad session can be inspected on the phone, no
 * computer needed. Share exports the file (e.g. to send on); Clear wipes it before a session.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        text = findViewById(R.id.diag_text)
        scroll = findViewById(R.id.diag_scroll)
        findViewById<Button>(R.id.diag_share).setOnClickListener { share() }
        findViewById<Button>(R.id.diag_clear).setOnClickListener {
            Diag.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        text.text = Diag.read()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun share() {
        val f = Diag.file() ?: return
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
