package com.resqnet.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.resqnet.app.R
import com.resqnet.app.data.ContactEntity
import com.resqnet.app.data.ContactState
import com.resqnet.app.data.PeerEntity
import kotlinx.coroutines.launch

class ContactsActivity : AppCompatActivity() {

    private val model by viewModels<ContactsViewModel>()

    // In-activity adapters for the three tabs (no Fragments needed — ViewPager2 + Adapters
    // is simpler and avoids Fragment backstack complexity with AppCompat/XML style).
    private val contactsAdapter = ContactAdapter()
    private val requestsAdapter = RequestAdapter()
    private val nearbyAdapter = PeerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Wire ViewPager2 with a simple array adapter pointing at three static views
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)

        // Create the three tab views once and hold references
        val tabViews = listOf(
            buildTabView(contactsAdapter, getString(R.string.empty_contacts)),
            buildTabView(requestsAdapter, getString(R.string.empty_requests)),
            buildTabView(nearbyAdapter, getString(R.string.empty_nearby)),
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
                0 -> getString(R.string.tab_contacts)
                1 -> getString(R.string.tab_requests)
                else -> getString(R.string.tab_nearby)
            }
        }.attach()

        // Adapter callbacks wired here (closures capture `model`)
        contactsAdapter.onMessage = { contact ->
            startActivity(Intent(this, DirectConversationActivity::class.java).apply {
                putExtra(DirectConversationActivity.EXTRA_NODE_ID, contact.nodeId)
                putExtra(DirectConversationActivity.EXTRA_DISPLAY_NAME, contact.displayName)
            })
        }
        contactsAdapter.onBlock = { contact ->
            if (contact.state == ContactState.BLOCKED) {
                model.unblockContact(contact.nodeId) { err -> if (err != null) showSnack(err) }
            } else {
                model.blockContact(contact.nodeId) { err -> if (err != null) showSnack(err) }
            }
        }
        contactsAdapter.onRemove = { contact ->
            AlertDialog.Builder(this)
                .setTitle("Remove ${contact.displayName}?")
                .setMessage("This will remove the contact locally. You can add them again later.")
                .setPositiveButton("Remove") { _, _ ->
                    model.removeContact(contact.nodeId) { err -> if (err != null) showSnack(err) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        requestsAdapter.onAccept = { contact ->
            model.acceptContact(contact.nodeId) { err ->
                if (err != null) showSnack(err)
            }
        }
        requestsAdapter.onDecline = { contact ->
            model.declineContact(contact.nodeId) { err ->
                if (err != null) showSnack(err)
            }
        }
        nearbyAdapter.onAdd = { peer ->
            showFingerprintDialog(peer)
        }

        // Observe state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.uiState.collect { state ->
                    contactsAdapter.submitList(state.trusted)
                    requestsAdapter.submitList(state.pending)
                    nearbyAdapter.submitList(state.nearby)

                    // Update empty-state visibility in each tab view
                    tabViews[0].updateEmpty(state.trusted.isEmpty())
                    tabViews[1].updateEmpty(state.pending.isEmpty())
                    tabViews[2].updateEmpty(state.nearby.isEmpty())
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    private fun showSnack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()

    private fun showFingerprintDialog(peer: PeerEntity) {
        val input = EditText(this).apply {
            hint = "Verify fingerprint: ${peer.fingerprint.chunked(8).joinToString(" ")}"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Add ${peer.displayName}")
            .setMessage(
                "Make sure you can see the peer's screen.\n\n" +
                "Their fingerprint: ${peer.fingerprint.chunked(8).joinToString(" ")}\n\n" +
                "Type the first 8 characters of the fingerprint shown on their screen to confirm."
            )
            .setView(input)
            .setPositiveButton("Send request") { _, _ ->
                val confirmed = input.text.toString().trim()
                if (confirmed.isEmpty()) { showSnack("Please enter the fingerprint prefix"); return@setPositiveButton }
                model.requestContact(peer.nodeId, peer.fingerprint) { err ->
                    if (err != null) showSnack(err) else showSnack("Contact request sent to ${peer.displayName}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun buildTabView(adapter: RecyclerView.Adapter<*>, emptyText: String): FrameLayout {
        val frame = layoutInflater.inflate(R.layout.fragment_contact_tab, null, false) as FrameLayout
        frame.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        frame.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@ContactsActivity)
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

    // ── Adapters ─────────────────────────────────────────────────────────────────

    inner class ContactAdapter : ListAdapter<ContactEntity, ContactAdapter.VH>(
        object : DiffUtil.ItemCallback<ContactEntity>() {
            override fun areItemsTheSame(o: ContactEntity, n: ContactEntity) = o.nodeId == n.nodeId
            override fun areContentsTheSame(o: ContactEntity, n: ContactEntity) = o == n
        }
    ) {
        var onMessage: (ContactEntity) -> Unit = {}
        var onBlock: (ContactEntity) -> Unit = {}
        var onRemove: (ContactEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.contactAvatar)
            val name: TextView = view.findViewById(R.id.contactName)
            val fingerprint: TextView = view.findViewById(R.id.contactFingerprint)
            val btnMessage: Button = view.findViewById(R.id.btnMessage)
            val btnOverflow: ImageButton = view.findViewById(R.id.btnOverflow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val contact = getItem(position)
            holder.avatar.text = avatarInitial(contact.displayName)
            holder.name.text = contact.displayName
            holder.fingerprint.text = contact.fingerprint.take(16).chunked(4).joinToString(" ")

            val blocked = contact.state == ContactState.BLOCKED
            holder.btnMessage.isEnabled = !blocked
            holder.btnMessage.alpha = if (blocked) 0.4f else 1f
            holder.btnMessage.setOnClickListener { onMessage(contact) }

            holder.btnOverflow.setOnClickListener { btn ->
                PopupMenu(btn.context, btn).apply {
                    menu.add(if (blocked) getString(R.string.action_unblock) else getString(R.string.action_block))
                        .setOnMenuItemClickListener { onBlock(contact); true }
                    menu.add(getString(R.string.action_remove))
                        .setOnMenuItemClickListener { onRemove(contact); true }
                    show()
                }
            }
        }
    }

    inner class RequestAdapter : ListAdapter<ContactEntity, RequestAdapter.VH>(
        object : DiffUtil.ItemCallback<ContactEntity>() {
            override fun areItemsTheSame(o: ContactEntity, n: ContactEntity) = o.nodeId == n.nodeId
            override fun areContentsTheSame(o: ContactEntity, n: ContactEntity) = o == n
        }
    ) {
        var onAccept: (ContactEntity) -> Unit = {}
        var onDecline: (ContactEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.requestAvatar)
            val name: TextView = view.findViewById(R.id.requestName)
            val fingerprint: TextView = view.findViewById(R.id.requestFingerprint)
            val label: TextView = view.findViewById(R.id.requestLabel)
            val btnAccept: Button = view.findViewById(R.id.btnAccept)
            val btnDecline: Button = view.findViewById(R.id.btnDecline)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_contact_request, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val contact = getItem(position)
            holder.avatar.text = avatarInitial(contact.displayName)
            holder.name.text = contact.displayName
            holder.fingerprint.text = contact.fingerprint.take(16).chunked(4).joinToString(" ")

            val isIncoming = contact.state == ContactState.PENDING_INCOMING
            holder.label.text = if (isIncoming) getString(R.string.label_pending_in)
                                  else getString(R.string.label_pending_out)
            holder.btnAccept.visibility = if (isIncoming) View.VISIBLE else View.GONE
            holder.btnDecline.visibility = if (isIncoming) View.VISIBLE else View.GONE
            holder.btnAccept.setOnClickListener { onAccept(contact) }
            holder.btnDecline.setOnClickListener { onDecline(contact) }
        }
    }

    inner class PeerAdapter : ListAdapter<PeerEntity, PeerAdapter.VH>(
        object : DiffUtil.ItemCallback<PeerEntity>() {
            override fun areItemsTheSame(o: PeerEntity, n: PeerEntity) = o.nodeId == n.nodeId
            override fun areContentsTheSame(o: PeerEntity, n: PeerEntity) = o == n
        }
    ) {
        var onAdd: (PeerEntity) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.peerAvatar)
            val name: TextView = view.findViewById(R.id.peerName)
            val nodeId: TextView = view.findViewById(R.id.peerNodeId)
            val fingerprint: TextView = view.findViewById(R.id.peerFingerprint)
            val btnAdd: Button = view.findViewById(R.id.btnAddContact)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val peer = getItem(position)
            holder.avatar.text = avatarInitial(peer.displayName)
            holder.name.text = peer.displayName
            holder.nodeId.text = "ID: ${peer.nodeId.take(12)}…"
            holder.fingerprint.text = "Key: ${peer.fingerprint.take(16).chunked(4).joinToString(" ")}"
            holder.btnAdd.setOnClickListener { onAdd(peer) }
        }
    }
}
