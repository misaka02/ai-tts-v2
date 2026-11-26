package com.aitts.v2.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Low-latency AudioTrack PCM player supporting real-time streaming chunks and batch playback,
 * computing live RMS amplitude directly from output PCM samples without mic permissions.
 */
class StreamingAudioTrackPlayer {

    private val lock = Any()
    private var track: AudioTrack? = null

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow.asStateFlow()

    fun openStream(sampleRate: Int = 24000) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        synchronized(lock) {
            track = audioTrack
            audioTrack.play()
            _isPlayingFlow.value = true
        }
    }

    fun writeChunk(pcm16Bytes: ByteArray) {
        if (pcm16Bytes.isEmpty()) return
        synchronized(lock) { track } ?: return
        updateRms(pcm16Bytes)
        var offset = 0
        while (offset < pcm16Bytes.size) {
            val active = synchronized(lock) { track } ?: break
            if (active.playState != AudioTrack.PLAYSTATE_PLAYING) break
            val written = active.write(pcm16Bytes, offset, pcm16Bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    fun finishStream() {
        synchronized(lock) {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
            _isPlayingFlow.value = false
            _rmsFlow.value = 0f
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            }
            track = null
            _isPlayingFlow.value = false
            _rmsFlow.value = 0f
        }
    }

    private fun updateRms(pcm16Bytes: ByteArray) {
        val sampleCount = pcm16Bytes.size / 2
        if (sampleCount <= 0) return
        val step = maxOf(1, sampleCount / 64)
        val bb = ByteBuffer.wrap(pcm16Bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var count = 0
        var idx = 0
        while (idx < sampleCount) {
            val sample = bb.getShort(idx * 2) / 32768.0
            sumSq += sample * sample
            count++
            idx += step
        }
        val rms = if (count > 0) sqrt(sumSq / count).toFloat().coerceIn(0f, 1f) else 0f
        _rmsFlow.value = _rmsFlow.value * 0.35f + rms * 0.65f
    }
}
