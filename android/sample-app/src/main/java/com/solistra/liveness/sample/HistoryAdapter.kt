package com.solistra.liveness.sample

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.solistra.liveness.core.LivenessChallenge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LivenessHistoryItem(
    val isLive: Boolean,
    val passiveScore: Float,
    val sessionDurationMs: Long,
    val completedChallenges: List<LivenessChallenge>,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class HistoryAdapter(
    private val items: MutableList<LivenessHistoryItem> = mutableListOf()
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStatus: TextView = view.findViewById(R.id.tvHistoryStatus)
        val tvTime: TextView = view.findViewById(R.id.tvHistoryTime)
        val tvScore: TextView = view.findViewById(R.id.tvHistoryScore)
        val tvDuration: TextView = view.findViewById(R.id.tvHistoryDuration)
        val tvChallenges: TextView = view.findViewById(R.id.tvHistoryChallenges)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        if (item.isLive) {
            holder.tvStatus.text = "PASSED"
            holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.status_success))
            holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_passed)
            holder.tvScore.text = "Passive: ${(item.passiveScore * 100).toInt()}%"
            holder.tvDuration.text = "${item.sessionDurationMs} ms"
            val challengeNames = if (item.completedChallenges.isNotEmpty()) {
                item.completedChallenges.joinToString(", ") { it.name }
            } else {
                "None"
            }
            holder.tvChallenges.text = "Challenges: $challengeNames"
        } else {
            holder.tvStatus.text = "FAILED"
            holder.tvStatus.setTextColor(ContextCompat.getColor(context, R.color.status_error))
            holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_failed)
            holder.tvScore.text = "Error: ${item.errorMessage ?: "Verification Failed"}"
            holder.tvDuration.text = "${item.sessionDurationMs} ms"
            holder.tvChallenges.text = "Spoof Score: ${(item.passiveScore * 100).toInt()}%"
        }

        holder.tvTime.text = timeFormat.format(Date(item.timestamp))
    }

    override fun getItemCount(): Int = items.size

    fun addItem(item: LivenessHistoryItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun isEmpty(): Boolean = items.isEmpty()
}
