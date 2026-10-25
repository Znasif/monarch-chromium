package org.monarch.browser

import org.json.JSONObject
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.SceneViewport
import org.monarch.tactile.model.TactileScene
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One component of the structured tactile graphic (plan §9), image-normalized coordinates. */
data class GraphicPart(
    val key: String,
    val label: String,
    val parentKey: String?,
    val role: String,
    val box: NormalizedBox,
    val strokes: List<Pair<List<FloatArray>, Boolean>>,
    val embeddedText: String,
    val description: String,
)

/**
 * Renders a structured tactile graphic into a [TactileScene] for one view box.
 * Strokes become 1-pin lines; text parts are touch regions without pins; the view
 * keeps the image's aspect ratio (pins are on a square pitch).
 */
class GraphicScene(json: String, private val imageWidth: Int, private val imageHeight: Int) {
    val parts: List<GraphicPart> = parse(json)
    private val idByKey = parts.mapIndexed { i, p -> p.key to i + 1 }.toMap()

    /** Box of the whole image, widened to the canvas aspect. */
    val overview: NormalizedBox get() = fitToCanvas(NormalizedBox(0f, 0f, 1f, 1f))

    fun idOf(key: String) = idByKey[key]
    fun partOf(id: Int) = parts.getOrNull(id - 1)

    fun fitToCanvas(box: NormalizedBox, margin: Float = 0.04f): NormalizedBox {
        val canvasAspect = (PinGrid.WIDTH - 1f) / (PinGrid.CANVAS_HEIGHT - 1f)
        var w = box.width * (1 + margin)
        var h = box.height * (1 + margin)
        // compare in source pixels so circles stay round
        val aspect = (w * imageWidth) / (h * imageHeight)
        if (aspect > canvasAspect) h = w * imageWidth / (canvasAspect * imageHeight)
        else w = h * imageHeight * canvasAspect / imageWidth
        val cx = (box.xmin + box.xmax) / 2
        val cy = (box.ymin + box.ymax) / 2
        return NormalizedBox(ymin = cy - h / 2, xmin = cx - w / 2, ymax = cy + h / 2, xmax = cx + w / 2)
    }

    fun render(view: NormalizedBox, maxFill: Float = 0.25f): TactileScene {
        val viewport = SceneViewport(isolatedBox = view)
        val frames = panelFrames(viewport)
        // Smaller parts lose strokes first when the fill limit is exceeded.
        val dropOrder = parts.indices.filter { parts[it].strokes.isNotEmpty() && parts[it].role != "panel" }
            .sortedBy { parts[it].box.width * parts[it].box.height }
        val dropped = HashSet<Int>()
        var pinsByPart: List<IntArray>
        while (true) {
            pinsByPart = parts.mapIndexed { i, p -> if (i in dropped) IntArray(0) else rasterize(p, viewport, frames[i]) }
            val raised = pinsByPart.flatMap { it.asList() }.toSet().size
            if (raised <= maxFill * PinGrid.CANVAS_PIN_COUNT || dropped.size == dropOrder.size) break
            dropped += dropOrder[dropped.size]
        }
        val grid = PinGrid()
        val components = parts.mapIndexed { i, p ->
            pinsByPart[i].forEach { grid.setCanvasPin(PinGrid.unpackX(it), PinGrid.unpackY(it), true) }
            Component(
                id = i + 1,
                label = p.label,
                description = p.description,
                embeddedText = p.embeddedText,
                bbox = p.box,
                pinBox = p.box.toPinRect(viewport),
                parentId = p.parentKey?.let { idByKey[it] },
                childIds = parts.indices.filter { parts[it].parentKey == p.key }.map { it + 1 },
                raisedPins = pinsByPart[i],
                key = p.key,
                role = p.role,
            )
        }
        return TactileScene(
            pinGrid = grid,
            components = components,
            rootComponentIds = components.filter { it.parentId == null }.map { it.id },
            viewport = viewport,
        )
    }

