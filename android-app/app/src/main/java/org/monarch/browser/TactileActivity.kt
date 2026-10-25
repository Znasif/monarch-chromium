package org.monarch.browser

import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.humanware.keysoftsdk.selfbrailling.SelfBraillingManager
import com.humanware.keysoftsdk.selfbrailling.widget.SelfBraillingWidget
import org.monarch.tactile.hit.HitGrid
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.ComponentGraph
import org.monarch.tactile.nav.Direction
import kotlin.concurrent.thread

/** The image the browser asked to open (too large for an Intent extra). */
object PendingImage {
    var bytes: ByteArray? = null
    var title: String = ""
}

/**
 * Full-screen tactile viewer: draws the structured graphic on the pins, reads the part
 * under the finger, and navigates the component tree (D-pad moves between siblings,
 * Enter goes into a part, Back comes out, Zoom keys zoom on the focused part).
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
        widget = SelfBraillingWidget(this).also { setContentView(it) }
        widget.onSelfBraillingWidgetListener = object : SelfBraillingWidget.OnSelfBraillingWidgetListener {
            override fun onFocused() = show()
            override fun onSingleTapAtBraillePosition(pointX: Int, pointY: Int) {
                partAt(pointX, pointY)?.let { focus(it) }
            }
            override fun onDoubleTapAtBraillePosition(pointX: Int, pointY: Int) {
                partAt(pointX, pointY)?.let { focus(it, speak = false); enter() }
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
        handler.post(pollTouch)
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

    private val pollTouch = object : Runnable {
        override fun run() {
            val point = runCatching { manager.touchedPinCoordinates }.getOrNull()?.firstOrNull()
            if (point != null) {
                val id = partAt(point.x, point.y) ?: 0
                if (id != 0 && id != touchedId) {
                    touchedId = id
                    vibrate()
                    say(spoken(id), captionText = labelOf(id))
                }
            } else {
                touchedId = 0
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "key $keyCode ${event?.metaState}")
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Direction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Direction.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Direction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Direction.DOWN
            else -> null
        }
        if (direction != null) {
            val step = graph?.stepDirectional(focusId, direction) ?: return true
            if (step.moved) focus(step.toComponentId) else beep()
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { enter(); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!back()) finish(); true }
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> { zoomIn(); true }
            KeyEvent.KEYCODE_ZOOM_OUT, KeyEvent.KEYCODE_MINUS -> { if (!back()) beep(); true }
            else -> super.onKeyDown(keyCode, event)
        }
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
        if (captionText != caption) { caption = captionText; show() }
        widget.announceForAccessibility(text)
    }

    private fun vibrate() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun beep() = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)

    override fun onResume() {
        super.onResume()
        manager.bindService()
        handler.postDelayed({ show() }, 500)
    }

    override fun onStop() {
        handler.removeCallbacks(pollTouch)
        manager.unbindService()
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        if (graphic != null) handler.post(pollTouch)
    }

    companion object {
        private const val TAG = "MonarchTactile"
        private const val POLL_MS = 40L
    }
}
