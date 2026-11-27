package code.name.monkey.retromusic.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.util.PreferenceUtil

/**
 * "Play last song when the app opens": once per app start, waits the chosen delay and then resumes
 * the last song quietly (see [MusicService.startPlaybackQuietly]).
 */
object AutoStartPlayback {

    private const val TAG = "AutoStartPlayback"

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false

    /** Call when the main screen is created. Only the first call after the app starts counts. */
    fun onAppOpened(context: Context) {
        if (handled) return
        handled = true
        if (!PreferenceUtil.isAutoStartPlayback) return
        val appContext = context.applicationContext
        val delayMs = PreferenceUtil.autoStartDelaySeconds.coerceAtLeast(0) * 1000L
        handler.postDelayed({ start(appContext) }, delayMs)
    }

    private fun start(context: Context) {
        // It may have been switched off while waiting.
        if (!PreferenceUtil.isAutoStartPlayback) return
        val service = MusicPlayerRemote.musicService
        if (service != null) {
            service.startPlaybackQuietly(PreferenceUtil.autoStartVolume)
            return
        }
        // The screen was closed before the delay ended and the service is not bound any more.
        try {
            context.startService(
                Intent(context, MusicService::class.java).setAction(MusicService.ACTION_AUTO_START)
            )
        } catch (e: Exception) {
            // Starting a service from the background is not always allowed; nothing to do then.
            Log.w(TAG, "Could not start playback: ${e.message}")
        }
    }
}
