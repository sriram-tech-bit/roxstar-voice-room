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

    // Prevents rapid repeated taps on record button
    @Volatile
    private var isStartingRecording = false

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->

        if (granted) {
            startRecording()
        } else {
            toast("Microphone permission is required to record")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drafts = DraftStore(this)

        // URL is no longer shown or editable in the UI.
        // It is taken from BuildConfig.
        binding.apiUrl.visibility = android.view.View.GONE

        adapter = DraftAdapter(

            onPlay = { draft ->
                thread {
                    NativeAudio.startPlayback(draft.path)
                }
            },

            onDelete = {
                File(it.path).delete()

                drafts.delete(it.id)

                if (selectedDraft?.id == it.id) {
                    selectedDraft = null
                }

                refreshDrafts()
            },

            onSelect = {
                selectedDraft = it
            }
        )

        binding.draftList.layoutManager = LinearLayoutManager(this)
        binding.draftList.adapter = adapter

        refreshDrafts()

        // ---------------------------------------------------------
        // RECORD
        // ---------------------------------------------------------

        binding.recordButton.setOnClickListener {

            if (!isStartingRecording) {
                isStartingRecording = true
                ensureMicThenRecord()
            }
        }

        // ---------------------------------------------------------
        // STOP RECORDING
        // ---------------------------------------------------------

        binding.stopButton.setOnClickListener {
            stopRecording()
        }

        // ---------------------------------------------------------
        // CANCEL RECORDING
        // ---------------------------------------------------------

        binding.cancelButton.setOnClickListener {

            NativeAudio.cancelRecording()

            recordingFile?.delete()
            recordingFile = null

            log("Recording cancelled")
        }

        // ---------------------------------------------------------
        // CREATE ROOM
        // ---------------------------------------------------------

        binding.createRoom.setOnClickListener {

            runNet {

                ensureUser()

                val snap = api.createRoom()

                enterRoom(snap)
            }
        }

        // ---------------------------------------------------------
        // JOIN ROOM
        // ---------------------------------------------------------

        binding.joinRoom.setOnClickListener {

            runNet {

                ensureUser()

                val snap = api.joinRoom(
                    binding.roomCode.text.toString()
                )

                enterRoom(snap)
            }
        }

        // ---------------------------------------------------------
        // LEAVE ROOM
        // ---------------------------------------------------------

        binding.leaveRoom.setOnClickListener {

            val id = roomId ?: return@setOnClickListener

            runNet {

                api.leaveRoom(id)

                realtime.disconnect()

                roomId = null

                log("Left room")
            }
        }

        // ---------------------------------------------------------
        // SHARE DRAFT
        // ---------------------------------------------------------

        binding.shareDraft.setOnClickListener {

            val id = roomId
            val draft = selectedDraft

            if (id == null || draft == null) {

                toast("Join a room and select a draft")

                return@setOnClickListener
            }

            runNet {

                api.shareDraft(
                    id,
                    draft.name,
                    draft.durationMs,
                    draft.effect
                )

                log("Draft shared")
            }
        }

        // ---------------------------------------------------------
        // START SPIN
        // ---------------------------------------------------------

        binding.startSpin.setOnClickListener {

            val id = roomId
                ?: return@setOnClickListener toast("Join a room first")

            runNet {

                val result = api.startSpin(id)

                log("Start spin: $result")
            }
        }

        // ---------------------------------------------------------
        // RECONNECT
        // ---------------------------------------------------------

        binding.reconnect.setOnClickListener {

            val id = roomId
                ?: return@setOnClickListener

            val uid = api.userId
                ?: return@setOnClickListener

            realtime.reconnectState(id, uid)

            runNet {

                log("State: ${api.roomState(id)}")
            }
        }
    }

    // =============================================================
    // MICROPHONE PERMISSION
    // =============================================================

    private fun ensureMicThenRecord() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            startRecording()

        } else {

            permission.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    // =============================================================
    // START RECORDING
    // =============================================================

    private fun startRecording() {

        isStartingRecording = true

        thread {

            try {

                val file = drafts.newFile()

                recordingFile = file

                val ok = NativeAudio.startRecording(
                    file.absolutePath,
                    echo = true
                )

                runOnUiThread {

                    if (!ok) {

                        toast(
                            "Oboe could not start the input stream"
                        )

                    } else {

                        recordStartedAt =
                            System.currentTimeMillis()

                        log(
                            "Recording with Echo via Oboe…"
                        )
                    }

                    isStartingRecording = false
                }

            } catch (t: Throwable) {

                runOnUiThread {

                    toast(
                        "Record failed: ${t.message}"
                    )

                    isStartingRecording = false
                }
            }
        }
    }

    // =============================================================
    // STOP RECORDING
    // =============================================================

    private fun stopRecording() {

        NativeAudio.stopRecording()

        val file = recordingFile ?: return

        val duration =
            System.currentTimeMillis() - recordStartedAt

        thread {

            val wavDuration =
                wavDurationMs(file)
                    .takeIf { it > 0 }
                    ?: duration

            val name =
                "Draft " +
                        SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.US
                        ).format(Date())

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

            runOnUiThread {

                refreshDrafts()

                log(
                    "Saved $name (${wavDuration}ms)"
                )
            }
        }
    }

    // =============================================================
    // GET WAV DURATION
    // =============================================================

    private fun wavDurationMs(file: File): Long {

        return try {

            val retriever =
                MediaMetadataRetriever()

            retriever.setDataSource(
                file.absolutePath
            )

            val value =
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )

            retriever.release()

            value?.toLongOrNull() ?: 0L

        } catch (_: Exception) {

            0L
        }
    }

    // =============================================================
    // ENSURE USER
    // =============================================================

    private fun ensureUser() {

        // Use the compiled-in base URL.
        api.baseUrl =
            BuildConfig.API_BASE_URL.trimEnd('/')

        val name =
            binding.displayName
                .text
                .toString()
                .ifBlank {
                    "Player"
                }

        if (api.userId == null) {

            val user =
                api.createUser(name)

            api.userId =
                user.getString("id")

            log(
                "User ${api.userId}"
            )
        }
    }

    // =============================================================
    // ENTER ROOM
    // =============================================================

    private fun enterRoom(snapshot: JSONObject) {

        val room =
            snapshot.getJSONObject("room")

        roomId =
            room.getString("id")

        val code =
            room.getString("code")

        runOnUiThread {

            binding.roomCode.setText(code)
        }

        val uid =
            api.userId ?: return

        // Connect realtime socket
        realtime.connect(
            api.baseUrl,
            roomId!!,
            uid
        ) { event, payload ->

            handleRealtimeEvent(
                event,
                payload
            )
        }

        log(
            "Room $code members=${snapshot.optJSONArray("members")}"
        )
    }

    // =============================================================
    // REALTIME EVENT HANDLER
    // =============================================================

    private fun handleRealtimeEvent(
        event: String,
        payload: JSONObject
    ) {

        runOnUiThread {

            when (event) {

                // -------------------------------------------------
                // WINNER
                // -------------------------------------------------

                "winner_announced" -> {

                    val winnerName =
                        payload
                            .optString("winnerName")
                            .ifBlank {
                                "Unknown player"
                            }

                    val points =
                        payload.optInt(
                            "virtualPoints",
                            0
                        )

                    log(
                        "🏆 WINNER: $winnerName"
                    )

                    log(
                        "💰 Points: +$points"
                    )

                    toast(
                        "🏆 $winnerName wins!"
                    )
                }

                // -------------------------------------------------
                // PLAYER ELIMINATED
                // -------------------------------------------------

                "user_eliminated" -> {

                    val userId =
                        payload.optString(
                            "userId",
                            "Unknown"
                        )

                    val reason =
                        payload.optString(
                            "reason",
                            "eliminated"
                        )

                    log(
                        "❌ Player eliminated: $userId ($reason)"
                    )
                }

                // -------------------------------------------------
                // SPIN STARTED
                // -------------------------------------------------

                "spin_started" -> {

                    log(
                        "🎯 Spin started"
                    )
                }

                // -------------------------------------------------
                // OTHER EVENTS
                // -------------------------------------------------

                else -> {

                    log(
                        "$event $payload"
                    )
                }
            }
        }
    }

    // =============================================================
    // REFRESH DRAFTS
    // =============================================================

    private fun refreshDrafts() {

        thread {

            val items =
                drafts.list()

            runOnUiThread {

                adapter?.submit(items)
            }
        }
    }

    // =============================================================
    // NETWORK
    // =============================================================

    private fun runNet(
        block: () -> Unit
    ) {

        thread {

            try {

                block()

            } catch (t: Throwable) {

                runOnUiThread {

                    log(
                        "Error: ${t.message}"
                    )

                    toast(
                        t.message
                            ?: "Network error"
                    )
                }
            }
        }
    }

    // =============================================================
    // LOG
    // =============================================================

    private fun log(message: String) {

        runOnUiThread {

            binding.logView.append(
                "\n$message"
            )
        }
    }

    // =============================================================
    // TOAST
    // =============================================================

    private fun toast(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    // =============================================================
    // DESTROY
    // =============================================================

    override fun onDestroy() {

        realtime.disconnect()

        NativeAudio.stopPlayback()

        super.onDestroy()
    }
}

