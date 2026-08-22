package com.example.meshtest.acoustic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * High-Performance Acoustic Transport Tier.
 * Uses Voice-Band MFSK (Multi-Frequency Shift Keying) between 1150 Hz and 2450 Hz.
 *
 * Designed specifically for maximum range (10-15m across rooms) without Bluetooth or Wi-Fi.
 */
class AcousticTransport(private val context: Context) {
    private val TAG = "AcousticTransport"

    private val isListeningActive = AtomicBoolean(false)
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastTransmitTimeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onBeaconCallback: ((String) -> Unit)? = null
    private val recentlyReceivedBeacons = mutableSetOf<String>()

    /**
     * Transmit a compact acoustic beacon over the device speaker.
     * Enforces rate-limiting cooldown (6s) between transmissions.
     */
    fun transmit(beacon: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTransmitTimeMs < COOLDOWN_MS) {
            val remainingSec = ((COOLDOWN_MS - (now - lastTransmitTimeMs)) / 1000) + 1
            Log.w(TAG, "Acoustic transmit rate-limited ($remainingSec s remaining)")
            return false
        }

        lastTransmitTimeMs = now
        val cleanBeacon = beacon.uppercase().trim()
        Log.d(TAG, "🔊 Transmitting acoustic beacon: $cleanBeacon")

