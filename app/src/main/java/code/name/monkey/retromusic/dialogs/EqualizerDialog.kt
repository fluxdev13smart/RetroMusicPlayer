package code.name.monkey.retromusic.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.service.EqualizerController
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Simple in-app equalizer: on/off switch, presets, one slider per band, bass boost and loudness.
 * Everything is applied live to the player's audio session.
 */
class EqualizerDialog : DialogFragment() {

    private var controller: EqualizerController? = null
    private val bandViews = mutableListOf<Pair<SeekBar, TextView>>()
    private var bandMin = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val service = MusicPlayerRemote.musicService
        val sessionId = try {
            service?.audioSessionId ?: -1
        } catch (e: Exception) {
            -1
        }
        val eq = service?.equalizerController
        if (service == null || eq == null || sessionId <= 0) {
            return materialDialog(R.string.equalizer)
                .setMessage(R.string.eq_play_first)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .colorButtons()
        }
        eq.attach(sessionId)
        if (!eq.isAvailable) {
            return materialDialog(R.string.equalizer)
                .setMessage(R.string.eq_not_supported)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .colorButtons()
        }
        controller = eq

        return materialDialog(R.string.equalizer)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.eq_reset) { _, _ -> eq.resetToFlat() }
            .setView(buildContent(eq))
            .create()
            .colorButtons()
    }

    private fun buildContent(eq: EqualizerController): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(8))
        }

        val enableSwitch = MaterialSwitch(ctx).apply {
            setText(R.string.eq_enable)
            isChecked = eq.isEnabled
            setOnCheckedChangeListener { _, checked -> eq.enable(checked) }
        }
        root.addView(enableSwitch)

        // One slider for the "boom": lifts the low bands and the bass boost together
        root.addView(label(getString(R.string.eq_bass_punch)))
        root.addView(simpleSeek(100, eq.bassLevel) { level ->
            eq.setBassLevel(level)
            if (level > 0 && !eq.isEnabled) {
                eq.enable(true)
                enableSwitch.isChecked = true
            }
        })

        // Presets
        val presetNames = listOf(getString(R.string.eq_preset_custom)) + eq.presetNames()
        val presetSpinner = Spinner(ctx)
        presetSpinner.adapter =
            ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, presetNames)
        presetSpinner.setSelection(eq.selectedPreset + 1, false)
        root.addView(label(getString(R.string.eq_preset)))
        root.addView(presetSpinner)

        // Bands
        val range = eq.bandLevelRange
        bandMin = range.first
        val span = range.second - range.first
        for (band in 0 until eq.numberOfBands) {
            val text = label(bandText(eq, band, eq.bandLevel(band)))
            val seek = SeekBar(ctx).apply {
                max = span
                progress = eq.bandLevel(band) - bandMin
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                        val level = value + bandMin
                        text.text = bandText(eq, band, level)
                        if (fromUser) {
                            eq.setBandLevel(band, level)
                            presetSpinner.setSelection(0, false)
                        }
                    }

                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            bandViews.add(seek to text)
            root.addView(text)
            root.addView(seek)
        }

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position == 0) return
                eq.usePreset(position - 1)
                bandViews.forEachIndexed { band, (seek, text) ->
                    val level = eq.bandLevel(band)
                    seek.progress = level - bandMin
                    text.text = bandText(eq, band, level)
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        if (eq.hasBassBoost) {
            root.addView(label(getString(R.string.eq_bass_boost)))
            root.addView(simpleSeek(1000, eq.bassBoostStrength) { eq.setBassBoost(it) })
        }
        if (eq.hasLoudness) {
            root.addView(label(getString(R.string.eq_loudness)))
            root.addView(simpleSeek(1000, eq.loudnessGainMb) { eq.setLoudness(it) })
        }

        return ScrollView(ctx).apply { addView(root) }
    }

    private fun label(text: String) = TextView(requireContext()).apply {
        this.text = text
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun bandText(eq: EqualizerController, band: Int, levelMb: Int): String {
        val hz = eq.centerFrequencyHz(band)
        val freq = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
        val db = levelMb / 100f
        return "%s   %+.1f dB".format(freq, db)
    }

    private fun simpleSeek(max: Int, current: Int, onChange: (Int) -> Unit) =
        SeekBar(requireContext()).apply {
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

    companion object {
        const val TAG = "EqualizerDialog"
    }
}
