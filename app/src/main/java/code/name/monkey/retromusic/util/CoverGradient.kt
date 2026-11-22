package code.name.monkey.retromusic.util

import androidx.core.graphics.ColorUtils

/**
 * Colors for the "cover gradient" look of the Normal player: a gradient made only from the
 * song's cover color, with playback buttons in a matching tint. The shades are picked so the
 * existing text and icons of the current (light or dark) theme stay readable.
 */
object CoverGradient {

    private fun hsl(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }

    /** Top of the gradient: the cover color, kept in a range that is not washed out or black. */
    fun top(color: Int, darkTheme: Boolean): Int {
        val h = hsl(color)
        h[1] = h[1].coerceAtLeast(0.25f)
        h[2] = if (darkTheme) h[2].coerceIn(0.22f, 0.5f) else h[2].coerceIn(0.55f, 0.8f)
        return ColorUtils.HSLToColor(h)
    }

    /** Bottom of the gradient: the same hue, very dark (dark theme) or very light (light theme). */
    fun bottom(color: Int, darkTheme: Boolean): Int {
        val h = hsl(color)
        h[2] = if (darkTheme) 0.08f else 0.94f
        return ColorUtils.HSLToColor(h)
    }

    /** Color for the playback buttons: same hue, bright on dark themes, deep on light ones. */
    fun buttons(color: Int, darkTheme: Boolean): Int {
        val h = hsl(color)
        h[1] = h[1].coerceAtLeast(0.35f)
        h[2] = if (darkTheme) 0.82f else 0.26f
        return ColorUtils.HSLToColor(h)
    }
}
