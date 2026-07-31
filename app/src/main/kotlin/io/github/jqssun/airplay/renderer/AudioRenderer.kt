package io.github.jqssun.airplay.renderer
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.viewmodel.AudioDebug
import kotlin.math.pow

private const val TAG = "AudioRenderer"

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var volume = 1.0f; private set
    @Volatile var codecLabel = ""; private set

    // Diagnostic: track whether audio output was ever successfully opened
    @Volatile private var _audioPathAvailable = false; private set

    // Diagnostic: track the last start() return value from native
    @Volatile private var _lastStartResult = false; private set

    private var serverHandle = 0L

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
        NativeBridge.nativeServerAudioSetVolume(serverHandle, volume)
        Log.i(TAG, "attachEngine: serverHandle=$server")
    }

    @Synchronized
    fun detachEngine() {
        Log.i(TAG, "detachEngine: clearing engine reference")
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start(): Boolean {
        if (serverHandle == 0L) {
            Log.w(TAG, "start() called with no server handle — skipping")
            _lastStartResult = false
            return false
        }
        
        // DIAGNOSTIC: Check audio manager properties before starting
        val am = android.content.ContextCompat.getSystemService(
            android.app.Application() ?: throw IllegalStateException("Application context required"),
            android.media.AudioManager::class.java
        )
        
        val actualRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val actualBurst = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        
        Log.i(TAG, "start() called: serverHandle=$serverHandle, deviceSampleRate=$actualRate, burstSize=$actualBurst")
        
        // Check if we're on a device that likely has HDMI/audio output available
        val isFireTv = android.os.Build.MANUFACTURER.lowercase().contains("amazon")
        if (isFireTv) {
            Log.w(TAG, "WARNING: Amazon Fire TV detected — audio output may not be available")
        }
        
        val result = NativeBridge.nativeServerAudioStart(serverHandle)
        _lastStartResult = result
        _audioPathAvailable = result
        
        if (result) {
            Log.i(TAG, "start() SUCCESS — audio path opened")
        } else {
            Log.e(TAG, "start() FAILED — audio path could not be opened (HDMI/audio unavailable)")
        }
        
        return result
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        Log.i(TAG, "stop() — pausing audio output")
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.realtimePriority, config.lowLatency, config.benchmarkLog)
    }

    private val debugBuf = java.nio.ByteBuffer.allocateDirect(DEBUG_BUF_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // field order/width must mirror native packed AudioDebugData exactly
    @Synchronized
    fun audioDebug(): AudioDebug? {
        if (serverHandle == 0L) return null
        if (!NativeBridge.nativeServerAudioDebug(serverHandle, debugBuf)) return null
        val buf = debugBuf
        buf.rewind()
        val backlogMs = buf.short.toInt() and 0xFFFF
        val tunedCushionMs = buf.short.toInt() and 0xFFFF
        val trims = buf.int
        val drops = buf.int
        val silences = buf.int
        val underruns = buf.int
        val meanUs = buf.int
        val maxUs = buf.int
        val held = buf.short.toInt() and 0xFFFF
        val xrun = buf.int
        val decodeErrors = buf.int
        return AudioDebug(backlogMs, tunedCushionMs, trims, drops, silences, underruns, xrun,
                          meanUs, maxUs, held, decodeErrors)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        
        // DIAGNOSTIC: Log sample rate mismatch between AirPlay and device
        val am = android.content.ContextCompat.getSystemService(
            android.app.Application() ?: throw IllegalStateException("Application context required"),
            android.media.AudioManager::class.java
        )
        val deviceSampleRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        
        Log.i(TAG, "setFormat(): airPlayCodec=$codecLabel spf=$spf deviceSampleRate=${deviceSampleRate}Hz")
        
        if (deviceSampleRate > 0 && spf > 0) {
            // AirPlay sends spf=480 which implies 48kHz (spf * 100 = ~48000 for ALAC)
            // If device is 44100Hz, there will be a resampling mismatch
            val airplaySampleRate = spf * 100  // approximate
            if (airplaySampleRate != deviceSampleRate) {
                Log.w(TAG, "SAMPLE RATE MISMATCH: AirPlay expects ~${airplaySampleRate}Hz but device reports ${deviceSampleRate}Hz")
            }
        }
        
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    @Synchronized
    fun setVolume(vol: Float) {
        // dB: max = 0, min = -30, mute = -144
        volume = when {
            vol <= -144f -> 0f
            vol >= 0f -> 1f
            else -> 10f.pow(vol / 20f)
        }
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioSetVolume(serverHandle, volume)
    }

    /**
     * Returns true if the audio output path was successfully opened at least once.
     * Use this to determine whether to continue with audio-only mode or fall back to video-only.
     */
    fun isAudioPathAvailable(): Boolean = _audioPathAvailable

    /**
     * Returns the result of the last start() call for diagnostic purposes.
     */
    fun getLastStartResult(): Boolean = _lastStartResult

    companion object {
        // must be >= native sizeof(AudioDebugData) (38 B)
        private const val DEBUG_BUF_BYTES = 64
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
