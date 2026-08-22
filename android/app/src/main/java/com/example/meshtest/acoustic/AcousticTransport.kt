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
 * AcousticTransport handles acoustic sound-based data transmission and reception
 * using Frequency Shift Keying (FSK) audio chirps.
 *
 * Implements rate-limiting (minimum 10s cooldown) to preserve battery.
 */
class AcousticTransport(private val context: Context) {
    private val TAG = "AcousticTransport"

    private val isListeningActive = AtomicBoolean(false)
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastTransmitTimeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onBeaconCallback: ((String) -> Unit)? = null

    // Deduplication of recently received acoustic beacons
    private val recentlyReceivedBeacons = mutableSetOf<String>()

    /**
     * Transmit a compact acoustic beacon over the device speaker.
     * Enforces a 10-second rate-limiting cooldown.
     */
    fun transmit(beacon: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTransmitTimeMs < COOLDOWN_MS) {
            val remainingSec = ((COOLDOWN_MS - (now - lastTransmitTimeMs)) / 1000) + 1
            Log.w(TAG, "Acoustic transmit rate-limited ($remainingSec s remaining)")
            return false
        }

        lastTransmitTimeMs = now
        Log.d(TAG, "Transmitting acoustic beacon: $beacon")

        scope.launch(Dispatchers.IO) {
            try {
                playFskBeaconAudio(beacon)
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
        Log.d(TAG, "Starting acoustic beacon listening engine...")

        listeningJob = scope.launch(Dispatchers.IO) {
            runAudioListeningLoop()
        }
    }

    /**
     * Stop background microphone listening loop.
     */
    fun stopListening() {
        if (!isListeningActive.getAndSet(false)) return

        Log.d(TAG, "Stopping acoustic beacon listening engine...")
        listeningJob?.cancel()
        listeningJob = null
    }

    fun isListening(): Boolean = isListeningActive.get()

    /**
     * Synthesizes and plays FSK audio tones via AudioTrack.
     */
    private fun playFskBeaconAudio(text: String) {
        val sampleRate = SAMPLE_RATE
        val charDuration = CHAR_DURATION_SEC
        val preambleDuration = PREAMBLE_DURATION_SEC

        val totalSamples = (sampleRate * (preambleDuration + (text.length * charDuration) + preambleDuration)).toInt()
        val audioData = ShortArray(totalSamples)
        var sampleIdx = 0

        // 1. Preamble Tone (1800 Hz)
        val preambleSamples = (sampleRate * preambleDuration).toInt()
        for (i in 0 until preambleSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (0.5 * sin(2.0 * PI * PREAMBLE_FREQ * t) * Short.MAX_VALUE).toInt().toShort()
            if (sampleIdx < audioData.size) audioData[sampleIdx++] = sample
        }

        // 2. Data Characters (FSK: base + (char - 32) * step)
        for (c in text) {
            val charCode = c.code.coerceIn(32, 126)
            val freq = BASE_DATA_FREQ + ((charCode - 32) * FREQ_STEP)
            val charSamples = (sampleRate * charDuration).toInt()

            for (i in 0 until charSamples) {
                val t = i.toDouble() / sampleRate
                // Hann window to prevent clicks
                val window = 0.5 * (1.0 - cos((2.0 * PI * i) / charSamples))
                val sample = (0.5 * window * sin(2.0 * PI * freq * t) * Short.MAX_VALUE).toInt().toShort()
                if (sampleIdx < audioData.size) audioData[sampleIdx++] = sample
            }
        }

        // 3. Postamble Tone (2200 Hz)
        val postambleSamples = (sampleRate * preambleDuration).toInt()
        for (i in 0 until postambleSamples) {
            val t = i.toDouble() / sampleRate
            val sample = (0.5 * sin(2.0 * PI * POSTAMBLE_FREQ * t) * Short.MAX_VALUE).toInt().toShort()
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
            .setBufferSizeInBytes(maxOf(bufferSize, audioData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack.write(audioData, 0, audioData.size)
            audioTrack.play()
            Thread.sleep(((totalSamples.toDouble() / sampleRate) * 1000).toLong() + 100)
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack error", e)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Continuous audio capture and FSK frequency analysis loop.
     */
    @Suppress("MissingPermission")
    private fun runAudioListeningLoop() {
        val sampleRate = SAMPLE_RATE
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            sampleRate / 10 // ~100ms frames
        )

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                isListeningActive.set(false)
                return
            }

            audioRecord.startRecording()
            Log.d(TAG, "AudioRecord started successfully at ${sampleRate}Hz")

            val audioBuffer = ShortArray(bufferSize)
            val decodedChars = StringBuilder()
            var isDecodingMessage = false
            var silenceFrames = 0

            while (isListeningActive.get()) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readCount <= 0) continue

                val dominantFreq = computeDominantFrequency(audioBuffer, readCount, sampleRate)

                // Detect Preamble (1800 Hz ± 40 Hz)
                if (!isDecodingMessage && abs(dominantFreq - PREAMBLE_FREQ) < 40) {
                    isDecodingMessage = true
                    decodedChars.clear()
                    silenceFrames = 0
                    Log.d(TAG, "Detected acoustic preamble tone!")
                    continue
                }

                // Detect Postamble (2200 Hz ± 40 Hz) or Silence End
                if (isDecodingMessage && abs(dominantFreq - POSTAMBLE_FREQ) < 40) {
                    finalizeDecodedMessage(decodedChars.toString())
                    isDecodingMessage = false
                    decodedChars.clear()
                    continue
                }

                if (isDecodingMessage) {
                    if (dominantFreq >= BASE_DATA_FREQ - 25 && dominantFreq <= BASE_DATA_FREQ + (95 * FREQ_STEP) + 25) {
                        val charOffset = ((dominantFreq - BASE_DATA_FREQ + (FREQ_STEP / 2)) / FREQ_STEP).toInt()
                        val charCode = (charOffset + 32).coerceIn(32, 126)
                        val c = charCode.toChar()

                        // Prevent duplicate consecutive same-frame reads
                        if (decodedChars.isEmpty() || decodedChars.last() != c) {
                            decodedChars.append(c)
                        }
                        silenceFrames = 0
                    } else {
                        silenceFrames++
                        if (silenceFrames > 15) { // Timeout after ~1.5s of no valid FSK tone
                            if (decodedChars.length >= 10) {
                                finalizeDecodedMessage(decodedChars.toString())
                            }
                            isDecodingMessage = false
                            decodedChars.clear()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio listening loop", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (ignored: Exception) {}
            Log.d(TAG, "AudioRecord released")
        }
    }

    private fun finalizeDecodedMessage(raw: String) {
        val trimmed = raw.trim()
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
     * Efficient Goertzel-based frequency detection across candidate FSK tone bands.
     */
    private fun computeDominantFrequency(buffer: ShortArray, length: Int, sampleRate: Int): Double {
        val targetFreqs = mutableListOf<Double>()
        targetFreqs.add(PREAMBLE_FREQ)
        targetFreqs.add(POSTAMBLE_FREQ)
        for (c in 32..126) {
            targetFreqs.add(BASE_DATA_FREQ + ((c - 32) * FREQ_STEP))
        }

        var maxEnergy = 0.0
        var bestFreq = 0.0

        for (targetFreq in targetFreqs) {
            val energy = calculateGoertzelEnergy(buffer, length, sampleRate, targetFreq)
            if (energy > maxEnergy) {
                maxEnergy = energy
                bestFreq = targetFreq
            }
        }

        // Noise floor threshold
        return if (maxEnergy > 1e7) bestFreq else 0.0
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
        const val PREAMBLE_FREQ = 1800.0
        const val POSTAMBLE_FREQ = 2200.0
        const val BASE_DATA_FREQ = 2400.0
        const val FREQ_STEP = 50.0 // 50 Hz spacing per ASCII symbol

        const val CHAR_DURATION_SEC = 0.080 // 80ms per symbol
        const val PREAMBLE_DURATION_SEC = 0.150 // 150ms preamble

        const val COOLDOWN_MS = 10000L // 10s cooldown rate-limiting
    }
}