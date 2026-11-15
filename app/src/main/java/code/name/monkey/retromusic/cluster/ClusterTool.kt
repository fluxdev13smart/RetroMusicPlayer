package code.name.monkey.retromusic.cluster

import android.content.Context
import android.os.Looper
import android.util.Base64
import java.lang.reflect.InvocationTargetException

/**
 * Command line tool that writes the "now playing" card of a BYD instrument cluster.
 *
 * It is NOT used by the app directly. It is started on the head unit with shell rights:
 *
 *   CLASSPATH=<this apk> app_process /system/bin code.name.monkey.retromusic.cluster.ClusterTool
 *       <source> <state> <base64 text>
 *
 * Apps are not allowed to talk to the car's instrument service, but the ADB shell user is.
 * Any argument can be "-" to leave that value untouched.
 *
 * The register numbers are the ones used by other open source BYD tools (DiPlay, BYDMate).
 */
object ClusterTool {

    private const val DEVICE = 1007
    private const val REG_SOURCE = 0x33F00030
    private const val REG_STATE = 0x43E0000A
    private const val REG_TEXT = 0x43FB1008
    private const val MAX_TEXT_BYTES = 255

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            write(args)
        } catch (t: Throwable) {
            println("write=ERR ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // ActivityThread leaves threads running; without this the shell command never returns.
            System.exit(0)
        }
    }

    private fun write(args: Array<String>) {
        if (args.size < 3) {
            println("write=ERR usage: <source> <state> <base64 text>")
            return
        }
        val text = if (args[2] == "-") {
            null
        } else {
            String(Base64.decode(args[2], Base64.DEFAULT), Charsets.UTF_8)
                .toByteArray(Charsets.UTF_16LE)
        }
        if (text != null && text.size > MAX_TEXT_BYTES) {
            println("write=ERR too long")
            return
        }

        runCatching { Looper.prepareMainLooper() }
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context

        val deviceClass =
            Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        val device = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (e: InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)
        }

        val setState = deviceClass.getMethod(
            "setMediaState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val setInfo = deviceClass.getMethod(
            "setMediaInfo", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ByteArray::class.java
        )

        // Source first, then the state, then the text, like the other tools do.
        args[0].toIntOrNull()?.let {
            println("source=" + setState.invoke(device, REG_SOURCE, DEVICE, it))
        }
        args[1].toIntOrNull()?.let {
            println("state=" + setState.invoke(device, REG_STATE, DEVICE, it))
        }
        if (text != null) {
            println("text=" + setInfo.invoke(device, REG_TEXT, DEVICE, text))
        }
    }
}
