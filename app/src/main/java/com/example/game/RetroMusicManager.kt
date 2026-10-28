package com.example.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class MusicTrackTheme(val id: String, val title: String, val subtitle: String) {
    AUTO_LEVEL("auto", "Dinámica por Nivel", "Cambia según el mundo actual"),
    MENU_OVERTURE("menu", "Obertura Galáctica", "Synthwave espacial (118 BPM)"),
    VALLE_CHIPTUNE("valle", "Valle Esmeralda", "Chiptune de 16-bits (132 BPM)"),
    NEON_SYNTHWAVE("neon", "Tempestad de Neón", "Darksynth cyberpunk (142 BPM)"),
    ESPACIO_ODYSSEY("espacio", "Odisea Sideral", "Arpegios cósmicos (126 BPM)")
}

/**
 * Manages dynamic procedural chiptune & synthwave background music for the game
 * so every level and menu has its own vibrant musical personality.
 */
class RetroMusicManager(private val context: Context) {

    var isEnabled: Boolean = true
        set(value) {
            field = value
            updatePlaybackState()
        }

    var volume: Float = 0.65f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    var selectedTheme: MusicTrackTheme = MusicTrackTheme.AUTO_LEVEL
        private set

    private var currentGameState: GameState = GameState.MENU
    private var currentLevelType: LevelType = LevelType.VALLE
    private var activeTrackKey: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val musicDir: File by lazy {
        File(context.cacheDir, "retro_music_v1").apply {
            if (!exists()) mkdirs()
        }
    }

    var activeTrackDisplayName: String = "Obertura Galáctica"
        private set

    fun selectTheme(theme: MusicTrackTheme) {
        selectedTheme = theme
        syncWithGameContext(currentGameState, currentLevelType, forceReload = true)
    }

