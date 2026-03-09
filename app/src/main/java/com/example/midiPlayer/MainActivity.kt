package com.example.midiPlayer

import android.widget.SeekBar
import android.os.ParcelFileDescriptor
import java.io.IOException
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams as CLParams

class MainActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val playlists = mutableMapOf<String, MutableList<Uri>>()
    private var currentPlaylistName: String = "Default"
    private val currentPlaylist: MutableList<Uri>
        get() = playlists.getOrPut(currentPlaylistName) { mutableListOf() }

    private var currentPosition = -1

    private lateinit var volumeSlider: SeekBar
    private lateinit var statusText: MaterialTextView
    private lateinit var rvPlaylist: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var spinnerPlaylists: Spinner

    private var currentPfd: ParcelFileDescriptor? = null


    private val pickMultipleMidi = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let {
            val contentResolver = contentResolver
            val validUris = it.mapNotNull { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
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


        volumeSlider = SeekBar(this).apply {
            id = View.generateViewId()
            max = 100
            progress = 100
            rotation = -90f
        }

        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val volume = progress / 100f
                mediaPlayer?.setVolume(volume, volume)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        volumeSlider.layoutParams = CLParams(
            500,80
        ).apply {
            endToEnd = CLParams.PARENT_ID
            topToTop = CLParams.PARENT_ID
            bottomToBottom = CLParams.PARENT_ID
            marginEnd = 8
        }



        val btnContainer = ConstraintLayout(this).apply {
            id = View.generateViewId()
            layoutParams = CLParams(CLParams.MATCH_PARENT, CLParams.WRAP_CONTENT).apply {
                bottomToBottom = CLParams.PARENT_ID
                startToStart = CLParams.PARENT_ID
                endToEnd = CLParams.PARENT_ID
                bottomMargin = 24
            }
            setPadding(0,16,0,16)
        }

        rvPlaylist = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = CLParams(CLParams.MATCH_PARENT, 0).apply {
                topToBottom = volumeSlider.id
                volumeSlider.thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                volumeSlider.progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                bottomToTop = btnContainer.id
                startToStart = CLParams.PARENT_ID
                endToEnd = CLParams.PARENT_ID
                topMargin = 16
                bottomMargin = 16
            }
        }

        // ── Larger, more readable buttons ───────────────────────────────────

        val btnAddFiles = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Add Files"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)

            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
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
                horizontalChainStyle = CLParams.CHAIN_SPREAD_INSIDE
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
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
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
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
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
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
                )
            )
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
                if (mediaPlayer?.isPlaying == true) return@setOnClickListener
                if (currentPosition >= 0) {
                    mediaPlayer?.start()
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
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
                )
            )
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
                mediaPlayer?.pause()
                playlistAdapter.notifyDataSetChanged()
            }
        }

        val btnStop = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Stop"
            textSize = 16f
            cornerRadius = 0
            setPadding(12, 2, 12, 2)
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
                )
            )
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
                mediaPlayer?.stop()
                mediaPlayer?.reset()
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
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf()
                ),
                intArrayOf(
                    0xFFFFFFFF.toInt(),  // color when finger is touching
                    0xFF000000.toInt()   // normal color
                )
            )

            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf()
                    ),
                    intArrayOf(
                        0xFF000000.toInt(), // pressed text
                        0xFFFFFFFF.toInt()  // normal text
                    )
                )
            )
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

        playlistAdapter = PlaylistAdapter { pos -> playAt(pos) }
        rvPlaylist.adapter = playlistAdapter

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
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Track ${currentPlaylist.indexOf(uri) + 1}"
    }

    private fun playAt(position: Int) {
        if (position < 0 || position >= currentPlaylist.size) return

        // Clean up previous player + FD
        mediaPlayer?.release()
        mediaPlayer = null
        currentPfd?.close()
        currentPfd = null
        currentPosition = position

        val uri = currentPlaylist[position]

        try {
            mediaPlayer = MediaPlayer().apply {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw java.io.FileNotFoundException("Cannot open ParcelFileDescriptor for $uri")

                currentPfd = pfd  // <-- Keep it alive!

                setDataSource(pfd.fileDescriptor)

                setOnPreparedListener { start() }
                setOnCompletionListener {
                    releasePlayer()  // will close pfd
                    if (currentPosition < currentPlaylist.size - 1) {
                        playAt(currentPosition + 1)
                    } else {
                        statusText.text = "Finished playlist"
                        this@MainActivity.currentPosition = -1
                        playlistAdapter.notifyDataSetChanged()
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    statusText.text = "Playback error: $what / $extra\n${getDisplayName(uri)}"
                    releasePlayer()
                    this@MainActivity.currentPosition = -1
                    playlistAdapter.notifyDataSetChanged()
                    true
                }
                prepareAsync()
            }

            statusText.text = "Loading: ${getDisplayName(uri)}"
            playlistAdapter.notifyDataSetChanged()

        } catch (e: java.io.FileNotFoundException) {
            statusText.text = "Access denied or file not found:\n${getDisplayName(uri)}"
            currentPosition = -1
            playlistAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            statusText.text = "Failed to load:\n${e.javaClass.simpleName}: ${e.localizedMessage}"
            currentPosition = -1
            playlistAdapter.notifyDataSetChanged()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPfd?.close()
        currentPfd = null
        currentPosition = -1
    }

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
            "current" to currentPlaylistName
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
                } catch (_: SecurityException) {
                    // permission lost
                } catch (_: Exception) {
                    // file gone
                }
            }

            playlists[name] = validUris
        }
        currentPlaylistName = (data["current"] as? String) ?: "Default"
        if (!playlists.containsKey(currentPlaylistName)) {
            currentPlaylistName = playlists.keys.firstOrNull() ?: "Default"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    inner class PlaylistAdapter(
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

        private val items: List<Uri> get() = currentPlaylist

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: MaterialTextView = view.findViewById(R.id.tv_file_name)
            val tvStatus: MaterialTextView = view.findViewById(R.id.tv_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_playlist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = items[position]
            holder.tvName.text = getDisplayName(uri)

            if (position == currentPosition) {
                holder.tvStatus.text = if (mediaPlayer?.isPlaying == true) "Playing" else "Paused"
                holder.tvStatus.visibility = View.VISIBLE
                holder.itemView.setBackgroundColor(0x220000FF)
            } else {
                holder.tvStatus.visibility = View.GONE
                holder.itemView.setBackgroundColor(0)
            }

            holder.itemView.setOnClickListener { onClick(position) }

            holder.itemView.setOnLongClickListener {
                showRemoveTrackDialog(position)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private fun showRemoveTrackDialog(position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove Track")
            .setMessage("Remove \"${getDisplayName(currentPlaylist[position])}\" ?")
            .setPositiveButton("Remove") { _, _ ->
                currentPlaylist.removeAt(position)
                if (currentPosition == position) {
                    mediaPlayer?.stop()
                    mediaPlayer?.reset()
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