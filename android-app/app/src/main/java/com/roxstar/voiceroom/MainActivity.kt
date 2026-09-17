package com.roxstar.voiceroom

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
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

        // API URL is not shown to the user.
        binding.apiUrl.visibility = View.GONE

        // Technical log is also hidden.
        binding.logView.visibility = View.GONE

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
                toast("Selected ${it.name}")
            }
        )

        binding.draftList.layoutManager =
            LinearLayoutManager(this)

        binding.draftList.adapter = adapter

        refreshDrafts()

        // =========================================================
        // RECORD
        // =========================================================

        binding.recordButton.setOnClickListener {

            if (!isStartingRecording) {
                isStartingRecording = true
                ensureMicThenRecord()
            }
        }

        // =========================================================
        // STOP
        // =========================================================

        binding.stopButton.setOnClickListener {
            stopRecording()
        }

        // =========================================================
        // CANCEL
        // =========================================================

        binding.cancelButton.setOnClickListener {

            NativeAudio.cancelRecording()

            recordingFile?.delete()
            recordingFile = null

            toast("Recording cancelled")
        }

        // =========================================================
        // CREATE ROOM
        // =========================================================

        binding.createRoom.setOnClickListener {

            runNet {

                ensureUser()

                val snapshot = api.createRoom()

                enterRoom(snapshot)
            }
        }

        // =========================================================
        // JOIN ROOM
        // =========================================================

        binding.joinRoom.setOnClickListener {

            val code = binding.roomCode.text
                .toString()
                .trim()

            if (code.isBlank()) {
                toast("Enter a room code")
                return@setOnClickListener
            }

            runNet {

                ensureUser()

                val snapshot =
                    api.joinRoom(code)

                enterRoom(snapshot)
            }
        }

        // =========================================================
        // LEAVE ROOM
        // =========================================================

        binding.leaveRoom.setOnClickListener {

            val id =
                roomId
                    ?: return@setOnClickListener toast(
                        "You are not in a room"
                    )

            runNet {

                api.leaveRoom(id)

                realtime.disconnect()

                roomId = null

                runOnUiThread {
                    toast("👋 You left the room")
                }
            }
        }

        // =========================================================
        // SHARE DRAFT
        // =========================================================

        binding.shareDraft.setOnClickListener {

            val id = roomId
            val draft = selectedDraft

            if (id == null) {
                toast("Join a room first")
                return@setOnClickListener
            }

            if (draft == null) {
                toast("Select a draft first")
                return@setOnClickListener
            }

            runNet {

                api.shareDraft(
                    id,
                    draft.name,
                    draft.durationMs,
                    draft.effect
                )

                runOnUiThread {
                    toast("🎵 Draft shared")
                }
            }
        }

        // =========================================================
        // START SPIN
        // =========================================================

        binding.startSpin.setOnClickListener {

            val id =
                roomId
                    ?: return@setOnClickListener toast(
                        "Join a room first"
                    )

            runNet {

                api.startSpin(id)

                runOnUiThread {
                    toast("🎯 Spin started")
                }
            }
        }

        // =========================================================
        // RECONNECT
        // =========================================================

        binding.reconnect.setOnClickListener {

            val id =
                roomId
                    ?: return@setOnClickListener toast(
                        "Join a room first"
                    )

            val uid =
                api.userId
                    ?: return@setOnClickListener toast(
                        "User session not ready"
                    )

            realtime.reconnectState(
                id,
                uid
            )

            runNet {

                api.roomState(id)

                runOnUiThread {
                    toast("🔄 Room connection refreshed")
                }
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

                val file =
                    drafts.newFile()

                recordingFile = file

                val ok =
                    NativeAudio.startRecording(
                        file.absolutePath,
                        echo = true
                    )

                runOnUiThread {

                    if (!ok) {

                        toast(
                            "Audio recording could not start"
                        )

                    } else {

                        recordStartedAt =
                            System.currentTimeMillis()

                        toast("🎙️ Recording started")
                    }

                    isStartingRecording = false
                }

            } catch (t: Throwable) {

                runOnUiThread {

                    toast(
                        "Record failed"
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

        val file =
            recordingFile ?: return

        val duration =
            System.currentTimeMillis() -
                    recordStartedAt

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
                    createdAt =
                        System.currentTimeMillis(),
                    durationMs =
                        wavDuration,
                    path =
                        file.absolutePath
                )
            )

            recordingFile = null

            runOnUiThread {

                refreshDrafts()

                toast("✅ $name saved")
            }
        }
    }

    // =============================================================
    // WAV DURATION
    // =============================================================

    private fun wavDurationMs(
        file: File
    ): Long {

        return try {

            val retriever =
                MediaMetadataRetriever()

            retriever.setDataSource(
                file.absolutePath
            )

            val value =
                retriever.extractMetadata(
                    MediaMetadataRetriever
                        .METADATA_KEY_DURATION
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

        api.baseUrl =
            BuildConfig.API_BASE_URL
                .trimEnd('/')

        val name =
            binding.displayName
                .text
                .toString()
                .trim()
                .ifBlank {
                    "Player"
                }

        if (api.userId == null) {

            val user =
                api.createUser(name)

            api.userId =
                user.getString("id")
        }
    }

    // =============================================================
    // ENTER ROOM
    // =============================================================

    private fun enterRoom(
        snapshot: JSONObject
    ) {

        val room =
            snapshot.getJSONObject("room")

        roomId =
            room.getString("id")

        val code =
            room.getString("code")

        runOnUiThread {

            binding.roomCode.setText(code)

            toast("🎮 Joined room $code")
        }

        val uid =
            api.userId ?: return

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
    }

    // =============================================================
    // REALTIME EVENTS
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
                            .trim()
                            .ifBlank {
                                "Unknown player"
                            }

                    val points =
                        payload.optInt(
                            "virtualPoints",
                            0
                        )

                    toast(
                        "🏆 $winnerName wins!\n💰 +$points points"
                    )
                }

                // -------------------------------------------------
                // PLAYER ELIMINATED
                // -------------------------------------------------

                "user_eliminated" -> {

                    toast(
                        "❌ A player was eliminated"
                    )
                }

                // -------------------------------------------------
                // SPIN STARTED
                // -------------------------------------------------

                "spin_started" -> {

                    toast(
                        "🎯 Spin started"
                    )
                }

                // -------------------------------------------------
                // PLAYER JOINED
                // -------------------------------------------------

                "user_joined" -> {

                    toast(
                        "🟢 A player joined the room"
                    )
                }

                // -------------------------------------------------
                // PLAYER LEFT
                // -------------------------------------------------

                "user_left" -> {

                    toast(
                        "🔴 A player left the room"
                    )
                }

                // -------------------------------------------------
                // DRAFT SHARED
                // -------------------------------------------------

                "draft_shared" -> {

                    toast(
                        "🎵 A draft was shared"
                    )
                }

                // -------------------------------------------------
                // ROOM STATE
                // -------------------------------------------------

                "room_state" -> {
                    // Intentionally hidden.
                    // Do not show raw room state or IDs.
                }

                // -------------------------------------------------
                // UNKNOWN
                // -------------------------------------------------

                else -> {
                    // Do not show technical events.
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

            } catch (_: Throwable) {

                runOnUiThread {

                    toast(
                        "Something went wrong. Please try again."
                    )
                }
            }
        }
    }

    // =============================================================
    // OLD LOG FUNCTION
    // =============================================================

    private fun log(
        message: String
    ) {

        // Kept only for compatibility.
        // logView is hidden from the user.
        runOnUiThread {

            binding.logView.append(
                "\n$message"
            )
        }
    }

    // =============================================================
    // TOAST
    // =============================================================

    private fun toast(
        message: String
    ) {

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