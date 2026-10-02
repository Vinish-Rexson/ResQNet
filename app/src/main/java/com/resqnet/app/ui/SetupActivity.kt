package com.resqnet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication

class SetupActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ResQNetApplication
        if (app.profile.configured) { openChat(); return }
        setContentView(R.layout.activity_setup)

        // Request all permissions as soon as the app opens
        if (!PermissionHelper.hasPermissions(this)) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        }

        val fingerprint = app.signer.fingerprint
        findViewById<TextView>(R.id.fingerprint).text = fingerprint

        findViewById<ImageButton>(R.id.btnCopyFingerprint)?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ResQNet Fingerprint", fingerprint))
            Toast.makeText(this, "Fingerprint copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        val nameInput = findViewById<EditText>(R.id.displayName)
        val counterView = findViewById<TextView>(R.id.nameCounter)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counterView?.text = "${s?.length ?: 0}/32"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<Button>(R.id.continueButton).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.length !in 2..32) {
                nameInput.error = "Use 2–32 characters"
            } else { app.profile.displayName = name; openChat() }
        }
    }
    private fun openChat() { startActivity(Intent(this, ChatActivity::class.java)); finish() }
}
