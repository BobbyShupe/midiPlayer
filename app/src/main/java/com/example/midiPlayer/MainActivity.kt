package com.example.midiPlayer

import android.widget.ImageButton
import android.graphics.Typeface
import android.app.Service
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.Process
import android.content.Context
import android.graphics.Canvas
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams as CLParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var audioService: AudioPlaybackService? = null
    private val playlists = mutableMapOf<String, MutableList<Uri>>()
    private var currentPlaylistName: String = "Default"
    private val currentPlaylist: MutableList<Uri>
        get() = playlists.getOrPut(currentPlaylistName) { mutableListOf() }

    private var currentPosition = -1
    private var isAdlInitialized = false

    private lateinit var volumeSlider: VerticalSeekBar
    private lateinit var statusText: MaterialTextView
    private lateinit var rvPlaylist: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var spinnerPlaylists: Spinner

    // Audio playback

    val audioBufferSize = AudioTrack.getMinBufferSize(
        48000,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 4
    val bufferSize = AudioTrack.getMinBufferSize(
        48000,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 4

    private val handler = Handler(Looper.getMainLooper())
    private var positionCheckRunnable: Runnable? = null

    // Native JNI methods
    private external fun initAdlMidi(sampleRate: Int = 48000): Boolean
    private external fun loadMidiData(data: ByteArray): Boolean
    private external fun generateSamples(buffer: ShortArray, sampleCount: Int): Int
    private external fun playAdl()
    private external fun pauseAdl()
    private external fun stopAdl()
    private external fun setAdlVolume(vol: Float)
    private external fun setIsActive(active: Boolean)
    private external fun isAdlPlaying(): Boolean
    private external fun getAdlPositionMs(): Int
    private external fun getAdlDurationMs(): Int
    private external fun releaseAdl()

    companion object {
        init {
            System.loadLibrary("midiplayer")
        }
    }

    private val pickMultipleMidi = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.let {
            val validUris = it.mapNotNull { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    uri
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.toString() }

            currentPlaylist.addAll(validUris)
            playlistAdapter.notifyDataSetChanged()
            savePlaylists()
            if (currentPosition == -1 && currentPlaylist.isNotEmpty()) {
                playAt(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val channel = NotificationChannel(
            "midi",
            "MIDI Playback",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val root = ConstraintLayout(this).apply {
            layoutParams = CLParams(CLParams.MATCH_PARENT, CLParams.MATCH_PARENT)
            setBackgroundColor(0xFF000000.toInt())
        }

        statusText = MaterialTextView(this).apply {
            text = "No files in playlist"
            textSize = 20f
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER
            id = View.generateViewId()
            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToTop = CLParams.PARENT_ID
                startToStart = CLParams.PARENT_ID
                endToEnd = CLParams.PARENT_ID
                topMargin = 32
            }
        }

        spinnerPlaylists = Spinner(this).apply {
            id = View.generateViewId()
            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToBottom = statusText.id
                startToStart = CLParams.PARENT_ID
                endToEnd = CLParams.PARENT_ID
                topMargin = 24
            }
        }

        val btnContainer = ConstraintLayout(this).apply {
            id = View.generateViewId()
            layoutParams = CLParams(CLParams.MATCH_PARENT, CLParams.WRAP_CONTENT).apply {
                bottomToBottom = CLParams.PARENT_ID
                startToStart = CLParams.PARENT_ID
                endToEnd = CLParams.PARENT_ID
                bottomMargin = 24
            }
            setPadding(0, 16, 0, 16)
        }

        volumeSlider = VerticalSeekBar(this).apply {
            id = View.generateViewId()
            max = 100
            progress = 80
            thumbTintList = ColorStateList.valueOf(0xFF44FF44.toInt())
            progressTintList = ColorStateList.valueOf(0xFFAAAAAA.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xFF444444.toInt())

            layoutParams = CLParams(120, 0).apply {
                topToBottom = spinnerPlaylists.id
                bottomToTop = btnContainer.id
                endToEnd = CLParams.PARENT_ID
                marginEnd = 30
                topMargin = 16
                bottomMargin = 16
            }
        }

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val vol = progress / 12.5f   //
                setAdlVolume(vol)
                Log.d("MidiPlayer", "Slider volume set to $vol (native gain applied)")
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rvPlaylist = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = CLParams(0, 0).apply {
                width = CLParams.MATCH_CONSTRAINT
                height = CLParams.MATCH_CONSTRAINT
                topToBottom = spinnerPlaylists.id
                bottomToTop = btnContainer.id
                startToStart = CLParams.PARENT_ID
                endToStart = volumeSlider.id
                topMargin = 16
                bottomMargin = 16
                marginEnd = 16
            }
        }

        // Buttons (same as before)
        val btnAddFiles = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Add Files"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
                )
            )
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToTop = CLParams.PARENT_ID
                startToStart = CLParams.PARENT_ID
                marginStart = 2
                topMargin = 2
            }
            setOnClickListener {
                pickMultipleMidi.launch(arrayOf("audio/midi", "audio/mid", "audio/x-midi"))
            }
        }

        val btnNewPlaylist = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "New Playlist"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToTop = CLParams.PARENT_ID
                startToEnd = btnAddFiles.id
                marginStart = 4
                marginEnd = 4
                topMargin = 2
            }
            setOnClickListener { showCreatePlaylistDialog() }
        }

        val btnDeletePlaylist = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Delete Playlist"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToTop = CLParams.PARENT_ID
                startToEnd = btnNewPlaylist.id
                marginEnd = 2
                marginStart = 4
                topMargin = 2
            }
            setOnClickListener { showDeletePlaylistDialog() }
        }

        val btnPlay = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Play"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToBottom = btnAddFiles.id
                topMargin = 2
                startToStart = CLParams.PARENT_ID
                marginStart = 4
                bottomMargin = 2
            }
            setOnClickListener {
                if (currentPosition >= 0) {
                    playAdl()
                    startAudioPlaybackIfNeeded()
                    playlistAdapter.notifyDataSetChanged()
                } else if (currentPlaylist.isNotEmpty()) {
                    playAt(0)
                }
            }
        }

        val btnPause = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Pause"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToBottom = btnAddFiles.id
                topMargin = 2
                startToEnd = btnPlay.id
                marginStart = 16
                bottomMargin = 2
            }
            setOnClickListener {
                pauseAdl()
                pauseAudioPlayback()
                playlistAdapter.notifyDataSetChanged()
            }
        }

        val btnStop = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Stop"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToBottom = btnAddFiles.id
                topMargin = 2
                startToEnd = btnPause.id
                marginStart = 16
                bottomMargin = 2
            }
            setOnClickListener {
                stopPlayback()
                currentPosition = -1
                playlistAdapter.notifyDataSetChanged()
                statusText.text = "Stopped"
            }
        }

        val btnNext = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Next"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = btnAddFiles.backgroundTintList
            setTextColor(btnAddFiles.textColors)
            strokeWidth = 3
            strokeColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            layoutParams = CLParams(CLParams.WRAP_CONTENT, CLParams.WRAP_CONTENT).apply {
                topToBottom = btnAddFiles.id
                topMargin = 2
                startToEnd = btnStop.id
                marginStart = 16
                marginEnd = 32
                bottomMargin = 2
            }
            setOnClickListener {
                if (currentPosition < currentPlaylist.size - 1) {
                    playAt(currentPosition + 1)
                }
            }
        }

        btnContainer.addView(btnAddFiles)
        btnContainer.addView(btnNewPlaylist)
        btnContainer.addView(btnDeletePlaylist)
        btnContainer.addView(btnPlay)
        btnContainer.addView(btnPause)
        btnContainer.addView(btnStop)
        btnContainer.addView(btnNext)

        root.addView(statusText)
        root.addView(spinnerPlaylists)
        root.addView(volumeSlider)
        root.addView(rvPlaylist)
        root.addView(btnContainer)

        setContentView(root)

        playlistAdapter = PlaylistAdapter(
            onClick = { pos -> playAt(pos) },
            onMoveUp = { pos ->
                if (pos > 0) {
                    val item = currentPlaylist.removeAt(pos)
                    currentPlaylist.add(pos - 1, item)
                    if (currentPosition == pos) currentPosition--
                    else if (currentPosition == pos - 1) currentPosition++
                    playlistAdapter.notifyItemMoved(pos, pos - 1)
                    savePlaylists()
                }
            },
            onMoveDown = { pos ->
                if (pos < currentPlaylist.size - 1) {
                    val item = currentPlaylist.removeAt(pos)
                    currentPlaylist.add(pos + 1, item)
                    if (currentPosition == pos) currentPosition++
                    else if (currentPosition == pos + 1) currentPosition--
                    playlistAdapter.notifyItemMoved(pos, pos + 1)
                    savePlaylists()
                }
            },
            onDelete = { pos ->
                showRemoveTrackDialog(pos)   // reuse your existing dialog
            }
        )
        rvPlaylist.adapter = playlistAdapter

        // Spinner setup
        val spinnerAdapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerPlaylists.adapter = spinnerAdapter

        spinnerPlaylists.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos in playlists.keys.indices) {
                    currentPlaylistName = playlists.keys.elementAt(pos)
                    playlistAdapter.notifyDataSetChanged()
                    stopPlayback()
                    currentPosition = -1
                    statusText.text = if (currentPlaylist.isEmpty()) {
                        "Playlist \"$currentPlaylistName\" is empty"
                    } else {
                        "Switched to \"$currentPlaylistName\""
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        playlistAdapter = PlaylistAdapter(
            onClick    = { pos -> playAt(pos) },
            onMoveUp   = { pos ->
                if (pos > 0) {
                    val item = currentPlaylist.removeAt(pos)
                    currentPlaylist.add(pos - 1, item)
                    if (currentPosition == pos) currentPosition = pos - 1
                    else if (currentPosition == pos - 1) currentPosition = pos
                    playlistAdapter.notifyDataSetChanged()
                    savePlaylists()
                }
            },
            onMoveDown = { pos ->
                if (pos < currentPlaylist.size - 1) {
                    val item = currentPlaylist.removeAt(pos)
                    currentPlaylist.add(pos + 1, item)
                    if (currentPosition == pos) currentPosition = pos + 1
                    else if (currentPosition == pos + 1) currentPosition = pos
                    playlistAdapter.notifyDataSetChanged()
                    savePlaylists()
                }
            },
            onDelete   = { pos -> showRemoveTrackDialog(pos) }
        )
        rvPlaylist.adapter = playlistAdapter

        // Init libADLMIDI
        isAdlInitialized = initAdlMidi(48000)
        if (!isAdlInitialized) {
            statusText.text = "Failed to initialize libADLMIDI"
        }

        loadPlaylists()
        updateSpinner()
    }

    private fun getDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
            ?: "Track ${currentPlaylist.indexOf(uri) + 1}"
    }

    private fun playAt(position: Int) {
        setIsActive(false) // Stop audio thread generation

        val uri = currentPlaylist[position]
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val midiBytes = inputStream?.use { it.readBytes() }

            setIsActive(false)

            stopAdl();

            Thread.sleep(50) // give parser time to die

            if (isAdlInitialized) {
                releaseAdl()                     // calls adl_close()
                isAdlInitialized = initAdlMidi(48000)  // re-init fresh player
                if (!isAdlInitialized) {
                    statusText.text = "Re-init failed!"
                    return
                }
            }

            if (midiBytes != null && midiBytes.isNotEmpty()) {
                Log.d("MidiPlayer", "File size: ${midiBytes.size} bytes")

                if (loadMidiData(midiBytes)) {
                    currentPosition = position
                    playAdl()       // Start sequencer
                    setIsActive(true) // Resume audio thread
                    startAudioPlaybackIfNeeded()

                    startPositionPolling()

                    // Force UI refresh so the new "currentPosition" is highlighted
                    handler.post { playlistAdapter.notifyDataSetChanged() }
                } else {
                    Log.e("MidiPlayer", "JNI loadMidiData returned false")
                }
            }
        } catch (e: Exception) {
            Log.e("MidiPlayer", "Failed to load MIDI", e)
        }
    }


    // Inside MainActivity class
    private var isBound = false

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.AudioBinder
            audioService = binder.getService()
            isBound = true

            // Sync the restored volume to the service immediately upon connection
            val currentVol = volumeSlider.progress / 100f
            audioService?.setVolume(currentVol)

            if (currentPosition != -1) {
                resumeOrStartAudio()
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private fun startAudioPlaybackIfNeeded() {
        val intent = Intent(this, AudioPlaybackService::class.java)
        // startForegroundService is required for background starts on API 26+
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun resumeOrStartAudio() {
        // Pass the JNI generateSamples method as a callback to the service
        audioService?.startPlayback(48000, 2048) { buffer, count ->
            generateSamples(buffer, count)
        }
        startPositionPolling()
    }

    private fun formatTime(ms: Int): String {
        val sec = (ms / 1000) % 60
        val min = (ms / 1000) / 60
        return "%d:%02d".format(min, sec)
    }

    private fun pauseAudioPlayback() {
        audioService?.pausePlayback()
        stopPositionPolling()
    }

    private fun stopPlayback() {
        stopPositionPolling()

        audioService?.let { service ->
            service.stopPlayback()           // sets interrupt + stops AudioTrack
            // Give thread time to exit cleanly
            Thread.sleep(80)                 // ← crude but effective for now
        }

        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, AudioPlaybackService::class.java))

        // Now it's reasonably safe to destroy the synth
        releaseAdl()
    }

    private fun startPositionPolling() {
        positionCheckRunnable?.let { handler.removeCallbacks(it) }

        positionCheckRunnable = object : Runnable {
            override fun run() {
                if (!isAdlPlaying()) {
                    stopPositionPolling()
                    return
                }

                val posMs = getAdlPositionMs()
                val durMs = getAdlDurationMs()

                // Update UI on main thread
                handler.post {
                    playlistAdapter.notifyItemChanged(currentPosition) // refresh row

                    // Optional: update statusText too
                    if (currentPosition >= 0 && currentPlaylist.isNotEmpty()) {
                        val posStr = formatTime(posMs)
                        val durStr = formatTime(durMs)
                        statusText.text = "${getDisplayName(currentPlaylist[currentPosition])}  $posStr / $durStr"
                    }

                    // Also update per-row position if you want
                    rvPlaylist.findViewHolderForAdapterPosition(currentPosition)?.let { vh ->
                        if (vh is PlaylistAdapter.ViewHolder) {
                            vh.tvPosition.text = "${formatTime(posMs)} / ${formatTime(durMs)}"
                        }
                    }
                }

                if (durMs > 1000 && posMs >= durMs - 800) {
                    handler.post { handleTrackCompletion() }
                    return
                }

                handler.postDelayed(this, 800)   // ~1 fps update is enough
            }
        }

        handler.postDelayed(positionCheckRunnable!!, 1500)
    }

    private fun stopPositionPolling() {
        positionCheckRunnable?.let { handler.removeCallbacks(it) }
        positionCheckRunnable = null
    }

    private fun handleTrackCompletion() {
        val next = currentPosition + 1
        if (next < currentPlaylist.size) {
            // Run on UI thread to ensure the adapter sees the change
            handler.post {
                playAt(next)
                playlistAdapter.notifyDataSetChanged()
            }
        } else {
            handler.post {
                stopPlayback()
                statusText.text = "Finished playlist"
                currentPosition = -1
                playlistAdapter.notifyDataSetChanged()
            }
        }
    }

    // Playlist / UI logic (unchanged from your previous working version)

    private fun showCreatePlaylistDialog() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !playlists.containsKey(name)) {
                    playlists[name] = mutableListOf()
                    currentPlaylistName = name
                    savePlaylists()
                    updateSpinner()
                    spinnerPlaylists.setSelection(playlists.keys.indexOf(name).coerceAtLeast(0))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog() {
        if (playlists.size <= 1) {
            statusText.text = "Cannot delete the last playlist"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("Delete \"$currentPlaylistName\"?")
            .setPositiveButton("Delete") { _, _ ->
                playlists.remove(currentPlaylistName)
                currentPlaylistName = playlists.keys.firstOrNull() ?: "Default"
                savePlaylists()
                updateSpinner()
                playlistAdapter.notifyDataSetChanged()
                statusText.text = "Playlist deleted"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSpinner() {
        (spinnerPlaylists.adapter as ArrayAdapter<String>).apply {
            clear()
            addAll(playlists.keys.toList())
            notifyDataSetChanged()
        }
        val index = playlists.keys.indexOf(currentPlaylistName).coerceAtLeast(0)
        spinnerPlaylists.setSelection(index)
    }

    private fun savePlaylists() {
        val prefs = getSharedPreferences("midi_player", MODE_PRIVATE)
        val data = mapOf(
            "playlists" to playlists.mapValues { (_, uris) -> uris.map { it.toString() } },
            "current" to currentPlaylistName,
            "volume" to volumeSlider.progress // This saves the current slider position
        )
        prefs.edit().putString("data", Gson().toJson(data)).apply()
    }

    private fun loadPlaylists() {
        val prefs = getSharedPreferences("midi_player", MODE_PRIVATE)
        val json = prefs.getString("data", null) ?: return
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val data: Map<String, Any> = Gson().fromJson(json, type) ?: return

        @Suppress("UNCHECKED_CAST")
        val saved = data["playlists"] as? Map<String, List<String>> ?: return
        saved.forEach { (name, uriStrings) ->
            val validUris = mutableListOf<Uri>()
            uriStrings.forEach { uriString ->
                val uri = Uri.parse(uriString)
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.close()
                    validUris.add(uri)
                } catch (_: Exception) {}
            }
            playlists[name] = validUris
        }
        currentPlaylistName = (data["current"] as? String) ?: "Default"
        if (!playlists.containsKey(currentPlaylistName)) {
            currentPlaylistName = playlists.keys.firstOrNull() ?: "Default"
        }

        // --- FIX FOR VOLUME PERSISTENCE ---
        // 1. Extract volume (Gson numbers are usually Double)
        val savedVol = (data["volume"] as? Double)?.toInt() ?: 80

        // 2. Update the UI slider
        volumeSlider.progress = savedVol

        // 3. Define volFloat and apply to native synth
        val volFloat = savedVol / 100f
        setAdlVolume(volFloat)

        // 4. Apply to service if it's already bound
        // (Note: Ensure you have the audioService variable and connection logic implemented)
        // audioService?.setVolume(volFloat)
    }

    override fun onDestroy() {
        stopPlayback()
        releaseAdl()
        super.onDestroy()
    }

    inner class PlaylistAdapter(
        private val onClick: (Int) -> Unit,
        private val onMoveUp: (Int) -> Unit,
        private val onMoveDown: (Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: MaterialTextView     = view.findViewById(R.id.tv_file_name)
            val tvStatus: MaterialTextView   = view.findViewById(R.id.tv_status)
            val tvPosition: MaterialTextView = view.findViewById(R.id.tv_position)
            val btnUp: ImageButton           = view.findViewById(R.id.btn_move_up)
            val btnDown: ImageButton         = view.findViewById(R.id.btn_move_down)
            val btnDelete: ImageButton       = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_playlist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = currentPlaylist[position]
            holder.tvName.text = getDisplayName(uri)

            val isPlaying = position == currentPosition

            if (isPlaying) {
                holder.tvStatus.visibility = View.VISIBLE
                holder.tvPosition.visibility = View.VISIBLE
                holder.tvStatus.text = if (isAdlPlaying()) "Playing" else "Paused"

                // Stronger visual feedback
                holder.itemView.setBackgroundColor(0xFF1E3A5F.toInt())   // dark blue-ish
                holder.tvName.setTextColor(0xFFBBDDFF.toInt())
                holder.tvName.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                holder.tvStatus.visibility = View.GONE
                holder.tvPosition.visibility = View.GONE
                holder.itemView.setBackgroundColor(0)
                holder.tvName.setTextColor(0xFFFFFFFF.toInt())
                holder.tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            // Position display (will be updated by polling)
            holder.tvPosition.text = "—:—— / —:——"

            // Click to play
            holder.itemView.setOnClickListener { onClick(position) }

            // Action buttons (visible always or only on playing — your choice)
            holder.btnUp.setOnClickListener { onMoveUp(position) }
            holder.btnDown.setOnClickListener { onMoveDown(position) }
            holder.btnDelete.setOnClickListener { onDelete(position) }

            // Optional: disable up/down at edges
            holder.btnUp.isEnabled = position > 0
            holder.btnDown.isEnabled = position < currentPlaylist.size - 1

            // Inside onBindViewHolder
            val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            holder.btnUp.imageTintList = whiteTint
            holder.btnDown.imageTintList = whiteTint
            holder.btnDelete.imageTintList = whiteTint
        }

        override fun getItemCount() = currentPlaylist.size
    }

    private fun showRemoveTrackDialog(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove Track")
            .setMessage("Remove \"${getDisplayName(currentPlaylist[position])}\" ?")
            .setPositiveButton("Remove") { _, _ ->
                currentPlaylist.removeAt(position)
                if (currentPosition == position) {
                    stopPlayback()
                    currentPosition = -1
                } else if (currentPosition > position) {
                    currentPosition--
                }
                playlistAdapter.notifyDataSetChanged()
                savePlaylists()
                statusText.text = "Track removed"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}



// Vertical See kBar (unchanged)
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val progressValue = (max * (height - event.y) / height).toInt()
                progress = progressValue.coerceIn(0, max)
                onSizeChanged(width, height, 0, 0)
                if (event.action != MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}