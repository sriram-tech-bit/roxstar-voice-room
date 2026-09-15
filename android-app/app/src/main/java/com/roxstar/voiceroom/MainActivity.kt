package com.roxstar.voiceroom

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.roxstar.voiceroom.audio.NativeAudio
import com.roxstar.voiceroom.data.Draft
import com.roxstar.voiceroom.data.DraftStore
import com.roxstar.voiceroom.databinding.ActivityMainBinding
import com.roxstar.voiceroom.net.ApiClient
import com.roxstar.voiceroom.net.RealtimeClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var drafts: DraftStore
    private val api = ApiClient()
    private val realtime = RealtimeClient()
    private var recordingFile: File? = null
    private var recordStartedAt = 0L
    private var selectedDraft: Draft? = null
    private var roomId: String? = null
    private var adapter: DraftAdapter? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else toast("Microphone permission is required to record")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drafts = DraftStore(this)
        binding.apiUrl.setText(BuildConfig.API_BASE_URL)
        adapter = DraftAdapter(
            onPlay = { NativeAudio.startPlayback(it.path) },
            onDelete = {
                File(it.path).delete()
                drafts.delete(it.id)
                if (selectedDraft?.id == it.id) selectedDraft = null
                refreshDrafts()
            },
            onSelect = { selectedDraft = it }
        )
        binding.draftList.layoutManager = LinearLayoutManager(this)
        binding.draftList.adapter = adapter
        refreshDrafts()

        binding.recordButton.setOnClickListener { ensureMicThenRecord() }
        binding.stopButton.setOnClickListener { stopRecording() }
        binding.cancelButton.setOnClickListener {
            NativeAudio.cancelRecording()
            recordingFile?.delete()
            recordingFile = null
            log("Recording cancelled")
        }
        binding.createRoom.setOnClickListener { runNet { ensureUser(); val snap = api.createRoom(); enterRoom(snap) } }
        binding.joinRoom.setOnClickListener {
            runNet {
                ensureUser()
                val snap = api.joinRoom(binding.roomCode.text.toString())
                enterRoom(snap)
            }
        }
        binding.leaveRoom.setOnClickListener {
            val id = roomId ?: return@setOnClickListener
            runNet {
                api.leaveRoom(id)
                realtime.disconnect()
                roomId = null
                log("Left room")
            }
        }
        binding.shareDraft.setOnClickListener {
            val id = roomId
            val draft = selectedDraft
            if (id == null || draft == null) {
                toast("Join a room and select a draft")
                return@setOnClickListener
            }
            runNet { api.shareDraft(id, draft.name, draft.durationMs, draft.effect); log("Draft shared") }
        }
        binding.startSpin.setOnClickListener {
            val id = roomId ?: return@setOnClickListener toast("Join a room first")
            runNet {
                val result = api.startSpin(id)
                log("Start spin: $result")
            }
        }
        binding.reconnect.setOnClickListener {
            val id = roomId ?: return@setOnClickListener
            val uid = api.userId ?: return@setOnClickListener
            realtime.reconnectState(id, uid)
            runNet { log("State: ${api.roomState(id)}") }
        }
    }

    private fun ensureMicThenRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            val file = drafts.newFile()
            recordingFile = file
            if (!NativeAudio.startRecording(file.absolutePath, echo = true)) {
                toast("Oboe could not start the input stream")
                return
            }
            recordStartedAt = System.currentTimeMillis()
            log("Recording with Echo via Oboe…")
        } catch (t: Throwable) {
            toast("Record failed: ${t.message}")
        }
    }

    private fun stopRecording() {
        NativeAudio.stopRecording()
        val file = recordingFile ?: return
        val duration = System.currentTimeMillis() - recordStartedAt
        val wavDuration = wavDurationMs(file).takeIf { it > 0 } ?: duration
        val name = "Draft " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        drafts.save(
            Draft(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = System.currentTimeMillis(),
                durationMs = wavDuration,
                path = file.absolutePath
            )
        )
        recordingFile = null
        refreshDrafts()
        log("Saved $name (${wavDuration}ms)")
    }

    private fun wavDurationMs(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            value?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun ensureUser() {
        api.baseUrl = binding.apiUrl.text.toString().trim().trimEnd('/')
        val name = binding.displayName.text.toString().ifBlank { "Player" }
        if (api.userId == null) {
            val user = api.createUser(name)
            api.userId = user.getString("id")
            log("User ${api.userId}")
        }
    }

    private fun enterRoom(snapshot: JSONObject) {
        val room = snapshot.getJSONObject("room")
        roomId = room.getString("id")
        val code = room.getString("code")
        runOnUiThread {
            binding.roomCode.setText(code)
        }
        val uid = api.userId ?: return
        realtime.connect(api.baseUrl, roomId!!, uid) { event, payload ->
            runOnUiThread { log("$event $payload") }
        }
        log("Room $code members=${snapshot.optJSONArray("members")}")
    }

    private fun refreshDrafts() {
        adapter?.submit(drafts.list())
    }

    private fun runNet(block: () -> Unit) {
        thread {
            try {
                block()
            } catch (t: Throwable) {
                runOnUiThread {
                    log("Error: ${t.message}")
                    toast(t.message ?: "Network error")
                }
            }
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            binding.logView.append("\n$message")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        realtime.disconnect()
        NativeAudio.stopPlayback()
        super.onDestroy()
    }
}

class DraftAdapter(
    private val onPlay: (Draft) -> Unit,
    private val onDelete: (Draft) -> Unit,
    private val onSelect: (Draft) -> Unit
) : RecyclerView.Adapter<DraftViewHolder>() {
    private var items: List<Draft> = emptyList()

    fun submit(value: List<Draft>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DraftViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_draft, parent, false)
        return DraftViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: DraftViewHolder, position: Int) {
        val item = items[position]
        val whenText = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(item.createdAt))
        holder.meta.text = "${item.name}\n$whenText • ${item.durationMs / 1000}s • ${item.effect}"
        holder.itemView.setOnClickListener { onSelect(item) }
        holder.play.setOnClickListener { onPlay(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }
}

class DraftViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
    val meta: android.widget.TextView = view.findViewById(R.id.draftMeta)
    val play: android.widget.Button = view.findViewById(R.id.playDraft)
    val delete: android.widget.Button = view.findViewById(R.id.deleteDraft)
}