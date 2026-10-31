package dev.clipvault.app.securepaste

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.min

/**
 * A non-interactive visual backing for the two native IME navigation glyphs.
 *
 * We do NOT recreate or handle Samsung/Android's keyboard switcher and dismiss buttons.
 * On Android builds that draw the IME nav bar in a separate/opaque surface this optional
 * backing may not be visible; the native icons remain usable in every case.
 */
internal class ImeNavPillsView(
    context: Context,
    private val accent: Int,
    private val surface: Int,
) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(155, Color.red(surface), Color.green(surface), Color.blue(surface))
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
    private val area = RectF()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        // Do not consume or alter WindowInsets dispatched to the platform's nav frame.
        setOnApplyWindowInsetsListener { _, insets ->
            postInvalidateOnAnimation()
            insets
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || width == 0 || height == 0) return
        val insets = rootWindowInsets ?: return
        if (!insets.isVisible(WindowInsets.Type.navigationBars()) &&
            !insets.isVisible(WindowInsets.Type.captionBar())) return
        // In 3-button mode the gesture region is typically absent. Do not draw
        // decorative ovals under unfamiliar navigation layouts.
        if (insets.getInsets(WindowInsets.Type.systemGestures()).bottom < dp(8f)) return
        // This View is a child of the system-owned IME nav frame itself. The nav
        // inset can be smaller than this frame on One UI, so use the actual
        // frame's measured height when centering the background ovals.
        val navHeight = height.toFloat()
        if (navHeight < dp(20f) || navHeight > dp(110f)) return

        val geometry = ImeNavPillGeometry.forNavigationBar(
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            navHeightPx = navHeight,
            pillWidthPx = dp(54f),
            pillHeightPx = min(dp(34f), navHeight * 0.62f),
        )
        for (pill in geometry) {
            area.set(pill.left, pill.top, pill.right, pill.bottom)
            canvas.drawRoundRect(area, area.height() / 2, area.height() / 2, backgroundPaint)
            canvas.drawRoundRect(area, area.height() / 2, area.height() / 2, outlinePaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * Fractional centers are based on the two observed One UI native IME glyphs, not
 * on fake view controls. This geometry never affects the nav icon hit targets.
 */
internal object ImeNavPillGeometry {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun forNavigationBar(
        widthPx: Float,
        heightPx: Float,
        navHeightPx: Float,
        pillWidthPx: Float,
        pillHeightPx: Float,
    ): List<Bounds> {
        if (widthPx <= 0f || heightPx <= 0f || navHeightPx <= 0f ||
            pillWidthPx <= 0f || pillHeightPx <= 0f) return emptyList()
        val leftCenter = widthPx * 0.137f
        val rightCenter = widthPx * 0.863f
        val centerY = heightPx - navHeightPx + navHeightPx * 0.35f
        val halfWidth = pillWidthPx / 2
        val halfHeight = pillHeightPx / 2
        if (leftCenter - halfWidth < 0f || rightCenter + halfWidth > widthPx ||
            leftCenter + halfWidth >= rightCenter - halfWidth ||
            centerY - halfHeight < heightPx - navHeightPx ||
            centerY + halfHeight > heightPx) return emptyList()
        return listOf(
            Bounds(leftCenter - halfWidth, centerY - halfHeight,
                leftCenter + halfWidth, centerY + halfHeight),
            Bounds(rightCenter - halfWidth, centerY - halfHeight,
                rightCenter + halfWidth, centerY + halfHeight),
        )
    }
}
