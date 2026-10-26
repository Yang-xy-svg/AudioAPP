package com.example.audio_app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import java.io.File

class AudioPlaybackController(
    context: Context,
    private val onStateChanged: (name: String?, isPlaying: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var player: MediaPlayer? = null
    private var currentName: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pause()
        }
    }

    fun play(file: File, name: String) {
        releasePlayer()
        if (!requestAudioFocus()) {
            onError("其他应用正在占用音频，暂时无法播放")
            return
        }

        currentName = name
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    it.start()
                    onStateChanged(name, true)
                }
                setOnCompletionListener {
                    onStateChanged(name, false)
                }
                setOnErrorListener { _, what, extra ->
                    onError("播放失败（$what/$extra）")
                    onStateChanged(name, false)
                    true
                }
                player = this
                prepareAsync()
            }
        }.onFailure {
            releasePlayer()
            abandonAudioFocus()
            currentName = null
            onError("无法播放这个 WAV 文件")
            onStateChanged(null, false)
        }
    }

    fun toggle() {
        val activePlayer = player ?: return
        val name = currentName ?: return
        runCatching {
            if (activePlayer.isPlaying) {
                activePlayer.pause()
                abandonAudioFocus()
                onStateChanged(name, false)
            } else if (requestAudioFocus()) {
                activePlayer.start()
                onStateChanged(name, true)
            }
        }.onFailure {
            onError("播放器状态异常，请重新点击播放")
            releasePlayer()
            onStateChanged(null, false)
        }
    }

    fun pauseIfPlaying(name: String) {
        if (currentName == name && player?.isPlaying == true) {
            pause()
        }
    }

    fun stop() {
        releasePlayer()
        abandonAudioFocus()
        currentName = null
        onStateChanged(null, false)
    }

    fun release() {
        releasePlayer()
        abandonAudioFocus()
        currentName = null
    }

    private fun pause() {
        val activePlayer = player ?: return
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            onStateChanged(currentName, false)
        }
        abandonAudioFocus()
    }

    private fun releasePlayer() {
        player?.runCatching {
            reset()
        }
        player?.release()
        player = null
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN,
            )
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
