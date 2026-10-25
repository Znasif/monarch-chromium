package org.monarch.browser

import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.humanware.keysoftsdk.selfbrailling.SelfBraillingManager
import com.humanware.keysoftsdk.selfbrailling.widget.SelfBraillingWidget
import org.monarch.tactile.hit.HitGrid
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.ComponentGraph
import kotlin.concurrent.thread

/** The image the browser asked to open (too large for an Intent extra). */
object PendingImage {
    var bytes: ByteArray? = null
    var title: String = ""
}

/**
 * Full-screen tactile viewer. The canvas never changes while you feel it; only speech
 * and the Braille caption follow the finger (KeySoft touch polled as in MonarchIO's
 * MapFingerTracker).
 *  - finger + double-press of the Action button: select the part under the finger;
 *    the same again on the selected part opens it
 *  - left D-pad: previous/next part; right D-pad (Shift + arrow): pan when zoomed
 *  - Enter (dot 8): open; Back: out; Zoom keys: zoom on the selected part
 */
class TactileActivity : AppCompatActivity() {
    private lateinit var manager: SelfBraillingManager
    private lateinit var widget: SelfBraillingWidget
    private val handler = Handler(Looper.getMainLooper())
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }

    private var graphic: GraphicScene? = null
    private val views = ArrayDeque<NormalizedBox>()
    private var scene: TactileScene? = null
    private var hits: HitGrid? = null
    private var graph: ComponentGraph? = null
    private var focusId = 0
    private var touchedId = 0
    private var caption = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = SelfBraillingManager(this).apply { bindService() }
        widget = SelfBraillingWidget(this).also { setContentView(it); it.isFocusableInTouchMode = true }
        widget.onSelfBraillingWidgetListener = object : SelfBraillingWidget.OnSelfBraillingWidgetListener {
            override fun onFocused() = show()
            override fun onSingleTapAtBraillePosition(pointX: Int, pointY: Int) {
                Log.d(TAG, "single tap $pointX,$pointY (ignored)")
            }
            override fun onDoubleTapAtBraillePosition(pointX: Int, pointY: Int) {
                // KeySoft reports the point 3 pins right of the fingertip.
                val id = partAt(pointX + FINGER_X_CORRECTION, pointY)
                Log.d(TAG, "action double-press $pointX,$pointY -> $id")
                when {
                    id == null -> beep()
                    id == focusId -> enter()
                    else -> focus(id)
                }
            }
        }

        val bytes = PendingImage.bytes ?: return finish()
        val title = PendingImage.title
        say("Loading tactile graphic. $title")
        thread {
            val result = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                GraphicScene(GraphicStore(this).load(bytes, title), options.outWidth, options.outHeight)
            }
            runOnUiThread {
                result.onSuccess { opened(it) }.onFailure {
                    Log.e(TAG, "graphic failed", it)
                    say("Could not make a tactile graphic: ${it.message}")
                }
            }
        }
    }

    private fun opened(g: GraphicScene) {
        graphic = g
        views.clear()
        views.addLast(g.overview)
        render()
        focusId = scene!!.rootComponentIds.firstOrNull() ?: 0
        caption = labelOf(focusId)
        show()
        val roots = scene!!.rootComponentIds.size
        say("${PendingImage.title}. $roots ${if (roots == 1) "part" else "parts"}. Explore with your finger. ${labelOf(focusId)}")
        startTracking()
    }

    private fun render() {
        val g = graphic ?: return
        scene = g.render(views.last()).also {
            hits = it.buildHitGrid()
            graph = it.buildGraph()
        }
        show()
    }

    /** Sends the canvas plus the Braille caption to the pins and the on-screen widget. */
    private fun show() {
        val grid = scene?.pinGrid?.copy() ?: return
        Braille.writeCaption(grid, caption)
        val dots = grid.toByteGrid()
        runCatching { manager.displayDots(dots) }.onFailure { Log.w(TAG, "displayDots", it) }
        widget.refresh(dots)
    }

    @Volatile private var tracking = false
    private var lastFinger: android.graphics.Point? = null

    /** Polls KeySoft's pin coordinates off the UI thread (a binder call) and follows one finger. */
    private fun startTracking() {
        if (tracking) return
        tracking = true
        thread(isDaemon = true, name = "FingerPoll") {
            while (tracking) {
                val started = android.os.SystemClock.uptimeMillis()
                val points = runCatching { manager.touchedPinCoordinates }.getOrNull()
                    ?.filter { it.x in 0 until 96 && it.y in 0 until CANVAS_ROWS }
                if (points != null) {
                    val last = lastFinger
                    // Keep following the same finger when a second hand comes down.
                    val finger = if (last == null) points.firstOrNull()
                        else points.minByOrNull { (it.x - last.x) * (it.x - last.x) + (it.y - last.y) * (it.y - last.y) }
                    lastFinger = finger
                    handler.post { if (tracking) onFinger(finger) }
                }
                val sleep = POLL_MS - (android.os.SystemClock.uptimeMillis() - started)
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun onFinger(point: android.graphics.Point?) {
        val id = point?.let { partAt(it.x, it.y) } ?: 0
        if (id == touchedId) return
        touchedId = id
        if (id == 0) return  // open space and lifting are silent
        Log.d(TAG, "touch ${point!!.x},${point.y} -> $id")
        say(spoken(id))
        handler.removeCallbacks(captionUpdate)
        handler.postDelayed(captionUpdate, CAPTION_DELAY_MS)
    }

    /** The caption follows the finger once it rests, so the pins are not resent on every move. */
    private val captionUpdate = Runnable { if (touchedId != 0) setCaption(labelOf(touchedId)) }

    private fun partAt(x: Int, y: Int): Int? = hits?.lookup(x, y)?.takeIf { graphic?.partOf(it) != null }

    private fun focus(id: Int, speak: Boolean = true) {
        focusId = id
        if (speak) say(spoken(id), captionText = labelOf(id))
    }

    private fun enter() {
        val g = graphic ?: return
        val part = g.partOf(focusId) ?: return
        val step = graph?.stepEnter(focusId) ?: return
        if (!step.moved) {
            say(listOf(part.label, part.description, part.embeddedText).filter { it.isNotBlank() }.joinToString(". "))
            return
        }
        views.addLast(g.fitToCanvas(part.box))
        render()
        focus(step.toComponentId)
    }

    private fun back(): Boolean {
        if (views.size <= 1) return false
        views.removeLast()
        val parent = graph?.stepBack(focusId)?.takeIf { it.moved }?.toComponentId ?: focusId
        render()
        focus(parent)
        return true
    }

    private fun zoomIn() {
        val g = graphic ?: return
        val view = views.last()
        val part = g.partOf(focusId)
        val cx = part?.let { (it.box.xmin + it.box.xmax) / 2 } ?: ((view.xmin + view.xmax) / 2)
        val cy = part?.let { (it.box.ymin + it.box.ymax) / 2 } ?: ((view.ymin + view.ymax) / 2)
        val w = view.width / 4; val h = view.height / 4
        views.addLast(g.fitToCanvas(NormalizedBox(ymin = cy - h, xmin = cx - w, ymax = cy + h, xmax = cx + w), margin = 0f))
        render()
        say("Zoom ${1 shl (views.size - 1)} times")
    }

    private var pendingShift = false
    private val clearPendingShift = Runnable { pendingShift = false }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        Log.d(TAG, "key ${KeyEvent.keyCodeToString(keyCode)} action=${event.action} meta=${event.metaState}")
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            // The right D-pad sends Shift just before its arrow.
            if (event.action == KeyEvent.ACTION_DOWN) {
                pendingShift = true
                handler.removeCallbacks(clearPendingShift)
                handler.postDelayed(clearPendingShift, 300)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val pan = pendingShift || event.isShiftPressed
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pan) pan(-1, 0) else stepSibling(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pan) pan(1, 0) else stepSibling(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> if (pan) pan(0, -1) else stepSibling(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (pan) pan(0, 1) else stepSibling(1)
            else -> null
        }
        if (handled != null) { pendingShift = false; return true }
        return onKey(keyCode) || super.dispatchKeyEvent(event)
    }

    private fun onKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { enter(); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!back()) finish(); true }
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> { zoomIn(); true }
            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_MINUS -> { if (!back()) beep(); true }
            else -> false
        }
    }

    /** Moves the zoomed view by a quarter of its size. */
    private fun pan(dx: Int, dy: Int) {
        val g = graphic ?: return
        if (views.size <= 1) { say("Zoom in to pan"); return }
        val v = views.removeLast()
        val ox = dx * v.width / 4; val oy = dy * v.height / 4
        views.addLast(NormalizedBox(ymin = v.ymin + oy, xmin = v.xmin + ox, ymax = v.ymax + oy, xmax = v.xmax + ox))
        render()
    }

    /** Previous/next part among the focused part's siblings, in reading order. */
    private fun stepSibling(delta: Int) {
        val g = graph ?: return
        val parent = scene?.componentById(focusId)?.parentId
        val siblings = if (parent == null) g.rootIds else g.orderedChildrenOf(parent)
        val next = siblings.getOrNull(siblings.indexOf(focusId) + delta)
        if (next == null) beep() else focus(next)
    }

    private fun labelOf(id: Int) = graphic?.partOf(id)?.label ?: ""

    private fun spoken(id: Int): String {
        val part = graphic?.partOf(id) ?: return ""
        val children = scene?.componentById(id)?.childIds?.size ?: 0
        return buildString {
            append(part.label)
            if (part.embeddedText.isNotBlank()) append(": ").append(part.embeddedText)
            if (children > 0) append(". $children parts, Enter to open")
        }
    }

    private fun say(text: String, captionText: String = caption) {
        setCaption(captionText)
        widget.announceForAccessibility(text)
    }

    /** Only the caption rows differ, so the canvas pins stay as they are. */
    private fun setCaption(text: String) {
        if (text != caption) { caption = text; show() }
    }

    private fun beep() = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)

    override fun onResume() {
        super.onResume()
        manager.bindService()
        handler.postDelayed({ show() }, 500)
    }

    override fun onStop() {
        tracking = false
        manager.unbindService()
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        if (graphic != null) startTracking()
    }

    companion object {
        private const val TAG = "MonarchTactile"
        private const val POLL_MS = 25L
        private const val CANVAS_ROWS = 35
        private const val CAPTION_DELAY_MS = 600L
        private const val FINGER_X_CORRECTION = -3
    }
}