// =================================================================
// DRAFT ADAPTER
// =================================================================

class DraftAdapter(

    private val onPlay: (Draft) -> Unit,

    private val onDelete: (Draft) -> Unit,

    private val onSelect: (Draft) -> Unit

) : RecyclerView.Adapter<DraftViewHolder>() {

    private var items: List<Draft> =
        emptyList()

    fun submit(
        value: List<Draft>
    ) {

        items = value

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: android.view.ViewGroup,
        viewType: Int
    ): DraftViewHolder {

        val view =
            android.view.LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.item_draft,
                    parent,
                    false
                )

        return DraftViewHolder(view)
    }

    override fun getItemCount() =
        items.size

    override fun onBindViewHolder(
        holder: DraftViewHolder,
        position: Int
    ) {

        val item =
            items[position]

        val whenText =
            SimpleDateFormat(
                "MMM d HH:mm",
                Locale.US
            ).format(
                Date(item.createdAt)
            )

        holder.meta.text =
            "${item.name}\n" +
                    "$whenText • " +
                    "${item.durationMs / 1000}s • " +
                    item.effect

        holder.itemView.setOnClickListener {

            onSelect(item)
        }

        holder.play.setOnClickListener {

            onPlay(item)
        }

        holder.delete.setOnClickListener {

            onDelete(item)
        }
    }
}

// =================================================================
// DRAFT VIEW HOLDER
// =================================================================

class DraftViewHolder(
    view: android.view.View
) : RecyclerView.ViewHolder(view) {

    val meta: android.widget.TextView =
        view.findViewById(
            R.id.draftMeta
        )

    val play: android.widget.Button =
        view.findViewById(
            R.id.playDraft
        )

    val delete: android.widget.Button =
        view.findViewById(
            R.id.deleteDraft
        )
}