package com.resqnet.app.ui

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
import com.google.android.material.snackbar.Snackbar
import com.resqnet.app.R
import com.resqnet.app.data.ConversationMessageEntity
import com.resqnet.app.data.DeliveryState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DirectConversationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    private val model by viewModels<DirectConversationViewModel>()
    private val adapter = DmMessageAdapter()

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
        val input = findViewById<EditText>(R.id.dmInput)
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

        // Wire ViewModel before any flow collection (lazy init depends on these)
        model.remoteNodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: run {
            finish(); return
        }
        model.remoteDisplayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "Unknown"

        setContentView(R.layout.activity_direct_conversation)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back)
            // Suppress default title — we use a custom view below
            setDisplayShowTitleEnabled(false)
        }
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        toolbar.setNavigationIconTint(getColor(R.color.text_primary))

        // Inflate custom avatar + name title
        val titleView = layoutInflater.inflate(R.layout.toolbar_dm_title, toolbar, false)
        val avatarView = titleView.findViewById<TextView>(R.id.toolbarAvatar)
        val titleText = titleView.findViewById<TextView>(R.id.toolbarTitle)
        val initial = model.remoteDisplayName.trimStart().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        avatarView.text = initial
        titleText.text = model.remoteDisplayName
        toolbar.addView(titleView)

        setupMeshAppBarBadge(this, toolbar)

        val list = findViewById<RecyclerView>(R.id.dmMessageList).apply {
            layoutManager = LinearLayoutManager(this@DirectConversationActivity).apply {
                stackFromEnd = true
            }
            adapter = this@DirectConversationActivity.adapter
        }

        val input = findViewById<EditText>(R.id.dmInput)
        val byteCount = findViewById<TextView>(R.id.dmByteCount)
        val emptyState = findViewById<TextView>(R.id.dmEmptyState)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                byteCount.text = "$bytes / 500 bytes"
                byteCount.setTextColor(getColor(if (bytes > 500) R.color.alert else R.color.text_muted))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<View>(R.id.dmLocationButton).setOnClickListener {
            if (!PermissionHelper.hasPermissions(this)) {
                locationPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }
            pasteLocation()
        }

        findViewById<Button>(R.id.dmSendButton).setOnClickListener { btn ->
            val text = input.text.toString()
            model.send(text) { error ->
                runOnUiThread {
                    if (error == null) input.text.clear()
                    else Snackbar.make(btn, error, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.messages.collect { messages ->
                    adapter.submitList(messages) {
                        if (messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex)
                    }
                    emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    // ── DM Message Adapter ────────────────────────────────────────────────────

    class DmMessageAdapter : ListAdapter<ConversationMessageEntity, DmMessageAdapter.VH>(Diff) {
        object Diff : DiffUtil.ItemCallback<ConversationMessageEntity>() {
            override fun areItemsTheSame(o: ConversationMessageEntity, n: ConversationMessageEntity) =
                o.messageId == n.messageId
            override fun areContentsTheSame(o: ConversationMessageEntity, n: ConversationMessageEntity) =
                o == n
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
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
            val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (msg.outgoing) Gravity.END else Gravity.START
            holder.bubble.layoutParams = params
            holder.bubble.setBackgroundResource(
                if (msg.outgoing) R.drawable.bg_message_outgoing else R.drawable.bg_message_incoming
            )

            val ctx = holder.itemView.context
            val textColor = if (msg.outgoing) ctx.getColor(R.color.white) else ctx.getColor(R.color.text_primary)
            holder.author.setTextColor(textColor)
            holder.body.setTextColor(textColor)

            holder.author.text = if (msg.outgoing) "You" else msg.originName
            CoordinateUtils.highlightCoordinates(
                textView = holder.body,
                rawText = msg.text,
                isOutgoing = msg.outgoing
            )
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.createdAt))
            val status = when (msg.deliveryState) {
                DeliveryState.DELIVERED -> "✓✓ Delivered"
                DeliveryState.RELAYED -> "✓ Relayed"
                DeliveryState.QUEUED -> "Queued"
            }
            holder.meta.text = if (msg.outgoing) "$time  •  $status" else time
            holder.meta.setTextColor(
                if (msg.outgoing) 0x99FFFFFF.toInt() else ctx.getColor(R.color.text_muted)
            )
        }
    }
}
