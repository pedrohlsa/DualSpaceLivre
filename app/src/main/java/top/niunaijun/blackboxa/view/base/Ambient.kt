package top.niunaijun.blackboxa.view.base

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import top.niunaijun.blackboxa.R

/**
 * Ambient styling built around a single accent colour — the colour of the space
 * you are in. Everything here is a plain [GradientDrawable]: no blur, no
 * bitmaps, no extra layers to composite, so it stays cheap on a Moto G50.
 */
object Ambient {

    fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

    fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    /** Height of every glow view; the radius matches it so the light reaches
     *  zero exactly at the bottom edge instead of being cut off there. */
    const val GLOW_HEIGHT_DP = 340f

    /** Soft radial light in [base], painted behind the top of a screen. */
    fun glow(context: Context, base: Int): Drawable {
        val night = isNight(context)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.5f, 0f)
            gradientRadius = dp(context, GLOW_HEIGHT_DP)
            colors = intArrayOf(
                    ColorUtils.setAlphaComponent(base, if (night) 132 else 78),
                    ColorUtils.setAlphaComponent(base, if (night) 46 else 28),
                    Color.TRANSPARENT)
        }
    }

    /** Rounded gradient chip, used for the space colour swatches. */
    fun chip(base: Int, radiusPx: Float): Drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(shade(base, 1.22f), shade(base, 0.86f))).apply {
        cornerRadius = radiusPx
    }

    fun dot(base: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(base)
    }

    /** Glass tile behind an app icon, at whatever corner radius the grid needs. */
    fun tile(context: Context, radiusPx: Float): Drawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(ContextCompat.getColor(context, R.color.ds_glass))
        setStroke(
                dp(context, 1f).toInt(),
                ContextCompat.getColor(context, R.color.ds_border_hi))
    }

    /** Faint coloured halo behind an app icon. */
    fun halo(base: Int, radiusPx: Float, alpha: Int): Drawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(ColorUtils.setAlphaComponent(base, alpha))
    }

    /** Pill background at a low alpha of [base] — badges, "active" markers. */
    fun softPill(base: Int, radiusPx: Float, alpha: Int): Drawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(ColorUtils.setAlphaComponent(base, alpha))
    }

    /**
     * Translucent surface whose hairline border picks up [base], so a card in a
     * violet space and the same card in an amber space are visibly different.
     */
    fun glassCard(context: Context, base: Int, radiusPx: Float, selected: Boolean = false): Drawable {
        val night = isNight(context)
        val fill = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(if (selected) {
                ColorUtils.setAlphaComponent(base, if (night) 38 else 26)
            } else {
                ContextCompat.getColor(context, R.color.ds_glass)
            })
            setStroke(
                    dp(context, if (selected) 1.6f else 1f).toInt(),
                    ColorUtils.setAlphaComponent(base, when {
                        selected -> if (night) 170 else 150
                        night -> 92
                        else -> 66
                    }))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        return RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(base, 60)), fill, mask)
    }

    /** Foreground colour that stays readable on top of [base]. */
    fun onColor(base: Int): Int =
            if (ColorUtils.calculateLuminance(base) > 0.45) 0xFF14151C.toInt() else Color.WHITE

    fun shade(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255))
}
