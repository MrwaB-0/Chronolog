package com.marwabadak.chronolog.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 초침 소리(Tick / Tock)를 실시간 합성해 재생한다.
 *
 * 외부 음원 파일 없이 PCM 16bit 샘플을 만들어 AudioTrack(정적 모드)에 한 번 채워 두고,
 * 필요할 때마다 재생 위치만 되감아 즉시 소리를 낸다. 지연이 없고 용량도 0 에 가깝다.
 * 기계식 시계처럼 두 소리를 번갈아(Tick-Tock) 재생한다.
 */
class TickPlayer {

    private val sampleRate = 44_100

    private var tickTrack: AudioTrack? = null
    private var tockTrack: AudioTrack? = null
    private var prepared = false
    private var alternate = false

    /** 1초 경과할 때마다 호출한다. */
    fun tick() {
        ensurePrepared()
        val useTock = alternate
        alternate = !alternate
        playSafely(if (useTock) tockTrack else tickTrack)
    }

    /** 소리 없이 위상만 초기화한다. */
    fun resetPhase() {
        alternate = false
    }

    fun release() {
        releaseTrack(tickTrack)
        releaseTrack(tockTrack)
        tickTrack = null
        tockTrack = null
        prepared = false
    }

    private fun ensurePrepared() {
        if (prepared) return
        prepared = true
        try {
            // 짧고 단단한 고음 + 낮은 울림을 섞어 기계식 크로노그래프의 틱을 만든다.
            tickTrack = createTrack(synth(freqHz = 2_650.0, decaySec = 0.0032, thumpHz = 420.0, gain = 0.62, seed = 11))
            tockTrack = createTrack(synth(freqHz = 2_100.0, decaySec = 0.0040, thumpHz = 330.0, gain = 0.55, seed = 29))
        } catch (t: Throwable) {
            tickTrack = null
            tockTrack = null
        }
    }

    private fun createTrack(samples: ShortArray): AudioTrack? {
        if (samples.isEmpty()) return null
        val bytes = samples.size * 2
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, bytes) else bytes
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        val written = track.write(samples, 0, samples.size)
        if (written <= 0) {
            track.release()
            return null
        }
        track.setVolume(1.0f)
        return track
    }

    private fun playSafely(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.setPlaybackHeadPosition(0)
            track.play()
        } catch (t: Throwable) {
            // 재생 실패는 조용히 무시한다. 측정 자체는 계속 진행되어야 한다.
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            track.release()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun synth(freqHz: Double, decaySec: Double, thumpHz: Double, gain: Double, seed: Int): ShortArray {
        val length = (sampleRate * 0.032).toInt()
        val out = ShortArray(length)
        val random = Random(seed.toLong())
        var filteredNoise = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val body = sin(2.0 * PI * freqHz * t) * exp(-t / decaySec) * 0.78
            val thump = sin(2.0 * PI * thumpHz * t) * exp(-t / (decaySec * 2.6)) * 0.52
            var value = body + thump
            if (i < 70) {
                val noise = random.nextDouble() * 2.0 - 1.0
                filteredNoise = filteredNoise * 0.55 + noise * 0.45
                value += filteredNoise * 0.55 * (1.0 - i.toDouble() / 70.0)
            }
            val fadeLength = 320
            val fade = if (i > length - fadeLength) (length - i).toDouble() / fadeLength else 1.0
            var sample = value * gain * fade
            if (sample > 1.0) sample = 1.0
            if (sample < -1.0) sample = -1.0
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
