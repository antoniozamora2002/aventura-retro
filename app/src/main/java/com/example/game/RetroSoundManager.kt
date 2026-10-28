package com.example.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class RetroSoundEffect {
    JUMP,
    DOUBLE_JUMP,
    COIN,
    DIAMOND,
    POWERUP,
    STOMP,
    COLLISION,
    LEVEL_COMPLETE,
    GAME_OVER
}

/**
 * Low-latency retro sound effect manager powered by Android's [SoundPool].
 * Synthesizes authentic 8-bit chiptune WAV waveforms (square, pulse, triangle, and LFSR noise)
 * into the app cache directory and preloads them into native SoundPool memory for instant playback.
 */
class RetroSoundManager(context: Context) {

    var isEnabled: Boolean = true
    var volume: Float = 0.85f

    private var soundPool: SoundPool? = null
    private val soundIdMap = mutableMapOf<RetroSoundEffect, Int>()
    private val loadedSounds = mutableSetOf<Int>()

    init {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(audioAttributes)
                .build()

            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    loadedSounds.add(sampleId)
                }
            }

            soundPool = pool

            val sfxDir = File(context.cacheDir, "retro_sfx_v1").apply {
                if (!exists()) mkdirs()
            }

            RetroSoundEffect.entries.forEach { effect ->
                val wavFile = File(sfxDir, "sfx_${effect.name.lowercase()}.wav")
                if (!wavFile.exists() || wavFile.length() == 0L) {
                    val pcmSamples = synthesizeEffect(effect)
                    writeWavFile(wavFile, pcmSamples, SAMPLE_RATE)
                }
                val id = pool.load(wavFile.absolutePath, 1)
                soundIdMap[effect] = id
            }
        } catch (_: Throwable) {
            // Gracefully ignore in headless/unit-test environments where SoundPool is unavailable
        }
    }

    fun play(effect: RetroSoundEffect, rate: Float = 1.0f) {
        if (!isEnabled || volume <= 0.01f) return
        val pool = soundPool ?: return
        val soundId = soundIdMap[effect] ?: return
        if (loadedSounds.isNotEmpty() && !loadedSounds.contains(soundId)) return

        val clampedVolume = volume.coerceIn(0f, 1f)
        val clampedRate = rate.coerceIn(0.5f, 2.0f)
        val priority = when (effect) {
            RetroSoundEffect.COLLISION,
            RetroSoundEffect.GAME_OVER,
            RetroSoundEffect.LEVEL_COMPLETE -> 2
            else -> 1
        }
        try {
            pool.play(soundId, clampedVolume, clampedVolume, priority, 0, clampedRate)
        } catch (_: Throwable) {
            // Safe guard against released pool state
        }
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Throwable) {
        } finally {
            soundPool = null
            soundIdMap.clear()
            loadedSounds.clear()
        }
    }

    private fun synthesizeEffect(effect: RetroSoundEffect): ShortArray {
        return when (effect) {
            RetroSoundEffect.JUMP -> synthesizeSweep(
                durationMs = 135,
                startFreq = 210.0,
                endFreq = 680.0,
                dutyCycle = 0.5,
                curve = 1.6
            )
            RetroSoundEffect.DOUBLE_JUMP -> synthesizeSweep(
                durationMs = 145,
                startFreq = 360.0,
                endFreq = 1040.0,
                dutyCycle = 0.25,
                curve = 1.4
            )
            RetroSoundEffect.COIN -> synthesizeTwoTone(
                firstFreq = 987.77,  // B5
                firstMs = 55,
                secondFreq = 1318.51, // E6
                secondMs = 140,
                dutyCycle = 0.5
            )
            RetroSoundEffect.DIAMOND -> synthesizeArpeggio(
                frequencies = doubleArrayOf(1046.50, 1318.51, 1567.98, 2093.00), // C6 E6 G6 C7
                noteMs = 55,
                dutyCycle = 0.25
            )
            RetroSoundEffect.POWERUP -> synthesizeArpeggio(
                frequencies = doubleArrayOf(330.0, 392.0, 659.25, 523.25, 587.33, 783.99, 1046.50),
                noteMs = 42,
                dutyCycle = 0.5
            )
            RetroSoundEffect.STOMP -> synthesizeStomp(durationMs = 150)
            RetroSoundEffect.COLLISION -> synthesizeCollisionNoise(durationMs = 240)
            RetroSoundEffect.LEVEL_COMPLETE -> synthesizeArpeggio(
                frequencies = doubleArrayOf(523.25, 659.25, 783.99, 1046.50, 783.99, 1046.50),
                noteMs = 75,
                dutyCycle = 0.5
            )
            RetroSoundEffect.GAME_OVER -> synthesizeArpeggio(
                frequencies = doubleArrayOf(392.00, 349.23, 329.63, 261.63), // G4 F4 E4 C4
                noteMs = 125,
                dutyCycle = 0.5
            )
        }
    }

    private fun synthesizeSweep(
        durationMs: Int,
        startFreq: Double,
        endFreq: Double,
        dutyCycle: Double,
        curve: Double
    ): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs) / 1000
        val output = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples.toDouble()
            val shaped = progress.pow(curve)
            val freq = startFreq + (endFreq - startFreq) * shaped

            // Attack + exponential release envelope
            val attack = (progress / 0.06).coerceAtMost(1.0)
            val decay = (1.0 - progress).pow(0.75)
            val env = attack * decay

            val pulse = if (phase < dutyCycle) 1.0 else -1.0
            val fundamental = sin(2.0 * PI * phase)
            val sampleVal = (pulse * 0.65 + fundamental * 0.35) * env * 0.45

            output[i] = (sampleVal * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            phase += freq / SAMPLE_RATE
            phase -= phase.toInt()
        }
        return output
    }

    private fun synthesizeTwoTone(
        firstFreq: Double,
        firstMs: Int,
        secondFreq: Double,
        secondMs: Int,
        dutyCycle: Double
    ): ShortArray {
        val firstSamples = (SAMPLE_RATE * firstMs) / 1000
        val secondSamples = (SAMPLE_RATE * secondMs) / 1000
        val totalSamples = firstSamples + secondSamples
        val output = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val isFirst = i < firstSamples
            val freq = if (isFirst) firstFreq else secondFreq
            val localProgress = if (isFirst) {
                i.toDouble() / firstSamples.coerceAtLeast(1)
            } else {
                (i - firstSamples).toDouble() / secondSamples.coerceAtLeast(1)
            }

            val env = if (isFirst) 0.9 else (1.0 - localProgress).pow(1.2)
            val pulse = if (phase < dutyCycle) 1.0 else -1.0
            val overtone = sin(2.0 * PI * phase)
            val sampleVal = (pulse * 0.6 + overtone * 0.4) * env * 0.45

            output[i] = (sampleVal * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            phase += freq / SAMPLE_RATE
            phase -= phase.toInt()
        }
        return output
    }

    private fun synthesizeArpeggio(
        frequencies: DoubleArray,
        noteMs: Int,
        dutyCycle: Double
    ): ShortArray {
        val samplesPerNote = (SAMPLE_RATE * noteMs) / 1000
        val totalSamples = samplesPerNote * frequencies.size
        val output = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val noteIndex = (i / samplesPerNote).coerceIn(0, frequencies.lastIndex)
            val localSample = i % samplesPerNote
            val localProgress = localSample.toDouble() / samplesPerNote.coerceAtLeast(1)
            val freq = frequencies[noteIndex]

            val attack = (localProgress / 0.08).coerceAtMost(1.0)
            val release = (1.0 - localProgress * 0.65)
            val env = attack * release

            val pulse = if (phase < dutyCycle) 1.0 else -1.0
            val tri = sin(2.0 * PI * phase)
            val sampleVal = (pulse * 0.55 + tri * 0.45) * env * 0.42

            output[i] = (sampleVal * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            phase += freq / SAMPLE_RATE
            phase -= phase.toInt()
        }
        return output
    }

    private fun synthesizeStomp(durationMs: Int): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs) / 1000
        val output = ShortArray(totalSamples)
        var phase = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples.toDouble()
            // Quick downward thump followed by upward bounce
            val freq = if (progress < 0.35) {
                260.0 - progress * 420.0
            } else {
                140.0 + (progress - 0.35) * 520.0
            }
            val env = (1.0 - progress).pow(0.85)
            val wave = if (phase < 0.5) 1.0 else -1.0
            val sub = sin(2.0 * PI * phase)
            val sampleVal = (wave * 0.5 + sub * 0.5) * env * 0.48

            output[i] = (sampleVal * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            phase += freq / SAMPLE_RATE
            phase -= phase.toInt()
        }
        return output
    }

    private fun synthesizeCollisionNoise(durationMs: Int): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs) / 1000
        val output = ShortArray(totalSamples)
        val rng = Random(0x8B17)
        var phase = 0.0
        var heldNoise = 0.0

        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples.toDouble()
            // Descending gritty square wave + bitcrushed retro noise
            val freq = 320.0 * (1.0 - progress * 0.78)
            if (i % 14 == 0) {
                heldNoise = rng.nextDouble(-1.0, 1.0)
            }
            val env = (1.0 - progress).pow(0.9)
            val square = if (phase < 0.5) 1.0 else -1.0
            val sampleVal = (heldNoise * 0.65 + square * 0.35) * env * 0.5

            output[i] = (sampleVal * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            phase += freq / SAMPLE_RATE
            phase -= phase.toInt()
        }
        return output
    }

    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val byteDataSize = samples.size * 2
        val totalDataLen = byteDataSize + 36
        val byteRate = sampleRate * 2 // 16-bit mono

        val buffer = ByteBuffer.allocate(44 + byteDataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt subchunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM chunk size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(1) // NumChannels = 1 (Mono)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2) // BlockAlign = 2 bytes
        buffer.putShort(16) // BitsPerSample = 16
        // data subchunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(byteDataSize)

        for (sample in samples) {
            buffer.putShort(sample)
        }

        FileOutputStream(file).use { fos ->
            fos.write(buffer.array())
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }
}
