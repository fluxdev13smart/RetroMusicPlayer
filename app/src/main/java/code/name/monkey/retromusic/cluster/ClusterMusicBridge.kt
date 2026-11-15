package code.name.monkey.retromusic.cluster

import android.content.Context
import android.util.Base64
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Shows the current song on a BYD instrument cluster (driver display).
 *
 * The car only fills that card for a fixed list of players. Writing it needs shell rights, so
 * this connects to the head unit's own ADB daemon on 127.0.0.1 (the "ADB over network" /
 * wireless debugging option must be on, and the connection approved once on the screen) and
 * runs [ClusterTool] there.
 *
 * Everything is best effort: when anything fails it logs and backs off, and never affects
 * playback.
 */
class ClusterMusicBridge(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClusterMusicBridge").apply { isDaemon = true }
    }

    private var dadb: Dadb? = null
    private var failures = 0
    private var blockedUntil = 0L
    private var lastSent: String? = null

    /** Safe to call from any thread, as often as needed; only changes are sent. */
    fun update(title: String?, artist: String?, state: Int, source: Int, port: Int) {
        executor.execute { send(title, artist, state, source, port) }
    }

    /** Marks the card as stopped. */
    fun stop(source: Int, port: Int) {
        executor.execute { send(null, null, STATE_STOPPED, source, port) }
    }

    fun release() {
        executor.execute { closeConnection() }
        executor.shutdown()
    }

    private fun send(title: String?, artist: String?, state: Int, source: Int, port: Int) {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return

        val text = buildText(title, artist)
        val key = "$source|$state|$text"
        if (key == lastSent) return

        try {
            val connection = dadb ?: connect(port).also { dadb = it }
            val apk = appContext.applicationInfo.sourceDir
            val encoded = if (text.isEmpty()) "-" else
                Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val command = "CLASSPATH=$apk app_process /system/bin " +
                    "${ClusterTool::class.java.name} $source $state $encoded"
            val response = connection.shell(command)
            Log.i(TAG, "cluster write: ${response.allOutput.trim()}")
            lastSent = key
            failures = 0
        } catch (e: Throwable) {
            Log.w(TAG, "Could not update the cluster: ${e.message}")
            closeConnection()
            failures++
            // Back off so a missing or unapproved ADB connection does not drain the battery.
            if (failures >= MAX_FAILURES) {
                blockedUntil = System.currentTimeMillis() + BACKOFF_MS
                failures = 0
            }
        }
    }

    private fun connect(port: Int): Dadb {
        val privateKey = File(appContext.filesDir, "adbkey")
        val publicKey = File(appContext.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return Dadb.create("127.0.0.1", port, AdbKeyPair.read(privateKey, publicKey))
    }

    private fun closeConnection() {
        try {
            dadb?.close()
        } catch (_: Throwable) {
        }
        dadb = null
    }

    private fun buildText(title: String?, artist: String?): String {
        val t = title.orEmpty().trim()
        val a = artist.orEmpty().trim()
        val combined = when {
            t.isEmpty() -> ""
            a.isEmpty() || a.equals("<unknown>", true) -> t
            else -> "$t - $a"
        }
        // The cluster accepts at most 255 bytes of UTF-16, which is 127 characters.
        return if (combined.length > MAX_CHARS) combined.take(MAX_CHARS - 1) + "…" else combined
    }

    companion object {
        private const val TAG = "ClusterMusicBridge"
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_STOPPED = 3
        const val DEFAULT_PORT = 5555
        private const val MAX_CHARS = 127
        private const val MAX_FAILURES = 3
        private const val BACKOFF_MS = 60_000L
    }
}
