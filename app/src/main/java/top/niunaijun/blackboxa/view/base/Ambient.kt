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
 * The accent system.
 *
 * Each space owns a colour, and that colour is how you recognise where you are.
 * It is applied as an **accent**, not as a skin: a monogram, an indicator, the
 * tint under a selected row, a wash behind the header you would only notice if
 * it were missing. Everything structural — cards, sheets, rows — is built from
 * the neutral surface ladder in `colors.xml`, so eight spaces side by side read
 * as one product rather than eight themes.
 *
 * The rules this file exists to enforce:
 *
 * - **Depth comes from luminance**, not outlines. A hairline is for separating
 *   two surfaces that share a tone, and nothing else.
 * - **Accent never carries text.** Foreground stays on the neutral content
 *   tokens, so contrast does not depend on which colour a space happens to have.
 * - **No gradient does work that a solid could do.** The one gradient left is the
 *   header wash, at an alpha low enough to read as light rather than as paint.
 *
 * Everything is a plain [GradientDrawable]: no blur, no bitmaps, no layers that
 * cost a redraw. This has to stay cheap on a Moto G50.
 */
object Ambient {

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    )

    /**
     * Height of the header wash. Shorter than the old full-bleed glow on purpose:
     * a wash that reaches the content competes with it.
     */
    const val GLOW_HEIGHT_DP = 220f

    /** Alpha ceiling for the header wash. Deliberately barely there. */
    private const val WASH_ALPHA = 34

    /** Alpha for a tonal container filled with the accent. */
    private const val TONAL_ALPHA = 30

    /**
     * How much accent a selected surface takes.
     *
     * Measured on device rather than reasoned about: at 0.26 the current space
     * became a saturated block that outshouted the seven neutral rows beside it,
     * which is the exact failure this redesign exists to remove. A selected row
     * needs to be *found*, not to dominate — the tint plus a hairline plus the
     * word "Atual" already make it unmistakable.
     */
    private const val SELECTED_BLEND = 0.09f

    private fun surface(context: Context, id: Int) = ContextCompat.getColor(context, id)

    /** Mixes [over] into [under] by [fraction], keeping the result opaque. */
    fun blend(under: Int, over: Int, fraction: Float): Int =
        ColorUtils.blendARGB(under, over, fraction.coerceIn(0f, 1f))

    /**
     * The ambient wash behind a screen header.
     *
     * A vertical fade from a trace of the space colour down to the background.
     * Vertical rather than radial because a radial hotspot behind a title reads
     * as a spotlight; a fade reads as light coming from somewhere off-screen.
     */
    fun glow(context: Context, base: Int): Drawable {
        val bg = surface(context, R.color.ds_bg)
        val top = ColorUtils.setAlphaComponent(base, WASH_ALPHA)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.compositeColors(top, bg),
                ColorUtils.compositeColors(ColorUtils.setAlphaComponent(base, WASH_ALPHA / 3), bg),
                bg
            )
        )
    }

    /**
     * A tonal container in the space colour — the background of a monogram, a
     * status pill, a selected chip. Solid, low saturation, never gradient.
     */
    fun chip(base: Int, radiusPx: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(ColorUtils.setAlphaComponent(base, TONAL_ALPHA))
    }

    /** The small solid dot that marks a space. The one place colour is at full strength. */
    fun dot(base: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(base)
    }

    /** A neutral raised surface. Carries no accent at all — most things are this. */
    fun tile(context: Context, radiusPx: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(surface(context, R.color.ds_surface_2))
    }

    /**
     * A ring of the space colour. Used to mark selection on something round,
     * where a fill would swamp whatever sits inside it.
     */
    fun halo(base: Int, radiusPx: Float, alpha: Int): Drawable = GradientDrawable().apply {
        shape = if (radiusPx <= 0f) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (radiusPx > 0f) cornerRadius = radiusPx
        setColor(Color.TRANSPARENT)
        setStroke(2, ColorUtils.setAlphaComponent(base, alpha.coerceIn(0, 255)))
    }

    /** A tonal pill: metadata, counts, quiet status. */
    fun softPill(base: Int, radiusPx: Float, alpha: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(ColorUtils.setAlphaComponent(base, alpha.coerceIn(0, 255)))
    }

    /**
     * The standard card.
     *
     * Named for the glass surface it used to draw; it is a solid surface now.
     * Unselected it is pure neutral — the space colour appears only through
     * whatever the card *contains*. Selected, it takes a trace of the accent and
     * a hairline, which is enough to be unmistakable without a coloured outline
     * around every item in the list.
     */
    fun glassCard(
        context: Context,
        base: Int,
        radiusPx: Float,
        selected: Boolean = false
    ): Drawable {
        val fill = if (selected) {
            blend(surface(context, R.color.ds_surface_2), base, SELECTED_BLEND)
        } else {
            surface(context, R.color.ds_surface_2)
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (selected) {
                setStroke(
                    dp(context, 1f).toInt(),
                    ColorUtils.setAlphaComponent(base, 110)
                )
            }
        }
    }

    /** Wraps any drawable in the platform ripple, so touch feedback is consistent. */
    fun pressable(context: Context, content: Drawable, radiusPx: Float): Drawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(surface(context, R.color.ds_ripple)),
            content,
            mask
        )
    }

    /**
     * Foreground for text drawn directly on [base] at full strength.
     *
     * Only for the rare element that is genuinely filled with the space colour.
     * Everywhere else the accent sits behind neutral text at low alpha, which is
     * why contrast does not need this.
     */
    fun onColor(base: Int): Int =
        if (ColorUtils.calculateLuminance(base) > 0.45) Color.parseColor("#0A0B0E")
        else Color.WHITE

    fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    /**
     * Keeps a space colour legible as a foreground on dark surfaces.
     *
     * A user can pick a colour that is beautiful as a dot and unreadable as a
     * letter. This lifts only what needs lifting, so the chosen colour is still
     * recognisably itself.
     */
    fun readable(context: Context, base: Int): Int {
        if (!isNight(context)) return base
        var color = base
        var guard = 0
        while (ColorUtils.calculateLuminance(color) < 0.35 && guard < 12) {
            color = blend(color, Color.WHITE, 0.12f)
            guard++
        }
        return color
    }
}
