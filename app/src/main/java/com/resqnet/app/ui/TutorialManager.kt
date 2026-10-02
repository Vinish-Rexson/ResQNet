package com.resqnet.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.resqnet.app.R

object TutorialManager {

    private const val PREFS_NAME = "resqnet_tutorial_prefs"
    private const val KEY_TUTORIAL_COMPLETED = "has_completed_spotlight_tutorial"

    const val EXTRA_TUTORIAL_PHASE = "extra_tutorial_phase"
    const val PHASE_CHAT = "phase_chat"
    const val PHASE_CIRCLES = "phase_circles"
    const val PHASE_MESH = "phase_mesh"
    const val PHASE_NAVIGATE = "phase_navigate"

    fun isTutorialCompleted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false)
    }

    fun setTutorialCompleted(context: Context, completed: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_TUTORIAL_COMPLETED, completed).apply()
    }

    fun showTutorial(activity: Activity) = startFullTour(activity)

    fun startFullTour(activity: Activity) {
        if (activity is ChatActivity) {
            runChatTour(activity)
        } else {
            val intent = Intent(activity, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_TUTORIAL_PHASE, PHASE_CHAT)
            }
            activity.startActivity(intent)
        }
    }

    fun checkAndResumeTour(activity: Activity) {
        val phase = activity.intent?.getStringExtra(EXTRA_TUTORIAL_PHASE) ?: return
        activity.intent?.removeExtra(EXTRA_TUTORIAL_PHASE)

        activity.window.decorView.postDelayed({
            when (phase) {
                PHASE_CHAT -> if (activity is ChatActivity) runChatTour(activity)
                PHASE_CIRCLES -> if (activity is CirclesActivity) runCirclesTour(activity)
                PHASE_MESH -> if (activity is MeshControlActivity) runMeshTour(activity)
                PHASE_NAVIGATE -> if (activity is NavigateActivity) runNavigateTour(activity)
            }
        }, 350)
    }

    fun runChatTour(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targets = mutableListOf<SpotlightOverlayView.SpotlightTarget>()

        val statusBar = activity.findViewById<View>(R.id.statusBar)
        if (statusBar != null && statusBar.visibility == View.VISIBLE) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = statusBar,
                    title = "Live Mesh Status",
                    description = "Monitors your direct Bluetooth mesh connections. Tap 'Manage' anytime to inspect node details or start relaying.",
                    stepBadge = "1 of 7",
                    customButtonText = "Next  →"
                )
            )
        }

        val messageInput = activity.findViewById<View>(R.id.messageInput)
        if (messageInput != null && messageInput.visibility == View.VISIBLE) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = messageInput,
                    title = "Broadcast Emergency Alert",
                    description = "Type and send signed emergency messages to all nearby phones over Bluetooth mesh. Zero cell service, SIM card, or Wi-Fi required.",
                    stepBadge = "2 of 7",
                    customButtonText = "Next  →"
                )
            )
        }

        val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        val circlesTab = bottomNav?.findViewById<View>(R.id.nav_circles)
        if (circlesTab != null) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = circlesTab,
                    title = "Emergency Circles",
                    description = "Coordinate private rescue squads and broadcast live safety statuses. Tap below to visit Circles.",
                    stepBadge = "3 of 7",
                    customButtonText = "Go to Circles  →",
                    onNextClicked = {
                        val intent = Intent(activity, CirclesActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra(EXTRA_TUTORIAL_PHASE, PHASE_CIRCLES)
                        }
                        activity.startActivity(intent)
                    }
                )
            )
        }

        if (targets.isNotEmpty()) {
            val overlay = SpotlightOverlayView.attach(activity)
            overlay.start(targets)
        }
    }

    fun runCirclesTour(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targets = mutableListOf<SpotlightOverlayView.SpotlightTarget>()

        val fabCreate = activity.findViewById<View>(R.id.fabCreateCircle)
        if (fabCreate != null && fabCreate.visibility == View.VISIBLE) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = fabCreate,
                    title = "Create Circles & Live Status",
                    description = "Form private response circles (Family, Medical, Evac Squad) to broadcast real-time safety status (Safe, Need Help, Unknown).",
                    stepBadge = "4 of 7",
                    customButtonText = "Next  →"
                )
            )
        }

        val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        val meshTab = bottomNav?.findViewById<View>(R.id.nav_mesh)
        if (meshTab != null) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = meshTab,
                    title = "Mesh Engine Controls",
                    description = "Control Bluetooth relaying and discover nearby nodes. Tap below to view Mesh controls.",
                    stepBadge = "5 of 7",
                    customButtonText = "Go to Mesh  →",
                    onNextClicked = {
                        val intent = Intent(activity, MeshControlActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra(EXTRA_TUTORIAL_PHASE, PHASE_MESH)
                        }
                        activity.startActivity(intent)
                    }
                )
            )
        }

        if (targets.isNotEmpty()) {
            val overlay = SpotlightOverlayView.attach(activity)
            overlay.start(targets)
        }
    }

    fun runMeshTour(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targets = mutableListOf<SpotlightOverlayView.SpotlightTarget>()

        val startMeshBtn = activity.findViewById<View>(R.id.startMeshButton)
        if (startMeshBtn != null && startMeshBtn.visibility == View.VISIBLE) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = startMeshBtn,
                    title = "Start Mesh Relaying",
                    description = "Activate this engine so your phone acts as a relay node, hopping emergency messages across phones to reach help miles away.",
                    stepBadge = "6 of 7",
                    customButtonText = "Next  →"
                )
            )
        }

        val bottomNav = activity.findViewById<View>(R.id.bottomNav)
        val navTab = bottomNav?.findViewById<View>(R.id.nav_navigate)
        if (navTab != null) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = navTab,
                    title = "Offline Navigation",
                    description = "Access offline regional maps and emergency shelter routing. Tap below to explore Navigate.",
                    stepBadge = "7 of 7",
                    customButtonText = "Go to Navigate  →",
                    onNextClicked = {
                        val intent = Intent(activity, NavigateActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra(EXTRA_TUTORIAL_PHASE, PHASE_NAVIGATE)
                        }
                        activity.startActivity(intent)
                    }
                )
            )
        }

        if (targets.isNotEmpty()) {
            val overlay = SpotlightOverlayView.attach(activity)
            overlay.start(targets)
        }
    }

    fun runNavigateTour(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targets = mutableListOf<SpotlightOverlayView.SpotlightTarget>()

        val nearestBtn = activity.findViewById<View>(R.id.nearestShelterButton)
        if (nearestBtn != null && nearestBtn.visibility == View.VISIBLE) {
            targets.add(
                SpotlightOverlayView.SpotlightTarget(
                    targetView = nearestBtn,
                    title = "Find Nearest Shelter",
                    description = "Tap to instantly calculate turn-by-turn walking routes to the nearest community shelter, medical camp, or evacuation center completely offline.",
                    stepBadge = "Guide Complete",
                    customButtonText = "Got it, All Set!  ✓",
                    onNextClicked = {
                        setTutorialCompleted(activity, true)
                        val intent = Intent(activity, ChatActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        activity.startActivity(intent)
                        Snackbar.make(
                            activity.findViewById(android.R.id.content),
                            "🎉 You're ready to communicate off-grid!",
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                )
            )
        }

        if (targets.isNotEmpty()) {
            val overlay = SpotlightOverlayView.attach(activity)
            overlay.start(targets)
        }
    }
}
