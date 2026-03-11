package com.example.midiPlayer

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat

class AudioPlaybackService : Service() {

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val binder = AudioBinder()

    inner class AudioBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            1,
            NotificationCompat.Builder(this, "midi")
                .setContentTitle("MIDI Player")
                .setContentText("Playing")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Set the volume of the active AudioTrack.
     * range: 0.0 (silent) to 1.0 (max)
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume)
    }

    fun startPlayback(
        sampleRate: Int,
        framesPerChunk: Int,
        generate: (ShortArray, Int) -> Int
    ) {
        if (playbackThread != null) {
            audioTrack?.play()
            return
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO, // Fixed typo: replaced 0 with O
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // Fixed typo: replaced 0 with O
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(framesPerChunk * 2)

            while (!Thread.currentThread().isInterrupted) {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val frames = generate(buffer, framesPerChunk)
                    if (frames > 0) {
                        audioTrack?.write(buffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    } else {
                        // Give the CPU a break if we are transitioning songs
                        Thread.sleep(5)
                    }
                }
            }
        }, "AudioThread")
        playbackThread?.start()
    }

    fun pausePlayback() {
        audioTrack?.pause()
    }

    fun stopPlayback() {
        playbackThread?.interrupt()
        playbackThread = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}