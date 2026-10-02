package com.resqnet.app.ui

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
    bottomNav.setOnItemSelectedListener { item ->
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
            marginEnd = (16 * activity.resources.displayMetrics.density).toInt()
        }
        toolbar.addView(badge, params)
    }

    badge.setOnClickListener {
        if (activity !is MeshControlActivity) {
            val intent = Intent(activity, MeshControlActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            activity.startActivity(intent)
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