    /**
     * Panels are framed by their box (the model sometimes omits a border), with at
     * least one blank pin column between side-by-side frames.
     */
    private fun panelFrames(viewport: SceneViewport): Map<Int, IntArray> {
        val panels = parts.indices.filter { parts[it].role == "panel" }
        val rects = panels.associateWith {
            val b = parts[it].box
            intArrayOf(
                viewport.normalizedToPinX(b.xmin), viewport.normalizedToPinY(b.ymin),
                viewport.normalizedToPinX(b.xmax), viewport.normalizedToPinY(b.ymax),
            )
        }
        val byLeft = panels.sortedBy { rects[it]!![0] }
        for (k in 0 until byLeft.size - 1) {
            val a = rects[byLeft[k]]!!
            val b = rects[byLeft[k + 1]]!!
            val overlapY = minOf(a[3], b[3]) > maxOf(a[1], b[1])
            if (overlapY && b[0] - a[2] < 2 && a[2] >= b[0] - 2) a[2] = b[0] - 2
        }
        return rects
    }

    private fun isFrame(points: List<FloatArray>, closed: Boolean, box: NormalizedBox): Boolean {
        if (!closed || points.size > 5) return false
        val w = points.maxOf { it[0] } - points.minOf { it[0] }
        val h = points.maxOf { it[1] } - points.minOf { it[1] }
        return w > 0.8f * box.width && h > 0.8f * box.height
    }

    private fun rasterize(part: GraphicPart, viewport: SceneViewport, frame: IntArray?): IntArray {
        if (part.role == "text") return IntArray(0)
        val out = LinkedHashSet<Int>()
        if (frame != null) {
            val (x0, y0, x1, y1) = frame.toList()
            line(out, x0 to y0, x1 to y0); line(out, x1 to y0, x1 to y1)
            line(out, x1 to y1, x0 to y1); line(out, x0 to y1, x0 to y0)
        }
        for ((points, closed) in part.strokes) {
            if (frame != null && isFrame(points, closed, part.box)) continue
            val path = if (closed && points.size > 2) points + listOf(points.first()) else points
            val pins = path.map {
                Pair(viewport.normalizedToExactPinX(it[0]).roundToInt(), viewport.normalizedToExactPinY(it[1]).roundToInt())
            }
            if (pins.size == 1) plot(out, pins[0].first, pins[0].second)
            for (k in 0 until pins.size - 1) line(out, pins[k], pins[k + 1])
        }
        return out.toIntArray()
    }

    private fun line(out: MutableSet<Int>, a: Pair<Int, Int>, b: Pair<Int, Int>) {
        var (x0, y0) = a
        val (x1, y1) = b
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        repeat(max(dx, -dy) + 1) {
            plot(out, x0, y0)
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sx }
            if (e2 <= dx) { err += dx; y0 += sy }
        }
    }

    private fun plot(out: MutableSet<Int>, x: Int, y: Int) {
        if (x in 0 until PinGrid.WIDTH && y in 0 until PinGrid.CANVAS_HEIGHT) out += PinGrid.packCanvasCoord(x, y)
    }

    private fun parse(json: String): List<GraphicPart> {
        val array = JSONObject(json).getJSONArray("components")
        return (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            val b = c.getJSONArray("box")
            val strokes = c.optJSONArray("strokes")
            GraphicPart(
                key = c.getString("id"),
                label = c.optString("label"),
                parentKey = c.optString("parentId").takeIf { !c.isNull("parentId") && it.isNotEmpty() },
                role = c.optString("role", "other"),
                box = NormalizedBox(
                    ymin = b.getDouble(1).toFloat(), xmin = b.getDouble(0).toFloat(),
                    ymax = b.getDouble(3).toFloat(), xmax = b.getDouble(2).toFloat(),
                ),
                strokes = (0 until (strokes?.length() ?: 0)).map { s ->
                    val stroke = strokes!!.getJSONObject(s)
                    val pts = stroke.getJSONArray("points")
                    (0 until pts.length()).map { p ->
                        val pt = pts.getJSONArray(p)
                        floatArrayOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                    } to stroke.optBoolean("closed")
                },
                embeddedText = c.optString("embeddedText"),
                description = c.optString("description"),
            )
        }
    }
}
