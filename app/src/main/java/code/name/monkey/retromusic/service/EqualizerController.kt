package code.name.monkey.retromusic.service

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Built-in equalizer that is attached directly to the player's audio session, so it works even
 * when the device (e.g. a car head unit) has no system equalizer app.
 *
 * Settings are stored in their own preference file so changing them never triggers the
 * app-wide preference listeners.
 */
class EqualizerController(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId = AudioEffect.ERROR_BAD_VALUE

    val isAvailable: Boolean
        @Synchronized get() = equalizer != null

    val hasBassBoost: Boolean
        @Synchronized get() = bassBoost?.strengthSupported == true

    val hasLoudness: Boolean
        @Synchronized get() = loudness != null

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Index of the selected preset, or -1 when the bands were adjusted by hand. */
    var selectedPreset: Int
        get() = prefs.getInt(KEY_PRESET, -1)
        private set(value) = prefs.edit().putInt(KEY_PRESET, value).apply()

    var bassBoostStrength: Int
        get() = prefs.getInt(KEY_BASS, 0)
        private set(value) = prefs.edit().putInt(KEY_BASS, value).apply()

    /** 0..100. Raises the lowest bands and the bass boost together, for a felt "boom". */
    var bassLevel: Int
        get() = prefs.getInt(KEY_BASS_LEVEL, 0)
        private set(value) = prefs.edit().putInt(KEY_BASS_LEVEL, value).apply()

    var loudnessGainMb: Int
        get() = prefs.getInt(KEY_LOUDNESS, 0)
        private set(value) = prefs.edit().putInt(KEY_LOUDNESS, value).apply()

    val numberOfBands: Int
        @Synchronized get() = equalizer?.numberOfBands?.toInt() ?: 0

    /** Lowest and highest band level in millibels. */
    val bandLevelRange: Pair<Int, Int>
        @Synchronized get() {
            val range = equalizer?.bandLevelRange ?: return Pair(-1500, 1500)
            return Pair(range[0].toInt(), range[1].toInt())
        }

    @Synchronized
    fun centerFrequencyHz(band: Int): Int =
        (equalizer?.getCenterFreq(band.toShort()) ?: 0) / 1000

    @Synchronized
    fun presetNames(): List<String> {
        val eq = equalizer ?: return emptyList()
        return (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
    }

    /** Current level of a band in millibels, taken from the saved settings. */
    fun bandLevel(band: Int): Int = savedLevels().getOrNull(band) ?: 0

    /**
     * Binds the effects to the given audio session. Safe to call repeatedly; effects are only
     * recreated when the session changes.
     */
    @Synchronized
    fun attach(audioSessionId: Int) {
        // Session 0 would apply the effect to every app on the device, so never use it.
        if (audioSessionId <= 0) return
        if (audioSessionId == sessionId && equalizer != null) {
            apply()
            return
        }
        release()
        sessionId = audioSessionId
        equalizer = create("Equalizer") { Equalizer(0, audioSessionId) }
        bassBoost = create("BassBoost") { BassBoost(0, audioSessionId) }
        loudness = create("LoudnessEnhancer") { LoudnessEnhancer(audioSessionId) }
        apply()
    }

    @Synchronized
    fun release() {
        equalizer?.release()
        bassBoost?.release()
        loudness?.release()
        equalizer = null
        bassBoost = null
        loudness = null
        sessionId = AudioEffect.ERROR_BAD_VALUE
    }

    @Synchronized
    fun enable(enabled: Boolean) {
        isEnabled = enabled
        apply()
    }

    @Synchronized
    fun usePreset(preset: Int) {
        val eq = equalizer ?: return
        if (preset < 0 || preset >= eq.numberOfPresets) {
            selectedPreset = -1
            return
        }
        try {
            eq.usePreset(preset.toShort())
            selectedPreset = preset
            saveLevels((0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() })
        } catch (e: Exception) {
            Log.w(TAG, "usePreset failed", e)
        }
        apply()
    }

    @Synchronized
    fun setBandLevel(band: Int, levelMb: Int) {
        val bands = equalizer?.numberOfBands?.toInt() ?: return
        val levels = savedLevels().toMutableList()
        while (levels.size < bands) levels.add(0)
        levels[band] = levelMb
        saveLevels(levels)
        selectedPreset = -1
        apply()
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        bassBoostStrength = strength
        apply()
    }

    @Synchronized
    fun changeBassLevel(level: Int) {
        bassLevel = level.coerceIn(0, 100)
        apply()
    }

    @Synchronized
    fun setLoudness(gainMb: Int) {
        loudnessGainMb = gainMb
        apply()
    }

    @Synchronized
    fun resetToFlat() {
        saveLevels(emptyList())
        selectedPreset = -1
        bassBoostStrength = 0
        bassLevel = 0
        loudnessGainMb = 0
        apply()
    }

    private fun apply() {
        val enabled = isEnabled
        try {
            equalizer?.let { eq ->
                val levels = savedLevels()
                val range = eq.bandLevelRange
                // The lowest bands (below 250 Hz) get the extra bass; at most two of them.
                val bassBands = (0 until eq.numberOfBands)
                    .filter { eq.getCenterFreq(it.toShort()) / 1000 < 250 }
                    .take(2)
                val bassOffset = (bassLevel / 100f * minOf(range[1].toInt(), 700)).toInt()
                for (band in 0 until eq.numberOfBands) {
                    val extra = if (band in bassBands) bassOffset else 0
                    val level = ((levels.getOrNull(band) ?: 0) + extra)
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(band.toShort(), level.toShort())
                }
                eq.enabled = enabled
            }
            bassBoost?.let { bb ->
                val strength = (bassBoostStrength + bassLevel * 7).coerceAtMost(1000)
                if (bb.strengthSupported) {
                    bb.setStrength(strength.toShort())
                }
                bb.enabled = enabled && strength > 0
            }
            loudness?.let { le ->
                le.setTargetGain(loudnessGainMb)
                le.enabled = enabled && loudnessGainMb > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Applying equalizer settings failed", e)
        }
    }

    private fun savedLevels(): List<Int> =
        prefs.getString(KEY_LEVELS, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }

    private fun saveLevels(levels: List<Int>) {
        prefs.edit().putString(KEY_LEVELS, levels.joinToString(",")).apply()
    }

    private inline fun <T : AudioEffect> create(name: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "$name is not supported on this device", e)
        null
    }

    companion object {
        private const val TAG = "EqualizerController"
        private const val PREFS_NAME = "in_app_equalizer"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_LEVELS = "band_levels"
        private const val KEY_BASS_LEVEL = "bass_level"
        private const val KEY_BASS = "bass_boost"
        private const val KEY_LOUDNESS = "loudness_gain"
    }
}
