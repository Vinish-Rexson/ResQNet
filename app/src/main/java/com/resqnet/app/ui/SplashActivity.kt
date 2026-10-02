package com.resqnet.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.media.MediaPlayer
import android.widget.ImageView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication

class SplashActivity : AppCompatActivity() {

    private var hasNavigated = false
    private var videoView: VideoView? = null
    private var splashPoster: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Intercept Android 12+ system splash screen immediately
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge-to-edge immersive styling with transparent bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContentView(R.layout.activity_splash)

        videoView = findViewById(R.id.splashVideoView)
        splashPoster = findViewById(R.id.splashPoster)

        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.splashscreen}")
        videoView?.setVideoURI(videoUri)

        // As soon as the first video frame begins hardware rendering, hide the poster seamlessly
        videoView?.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                splashPoster?.visibility = View.GONE
                true
            } else {
                false
            }
        }

        videoView?.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView?.start()
        }

        videoView?.setOnCompletionListener {
            navigateNext()
        }

        videoView?.setOnErrorListener { _, _, _ ->
            navigateNext()
            true
        }
    }

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true

        videoView?.stopPlayback()

        val app = application as ResQNetApplication
        val intent = if (app.profile.configured) {
            Intent(this, ChatActivity::class.java)
        } else {
            Intent(this, SetupActivity::class.java)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (!hasNavigated) {
            videoView?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasNavigated && videoView?.isPlaying == false) {
            videoView?.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView?.stopPlayback()
    }
}
