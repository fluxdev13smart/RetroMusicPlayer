package code.name.monkey.retromusic.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.MainActivity
import code.name.monkey.retromusic.glide.RetroGlideExtension.getSongModel
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.util.PreferenceUtil
import com.bumptech.glide.Glide
import java.lang.ref.WeakReference

/**
 * Small draggable player drawn over other apps while the app itself is in the background.
 * Needs the "Display over other apps" permission and the matching setting turned on.
 */
object FloatingMiniPlayer {

    private const val PREFS_NAME = "floating_mini_player"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val BACKGROUND_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceRef: WeakReference<MusicService>? = null

    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var artView: ImageView? = null
    private var playPauseView: ImageView? = null
    private var shownSongId = Long.MIN_VALUE

    private var appInForeground = true
    private var dismissedUntilNextForeground = false

    private val refreshRunnable = Runnable { refreshNow() }

    fun attachService(service: MusicService?) {
        serviceRef = service?.let { WeakReference(it) }
        if (service == null) hide()
    }

    /** Called from the activity lifecycle tracker in [code.name.monkey.retromusic.App]. */
    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) dismissedUntilNextForeground = false
        mainHandler.removeCallbacks(refreshRunnable)
        // Small delay so a screen rotation (stop then start) does not make the overlay flicker.
        mainHandler.postDelayed(refreshRunnable, if (foreground) 0L else BACKGROUND_DELAY_MS)
    }

    /** Called whenever the song or play state changes. */
    fun refresh() {
        mainHandler.post(refreshRunnable)
    }

    fun hide() {
        mainHandler.post { removeView() }
    }

    private fun refreshNow() {
        val service = serviceRef?.get() ?: return removeView()
        val context = service.applicationContext
        val song = service.currentSong
        val allowed = PreferenceUtil.isFloatingMiniPlayer &&
                !appInForeground &&
                !dismissedUntilNextForeground &&
                song.id != -1L &&
                Settings.canDrawOverlays(context)
        if (!allowed) {
            removeView()
            return
        }
        try {
            if (rootView == null) createView(service)
            updateContent(service, song.id)
        } catch (e: Exception) {
            // Overlay not permitted or window manager unavailable: give up quietly.
            removeView()
        }
    }

    private fun updateContent(service: MusicService, songId: Long) {
        val song = service.currentSong
        titleView?.text = song.title
        artistView?.text = song.artistName
        playPauseView?.setImageResource(
            if (service.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        if (songId != shownSongId) {
            shownSongId = songId
            artView?.let {
                Glide.with(service.applicationContext)
                    .asBitmap()
                    .songCoverOptions(song)
                    .load(getSongModel(song))
                    .into(it)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(service: MusicService) {
        val context = service.applicationContext
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(Color.parseColor("#E6212121"))
            }
            elevation = px(8).toFloat()
        }

        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(px(48), px(48))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(px(120), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(8); marginEnd = px(4) }
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = TextView(context).apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(artist)

        fun button(icon: Int, description: String, action: () -> Unit) =
            ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                contentDescription = description
                setPadding(px(4), px(4), px(4), px(4))
                layoutParams = LinearLayout.LayoutParams(px(36), px(36))
                setOnClickListener { action() }
            }

        val previous = button(R.drawable.ic_skip_previous, "Previous") {
            service.playPreviousSong(true)
        }
        val playPause = button(R.drawable.ic_play_arrow, "Play or pause") {
            if (service.isPlaying) service.pause() else service.play()
        }
        val next = button(R.drawable.ic_skip_next, "Next") {
            service.playNextSong(true)
        }
        val close = button(R.drawable.ic_close, "Close") {
            dismissedUntilNextForeground = true
            removeView()
        }

        container.addView(art)
        container.addView(texts)
        container.addView(previous)
        container.addView(playPause)
        container.addView(next)
        container.addView(close)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, px(16))
            y = prefs.getInt(KEY_Y, px(120))
        }

        // Drag by touching the cover or the text; a tap without movement opens the app.
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val dragListener = object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = layoutParams.x
                        startY = layoutParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            moved = true
                        }
                        if (moved) {
                            layoutParams.x = startX + dx.toInt()
                            layoutParams.y = startY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(container, layoutParams)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.edit()
                                .putInt(KEY_X, layoutParams.x)
                                .putInt(KEY_Y, layoutParams.y)
                                .apply()
                        } else {
                            openApp(context)
                        }
                    }
                }
                return true
            }
        }
        art.setOnTouchListener(dragListener)
        texts.setOnTouchListener(dragListener)

        windowManager.addView(container, layoutParams)

        rootView = container
        params = layoutParams
        titleView = title
        artistView = artist
        artView = art
        playPauseView = playPause
        shownSongId = Long.MIN_VALUE
    }

    private fun openApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        context.startActivity(intent)
    }

    private fun removeView() {
        val view = rootView ?: return
        try {
            val windowManager =
                view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
        }
        rootView = null
        params = null
        titleView = null
        artistView = null
        artView = null
        playPauseView = null
        shownSongId = Long.MIN_VALUE
    }
}
