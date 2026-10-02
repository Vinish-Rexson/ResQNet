package com.resqnet.app.ui

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.resqnet.app.R
import com.resqnet.app.mesh.MeshRuntime
import kotlinx.coroutines.launch

fun setupBottomNav(activity: Activity, currentTabId: Int) {
    val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
    bottomNav.selectedItemId = currentTabId
    val blueColor = androidx.core.content.ContextCompat.getColor(activity, R.color.bottom_nav_bg)
    bottomNav.setBackgroundColor(blueColor)
    bottomNav.backgroundTintList = android.content.res.ColorStateList.valueOf(blueColor)

    // Ensure system navigation bar / safe area matches the exact same deep navy color
    activity.window.navigationBarColor = blueColor
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        activity.window.isNavigationBarContrastEnforced = false
    }
    androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightNavigationBars = false

    // Eliminate the bottom safe-area gap / cream strip caused by fitsSystemWindows on root layout,
    // and seamlessly push chat input above soft keyboard when IME appears.
    val parentLayout = bottomNav.parent as? ViewGroup
    val divider = parentLayout?.findViewById<View>(R.id.bottomNavDivider)

    if (parentLayout != null) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(parentLayout) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val isImeVisible = ime.bottom > navBars.bottom

            if (isImeVisible) {
                // Keyboard is open: hide bottom navbar and pad parent by keyboard height
                bottomNav.visibility = View.GONE
                divider?.visibility = View.GONE
                v.setPadding(0, 0, 0, ime.bottom)
            } else {
                // Keyboard is closed: show bottom navbar and extend into navigation safe area
                bottomNav.visibility = View.VISIBLE
                divider?.visibility = View.VISIBLE
                v.setPadding(0, 0, 0, 0)
                bottomNav.setPadding(0, 0, 0, navBars.bottom)
            }

            // Dispatch insets to children (CoordinatorLayout / AppBarLayout)
            for (i in 0 until (v as ViewGroup).childCount) {
                val child = v.getChildAt(i)
                if (child !== bottomNav && child !== divider) {
                    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(child, insets)
                }
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(parentLayout)

        parentLayout.post {
            val root = bottomNav.rootView
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            if (insets != null) {
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                val isImeVisible = ime.bottom > navBars.bottom
                if (isImeVisible) {
                    bottomNav.visibility = View.GONE
                    divider?.visibility = View.GONE
                    parentLayout.setPadding(0, 0, 0, ime.bottom)
                } else {
                    bottomNav.visibility = View.VISIBLE
                    divider?.visibility = View.VISIBLE
                    parentLayout.setPadding(0, 0, 0, 0)
                    bottomNav.setPadding(0, 0, 0, navBars.bottom)
                }
            }
        }
    }

    // Subtle gentle pop for active tab icon on entry (Instagram style)
    bottomNav.post {
        val selectedItem = bottomNav.findViewById<View>(currentTabId)
        val icon = selectedItem?.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view) ?: selectedItem
        icon?.let { v ->
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(160)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    bottomNav.setOnItemSelectedListener { item ->
        val itemView = bottomNav.findViewById<View>(item.itemId)
        // Animate ONLY the icon — labels stay crisp and rock-solid
        val iconView = itemView?.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view) ?: itemView

        iconView?.let { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            v.animate()
                .scaleX(0.86f)
                .scaleY(0.86f)
                .setDuration(80)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    v.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .setDuration(110)
                        .withEndAction {
                            v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(70)
                                .start()
                        }
                        .start()
                }
                .start()
        }

        if (item.itemId == currentTabId) return@setOnItemSelectedListener true

        val intent = when (item.itemId) {
            R.id.nav_chat -> Intent(activity, ChatActivity::class.java)
            R.id.nav_contacts -> Intent(activity, ContactsActivity::class.java)
            R.id.nav_circles -> Intent(activity, CirclesActivity::class.java)
            R.id.nav_mesh -> Intent(activity, MeshControlActivity::class.java)
            R.id.nav_navigate -> Intent(activity, NavigateActivity::class.java)
            else -> null
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(intent)
            // Zero flicker: bottom bar remains completely static like a Single-Page App
            activity.overridePendingTransition(0, 0)
        }
        false
    }

    if (activity is AppCompatActivity) {
        setupMeshAppBarBadge(activity)
    }
}

/**
 * Attaches a clean, consistent "Mesh Active" status badge to the AppBar across all screens.
 * Shows only when the mesh is active, and tapping it directly opens MeshControlActivity.
 */
fun setupMeshAppBarBadge(activity: AppCompatActivity, explicitToolbar: Toolbar? = null) {
    val toolbar = explicitToolbar ?: activity.findViewById(R.id.toolbar) ?: return
    var badge = toolbar.findViewById<View>(R.id.meshActiveBadge)
    if (badge == null) {
        badge = LayoutInflater.from(activity).inflate(R.layout.view_mesh_active_badge, toolbar, false)
        val params = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
        }
        toolbar.addView(badge, params)
    }

    // Add Tutorial button to the App Bar across screens
    var btnTutorial = toolbar.findViewById<View>(R.id.btnTutorial)
    if (btnTutorial == null) {
        val tutorialBtn = LayoutInflater.from(activity).inflate(R.layout.view_tutorial_appbar_button, toolbar, false)
        val tutorialParams = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
        }
        toolbar.addView(tutorialBtn, tutorialParams)
        tutorialBtn.setOnClickListener {
            TutorialManager.startFullTour(activity, force = true)
        }
    }

    // Add Logout button to the App Bar across screens
    var btnLogout = toolbar.findViewById<View>(R.id.btnLogoutAppbar)
    if (btnLogout == null) {
        val logoutBtn = LayoutInflater.from(activity).inflate(R.layout.view_logout_appbar_button, toolbar, false)
        val logoutParams = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = (12 * activity.resources.displayMetrics.density).toInt()
        }
        toolbar.addView(logoutBtn, logoutParams)
        logoutBtn.setOnClickListener {
            LogoutManager.showLogoutDialog(activity)
        }
    }

    badge.setOnClickListener {
        if (activity !is MeshControlActivity) {
            val intent = Intent(activity, MeshControlActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
        }
    }

    val badgeText = badge.findViewById<TextView>(R.id.badgeMeshText)
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            MeshRuntime.state.collect { state ->
                badge.visibility = if (state.active) View.VISIBLE else View.GONE
                if (state.active) {
                    badgeText?.text = if (state.peerCount > 0) "MESH ACTIVE · ${state.peerCount}" else "MESH ACTIVE"
                }
            }
        }
    }
}
