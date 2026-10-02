package com.resqnet.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.resqnet.app.R
import com.resqnet.app.circles.CircleEntity
import com.resqnet.app.circles.CircleLocalState
import kotlinx.coroutines.launch

class CirclesActivity : AppCompatActivity() {

    private val model by viewModels<CirclesViewModel>()

    private val activeAdapter = CircleAdapter()
    private val invitesAdapter = InviteAdapter()
    private val archivedAdapter = CircleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_circles)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.navigationIcon = null // top-level screen, no back button
        supportActionBar?.title = getString(R.string.action_circles)

        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        val fab = findViewById<FloatingActionButton>(R.id.fabCreateCircle)

        val tabViews = listOf(
            buildTabView(activeAdapter, getString(R.string.empty_active_circles)),
            buildTabView(invitesAdapter, getString(R.string.empty_circle_invites)),
            buildTabView(archivedAdapter, getString(R.string.empty_archived_circles)),
        )

        pager.offscreenPageLimit = 3
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = tabViews.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = tabViews[viewType]
                (v.parent as? ViewGroup)?.removeView(v)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun getItemViewType(position: Int) = position
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }

        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = when (pos) {
                0 -> getString(R.string.tab_active_circles)
                1 -> getString(R.string.tab_circle_invites)
                else -> getString(R.string.tab_archived_circles)
            }
        }.attach()

        fab.setOnClickListener { showCreateDialog() }

        // Adapter actions
        activeAdapter.onClick = { circle ->
            startActivity(Intent(this, CircleDetailActivity::class.java).apply {
                putExtra(CircleDetailActivity.EXTRA_CIRCLE_ID, circle.circleId)
            })
        }
        archivedAdapter.onClick = { circle ->
            startActivity(Intent(this, CircleDetailActivity::class.java).apply {
                putExtra(CircleDetailActivity.EXTRA_CIRCLE_ID, circle.circleId)
            })
        }

        invitesAdapter.onAccept = { circle ->
            model.acceptInvite(circle.circleId) { err ->
                if (err != null) showSnack(err)
            }
        }
        invitesAdapter.onDecline = { circle ->
            model.declineInvite(circle.circleId) { err ->
                if (err != null) showSnack(err)
            }
        }

        setupBottomNav(this, R.id.nav_circles)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.uiState.collect { state ->
                    activeAdapter.statusSummaries = state.statusSummaries
                    archivedAdapter.statusSummaries = state.statusSummaries

                    activeAdapter.submitList(state.active)
                    invitesAdapter.submitList(state.invites)
                    archivedAdapter.submitList(state.archived)

                    tabViews[0].updateEmpty(state.active.isEmpty())
                    tabViews[1].updateEmpty(state.invites.isEmpty())
                    tabViews[2].updateEmpty(state.archived.isEmpty())
                }
            }
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()

    private fun showCreateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_circle, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setBackground(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            .create()


        val input = dialogView.findViewById<EditText>(R.id.dialogCircleNameInput)
        val btnCancel = dialogView.findViewById<View>(R.id.dialogCancelButton)
        val btnCreate = dialogView.findViewById<View>(R.id.dialogCreateButton)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                dialog.dismiss()
                model.createCircle(name) { err ->
                    if (err != null) showSnack(err) else showSnack("Circle created")
                }
            } else {
                input.error = "Please enter a circle name"
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        input.requestFocus()
    }

    private fun buildTabView(adapter: RecyclerView.Adapter<*>, emptyText: String): FrameLayout {
        val frame = layoutInflater.inflate(R.layout.fragment_contact_tab, null, false) as FrameLayout
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        frame.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@CirclesActivity)
            this.adapter = adapter
        }
        frame.findViewById<TextView>(R.id.emptyState).text = emptyText
        return frame
    }

    private fun FrameLayout.updateEmpty(empty: Boolean) {
        val rv = findViewById<RecyclerView>(R.id.recyclerView)
        val tv = findViewById<TextView>(R.id.emptyState)
        rv.visibility = if (empty) View.GONE else View.VISIBLE
        tv.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun avatarInitial(name: String) = name.trimStart().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    inner class CircleAdapter : ListAdapter<CircleEntity, CircleAdapter.VH>(
        object : DiffUtil.ItemCallback<CircleEntity>() {
            override fun areItemsTheSame(o: CircleEntity, n: CircleEntity) = o.circleId == n.circleId
            override fun areContentsTheSame(o: CircleEntity, n: CircleEntity) = o == n
        }
    ) {
        var statusSummaries: Map<String, CircleStatusSummary> = emptyMap()
        var onClick: (CircleEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.circleAvatar)
            val name: TextView = view.findViewById(R.id.circleName)
            val state: TextView = view.findViewById(R.id.circleState)
            val pillsRow: View = view.findViewById(R.id.statusPillsRow)
            val pillSafe: TextView = view.findViewById(R.id.pillSafe)
            val pillNeedHelp: TextView = view.findViewById(R.id.pillNeedHelp)
            val pillUnknown: TextView = view.findViewById(R.id.pillUnknown)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_circle, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val circle = getItem(position)
            holder.avatar.text = avatarInitial(circle.name)
            holder.name.text = circle.name
            holder.state.text = when (circle.localState) {
                CircleLocalState.ACTIVE -> "Active"
                CircleLocalState.OWNER_ACTIVE -> "Active (Owner)"
                CircleLocalState.LEAVE_PENDING -> "Leaving..."
                CircleLocalState.ARCHIVED_DISSOLVED -> "Dissolved"
                CircleLocalState.ARCHIVED_REMOVED -> "Removed"
                else -> circle.localState.name
            }
            holder.itemView.setOnClickListener { onClick(circle) }

            // Bind status pills
            val summary = statusSummaries[circle.circleId]
            if (summary != null && summary.hasAnyStatus) {
                holder.pillsRow.visibility = View.VISIBLE
                bindPill(holder.pillSafe, summary.safeCount, "✓ ${summary.safeCount} Safe")
                bindPill(holder.pillNeedHelp, summary.needHelpCount, "! ${summary.needHelpCount} Need Help")
                bindPill(holder.pillUnknown, summary.unknownCount, "? ${summary.unknownCount} Unknown")
            } else {
                holder.pillsRow.visibility = View.GONE
            }
        }

        private fun bindPill(pill: TextView, count: Int, label: String) {
            if (count > 0) {
                pill.text = label
                pill.visibility = View.VISIBLE
            } else {
                pill.visibility = View.GONE
            }
        }
    }

    inner class InviteAdapter : ListAdapter<CircleEntity, InviteAdapter.VH>(
        object : DiffUtil.ItemCallback<CircleEntity>() {
            override fun areItemsTheSame(o: CircleEntity, n: CircleEntity) = o.circleId == n.circleId
            override fun areContentsTheSame(o: CircleEntity, n: CircleEntity) = o == n
        }
    ) {
        var onAccept: (CircleEntity) -> Unit = {}
        var onDecline: (CircleEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.circleAvatar)
            val name: TextView = view.findViewById(R.id.circleName)
            val state: TextView = view.findViewById(R.id.circleState)
            val btnAccept: Button = view.findViewById(R.id.btnAccept)
            val btnDecline: Button = view.findViewById(R.id.btnDecline)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_circle_invite, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val circle = getItem(position)
            holder.avatar.text = avatarInitial(circle.name)
            holder.name.text = circle.name
            val isPending = circle.localState == CircleLocalState.ACCEPTANCE_PENDING
            holder.state.text = if (isPending) "Waiting for owner confirmation..." else "Invited"
            holder.btnAccept.visibility = if (isPending) View.GONE else View.VISIBLE
            holder.btnDecline.visibility = if (isPending) View.GONE else View.VISIBLE
            holder.btnAccept.setOnClickListener { onAccept(circle) }
            holder.btnDecline.setOnClickListener { onDecline(circle) }
        }
    }
}
