package com.resqnet.app

import android.content.Context

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("resqnet_profile", Context.MODE_PRIVATE)
    var displayName: String
        get() = prefs.getString("display_name", "") ?: ""
        set(value) { prefs.edit().putString("display_name", value.trim()).apply() }
    val configured: Boolean get() = displayName.isNotBlank()

    var demoTopologyEnabled: Boolean
        get() = prefs.getBoolean("demo_topology", false)
        set(value) { prefs.edit().putBoolean("demo_topology", value).apply() }

    var demoRole: DemoRole
        get() = runCatching { DemoRole.valueOf(prefs.getString("demo_role", DemoRole.NONE.name)!!) }.getOrDefault(DemoRole.NONE)
        set(value) { prefs.edit().putString("demo_role", value.name).apply() }
}

enum class DemoRole(val code: Byte) { NONE(0), A(1), B(2), C(3);
    companion object { fun from(code: Byte) = entries.firstOrNull { it.code == code } ?: NONE }
}
