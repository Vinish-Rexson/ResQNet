package com.resqnet.app.ui

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.resqnet.app.R
import com.resqnet.app.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter : ListAdapter<MessageEntity, MessageAdapter.Holder>(Diff) {
    object Diff : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(old: MessageEntity, new: MessageEntity) = old.messageId == new.messageId
        override fun areContentsTheSame(old: MessageEntity, new: MessageEntity) = old == new
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.messageBubble)
        val author: TextView = view.findViewById(R.id.messageAuthor)
        val body: TextView = view.findViewById(R.id.messageBody)
        val metadata: TextView = view.findViewById(R.id.messageMetadata)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = getItem(position)
        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (message.outgoing) Gravity.END else Gravity.START
        holder.bubble.layoutParams = params
        holder.bubble.setBackgroundResource(if (message.outgoing) R.drawable.bg_message_outgoing else R.drawable.bg_message_incoming)
        holder.author.text = if (message.outgoing) "You" else message.originName
        holder.body.text = message.text
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt))
        val status = if (message.outgoing) if (message.relayed) "Relayed" else "Queued" else "Received"
        holder.metadata.text = "$time  •  $status  •  ${message.hopCount} hop${if (message.hopCount == 1) "" else "s"}"
    }
}
