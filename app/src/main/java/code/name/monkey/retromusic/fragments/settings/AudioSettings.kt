/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.preference.Preference
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.BLUETOOTH_PLAYBACK
import code.name.monkey.retromusic.CLUSTER_TEST
import code.name.monkey.retromusic.EQUALIZER
import code.name.monkey.retromusic.FLOATING_MINI_PLAYER
import code.name.monkey.retromusic.IN_APP_EQUALIZER
import code.name.monkey.retromusic.dialogs.EqualizerDialog
import code.name.monkey.retromusic.cluster.ClusterMusicBridge
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.service.FloatingMiniPlayer
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsBaseActivity.Companion.BLUETOOTH_PERMISSION_REQUEST
import code.name.monkey.retromusic.util.NavigationUtil

/**
 * @author Hemanth S (h4h13).
 */

class AudioSettings : AbsSettingsFragment() {
    override fun invalidateSettings() {
        val eqPreference: Preference? = findPreference(EQUALIZER)
        if (!hasEqualizer()) {
            eqPreference?.isEnabled = false
            eqPreference?.summary = resources.getString(R.string.no_equalizer)
        } else {
            eqPreference?.isEnabled = true
        }
        eqPreference?.setOnPreferenceClickListener {
            NavigationUtil.openEqualizer(requireActivity())
            true
        }
        findPreference<Preference>(IN_APP_EQUALIZER)?.setOnPreferenceClickListener {
            EqualizerDialog().show(childFragmentManager, EqualizerDialog.TAG)
            true
        }
        findPreference<Preference>(FLOATING_MINI_PLAYER)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean && !Settings.canDrawOverlays(requireContext())) {
                requireContext().showToast(R.string.floating_mini_player_permission)
                FloatingMiniPlayer.requestPermission(requireActivity())
                false
            } else {
                true
            }
        }
        findPreference<Preference>(CLUSTER_TEST)?.setOnPreferenceClickListener {
            runClusterTest()
            true
        }
        val bluetoothPreference: Preference? = findPreference(BLUETOOTH_PLAYBACK)
        if (VersionUtils.hasS()) {
            bluetoothPreference?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            BLUETOOTH_CONNECT
                        ) != PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            requireActivity(), arrayOf(
                                BLUETOOTH_CONNECT
                            ), BLUETOOTH_PERMISSION_REQUEST
                        )
                    }
                }
                return@setOnPreferenceChangeListener true
            }
        }
    }

    /** Sends a test text to the driver display and shows a step by step report. */
    private fun runClusterTest() {
        val appContext = requireContext().applicationContext
        requireContext().showToast(R.string.cluster_testing, Toast.LENGTH_LONG)
        val source = PreferenceUtil.clusterSource
        Thread {
            val report =
                ClusterMusicBridge.diagnose(appContext, source, ClusterMusicBridge.DEFAULT_PORT)
            activity?.runOnUiThread {
                if (isAdded) {
                    materialDialog(R.string.cluster_test_title)
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                        .colorButtons()
                        .show()
                }
                // Leave the test text up for a moment, then put the real song back.
                Handler(Looper.getMainLooper()).postDelayed({
                    MusicPlayerRemote.musicService?.forceUpdateCluster()
                }, 15_000L)
            }
        }.start()
    }

    private fun hasEqualizer(): Boolean {
        val effects = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)

        val pm = requireActivity().packageManager
        val ri = pm.resolveActivity(effects, 0)
        return ri != null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_audio)
    }
}
