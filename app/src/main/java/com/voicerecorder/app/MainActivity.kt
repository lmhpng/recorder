package com.voicerecorder.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var fabRecord: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var waveformView: WaveformView

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var currentFile: File? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerSeconds = 0
    private val recordingsList = mutableListOf<Recording>()
    private lateinit var adapter: RecordingAdapter

    private val REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkPermissions()
        loadRecordings()
    }

    private fun initViews() {
        fabRecord = findViewById(R.id.fabRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        rvRecordings = findViewById(R.id.rvRecordings)
        tvEmpty = findViewById(R.id.tvEmpty)
        waveformView = findViewById(R.id.waveformView)

        adapter = RecordingAdapter(
            recordingsList,
            onPlay = { playRecording(it) },
            onDelete = { deleteRecording(it) },
            onTranscribe = { transcribeRecording(it) },
            onSummarize = { showSummary(it) }
        )
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = adapter
        fabRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        }
    }

    private fun getRecordingsDir() = File(filesDir, "recordings").also { if (!it.exists()) it.mkdirs() }

    private fun loadRecordings() {
        recordingsList.clear()
        val prefs = getSharedPreferences("recordings_meta", MODE_PRIVATE)
        getRecordingsDir().listFiles()
            ?.filter { it.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                recordingsList.add(Recording(
                    id = file.name,
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    duration = getDuration(file),
                    date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                    transcript = prefs.getString("transcript_${file.name}", null),
                    summary = prefs.getString("summary_${file.name}", null)
                ))
            }
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (recordingsList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun getDuration(file: File): String {
        return try {
            val mp = MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
            val dur = mp.duration / 1000
            mp.release()
            "%02d:%02d".format(dur / 60, dur % 60)
        } catch (e: Exception) { "00:00" }
    }

    private fun startRecording() {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        currentFile = File(getRecordingsDir(), "录音_$dateStr.m4a")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(currentFile!!.absolutePath)
            prepare(); start()
        }
        isRecording = true
        fabRecord.setImageResource(R.drawable.ic_stop)
        tvStatus.text = "● 正在录音..."
        tvStatus.setTextColor(0xFFE53935.toInt())
        waveformView.startAnimation()
        timerSeconds = 0
        startTimer()
    }

    private fun stopRecording() {
        try { mediaRecorder?.apply { stop(); release() } } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        timerHandler.removeCallbacksAndMessages(null)
        waveformView.stopAnimation()
        fabRecord.setImageResource(R.drawable.ic_mic)
        tvStatus.text = "点击开始录音"
        tvStatus.setTextColor(0xFF888888.toInt())
        tvTimer.text = "00:00"
        currentFile?.takeIf { it.exists() && it.length() > 0 }?.let {
            Toast.makeText(this, "✅ 录音已保存", Toast.LENGTH_SHORT).show()
            loadRecordings()
        }
    }

    private fun startTimer() {
        timerHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRecording) return
                timerSeconds++
                tvTimer.text = "%02d:%02d".format(timerSeconds / 60, timerSeconds % 60)
                try { mediaRecorder?.let { waveformView.updateAmplitude(it.maxAmplitude) } } catch (e: Exception) {}
                timerHandler.postDelayed(this, 100)
            }
        }, 100)
    }

    private fun playRecording(recording: Recording) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(recording.filePath)
            prepare(); start()
            setOnCompletionListener { adapter.setPlaying(null) }
        }
        adapter.setPlaying(recording.id)
    }

    private fun deleteRecording(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("删除录音")
            .setMessage("确定要删除「${recording.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
                mediaPlayer = null
                File(recording.filePath).delete()
                getSharedPreferences("recordings_meta", MODE_PRIVATE).edit()
                    .remove("transcript_${recording.id}").remove("summary_${recording.id}").apply()
                loadRecordings()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null).show()
    }

    // ===== 讯飞语音转文字 =====
    private fun transcribeRecording(recording: Recording) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "录音文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        var progressMsg = "正在上传录音到讯飞服务器...\n预计需要10~30秒，请耐心等待 ⏳"
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("🎙️ 语音转文字")
            .setMessage(progressMsg)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        // 定时更新提示
        val msgs = listOf(
            8000L to "⏳ 正在识别语音内容...",
            18000L to "⏳ 识别中，稍候...\n（较长录音需要更多时间）",
            35000L to "⏳ 还在处理中，请稍候..."
        )
        msgs.forEach { (delay, msg) ->
            Handler(Looper.getMainLooper()).postDelayed({
                if (loadingDialog.isShowing) loadingDialog.setMessage(msg)
            }, delay)
        }

        lifecycleScope.launch {
            try {
                val transcript = IFlytekService.transcribeAudio(file)
                loadingDialog.dismiss()
                getSharedPreferences("recordings_meta", MODE_PRIVATE)
                    .edit().putString("transcript_${recording.id}", transcript).apply()
                loadRecordings()
                showTranscriptResultDialog(recording, transcript)
            } catch (e: Exception) {
                loadingDialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("❌ 识别失败")
                    .setMessage("原因：${e.message}\n\n建议：\n• 检查网络连接\n• 确保录音有效\n• 也可手动输入文字")
                    .setPositiveButton("手动输入") { _, _ -> showManualInputDialog(recording) }
                    .setNegativeButton("关闭", null).show()
            }
        }
    }

    private fun showTranscriptResultDialog(recording: Recording, transcript: String) {
        AlertDialog.Builder(this)
            .setTitle("✅ 转文字完成")
            .setMessage(transcript)
            .setPositiveButton("立即AI总结 🤖") { _, _ ->
                val updated = recordingsList.find { it.id == recording.id }
                if (updated != null) generateSummary(updated)
            }
            .setNeutralButton("复制文字") { _, _ -> copyToClipboard(transcript) }
            .setNegativeButton("关闭", null).show()
    }

    private fun showManualInputDialog(recording: Recording) {
        val editText = EditText(this).apply {
            hint = "在此输入文字内容..."
            minLines = 4; maxLines = 10
            setPadding(40, 20, 40, 20)
            setText(recording.transcript ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ 手动输入文字")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    getSharedPreferences("recordings_meta", MODE_PRIVATE)
                        .edit().putString("transcript_${recording.id}", text).apply()
                    loadRecordings()
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ===== AI 智能总结 =====
    private fun showSummary(recording: Recording) {
        val current = recordingsList.find { it.id == recording.id } ?: recording
        if (current.transcript.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("💡 提示")
                .setMessage("请先点击「📝 转文字」，将语音内容转为文字后再生成AI总结。")
                .setPositiveButton("去转文字") { _, _ -> transcribeRecording(recording) }
                .setNegativeButton("取消", null).show()
            return
        }
        if (current.summary != null) {
            showSummaryDialog(current.name, current.summary)
        } else {
            generateSummary(current)
        }
    }

    private fun generateSummary(recording: Recording) {
        val transcript = recording.transcript ?: return
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("🤖 AI 总结生成中...")
            .setMessage("正在智能分析内容要点...")
            .setCancelable(false).create()
        loadingDialog.show()

        Handler(Looper.getMainLooper()).postDelayed({
            val summary = buildAiSummary(transcript)
            getSharedPreferences("recordings_meta", MODE_PRIVATE)
                .edit().putString("summary_${recording.id}", summary).apply()
            loadingDialog.dismiss()
            loadRecordings()
            showSummaryDialog(recording.name, summary)
        }, 600)
    }

    private fun buildAiSummary(text: String): String {
        val cleanText = text.trim()
        val charCount = cleanText.length
        val sentences = cleanText.split(Regex("[。！？.!?\\n]")).map { it.trim() }.filter { it.length > 4 }
        val estimatedSecs = (charCount * 0.3).toInt()
        val durationStr = if (estimatedSecs >= 60) "${estimatedSecs/60}分${estimatedSecs%60}秒" else "${estimatedSecs}秒"

        return buildString {
            append("━━━━━━━━━━━━━━━━━━\n")
            append("🤖  AI 智能总结报告\n")
            append("━━━━━━━━━━━━━━━━━━\n\n")
            append("📊 基本信息\n")
            append("字数 $charCount 字 | 句数 ${sentences.size} 句 | 约$durationStr\n\n")
            append("📌 核心内容\n")
            append(extractCoreSummary(sentences, cleanText))
            append("\n\n")
            val keyPoints = extractKeyPoints(sentences)
            if (keyPoints.isNotEmpty()) {
                append("💡 要点提炼\n")
                keyPoints.forEachIndexed { i, p -> append("${i+1}. $p\n") }
                append("\n")
            }
            val keywords = extractKeywords(cleanText)
            if (keywords.isNotEmpty()) {
                append("🏷️ 关键词\n")
                append(keywords.joinToString("  |  "))
            }
            append("\n━━━━━━━━━━━━━━━━━━")
        }
    }

    private fun extractCoreSummary(sentences: List<String>, fullText: String): String {
        if (sentences.isEmpty()) return "（内容为空）"
        return sentences.take(2).joinToString("，") { it.take(35) } +
                if (fullText.length > 70) "……" else ""
    }

    private fun extractKeyPoints(sentences: List<String>): List<String> {
        val markers = listOf("首先","其次","然后","最后","第一","第二","第三","重要",
            "注意","需要","应该","建议","总结","关键","主要","因此","所以","结果","但是")
        val result = mutableListOf<String>()
        for (s in sentences) {
            if (result.size >= 5) break
            if (markers.any { s.contains(it) } && s.length >= 8)
                result.add(if (s.length > 42) s.take(42) + "..." else s)
        }
        if (result.isEmpty() && sentences.size >= 2) {
            listOf(0, sentences.size/2, sentences.size-1).distinct().forEach { i ->
                if (result.size < 4 && sentences[i].length >= 8) result.add(sentences[i].take(42))
            }
        }
        return result
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf("的","了","在","是","我","你","他","她","们","这","那","有","和","也","都","很","就","不","没","会","能","要","一个","这个","那个","什么","怎么","为什么")
        val freq = mutableMapOf<String, Int>()
        for (len in 2..4) {
            for (i in 0..text.length - len) {
                val w = text.substring(i, i + len)
                if (!stopWords.any { w.contains(it) } && w.all { it.code > 127 })
                    freq[w] = (freq[w] ?: 0) + 1
            }
        }
        return freq.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(6).map { it.key }
    }

    private fun showSummaryDialog(name: String, summary: String) {
        AlertDialog.Builder(this)
            .setTitle("🤖 AI 总结 - $name")
            .setMessage(summary)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ -> copyToClipboard(summary) }.show()
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        if (isRecording) stopRecording()
    }
}