    fun syncWithGameContext(
        gameState: GameState,
        levelType: LevelType,
        forceReload: Boolean = false
    ) {
        currentGameState = gameState
        currentLevelType = levelType

        val targetKey = resolveTargetTrackKey(gameState, levelType)
        activeTrackDisplayName = when (targetKey) {
            "menu" -> "Obertura Galáctica"
            "valle" -> "Valle Esmeralda (16-Bit)"
            "neon" -> "Tempestad de Neón (Cyberpunk)"
            "espacio" -> "Odisea Sideral (Cósmica)"
            else -> "Valle Esmeralda (16-Bit)"
        }

        if (!isEnabled) {
            pausePlayback()
            return
        }

        if (gameState == GameState.PAUSED) {
            applyVolume(multiplier = 0.35f)
            if (mediaPlayer?.isPlaying == false) {
                try {
                    mediaPlayer?.start()
                } catch (_: Throwable) {
                }
            }
            return
        } else {
            applyVolume(multiplier = 1.0f)
        }

        if (!forceReload && targetKey == activeTrackKey && mediaPlayer != null) {
            if (mediaPlayer?.isPlaying == false) {
                try {
                    mediaPlayer?.start()
                } catch (_: Throwable) {
                }
            }
            return
        }

        activeTrackKey = targetKey
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                ensureTrackFile(targetKey)
            }
            if (activeTrackKey == targetKey && file != null && file.exists()) {
                startMediaPlayer(file)
            }
        }
    }

    private fun resolveTargetTrackKey(gameState: GameState, levelType: LevelType): String {
        return when (selectedTheme) {
            MusicTrackTheme.AUTO_LEVEL -> {
                if (gameState == GameState.MENU) {
                    "menu"
                } else {
                    when (levelType) {
                        LevelType.VALLE -> "valle"
                        LevelType.NEON -> "neon"
                        LevelType.ESPACIO -> "espacio"
                    }
                }
            }
            MusicTrackTheme.MENU_OVERTURE -> "menu"
            MusicTrackTheme.VALLE_CHIPTUNE -> "valle"
            MusicTrackTheme.NEON_SYNTHWAVE -> "neon"
            MusicTrackTheme.ESPACIO_ODYSSEY -> "espacio"
        }
    }

    private fun ensureTrackFile(trackKey: String): File? {
        return try {
            val wavFile = File(musicDir, "bgm_${trackKey}_v1.wav")
            if (!wavFile.exists() || wavFile.length() == 0L) {
                val pcm = when (trackKey) {
                    "menu" -> synthesizeMenuOverture()
                    "valle" -> synthesizeValleTrack()
                    "neon" -> synthesizeNeonTrack()
                    "espacio" -> synthesizeEspacioTrack()
                    else -> synthesizeValleTrack()
                }
                writeWavFile(wavFile, pcm, SAMPLE_RATE)
            }
            wavFile
        } catch (_: Throwable) {
            null
        }
    }

    private fun startMediaPlayer(file: File) {
        try {
            releaseMediaPlayer()
            if (!isEnabled) return

            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.isLooping = true
            player.prepare()
            val mult = if (currentGameState == GameState.PAUSED) 0.35f else 1.0f
            val vol = (volume * mult).coerceIn(0f, 1f)
            player.setVolume(vol, vol)
            player.start()
            mediaPlayer = player
        } catch (_: Throwable) {
            // Safe fallback in headless test environments
        }
    }

    private fun updatePlaybackState() {
        if (!isEnabled) {
            pausePlayback()
        } else {
            syncWithGameContext(currentGameState, currentLevelType, forceReload = false)
        }
    }

    private fun pausePlayback() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyVolume(multiplier: Float = 1.0f) {
        try {
            val effective = if (isEnabled) (volume * multiplier).coerceIn(0f, 1f) else 0f
            mediaPlayer?.setVolume(effective, effective)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        releaseMediaPlayer()
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Throwable) {
        }
        mediaPlayer = null
    }

    // =========================================================================
    // MULTI-CHANNEL RETRO & SYNTHWAVE PROCEDURAL COMPOSER
    // =========================================================================

    /**
     * Menu Track: "Obertura Galáctica" (118 BPM, warm cosmic synth arpeggios + heroic space lead)
     */
    private fun synthesizeMenuOverture(): ShortArray {
        val melody = intArrayOf(
            69, 0, 72, 76, 79, 76, 72, 76,   69, 0, 72, 76, 81, 79, 76, 72,
            65, 0, 69, 72, 76, 72, 69, 72,   65, 69, 72, 76, 79, 76, 72, 69,
            60, 0, 64, 67, 72, 67, 64, 67,   72, 0, 76, 79, 84, 79, 76, 72,
            67, 0, 71, 74, 79, 74, 71, 74,   79, 77, 76, 74, 72, 71, 69, 67
        )
        val bass = intArrayOf(
            45, 45, 57, 45, 45, 45, 57, 45,  45, 45, 57, 45, 45, 45, 55, 45,
            41, 41, 53, 41, 41, 41, 53, 41,  41, 41, 53, 41, 41, 41, 52, 41,
            36, 36, 48, 36, 36, 36, 48, 36,  36, 36, 48, 36, 48, 48, 52, 48,
            43, 43, 55, 43, 43, 43, 55, 43,  43, 43, 55, 43, 47, 45, 43, 40
        )
        val chords = arrayOf(
            intArrayOf(57, 60, 64, 69), // Am7
            intArrayOf(53, 57, 60, 65), // Fmaj7
            intArrayOf(48, 55, 60, 64), // Cmaj
            intArrayOf(55, 59, 62, 67)  // Gmaj
        )
        return renderMultiChannelLoop(
            bpm = 118,
            melodyNotes = melody,
            bassNotes = bass,
            chordProgression = chords,
            leadDuty = 0.5,
            bassWave = BassWaveType.TRIANGLE_SUB,
            drumIntensity = 0.55
        )
    }

    /**
     * Level 1 (VALLE): "Valle Esmeralda" (132 BPM, upbeat adventurous 16-bit platformer theme)
     */
    private fun synthesizeValleTrack(): ShortArray {
        val melody = intArrayOf(
            72, 76, 79, 0,  84, 79, 76, 72,  74, 77, 81, 77,  79, 0, 76, 0,
            69, 72, 76, 0,  81, 76, 72, 69,  71, 74, 79, 74,  76, 79, 84, 0,
            84, 83, 81, 79, 77, 76, 74, 72,  69, 72, 76, 81,  79, 76, 72, 0,
            74, 77, 81, 84, 83, 79, 74, 71,  72, 76, 79, 84,  79, 76, 72, 0
        )
        val bass = intArrayOf(
            48, 60, 48, 60, 48, 60, 55, 60,  50, 62, 50, 62, 43, 55, 43, 55,
            45, 57, 45, 57, 45, 57, 52, 57,  43, 55, 43, 55, 48, 60, 55, 48,
            41, 53, 41, 53, 43, 55, 43, 55,  45, 57, 45, 57, 48, 60, 48, 60,
            50, 62, 50, 62, 43, 55, 47, 55,  48, 55, 60, 55, 48, 43, 48, 0
        )
        val chords = arrayOf(
            intArrayOf(60, 64, 67, 72), // C
            intArrayOf(57, 60, 64, 69), // Am
            intArrayOf(53, 57, 60, 65), // F
            intArrayOf(55, 59, 62, 67)  // G
        )
        return renderMultiChannelLoop(
            bpm = 132,
            melodyNotes = melody,
            bassNotes = bass,
            chordProgression = chords,
            leadDuty = 0.25,
            bassWave = BassWaveType.TRIANGLE_SUB,
            drumIntensity = 0.85
        )
    }

    /**
     * Level 2 (NEON): "Tempestad de Neón" (142 BPM, driving cyberpunk darksynth in E minor)
     */
    private fun synthesizeNeonTrack(): ShortArray {
        val melody = intArrayOf(
            76, 0, 76, 79,  83, 79, 76, 74,  76, 0, 79, 83,  86, 83, 79, 76,
            72, 0, 72, 76,  79, 76, 72, 71,  72, 76, 79, 84,  83, 79, 76, 72,
            74, 0, 74, 78,  81, 78, 74, 72,  74, 78, 81, 86,  83, 79, 74, 0,
            71, 75, 78, 83, 81, 78, 75, 71,  76, 79, 83, 88,  86, 83, 79, 76
        )
        val bass = intArrayOf(
            40, 52, 40, 52, 40, 52, 40, 52,  40, 52, 40, 52, 43, 55, 45, 57,
            36, 48, 36, 48, 36, 48, 36, 48,  36, 48, 36, 48, 40, 52, 36, 48,
            38, 50, 38, 50, 38, 50, 38, 50,  38, 50, 38, 50, 42, 54, 38, 50,
            35, 47, 35, 47, 35, 47, 39, 51,  40, 52, 43, 55, 47, 52, 40, 35
        )
        val chords = arrayOf(
            intArrayOf(52, 55, 59, 64), // Em
            intArrayOf(48, 52, 55, 60), // C
            intArrayOf(50, 54, 57, 62), // D
            intArrayOf(47, 51, 54, 59)  // B
        )
        return renderMultiChannelLoop(
            bpm = 142,
            melodyNotes = melody,
            bassNotes = bass,
            chordProgression = chords,
            leadDuty = 0.35,
            bassWave = BassWaveType.CYBER_SAW,
            drumIntensity = 1.0
        )
    }

    /**
     * Level 3 (ESPACIO): "Odisea Sideral" (126 BPM, cosmic D Dorian / minor space synth odyssey)
     */
    private fun synthesizeEspacioTrack(): ShortArray {
        val melody = intArrayOf(
            74, 81, 79, 74, 72, 74, 77, 0,   81, 86, 84, 81, 79, 77, 76, 72,
            70, 77, 74, 70, 69, 72, 76, 0,   77, 81, 84, 88, 86, 84, 81, 77,
            74, 81, 86, 81, 84, 79, 76, 72,  70, 74, 77, 81, 79, 76, 72, 69,
            67, 70, 74, 79, 81, 77, 74, 69,  74, 77, 81, 86, 84, 81, 77, 74
        )
        val bass = intArrayOf(
            38, 38, 50, 50, 38, 50, 45, 50,  38, 38, 50, 50, 36, 48, 43, 48,
            34, 34, 46, 46, 36, 48, 45, 48,  41, 41, 53, 53, 40, 52, 45, 52,
            38, 50, 38, 50, 36, 48, 36, 48,  34, 46, 34, 46, 33, 45, 33, 45,
            31, 43, 34, 46, 36, 48, 33, 45,  38, 50, 45, 50, 38, 45, 50, 38
        )
        val chords = arrayOf(
            intArrayOf(50, 53, 57, 62), // Dm
            intArrayOf(46, 50, 53, 58), // Bb
            intArrayOf(48, 52, 55, 60), // C
            intArrayOf(45, 49, 52, 57)  // A
        )
        return renderMultiChannelLoop(
            bpm = 126,
            melodyNotes = melody,
            bassNotes = bass,
            chordProgression = chords,
            leadDuty = 0.5,
            bassWave = BassWaveType.CYBER_SAW,
            drumIntensity = 0.9
        )
    }

    private enum class BassWaveType {
        TRIANGLE_SUB,
        CYBER_SAW
    }

    private fun renderMultiChannelLoop(
        bpm: Int,
        melodyNotes: IntArray,
        bassNotes: IntArray,
        chordProgression: Array<IntArray>,
        leadDuty: Double,
        bassWave: BassWaveType,
        drumIntensity: Double
    ): ShortArray {
        val totalSteps = melodyNotes.size
        val stepDurationSec = (60.0 / bpm.toDouble()) / 4.0 // 16th-note steps
        val samplesPerStep = (SAMPLE_RATE * stepDurationSec).toInt().coerceAtLeast(1)
        val totalSamples = samplesPerStep * totalSteps
        val output = ShortArray(totalSamples)

        var leadPhase = 0.0
        var arpPhase = 0.0
        var bassPhase = 0.0
        var kickPhase = 0.0
        val noiseRng = Random(0xC051C)
        var heldNoise = 0.0

        for (i in 0 until totalSamples) {
            val step = (i / samplesPerStep).coerceIn(0, totalSteps - 1)
            val stepSample = i % samplesPerStep
            val stepProgress = stepSample.toDouble() / samplesPerStep.toDouble()

            // 1. Lead Channel (Pulse + Subtle Vibrato)
            val midiLead = melodyNotes[step]
            var leadSample = 0.0
            if (midiLead > 0) {
                val vibrato = 1.0 + 0.004 * sin(2.0 * PI * 6.0 * (i.toDouble() / SAMPLE_RATE))
                val freq = midiToFreq(midiLead) * vibrato
                val attack = (stepProgress / 0.06).coerceAtMost(1.0)
                val decay = (1.0 - stepProgress * 0.55).coerceAtLeast(0.25)
                val env = attack * decay
                val pulse = if (leadPhase < leadDuty) 1.0 else -1.0
                val warm = sin(2.0 * PI * leadPhase)
                leadSample = (pulse * 0.65 + warm * 0.35) * env * 0.24
                leadPhase += freq / SAMPLE_RATE
                leadPhase -= leadPhase.toInt()
            }

            // 2. Fast Chiptune Arpeggio Channel
            val barIndex = (step / 16) % chordProgression.size
            val chord = chordProgression[barIndex]
            val arpSubIdx = ((i / (SAMPLE_RATE / 55).coerceAtLeast(1)) + step) % chord.size
            val arpFreq = midiToFreq(chord[arpSubIdx])
            val arpEnv = (1.0 - stepProgress * 0.4)
            val arpSquare = if (arpPhase < 0.5) 1.0 else -1.0
            val arpSample = arpSquare * arpEnv * 0.11
            arpPhase += arpFreq / SAMPLE_RATE
            arpPhase -= arpPhase.toInt()

            // 3. Bass Channel (Triangle/Sub or Cyber Sawtooth)
            val midiBass = bassNotes[step % bassNotes.size]
            var bassSample = 0.0
            if (midiBass > 0) {
                val bFreq = midiToFreq(midiBass)
                val bEnv = (1.0 - stepProgress * 0.45).coerceAtLeast(0.2)
                val rawBass = when (bassWave) {
                    BassWaveType.TRIANGLE_SUB -> {
                        val tri = 1.0 - 4.0 * abs(bassPhase - 0.5)
                        val sub = sin(2.0 * PI * bassPhase)
                        tri * 0.65 + sub * 0.35
                    }
                    BassWaveType.CYBER_SAW -> {
                        val saw = 2.0 * bassPhase - 1.0
                        val sq = if (bassPhase < 0.5) 1.0 else -1.0
                        saw * 0.6 + sq * 0.4
                    }
                }
                bassSample = rawBass * bEnv * 0.28
                bassPhase += bFreq / SAMPLE_RATE
                bassPhase -= bassPhase.toInt()
            }

            // 4. Retro Percussion Channel (Kick, Snare, Hi-Hat)
            val barStep = step % 16
            var drumSample = 0.0
            if (i % 10 == 0) {
                heldNoise = noiseRng.nextDouble(-1.0, 1.0)
            }

            if (barStep == 0 || barStep == 8 || (barStep == 10 && drumIntensity > 0.8)) {
                if (stepProgress < 0.55) {
                    val kProg = stepProgress / 0.55
                    val kFreq = 150.0 * exp(-kProg * 3.2) + 42.0
                    val kEnv = (1.0 - kProg).pow(1.3)
                    drumSample += sin(2.0 * PI * kickPhase) * kEnv * 0.32 * drumIntensity
                    kickPhase += kFreq / SAMPLE_RATE
                    kickPhase -= kickPhase.toInt()
                }
            } else if (barStep == 4 || barStep == 12) {
                if (stepProgress < 0.65) {
                    val sProg = stepProgress / 0.65
                    val sEnv = (1.0 - sProg).pow(1.6)
                    val body = sin(2.0 * PI * 185.0 * (stepSample.toDouble() / SAMPLE_RATE))
                    drumSample += (heldNoise * 0.75 + body * 0.25) * sEnv * 0.24 * drumIntensity
                }
            } else if (barStep % 2 == 1) {
                if (stepProgress < 0.22) {
                    val hProg = stepProgress / 0.22
                    val hEnv = (1.0 - hProg).pow(2.0)
                    drumSample += heldNoise * hEnv * 0.09 * drumIntensity
                }
            }

            val rawMix = leadSample + arpSample + bassSample + drumSample
            val limited = (rawMix * 0.92).coerceIn(-0.98, 0.98)
            output[i] = (limited * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Apply subtle delay/echo across the loop buffer for spaciousness
        val delaySamples = (samplesPerStep * 3)
        for (i in 0 until totalSamples) {
            val prevIdx = (i - delaySamples + totalSamples) % totalSamples
            val mixed = output[i].toInt() + (output[prevIdx].toInt() * 22) / 100
            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    private fun midiToFreq(midi: Int): Double {
        return 440.0 * 2.0.pow((midi - 69).toDouble() / 12.0)
    }

    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val byteDataSize = samples.size * 2
        val totalDataLen = byteDataSize + 36
        val byteRate = sampleRate * 2

        val buffer = ByteBuffer.allocate(44 + byteDataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(byteDataSize)

        for (s in samples) {
            buffer.putShort(s)
        }

        FileOutputStream(file).use { fos ->
            fos.write(buffer.array())
        }
    }

    companion object {
        private const val SAMPLE_RATE = 22050
    }
}
