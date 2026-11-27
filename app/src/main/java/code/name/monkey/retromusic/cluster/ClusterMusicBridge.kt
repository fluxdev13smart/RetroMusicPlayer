package code.name.monkey.retromusic.cluster

import android.content.Context
import android.util.Base64
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /** Forget what was sent last so the next [update] is written again. */
    fun invalidate() {
        executor.execute { lastSent = null }
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

    private fun connect(port: Int): Dadb = openConnection(appContext, port)

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
        private const val CONNECT_TIMEOUT_S = 60L
        private const val WRITE_TIMEOUT_S = 30L

        /** Dadb connects lazily: nothing happens on the network until the first command. */
        fun openConnection(context: Context, port: Int): Dadb {
            val privateKey = File(context.filesDir, "adbkey")
            val publicKey = File(context.filesDir, "adbkey.pub")
            if (!privateKey.exists() || !publicKey.exists()) {
                AdbKeyPair.generate(privateKey, publicKey)
            }
            return Dadb.create("127.0.0.1", port, AdbKeyPair.read(privateKey, publicKey))
        }

        /**
         * Gives this app the "Display over other apps" permission through the car's own ADB, for
         * systems that have no screen for it. Blocking; call from a background thread.
         */
        fun grantOverlayPermission(context: Context, port: Int): Boolean {
            var dadb: Dadb? = null
            return try {
                dadb = openConnection(context, port)
                dadb.shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                android.provider.Settings.canDrawOverlays(context)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not grant the overlay permission: ${e.message}")
                false
            } finally {
                try {
                    dadb?.close()
                } catch (_: Throwable) {
                }
            }
        }

        private fun <T> withTimeout(worker: ExecutorService, seconds: Long, block: () -> T): T {
            val future = worker.submit(Callable { block() })
            try {
                return future.get(seconds, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw e
            }
        }

        private fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message}"

        /**
         * Runs the whole chain once and returns a plain text report of every step, so it is clear
         * whether the car's ADB, the permission or the write itself is what fails.
         * Blocking; call from a background thread.
         */
        fun diagnose(context: Context, source: Int, port: Int): String {
            val app = context.applicationContext
            val report = StringBuilder()
            fun add(line: String) {
                report.append(line).append('\n')
            }

            val worker = Executors.newSingleThreadExecutor { r ->
                Thread(r, "ClusterTest").apply { isDaemon = true }
            }
            var dadb: Dadb? = null
            try {
                add("Step 1: connect to the car's ADB at 127.0.0.1:$port")
                val connection = openConnection(app, port)
                dadb = connection
                try {
                    val id = withTimeout(worker, CONNECT_TIMEOUT_S) {
                        connection.shell("id").allOutput.trim()
                    }
                    add("  OK: $id")
                    if (!id.contains("uid=2000")) {
                        add("  Warning: not the shell user, the write will probably be refused.")
                    }
                } catch (e: TimeoutException) {
                    add("  FAILED: no answer after ${CONNECT_TIMEOUT_S}s")
                    add("  If a \"Allow USB debugging?\" box showed up on the car screen, tick")
                    add("  \"Always allow\", press OK and run this test again.")
                    return report.toString()
                } catch (e: Throwable) {
                    add("  FAILED: ${describe(e)}")
                    add("  The car's ADB is not reachable. In the car's developer options turn on")
                    add("  \"ADB over network\" (or run \"adb tcpip $port\" once from a PC over USB),")
                    add("  then run this test again and press \"Always allow\" when asked.")
                    return report.toString()
                }

                add("")
                add("Step 2: write a test text to the driver display (source $source)")
                val apk = app.applicationInfo.sourceDir
                val text = Base64.encodeToString(
                    "Retro Music - test".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                )
                val command = "CLASSPATH=$apk app_process /system/bin " +
                        "${ClusterTool::class.java.name} $source $STATE_PLAYING $text"
                try {
                    val output = withTimeout(worker, WRITE_TIMEOUT_S) {
                        connection.shell(command).allOutput.trim()
                    }
                    if (output.isEmpty()) {
                        add("  FAILED: the helper printed nothing (it could not start).")
                    } else {
                        output.lines().forEach { add("  $it") }
                        if (output.contains("write=ERR")) {
                            add("  FAILED: the car refused or does not have this interface.")
                        } else {
                            add("  Written. Look at the driver display now.")
                            add("  If it stays blank, change \"Cluster source\" to the other value and")
                            add("  test again, then send me this report.")
                        }
                    }
                } catch (e: TimeoutException) {
                    add("  FAILED: the helper did not finish within ${WRITE_TIMEOUT_S}s")
                } catch (e: Throwable) {
                    add("  FAILED: ${describe(e)}")
                }
            } finally {
                try {
                    dadb?.close()
                } catch (_: Throwable) {
                }
                worker.shutdownNow()
            }
            return report.toString()
        }

        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_STOPPED = 3
        const val DEFAULT_PORT = 5555
        private const val MAX_CHARS = 127
        private const val MAX_FAILURES = 3
        private const val BACKOFF_MS = 60_000L
    }
}
