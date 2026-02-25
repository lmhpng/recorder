package com.voicerecorder.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecordingAdapter(
    private val recordings: List<Recording>,
    private val onPlay: (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit,
    private val onTranscribe: (Recording) -> Unit,
    private val onSummarize: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

    private var playingId: String? = null

    fun setPlaying(id: String?) {
        playingId = id
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvTranscriptBadge: TextView = view.findViewById(R.id.tvTranscriptBadge)
        val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val btnTranscribe: TextView = view.findViewById(R.id.btnTranscribe)
        val btnSummarize: TextView = view.findViewById(R.id.btnSummarize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recording = recordings[position]
        holder.tvName.text = recording.name
        holder.tvDate.text = recording.date
        holder.tvDuration.text = recording.duration

        val isPlaying = playingId == recording.id
        holder.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        holder.tvTranscriptBadge.visibility = if (recording.transcript != null) View.VISIBLE else View.GONE
        holder.btnSummarize.alpha = if (recording.transcript != null) 1.0f else 0.5f

        holder.btnPlay.setOnClickListener { onPlay(recording) }
        holder.btnDelete.setOnClickListener { onDelete(recording) }
        holder.btnTranscribe.setOnClickListener { onTranscribe(recording) }
        holder.btnSummarize.setOnClickListener { onSummarize(recording) }
    }

    override fun getItemCount() = recordings.size
}
