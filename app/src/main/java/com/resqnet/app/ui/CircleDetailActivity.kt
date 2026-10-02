package com.resqnet.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.*
import com.resqnet.app.data.ContactState
import com.resqnet.app.protocol.SafetyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class CircleDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CIRCLE_ID = "circle_id"
        private const val MENU_MEMBERS = 1
        private const val MENU_RENAME = 2
    }

    private val model by viewModels<CircleDetailViewModel>()
    private val messageAdapter = CircleMessageAdapter()
    private val memberAdapter = MemberAdapter()

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        model.circleId = intent.getStringExtra(EXTRA_CIRCLE_ID) ?: run {
            finish(); return
        }
        
        setContentView(R.layout.activity_circle_detail)
        
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupMeshAppBarBadge(this, toolbar)
        
        drawerLayout = findViewById(R.id.drawerLayout)

        val messageList = findViewById<RecyclerView>(R.id.messageList).apply {
            layoutManager = LinearLayoutManager(this@CircleDetailActivity).apply { stackFromEnd = true }
            adapter = messageAdapter
        }

        findViewById<RecyclerView>(R.id.memberList).apply {
            layoutManager = LinearLayoutManager(this@CircleDetailActivity)
            adapter = memberAdapter
        }

        val input = findViewById<EditText>(R.id.chatInput)
        val byteCount = findViewById<TextView>(R.id.chatByteCount)
        val emptyState = findViewById<TextView>(R.id.emptyState)
        
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                byteCount.text = "$bytes / 500 bytes"
                byteCount.setTextColor(getColor(if (bytes > 500) R.color.alert else R.color.text_muted))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<Button>(R.id.btnSend).setOnClickListener { btn ->
            val text = input.text.toString()
            if (text.isNotBlank()) {
                model.send(text) { error ->
                    runOnUiThread {
                        if (error == null) input.text.clear()
                        else Snackbar.make(btn, error, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnUpdateStatus).setOnClickListener {
            showUpdateStatusDialog()
        }

        findViewById<Button>(R.id.btnInviteMember).setOnClickListener {
            showInviteMemberDialog()
        }

        val btnLeaveDissolve = findViewById<Button>(R.id.btnLeaveDissolve)
        btnLeaveDissolve.setOnClickListener {
            val isOwner = model.uiState.value.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId
            if (isOwner) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_dissolve)
                    .setMessage("Are you sure you want to dissolve this circle? This action cannot be undone.")
                    .setPositiveButton(R.string.action_dissolve) { _, _ ->
                        model.dissolve { err ->
                            if (err != null) showSnack(err) else finish()
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_leave)
                    .setMessage("Are you sure you want to leave this circle?")
                    .setPositiveButton(R.string.action_leave) { _, _ ->
                        model.leave { err ->
                            if (err != null) showSnack(err) else finish()
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        
        memberAdapter.onRemove = { uiModel ->
            AlertDialog.Builder(this)
                .setTitle(R.string.action_remove_member)
                .setMessage("Remove ${uiModel.displayName} from the circle?")
                .setPositiveButton(R.string.action_remove_member) { _, _ ->
                    model.removeMember(uiModel.member.nodeId) { err ->
                        if (err != null) showSnack(err)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.load() // Initial load
                model.uiState.collect { state ->
                    supportActionBar?.title = state.circle?.name ?: "Circle"
                    
                    val isOwner = state.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId
                    findViewById<Button>(R.id.btnInviteMember).visibility = if (isOwner) View.VISIBLE else View.GONE
                    btnLeaveDissolve.text = getString(if (isOwner) R.string.action_dissolve else R.string.action_leave)
                    
                    messageAdapter.submitList(state.messages) {
                        if (state.messages.isNotEmpty()) messageList.scrollToPosition(state.messages.lastIndex)
                    }
                    emptyState.visibility = if (state.messages.isEmpty()) View.VISIBLE else View.GONE
                    
                    memberAdapter.isOwner = isOwner
                    memberAdapter.localNodeId = (application as ResQNetApplication).signer.nodeId
                    memberAdapter.submitList(state.members)
                    
                    updateStatusPanel(state.localStatus)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MEMBERS, 0, "Members").apply {
            setIcon(R.drawable.ic_more_vert)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_RENAME, 1, "Rename circle").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isOwner = model.uiState.value.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId
        menu.findItem(MENU_RENAME)?.isVisible = isOwner
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            MENU_MEMBERS -> {
                if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    drawerLayout.closeDrawer(GravityCompat.END)
                } else {
                    drawerLayout.openDrawer(GravityCompat.END)
                }
                true
            }
            MENU_RENAME -> { showRenameDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            hint = "New circle name"
            setText(model.uiState.value.circle?.name ?: "")
            setPadding(56, 24, 56, 8)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Rename circle")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    model.rename(name) { err ->
                        if (err != null) showSnack(err) else supportActionBar?.title = name
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSnack(msg: String) = Snackbar.make(drawerLayout, msg, Snackbar.LENGTH_LONG).show()

    private fun updateStatusPanel(status: CircleStatusEventEntity?) {
        val tvStatus = findViewById<TextView>(R.id.myStatusText)
        val tvNote = findViewById<TextView>(R.id.myStatusNote)
        if (status == null) {
            tvStatus.text = getString(R.string.label_unknown)
            tvStatus.setTextColor(getColor(R.color.color_unknown))
            tvNote.visibility = View.GONE
        } else {
            val (text, color) = when(status.status) {
                SafetyStatus.SAFE -> getString(R.string.label_safe) to R.color.color_safe
                SafetyStatus.NEED_HELP -> getString(R.string.label_need_help) to R.color.color_need_help
                SafetyStatus.UNKNOWN -> getString(R.string.label_unknown) to R.color.color_unknown
            }
            tvStatus.text = text
            tvStatus.setTextColor(getColor(color))
            if (status.note.isNullOrBlank()) {
                tvNote.visibility = View.GONE
            } else {
                tvNote.visibility = View.VISIBLE
                tvNote.text = status.note
            }
        }
    }

    private fun showUpdateStatusDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }
        val rg = RadioGroup(this)
        val rbSafe = RadioButton(this).apply { text = getString(R.string.label_safe) }
        val rbNeedHelp = RadioButton(this).apply { text = getString(R.string.label_need_help) }
        val rbUnknown = RadioButton(this).apply { text = getString(R.string.label_unknown) }
        rg.addView(rbSafe); rg.addView(rbNeedHelp); rg.addView(rbUnknown)

        val currentStatus = model.uiState.value.localStatus?.status ?: SafetyStatus.UNKNOWN
        when (currentStatus) {
            SafetyStatus.SAFE -> rbSafe.isChecked = true
            SafetyStatus.NEED_HELP -> rbNeedHelp.isChecked = true
            SafetyStatus.UNKNOWN -> rbUnknown.isChecked = true
        }

        val noteInput = EditText(this).apply {
            hint = getString(R.string.hint_status_note)
            setText(model.uiState.value.localStatus?.note)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

        layout.addView(rg)
        layout.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.title_update_status)
            .setView(layout)
            .setPositiveButton("Update") { _, _ ->
                val newStatus = when (rg.checkedRadioButtonId) {
                    rbSafe.id -> SafetyStatus.SAFE
                    rbNeedHelp.id -> SafetyStatus.NEED_HELP
                    else -> SafetyStatus.UNKNOWN
                }
                model.updateStatus(newStatus, noteInput.text.toString().trim().ifEmpty { null }) { err ->
                    if (err != null) showSnack(err)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showInviteMemberDialog() {
        lifecycleScope.launch {
            // Fetch contacts to show in dialog
            val app = application as ResQNetApplication
            val contacts = app.contacts.observeContacts().first()
            val trusted = contacts.filter { it.state == ContactState.TRUSTED }
            
            if (trusted.isEmpty()) {
                showSnack("No trusted contacts available to invite.")
                return@launch
            }
            
            val names = trusted.map { it.displayName }.toTypedArray()
            
            AlertDialog.Builder(this@CircleDetailActivity)
                .setTitle(R.string.title_invite_member)
                .setItems(names) { _, which ->
                    val contact = trusted[which]
                    model.inviteMember(contact.nodeId) { err ->
                        if (err != null) showSnack(err) else showSnack("Invited ${contact.displayName}")
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    inner class CircleMessageAdapter : ListAdapter<CircleMessageEntity, CircleMessageAdapter.VH>(
        object : DiffUtil.ItemCallback<CircleMessageEntity>() {
            override fun areItemsTheSame(o: CircleMessageEntity, n: CircleMessageEntity) = o.messageId == n.messageId
            override fun areContentsTheSame(o: CircleMessageEntity, n: CircleMessageEntity) = o == n
        }
    ) {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val bubble: LinearLayout = view.findViewById(R.id.dmBubble)
            val author: TextView = view.findViewById(R.id.dmAuthor)
            val body: TextView = view.findViewById(R.id.dmBody)
            val meta: TextView = view.findViewById(R.id.dmMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_dm_message, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = getItem(position)
            val isOutgoing = msg.originNodeId == (application as ResQNetApplication).signer.nodeId

            val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (isOutgoing) Gravity.END else Gravity.START
            holder.bubble.layoutParams = params
            holder.bubble.setBackgroundResource(
                if (isOutgoing) R.drawable.bg_message_outgoing else R.drawable.bg_message_incoming
            )

            // Text colours flip for outgoing (dark bubble)
            val textColor = if (isOutgoing) getColor(R.color.white) else getColor(R.color.text_primary)
            val metaColor = if (isOutgoing) 0x99FFFFFF.toInt() else getColor(R.color.text_muted)
            holder.author.setTextColor(textColor)
            holder.body.setTextColor(textColor)
            holder.meta.setTextColor(metaColor)

            holder.author.text = if (isOutgoing) "You" else msg.originName
            holder.body.text = msg.text
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(msg.createdAt))

            // Show delivery progress for outgoing messages
            val meta = if (isOutgoing) {
                val progress = model.uiState.value.deliveryProgress[msg.messageId]
                if (progress != null && progress.possible > 0) "$time · ${progress.delivered}/${progress.possible}"
                else time
            } else {
                if (msg.hopCount > 0) "$time · ${msg.hopCount} hops" else time
            }
            holder.meta.text = meta
        }
    }

    inner class MemberAdapter : ListAdapter<MemberUiModel, MemberAdapter.VH>(
        object : DiffUtil.ItemCallback<MemberUiModel>() {
            override fun areItemsTheSame(o: MemberUiModel, n: MemberUiModel) = o.member.nodeId == n.member.nodeId
            override fun areContentsTheSame(o: MemberUiModel, n: MemberUiModel) = o == n
        }
    ) {
        var isOwner = false
        var localNodeId = ""
        var onRemove: (MemberUiModel) -> Unit = {}

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: TextView = view.findViewById(R.id.memberAvatar)
            val name: TextView = view.findViewById(R.id.memberName)
            val role: TextView = view.findViewById(R.id.memberRole)
            val status: TextView = view.findViewById(R.id.memberStatus)
            val btnOverflow: ImageButton = view.findViewById(R.id.btnOverflow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_circle_member, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uiModel = getItem(position)
            val member = uiModel.member
            val owner = model.uiState.value.circle?.ownerNodeId
            
            holder.avatar.text = uiModel.displayName.trimStart().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            holder.name.text = if (member.nodeId == localNodeId) "You" else uiModel.displayName
            
            val isMemberOwner = member.nodeId == owner
            holder.role.text = if (isMemberOwner) "Owner" else "Member"
            
            val memStatus = uiModel.status
            if (memStatus == null) {
                holder.status.text = "Unknown"
                holder.status.setTextColor(getColor(R.color.color_unknown))
            } else {
                val (text, color) = when(memStatus.status) {
                    SafetyStatus.SAFE -> "Safe" to R.color.color_safe
                    SafetyStatus.NEED_HELP -> "Need Help" to R.color.color_need_help
                    SafetyStatus.UNKNOWN -> "Unknown" to R.color.color_unknown
                }
                holder.status.text = if (memStatus.note.isNullOrBlank()) text else "$text - ${memStatus.note}"
                holder.status.setTextColor(getColor(color))
            }

            if (isOwner && !isMemberOwner) {
                holder.btnOverflow.visibility = View.VISIBLE
                holder.btnOverflow.setOnClickListener {
                    val popup = PopupMenu(it.context, it)
                    popup.menu.add(R.string.action_remove_member).setOnMenuItemClickListener {
                        onRemove(uiModel)
                        true
                    }
                    popup.show()
                }
            } else {
                holder.btnOverflow.visibility = View.GONE
            }
        }
    }
}
