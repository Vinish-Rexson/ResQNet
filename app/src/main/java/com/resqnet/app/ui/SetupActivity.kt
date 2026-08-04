package com.resqnet.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ResQNetApplication
        if (app.profile.configured) { openChat(); return }
        setContentView(R.layout.activity_setup)
        findViewById<TextView>(R.id.fingerprint).text = app.signer.fingerprint
        findViewById<Button>(R.id.continueButton).setOnClickListener {
            val name = findViewById<EditText>(R.id.displayName).text.toString().trim()
            if (name.length !in 2..32) {
                findViewById<EditText>(R.id.displayName).error = "Use 2–32 characters"
            } else { app.profile.displayName = name; openChat() }
        }
    }
    private fun openChat() { startActivity(Intent(this, ChatActivity::class.java)); finish() }
}