        scope.launch(Dispatchers.IO) {
            try {
                playMfskBeaconAudio(cleanBeacon)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play acoustic beacon", e)
            }
        }
        return true
    }

    /**
     * Start background microphone listening loop for incoming acoustic beacons.
     */
    fun startListening(onBeaconReceived: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start listening: RECORD_AUDIO permission not granted")
            return
        }

        if (isListeningActive.getAndSet(true)) {
            Log.d(TAG, "Acoustic listening already active")
            return
        }

        this.onBeaconCallback = onBeaconReceived
        Log.d(TAG, "Starting voice-band acoustic listening engine...")

        listeningJob = scope.launch(Dispatchers.IO) {
            runAudioListeningLoop()
        }
    }

    /**
     * Stop background microphone listening loop.
     */
    fun stopListening() {
        if (!isListeningActive.getAndSet(false)) return

        Log.d(TAG, "Stopping acoustic listening engine...")
        listeningJob?.cancel()
        listeningJob = null
    }

    fun isListening(): Boolean = isListeningActive.get()

    /**
     * Plays MFSK audio tones in the resonant voice band (1150 Hz - 2450 Hz) at full volume.
     */
    private fun playMfskBeaconAudio(text: String) {
        val sampleRate = SAMPLE_RATE
        val charDuration = CHAR_DURATION_SEC
        val preambleDuration = PREAMBLE_DURATION_SEC

        val totalSamples = (sampleRate * (preambleDuration + (text.length * charDuration) + preambleDuration)).toInt()
        val audioData = ShortArray(totalSamples)
        var sampleIdx = 0

        // 1. Preamble Burst (1150 Hz - Lead Tone)
        val preambleSamples = (sampleRate * preambleDuration).toInt()
        for (i in 0 until preambleSamples) {
            val t = i.toDouble() / sampleRate
            val window = 0.5 * (1.0 - cos((2.0 * PI * i) / preambleSamples))
            val sample = (0.95 * window * sin(2.0 * PI * PREAMBLE_FREQ * t) * Short.MAX_VALUE).toInt().toShort()
            if (sampleIdx < audioData.size) audioData[sampleIdx++] = sample
        }

        // 2. Data Characters MFSK Tones
        for (c in text) {
            val freq = charToFrequency(c)
            val charSamples = (sampleRate * charDuration).toInt()

            for (i in 0 until charSamples) {
                val t = i.toDouble() / sampleRate
                // Smooth Hann envelope on each symbol to eliminate spectral splatter
                val window = 0.5 * (1.0 - cos((2.0 * PI * i) / charSamples))
                val sample = (0.95 * window * sin(2.0 * PI * freq * t) * Short.MAX_VALUE).toInt().toShort()
                if (sampleIdx < audioData.size) audioData[sampleIdx++] = sample
            }
        }

        // 3. Postamble Burst (2450 Hz - Tail Tone)
        val postambleSamples = (sampleRate * preambleDuration).toInt()
        for (i in 0 until postambleSamples) {
            val t = i.toDouble() / sampleRate
            val window = 0.5 * (1.0 - cos((2.0 * PI * i) / postambleSamples))
            val sample = (0.95 * window * sin(2.0 * PI * POSTAMBLE_FREQ * t) * Short.MAX_VALUE).toInt().toShort()
            if (sampleIdx < audioData.size) audioData[sampleIdx++] = sample
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, audioData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack.setVolume(1.0f)
            audioTrack.write(audioData, 0, audioData.size)
            audioTrack.play()
            Thread.sleep(((totalSamples.toDouble() / sampleRate) * 1000).toLong() + 100)
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error", e)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Continuous audio capture and Goertzel Demodulator loop.
     */
    @Suppress("MissingPermission")
    private fun runAudioListeningLoop() {
        val sampleRate = SAMPLE_RATE
        val frameSamples = (sampleRate * 0.050).toInt() // 50ms processing slices
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            frameSamples * 2
        )

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                isListeningActive.set(false)
                return
            }

            audioRecord.startRecording()
            Log.d(TAG, "Voice-band acoustic listener online (${sampleRate}Hz)")

            val audioBuffer = ShortArray(frameSamples)
            val decodedBuilder = StringBuilder()
            var isDecoding = false
            var lastDecodedChar: Char? = null
            var silenceCount = 0

            while (isListeningActive.get()) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readCount < frameSamples) continue

                val detectedChar = detectSymbolInFrame(audioBuffer, readCount, sampleRate)

                // 1. Preamble Detection (1150 Hz)
                if (!isDecoding && detectedChar == PREAMBLE_MARKER) {
                    isDecoding = true
                    decodedBuilder.clear()
                    lastDecodedChar = null
                    silenceCount = 0
                    Log.d(TAG, "Detected acoustic preamble marker!")
                    continue
                }

                // 2. Postamble Detection (2450 Hz)
                if (isDecoding && detectedChar == POSTAMBLE_MARKER) {
                    finalizeDecodedMessage(decodedBuilder.toString())
                    isDecoding = false
                    decodedBuilder.clear()
                    lastDecodedChar = null
                    continue
                }

                // 3. Data Characters
                if (isDecoding) {
                    if (detectedChar != null && detectedChar != PREAMBLE_MARKER && detectedChar != POSTAMBLE_MARKER) {
                        if (detectedChar != lastDecodedChar) {
                            decodedBuilder.append(detectedChar)
                            lastDecodedChar = detectedChar
                            Log.d(TAG, "Acoustic RX symbol: $detectedChar (Buffer: $decodedBuilder)")
                        }
                        silenceCount = 0
                    } else {
                        silenceCount++
                        if (silenceCount > 25) { // Timeout after ~1.25s of no symbol
                            if (decodedBuilder.length >= 8) {
                                finalizeDecodedMessage(decodedBuilder.toString())
                            }
                            isDecoding = false
                            decodedBuilder.clear()
                            lastDecodedChar = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Acoustic listener loop exception", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (ignored: Exception) {}
            Log.d(TAG, "AudioRecord released")
        }
    }

    private fun finalizeDecodedMessage(raw: String) {
        val trimmed = raw.trim().uppercase()
        val payload = AcousticBeacon.parseBeacon(trimmed) ?: return

        synchronized(recentlyReceivedBeacons) {
            if (recentlyReceivedBeacons.contains(payload.id)) {
                return
            }
            recentlyReceivedBeacons.add(payload.id)
        }

        Log.d(TAG, "🎉 Successfully decoded acoustic beacon: $trimmed -> $payload")

        mainHandler.post {
            onBeaconCallback?.invoke(trimmed)
        }
    }

    /**
     * Uses Goertzel filters with SNR peak-to-average validation to identify candidate tone.
     */
    private fun detectSymbolInFrame(buffer: ShortArray, length: Int, sampleRate: Int): Char? {
        val energies = DoubleArray(ALL_SYMBOLS.size)
        var totalEnergy = 0.0
        var maxEnergy = 0.0
        var bestIdx = -1

        for (i in ALL_SYMBOLS.indices) {
            val freq = ALL_FREQS[i]
            val e = calculateGoertzelEnergy(buffer, length, sampleRate, freq)
            energies[i] = e
            totalEnergy += e
            if (e > maxEnergy) {
                maxEnergy = e
                bestIdx = i
            }
        }

        if (bestIdx == -1) return null

        val meanNoise = (totalEnergy - maxEnergy) / (ALL_SYMBOLS.size - 1)
        val snrRatio = if (meanNoise > 0) maxEnergy / meanNoise else 0.0

        // Requires SNR peak at least 3.0x higher than surrounding noise floor
        return if (snrRatio >= 3.0 && maxEnergy > 5e5) {
            ALL_SYMBOLS[bestIdx]
        } else {
            null
        }
    }

    private fun calculateGoertzelEnergy(buffer: ShortArray, length: Int, sampleRate: Int, targetFreq: Double): Double {
        val k = (0.5 + ((length * targetFreq) / sampleRate)).toInt()
        val omega = (2.0 * PI * k) / length
        val coeff = 2.0 * cos(omega)

        var q0: Double
        var q1 = 0.0
        var q2 = 0.0

        for (i in 0 until length) {
            q0 = coeff * q1 - q2 + buffer[i]
            q2 = q1
            q1 = q0
        }

        return q1 * q1 + q2 * q2 - q1 * q2 * coeff
    }

    companion object {
        const val SAMPLE_RATE = 44100

        // Voice band frequency allocation (1150 Hz to 2450 Hz)
        const val PREAMBLE_FREQ = 1150.0
        const val POSTAMBLE_FREQ = 2450.0
        const val BASE_DATA_FREQ = 1300.0
        const val FREQ_STEP = 30.0

        const val CHAR_DURATION_SEC = 0.090     // 90ms per symbol
        const val PREAMBLE_DURATION_SEC = 0.180 // 180ms preamble
        const val COOLDOWN_MS = 6000L           // 6s cooldown rate-limiting

        const val PREAMBLE_MARKER = '^'
        const val POSTAMBLE_MARKER = '$'

        val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ|"
        val ALL_SYMBOLS: List<Char>
        val ALL_FREQS: DoubleArray

        init {
            val syms = mutableListOf<Char>()
            val freqs = mutableListOf<Double>()

            // 0. Preamble
            syms.add(PREAMBLE_MARKER)
            freqs.add(PREAMBLE_FREQ)

            // 1..N. Data Symbols
            for (i in ALPHABET.indices) {
                syms.add(ALPHABET[i])
                freqs.add(BASE_DATA_FREQ + (i * FREQ_STEP))
            }

            // N+1. Postamble
            syms.add(POSTAMBLE_MARKER)
            freqs.add(POSTAMBLE_FREQ)

            ALL_SYMBOLS = syms
            ALL_FREQS = freqs.toDoubleArray()
        }

        fun charToFrequency(c: Char): Double {
            val idx = ALPHABET.indexOf(c.uppercaseChar())
            return if (idx >= 0) {
                BASE_DATA_FREQ + (idx * FREQ_STEP)
            } else {
                BASE_DATA_FREQ
            }
        }
    }
}