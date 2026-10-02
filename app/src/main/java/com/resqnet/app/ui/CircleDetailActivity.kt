package com.resqnet.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
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
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private lateinit var rootLayout: View

    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionHelper.hasPermissions(this)) {
            pasteLocation()
        } else {
            android.widget.Toast.makeText(this, "Location permission required to paste coordinates", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteLocation() {
        val input = findViewById<EditText>(R.id.chatInput)
        CoordinateUtils.fetchCoordinates(
            context = this,
            onSuccess = { lat, lon ->
                CoordinateUtils.insertCoordinatesIntoInput(input, lat, lon)
            },
            onError = { err ->
                android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model.circleId = intent.getStringExtra(EXTRA_CIRCLE_ID) ?: run {
            finish(); return
        }

        setContentView(R.layout.activity_circle_detail)
        rootLayout = findViewById(R.id.rootLayout)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        toolbar.setNavigationIconTint(getColor(R.color.text_primary))
        toolbar.overflowIcon?.setTint(getColor(R.color.text_primary))
        setupMeshAppBarBadge(this, toolbar)

        val messageList = findViewById<RecyclerView>(R.id.messageList).apply {
            layoutManager = LinearLayoutManager(this@CircleDetailActivity).apply { stackFromEnd = true }
            adapter = messageAdapter
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

        findViewById<View>(R.id.btnLocation).setOnClickListener {
            if (!PermissionHelper.hasPermissions(this)) {
                locationPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }
            pasteLocation()
        }

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

        memberAdapter.onRemove = { uiModel ->
            showConfirmDialog(
                title = getString(R.string.action_remove_member),
                message = "Remove ${uiModel.displayName} from the circle?",
                confirmText = getString(R.string.action_remove_member),
                isDestructive = true
            ) {
                model.removeMember(uiModel.member.nodeId) { err ->
                    if (err != null) showSnack(err)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.load() // Initial load
                model.uiState.collect { state ->
                    supportActionBar?.title = state.circle?.name ?: "Circle"

                    val isOwner = state.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId

                    messageAdapter.submitList(state.messages) {
                        if (state.messages.isNotEmpty()) messageList.scrollToPosition(state.messages.lastIndex)
                    }
                    emptyState.visibility = if (state.messages.isEmpty()) View.VISIBLE else View.GONE

                    memberAdapter.isOwner = isOwner
                    memberAdapter.localNodeId = (application as ResQNetApplication).signer.nodeId
                    memberAdapter.submitList(state.members)

                    updateStatusPanel(state.localStatus)
                    invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        findViewById<MaterialToolbar>(R.id.toolbar).overflowIcon?.setTint(getColor(R.color.text_primary))
        menu.add(0, MENU_MEMBERS, 0, "Members").apply {
            setIcon(R.drawable.ic_nav_circles)
            icon?.setTint(getColor(R.color.text_primary))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_RENAME, 1, "Rename circle").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        findViewById<MaterialToolbar>(R.id.toolbar).overflowIcon?.setTint(getColor(R.color.text_primary))
        val isOwner = model.uiState.value.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId
        menu.findItem(MENU_RENAME)?.isVisible = isOwner
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            MENU_MEMBERS -> {
                showMembersBottomSheet()
                true
            }
            MENU_RENAME -> { showRenameDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Members Bottom Sheet ────────────────────────────────────────────────

    private fun showMembersBottomSheet() {
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_circle_members, null)
        val sheetDialog = BottomSheetDialog(this)
        sheetDialog.setContentView(sheetView)

        sheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheetDialog.behavior.skipCollapsed = true
        sheetDialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        val memberRecycler = sheetView.findViewById<RecyclerView>(R.id.memberList)
        val btnInvite = sheetView.findViewById<Button>(R.id.btnInviteMember)
        val btnLeave = sheetView.findViewById<Button>(R.id.btnLeaveDissolve)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseSheet)

        memberRecycler.layoutManager = LinearLayoutManager(this)
        memberRecycler.adapter = memberAdapter

        val isOwner = model.uiState.value.circle?.ownerNodeId == (application as ResQNetApplication).signer.nodeId
        btnInvite.visibility = if (isOwner) View.VISIBLE else View.GONE
        btnLeave.text = getString(if (isOwner) R.string.action_dissolve else R.string.action_leave)

        btnClose.setOnClickListener { sheetDialog.dismiss() }

        btnInvite.setOnClickListener {
            sheetDialog.dismiss()
            showInviteMemberDialog()
        }

        btnLeave.setOnClickListener {
            sheetDialog.dismiss()
            confirmLeaveOrDissolve(isOwner)
        }

        sheetDialog.show()
    }

    private fun confirmLeaveOrDissolve(isOwner: Boolean) {
        if (isOwner) {
            showConfirmDialog(
                title = getString(R.string.action_dissolve),
                message = "Are you sure you want to dissolve this circle? This action cannot be undone.",
                confirmText = getString(R.string.action_dissolve),
                isDestructive = true
            ) {
                model.dissolve { err ->
                    if (err != null) showSnack(err) else finish()
                }
            }
        } else {
            showConfirmDialog(
                title = getString(R.string.action_leave),
                message = "Are you sure you want to leave this circle?",
                confirmText = getString(R.string.action_leave),
                isDestructive = true
            ) {
                model.leave { err ->
                    if (err != null) showSnack(err) else finish()
                }
            }
        }
    }

    // ── Custom Dialogs ──────────────────────────────────────────────────────

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        isDestructive: Boolean = true,
        onConfirm: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setBackground(ColorDrawable(Color.TRANSPARENT))
            .create()

        val tvTitle = view.findViewById<TextView>(R.id.dialogConfirmTitle)
        val tvMsg = view.findViewById<TextView>(R.id.dialogConfirmMessage)
        val btnCancel = view.findViewById<View>(R.id.dialogConfirmCancelButton)
        val btnAction = view.findViewById<MaterialButton>(R.id.dialogConfirmActionButton)
        val iconContainer = view.findViewById<FrameLayout>(R.id.dialogIconContainer)
        val icon = view.findViewById<ImageView>(R.id.dialogIcon)

        tvTitle.text = title
        tvMsg.text = message
        btnAction.text = confirmText

        if (!isDestructive) {
            btnAction.backgroundTintList = ColorStateList.valueOf(getColor(R.color.palette_amber))
            btnAction.setTextColor(getColor(R.color.palette_deep_navy))
            iconContainer.backgroundTintList = ColorStateList.valueOf(getColor(R.color.palette_amber_subtle))
            icon.imageTintList = ColorStateList.valueOf(getColor(R.color.palette_amber))
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnAction.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showRenameDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_rename_circle, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setBackground(ColorDrawable(Color.TRANSPARENT))
            .create()

        val input = view.findViewById<EditText>(R.id.dialogRenameInput)
        val btnCancel = view.findViewById<View>(R.id.dialogRenameCancelButton)
        val btnSave = view.findViewById<View>(R.id.dialogRenameSaveButton)

        input.setText(model.uiState.value.circle?.name ?: "")
        input.setSelection(input.text.length)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                dialog.dismiss()
                model.rename(name) { err ->
                    if (err != null) showSnack(err) else supportActionBar?.title = name
                }
            } else {
                input.error = "Please enter a circle name"
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        input.requestFocus()
    }

    private fun showUpdateStatusDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_update_status, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setBackground(ColorDrawable(Color.TRANSPARENT))
            .create()

        val rg = view.findViewById<RadioGroup>(R.id.statusRadioGroup)
        val rbSafe = view.findViewById<RadioButton>(R.id.rbSafe)
        val rbNeedHelp = view.findViewById<RadioButton>(R.id.rbNeedHelp)
        val rbUnknown = view.findViewById<RadioButton>(R.id.rbUnknown)
        val noteInput = view.findViewById<EditText>(R.id.statusNoteInput)
        val btnCancel = view.findViewById<View>(R.id.dialogCancelButton)
        val btnUpdate = view.findViewById<View>(R.id.dialogUpdateButton)

        val currentStatus = model.uiState.value.localStatus?.status ?: SafetyStatus.UNKNOWN
        when (currentStatus) {
            SafetyStatus.SAFE -> rbSafe.isChecked = true
            SafetyStatus.NEED_HELP -> rbNeedHelp.isChecked = true
            SafetyStatus.UNKNOWN -> rbUnknown.isChecked = true
        }
        noteInput.setText(model.uiState.value.localStatus?.note ?: "")

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnUpdate.setOnClickListener {
            val newStatus = when (rg.checkedRadioButtonId) {
                R.id.rbSafe -> SafetyStatus.SAFE
                R.id.rbNeedHelp -> SafetyStatus.NEED_HELP
                else -> SafetyStatus.UNKNOWN
            }
            dialog.dismiss()
            model.updateStatus(newStatus, noteInput.text.toString().trim().ifEmpty { null }) { err ->
                if (err != null) showSnack(err)
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun showInviteMemberDialog() {
        lifecycleScope.launch {
            val app = application as ResQNetApplication
            val contacts = app.contacts.observeContacts().first()
            val currentMemberIds = model.uiState.value.members.map { it.member.nodeId }.toSet()
            val trusted = contacts.filter { it.state == ContactState.TRUSTED && it.nodeId !in currentMemberIds }

            val view = layoutInflater.inflate(R.layout.dialog_invite_member, null)
            val dialog = MaterialAlertDialogBuilder(this@CircleDetailActivity)
                .setView(view)
                .setBackground(ColorDrawable(Color.TRANSPARENT))
                .create()

            val recycler = view.findViewById<RecyclerView>(R.id.inviteContactList)
            val emptyTv = view.findViewById<TextView>(R.id.tvNoTrustedContacts)
            val btnCancel = view.findViewById<View>(R.id.dialogInviteCancelButton)

            btnCancel.setOnClickListener { dialog.dismiss() }

            if (trusted.isEmpty()) {
                emptyTv.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                emptyTv.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.layoutManager = LinearLayoutManager(this@CircleDetailActivity)
                recycler.adapter = object : RecyclerView.Adapter<InviteContactViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteContactViewHolder {
                        val row = layoutInflater.inflate(R.layout.item_invite_contact, parent, false)
                        return InviteContactViewHolder(row)
                    }

                    override fun getItemCount() = trusted.size

                    override fun onBindViewHolder(holder: InviteContactViewHolder, position: Int) {
                        val contact = trusted[position]
                        holder.avatar.text = contact.displayName.trimStart().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                        holder.name.text = contact.displayName
                        val shortKey = if (contact.nodeId.length > 8) contact.nodeId.take(8) + "..." else contact.nodeId
                        holder.fingerprint.text = shortKey
                        holder.btnInvite.setOnClickListener {
                            dialog.dismiss()
                            model.inviteMember(contact.nodeId) { err ->
                                if (err != null) showSnack(err) else showSnack("Invited ${contact.displayName}")
                            }
                        }
                        holder.itemView.setOnClickListener {
                            holder.btnInvite.performClick()
                        }
                    }
                }
            }

            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private class InviteContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.inviteContactAvatar)
        val name: TextView = view.findViewById(R.id.inviteContactName)
        val fingerprint: TextView = view.findViewById(R.id.inviteContactFingerprint)
        val btnInvite: MaterialButton = view.findViewById(R.id.btnDoInvite)
    }

    private fun showSnack(msg: String) = Snackbar.make(rootLayout, msg, Snackbar.LENGTH_LONG).show()

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
            CoordinateUtils.highlightCoordinates(
                textView = holder.body,
                rawText = msg.text,
                isOutgoing = isOutgoing
            )
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt))

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
                    val popup = PopupMenu(ContextThemeWrapper(it.context, R.style.ThemeOverlay_ResQNet_Popup), it)
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
