package com.resqnet.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.resqnet.app.R

class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "resqnet_onboarding_prefs"
        const val KEY_ONBOARDING_COMPLETED = "has_completed_onboarding"

        fun isOnboardingCompleted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        }

        fun setOnboardingCompleted(context: Context, completed: Boolean = true) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        }
    }

    data class OnboardingItem(
        val title: String,
        val description: String,
        val illustrationRes: Int
    )

    private val pages = listOf(
        OnboardingItem(
            title = "Stay connected, even offline",
            description = "ResQNet helps you communicate with nearby people when mobile networks or the internet are unavailable.",
            illustrationRes = R.drawable.onboarding_mesh_network
        ),
        OnboardingItem(
            title = "Send help when it matters",
            description = "Share emergency alerts, your location, and urgent needs with people nearby through ResQNet's communication network.",
            illustrationRes = R.drawable.onboarding_emergency_sos
        ),
        OnboardingItem(
            title = "Communicate with people you trust",
            description = "Create private circles, connect with trusted contacts, and exchange encrypted messages with people who matter to you.",
            illustrationRes = R.drawable.onboarding_trusted_circles
        ),
        OnboardingItem(
            title = "Find your way, without internet",
            description = "Access downloaded offline maps, explore nearby points of interest, and plan routes when online map services are unavailable.",
            illustrationRes = R.drawable.onboarding_offline_navigation
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingCompleted(this)) {
            val intent = Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
            finish()
            return
        }
        val bgColor = ContextCompat.getColor(this, R.color.color_onboarding_bg)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContentView(R.layout.activity_onboarding)

        val viewPager = findViewById<ViewPager2>(R.id.onboardingViewPager)
        val btnAction = findViewById<MaterialButton>(R.id.btnOnboardingAction)
        val btnSkip = findViewById<MaterialButton>(R.id.btnOnboardingSkip)

        val dots = listOf(
            findViewById<View>(R.id.onboardingDot0),
            findViewById<View>(R.id.onboardingDot1),
            findViewById<View>(R.id.onboardingDot2),
            findViewById<View>(R.id.onboardingDot3)
        )

        viewPager.adapter = object : RecyclerView.Adapter<OnboardingViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
                return OnboardingViewHolder(view)
            }

            override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
                val page = pages[position]
                holder.ivIllustration.setImageResource(page.illustrationRes)
                holder.tvTitle.text = page.title
                holder.tvDescription.text = page.description
            }

            override fun getItemCount(): Int = pages.size
        }

        fun updateIndicators(position: Int) {
            val isLastPage = (position == pages.lastIndex)
            btnAction.text = if (isLastPage) "Get Started  ✓" else "Next  →"
            btnSkip.visibility = if (isLastPage) View.INVISIBLE else View.VISIBLE

            dots.forEachIndexed { i, dot ->
                val isActive = (i == position)
                val params = dot.layoutParams
                val targetWidth = if (isActive) (22 * resources.displayMetrics.density).toInt() else (6 * resources.displayMetrics.density).toInt()
                params.width = targetWidth
                dot.layoutParams = params
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (isActive) R.color.palette_amber else R.color.color_divider)
                )
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
            }
        })

        btnAction.setOnClickListener {
            if (viewPager.currentItem < pages.lastIndex) {
                viewPager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        btnSkip.setOnClickListener {
            TutorialManager.setTutorialCompleted(this, true)
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        setOnboardingCompleted(this, true)
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private class OnboardingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIllustration: ImageView = view.findViewById(R.id.ivOnboardingIllustration)
        val tvTitle: TextView = view.findViewById(R.id.tvOnboardingTitle)
        val tvDescription: TextView = view.findViewById(R.id.tvOnboardingDescription)
    }
}
