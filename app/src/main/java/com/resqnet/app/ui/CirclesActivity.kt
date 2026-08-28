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

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.uiState.collect { state ->
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

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    private fun showSnack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_circle_name)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_create_circle))
            .setView(input)
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    model.createCircle(name) { err ->
                        if (err != null) showSnack(err) else showSnack("Circle created")
                    }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
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
        var onClick: (CircleEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.circleAvatar)
            val name: TextView = view.findViewById(R.id.circleName)
            val state: TextView = view.findViewById(R.id.circleState)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_circle, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val circle = getItem(position)
            holder.avatar.text = avatarInitial(circle.name)
            holder.name.text = circle.name
            holder.state.text = when(circle.localState) {
                CircleLocalState.ACTIVE -> "Active"
                CircleLocalState.LEAVE_PENDING -> "Leaving..."
                CircleLocalState.ARCHIVED_DISSOLVED -> "Dissolved"
                CircleLocalState.ARCHIVED_REMOVED -> "Removed"
                else -> circle.localState.name
            }
            holder.itemView.setOnClickListener { onClick(circle) }
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
