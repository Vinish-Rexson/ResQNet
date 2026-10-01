package com.resqnet.app.ui

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.resqnet.app.R

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
}
