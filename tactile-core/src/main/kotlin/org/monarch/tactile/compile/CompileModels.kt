package org.monarch.tactile.compile

import org.monarch.tactile.edit.PinDrawingLayer
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.PinRect
import org.monarch.tactile.model.SceneViewport
import org.monarch.tactile.model.TactileScene
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compiler viewport for vector and centerline re-rasterization across zoom levels (1x to 8x),
 * panning, or component isolation.
 */
data class Viewport(
    val zoom: Float = 1.0f,
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val isolatedComponentBox: NormalizedBox? = null,
    val preserveAspectRatio: Boolean = false
) {
    init {
        require(zoom in 1.0f..8.0f) { "Viewport zoom must be in 1.0..8.0, got $zoom" }
    }

    fun toSceneViewport(): SceneViewport = SceneViewport(
        zoom = zoom,
        panX = panX,
        panY = panY,
        isolatedBox = isolatedComponentBox
    )
}

/**
 * Normalized vector polyline/polygon centerline stroke in [0.0, 1.0] source coordinates.
 */
data class VectorStroke(
    val points: List<NormalizedPoint>,
    val closed: Boolean = false,
    val salience: Float = 1.0f,
    val minZoom: Float = 1.0f
) {
    fun arcLength(): Float {
        if (points.size < 2) return 0f
        var sum = 0f
        for (i in 0 until points.size - 1) {
            sum += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        if (closed && points.size > 2) {
            sum += hypot(points.first().x - points.last().x, points.first().y - points.last().y)
        }
        return sum
    }
}

/**
 * Optional semantic component hint passed into [RasterContourCompiler] or produced by Tier 2.
 */
data class ComponentHint(
    val id: Int,
    val label: String,
    val description: String = "",
    val embeddedText: String = "",
    val bbox: NormalizedBox,
    val parentId: Int? = null,
    val childIds: List<Int> = emptyList(),
    val key: String = "c$id",
    val role: String = "",
    val strokes: List<VectorStroke> = emptyList()
)

/**
 * 8-bit grayscale bitmap (row-major luminance in 0..255, where 0 = black ink, 255 = white paper).
 */
class GrayscaleBitmap(
    val width: Int,
    val height: Int,
    val pixels: ByteArray = ByteArray(width * height) { 255.toByte() }
) {
    init {
        require(width > 0 && height > 0) { "Bitmap dimensions must be positive: ${width}x${height}" }
        require(pixels.size == width * height) {
            "Expected ${width * height} pixels, got ${pixels.size}"
        }
    }

    fun getLuminance(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 255
        return pixels[y * width + x].toInt() and 0xFF
    }

    fun setLuminance(x: Int, y: Int, value: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    fun fill(value: Int) {
        pixels.fill(value.coerceIn(0, 255).toByte())
    }

    /**
     * Draws a solid ink line of [thickness] pixels (e.g. 3-4 px strokes for synthetic comic fixtures).
     */
    fun drawThickLine(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 3, luminance: Int = 0) {
        val pts = PinDrawingLayer.bresenhamLine(x0, y0, x1, y1)
        val rLow = -((thickness - 1) / 2)
        val rHigh = thickness / 2
        for ((px, py) in pts) {
            for (dy in rLow..rHigh) {
                for (dx in rLow..rHigh) {
                    setLuminance(px + dx, py + dy, luminance)
                }
            }
        }
    }

    /**
     * Draws a closed rectangular border of [thickness] pixels.
     */
    fun drawThickRect(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 3, luminance: Int = 0) {
        val left = min(x0, x1)
        val right = max(x0, x1)
        val top = min(y0, y1)
        val bottom = max(y0, y1)
        for (t in 0 until thickness) {
            for (x in left..right) {
                setLuminance(x, top + t, luminance)
                setLuminance(x, bottom - t, luminance)
            }
            for (y in top..bottom) {
                setLuminance(left + t, y, luminance)
                setLuminance(right - t, y, luminance)
            }
        }
    }

    /**
     * Draws a circular outline of [thickness] pixels using midpoint circle steps.
     */
    fun drawThickCircle(cx: Int, cy: Int, radius: Int, thickness: Int = 3, luminance: Int = 0) {
        val steps = max(36, radius * 8)
        var prevX = cx + radius
        var prevY = cy
        for (i in 1..steps) {
            val angle = (2.0 * Math.PI * i) / steps
            val nx = (cx + radius * kotlin.math.cos(angle)).roundToInt()
            val ny = (cy + radius * kotlin.math.sin(angle)).roundToInt()
            drawThickLine(prevX, prevY, nx, ny, thickness, luminance)
            prevX = nx
            prevY = ny
        }
    }

    /**
     * Exports a binary ASCII PBM (P1) representation for human-readable golden diffs.
     */
    fun toPbmAscii(threshold: Int = 128): String {
        val sb = StringBuilder()
        sb.append("P1\n").append(width).append(' ').append(height).append('\n')
        for (y in 0 until height) {
            for (x in 0 until width) {
                sb.append(if (getLuminance(x, y) < threshold) '1' else '0')
                if (x < width - 1) sb.append(' ')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        fun fromPbmAscii(pbm: String): GrayscaleBitmap {
            val tokens = pbm.lineSequence()
                .map { line -> line.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .flatMap { it.split(Regex("\\s+")).asSequence() }
                .toList()
            require(tokens.size >= 3 && tokens[0] == "P1") { "Expected ASCII P1 PBM header" }
            val w = tokens[1].toInt()
            val h = tokens[2].toInt()
            val bmp = GrayscaleBitmap(w, h)
            var idx = 3
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (idx < tokens.size) {
                        val bit = tokens[idx++]
                        bmp.setLuminance(x, y, if (bit == "1") 0 else 255)
                    }
                }
            }
            return bmp
        }
    }
}

/**
 * Enforces the tactile pin density ceiling (raised-pin fill <= 25% across rows 0..34)
 * by dropping lowest-salience / smallest detail components or interior strokes first,
 * without breaking primary panel/container or figure outlines.
 */
class DensityController(
    val maxFillRatio: Float = 0.25f,
    val targetOverviewRatio: Float = 0.20f
) {
    val maxRaisedPins: Int = floor(PinGrid.CANVAS_PIN_COUNT * maxFillRatio).toInt()

    fun enforceLimit(scene: TactileScene): TactileScene {
        if (scene.pinGrid.canvasRaisedCount() <= maxRaisedPins) {
            return scene
        }

        // Rank components from lowest priority (first to shed pins) to highest priority (last to shed):
        // 1. Decorative / minor roles ("other", "arrow", "object") shed before "figure" and "panel".
        // 2. Smaller bounding-box perimeter sheds before larger structural outlines.
        val sortedByDropOrder = scene.components.sortedWith(
            compareBy<Component> { rolePriority(it.role, it.parentId == null) }
                .thenBy { it.bbox.width + it.bbox.height }
                .thenByDescending { it.id }
        )

        val droppedComponentIds = HashSet<Int>()
        val keptPinsByComponent = LinkedHashMap<Int, IntArray>()
        for (comp in scene.components) {
            keptPinsByComponent[comp.id] = comp.raisedPins
        }

        fun totalDistinctPins(): Int {
            val mask = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
            var count = 0
            for (pins in keptPinsByComponent.values) {
                for (packed in pins) {
                    if (packed in 0 until PinGrid.CANVAS_PIN_COUNT && !mask[packed]) {
                        mask[packed] = true
                        count++
                    }
                }
            }
            return count
        }

        // Step 1: Drop whole minor components (never dropping top-level panels if avoidable)
        for (candidate in sortedByDropOrder) {
            if (totalDistinctPins() <= maxRaisedPins) break
            if (candidate.parentId != null && candidate.raisedPins.isNotEmpty()) {
                keptPinsByComponent[candidate.id] = IntArray(0)
                droppedComponentIds.add(candidate.id)
            }
        }

        // Step 2: If still above maxRaisedPins, trim excess trailing pins from largest remaining sets
        if (totalDistinctPins() > maxRaisedPins) {
            val globalSeen = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
            var budget = maxRaisedPins
            // Keep high-priority components first
            val highToLow = sortedByDropOrder.asReversed()
            for (comp in highToLow) {
                val original = keptPinsByComponent[comp.id] ?: IntArray(0)
                if (original.isEmpty()) continue
                val trimmed = IntArray(original.size)
                var tCount = 0
                for (packed in original) {
                    if (packed !in 0 until PinGrid.CANVAS_PIN_COUNT) continue
                    if (globalSeen[packed]) {
                        trimmed[tCount++] = packed
                    } else if (budget > 0) {
                        globalSeen[packed] = true
                        budget--
                        trimmed[tCount++] = packed
                    }
                }
                keptPinsByComponent[comp.id] = trimmed.copyOf(tCount)
            }
        }

        val newGrid = PinGrid()
        val updatedComponents = scene.components.map { comp ->
            val pins = keptPinsByComponent[comp.id] ?: IntArray(0)
            for (packed in pins) {
                newGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
            }
            comp.copy(raisedPins = pins)
        }

        return scene.copy(
            pinGrid = newGrid,
            components = updatedComponents
        )
    }

    private fun rolePriority(role: String, isRoot: Boolean): Int {
        if (isRoot) return 100
        return when (role.lowercase()) {
            "panel", "container" -> 90
            "figure", "chamber", "vessel" -> 80
            "object", "valve" -> 50
            "arrow", "tail", "squiggle" -> 20
            "speech", "text" -> 10
            else -> 40
        }
    }
}

/**
 * Shared 1-pin vector stroke rasterizer used by [SvgCompiler], [RasterContourCompiler], and Tier 2.
 */
object VectorStrokeRasterizer {
    fun rasterizeStrokeToPins(
        stroke: VectorStroke,
        viewport: SceneViewport,
        clipRect: PinRect? = null
    ): List<Int> {
        if (stroke.points.isEmpty()) return emptyList()
        if (viewport.zoom < stroke.minZoom && viewport.isolatedBox == null) return emptyList()

        val packed = ArrayList<Int>(stroke.points.size * 4)
        if (stroke.points.size == 1) {
            val px = viewport.normalizedToPinX(stroke.points[0].x)
            val py = viewport.normalizedToPinY(stroke.points[0].y)
            if (isInsideCanvasAndClip(px, py, clipRect)) {
                packed.add(PinGrid.packCanvasCoord(px, py))
            }
            return packed
        }

        val count = stroke.points.size
        val segCount = if (stroke.closed && count > 2) count else count - 1
        for (i in 0 until segCount) {
            val a = stroke.points[i]
            val b = stroke.points[(i + 1) % count]
            val x0 = viewport.normalizedToPinX(a.x)
            val y0 = viewport.normalizedToPinY(a.y)
            val x1 = viewport.normalizedToPinX(b.x)
            val y1 = viewport.normalizedToPinY(b.y)
            for ((px, py) in PinDrawingLayer.bresenhamLine(x0, y0, x1, y1)) {
                if (isInsideCanvasAndClip(px, py, clipRect)) {
                    packed.add(PinGrid.packCanvasCoord(px, py))
                }
            }
        }
        return packed.distinct()
    }

    fun rasterizeRectBorderPins(rect: PinRect): IntArray {
        if (rect.width <= 0 || rect.height <= 0) return IntArray(0)
        val set = LinkedHashSet<Int>((rect.width + rect.height) * 2)
        val minX = rect.minX.coerceIn(0, PinGrid.WIDTH - 1)
        val maxX = rect.maxX.coerceIn(0, PinGrid.WIDTH - 1)
        val minY = rect.minY.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
        val maxY = rect.maxY.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
        for (x in minX..maxX) {
            set.add(PinGrid.packCanvasCoord(x, minY))
            set.add(PinGrid.packCanvasCoord(x, maxY))
        }
        for (y in minY..maxY) {
            set.add(PinGrid.packCanvasCoord(minX, y))
            set.add(PinGrid.packCanvasCoord(maxX, y))
        }
        return set.toIntArray()
    }

    private fun isInsideCanvasAndClip(x: Int, y: Int, clip: PinRect?): Boolean {
        if (x !in 0 until PinGrid.WIDTH || y !in 0 until PinGrid.CANVAS_HEIGHT) return false
        if (clip != null && !clip.contains(x, y)) return false
        return true
    }
}
