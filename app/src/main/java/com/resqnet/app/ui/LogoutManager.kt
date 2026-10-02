package com.resqnet.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.Button
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.mesh.MeshService

object LogoutManager {

    fun showLogoutDialog(activity: Activity) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_logout_slider, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val slideButton = view.findViewById<SlideButton>(R.id.logoutSlideButton)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelLogout)

        slideButton.setAlertTheme()
        slideButton.setText("Slide to log out  ❯❯❯")

        slideButton.onSlideCompleteListener = {
            dialog.dismiss()
            performLogout(activity)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun performLogout(activity: Activity) {
        val app = activity.application as ResQNetApplication
        MeshService.command(activity, MeshService.ACTION_STOP)
        app.profile.displayName = ""
        val intent = Intent(activity, SetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
