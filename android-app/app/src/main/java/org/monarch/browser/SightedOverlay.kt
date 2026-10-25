package org.monarch.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.TactileScene

/**
 * What a sighted viewer (HDMI, scrcpy) sees while the pins show a graphic, as in
 * MonarchTempest's TouchOverlayView: the real picture under the same view box, the raised
 * pins as translucent dots, the selected part, the finger, and the caption in print.
 * It is invisible to accessibility; the pin widget underneath keeps KeySoft's gestures.
 */
class SightedOverlay(context: Context) : View(context) {
    var image: Bitmap? = null
    var view: NormalizedBox? = null
    var scene: TactileScene? = null
    var focusId = 0
    var finger: Pair<Int, Int>? = null
    var caption = ""
    var spoken = ""

    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 220, 0, 0) }
    private val caption1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; isFakeBoldText = true }
    private val caption2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 30f }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 90, 255); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 170, 0); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 170 }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val cell = width / PinGrid.WIDTH.toFloat()
        val v = view ?: return
        // Pin (x, y) has its centre at ((x + .5) * cell, (y + .5) * cell); normalized image
        // coordinates map to pins as SceneViewport does with isolatedBox.
        fun sx(nx: Float) = ((nx - v.xmin) / v.width * (PinGrid.WIDTH - 1) + 0.5f) * cell
        fun sy(ny: Float) = ((ny - v.ymin) / v.height * (PinGrid.CANVAS_HEIGHT - 1) + 0.5f) * cell
        val canvasRect = RectF(0f, 0f, width.toFloat(), PinGrid.CANVAS_HEIGHT * cell)

        canvas.save()
        canvas.clipRect(canvasRect)
        image?.let { canvas.drawBitmap(it, null, RectF(sx(0f), sy(0f), sx(1f), sy(1f)), imagePaint) }
        scene?.let { s ->
            for (y in 0 until PinGrid.CANVAS_HEIGHT) for (x in 0 until PinGrid.WIDTH) {
                if (s.pinGrid.getPin(x, y)) canvas.drawCircle((x + .5f) * cell, (y + .5f) * cell, cell * 0.28f, pin)
            }
            s.componentById(focusId)?.bbox?.let { b -> canvas.drawRect(sx(b.xmin), sy(b.ymin), sx(b.xmax), sy(b.ymax), focus) }
        }
        finger?.let { (x, y) -> canvas.drawCircle((x + .5f) * cell, (y + .5f) * cell, cell * 1.4f, fingerPaint) }
        canvas.restore()

        // Caption rows 36-39 in print, with what was last spoken under it.
        val top = (PinGrid.CAPTION_START_ROW) * cell
        canvas.drawText(caption, 16f, top + 46f, caption1)
        canvas.drawText(spoken.take(140), 16f, top + 90f, caption2)
    }
}
