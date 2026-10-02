package com.resqnet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication

import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class SetupActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ResQNetApplication
        val isEditing = intent.getBooleanExtra("IS_EDITING", false)
        if (app.profile.configured && !isEditing) { openChat(); return }
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContentView(R.layout.activity_setup)

        val cardAndButton = findViewById<View>(R.id.cardAndButtonContainer)
        val continueButton = findViewById<View>(R.id.continueButton)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val isImeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())

            if (isImeVisible && imeInsets.bottom > 0) {
                cardAndButton.post {
                    val loc = IntArray(2)
                    continueButton.getLocationOnScreen(loc)
                    val currentTranslation = cardAndButton.translationY
                    val rawButtonBottom = loc[1] + continueButton.height - currentTranslation
                    val screenHeight = resources.displayMetrics.heightPixels
                    val keyboardTop = screenHeight - imeInsets.bottom
                    val margin = (16 * resources.displayMetrics.density).toInt()
                    val neededShift = (rawButtonBottom - keyboardTop + margin).toFloat()
                    if (neededShift > 0f) {
                        cardAndButton.animate()
                            .translationY(-neededShift)
                            .setDuration(250)
                            .start()
                    }
                }
            } else {
                cardAndButton.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .start()
            }
            insets
        }

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        if (isEditing) {
            btnBack?.visibility = android.view.View.VISIBLE
            btnBack?.setOnClickListener { finish() }
        }

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


        val slideButton = findViewById<SlideButton>(R.id.continueButton)

        if (isEditing && app.profile.displayName.isNotEmpty()) {
            nameInput.setText(app.profile.displayName)
            counterView?.text = "${app.profile.displayName.length}/32"
            slideButton?.setText("Slide to save changes  ❯❯❯")
        }

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counterView?.text = "${s?.length ?: 0}/32"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        slideButton.onSlideCompleteListener = {
            val name = nameInput.text.toString().trim()
            if (name.length !in 2..32) {
                nameInput.error = "Use 2–32 characters"
                nameInput.requestFocus()
                slideButton.resetSlider(shake = true)
            } else { 
                app.profile.displayName = name
                if (isEditing) finish() else openChat()
            }
        }
    }

    private fun openChat() {
        if (!OnboardingActivity.isOnboardingCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        } else {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        finish()
    }
}
