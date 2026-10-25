package org.monarch.tactile.compile

import org.monarch.tactile.edit.PinDrawingLayer
import org.monarch.tactile.edit.SimpleJsonParser
import org.monarch.tactile.hit.HitGrid
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.PinRect
import org.monarch.tactile.model.SceneViewport
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.ComponentGraph
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single centerline polyline or closed outline in normalized [0.0, 1.0] source coordinates
 * (origin top-left), matching `LONG_TERM_PLAN.md` §9.
 */
data class StructuredStroke(
    val points: List<NormalizedPoint>,
    val closed: Boolean = false
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

    fun toVectorStroke(salience: Float = 1.0f): VectorStroke = VectorStroke(
        points = points,
        closed = closed,
        salience = salience
    )
}

/**
 * Semantic component with normalized bounding box and 1-pin centerline strokes produced by
 * `gemini-4-argon` (or recorded JSON fixtures), matching `LONG_TERM_PLAN.md` §9.
 */
data class StructuredComponent(
    val id: String,
    val label: String,
    val parentId: String? = null,
    val role: String = "object",
    val box: NormalizedBox,
    val strokes: List<StructuredStroke> = emptyList(),
    val embeddedText: String = "",
    val description: String = ""
)

/**
 * Complete structured tactile graphic model output from Tier 2 (`LONG_TERM_PLAN.md` §9).
 */
data class StructuredTactileGraphic(
    val sceneSummary: String = "",
    val components: List<StructuredComponent>
) {
    fun toComponentHints(): List<ComponentHint> {
        val idMap = LinkedHashMap<String, Int>()
        components.forEachIndexed { idx, comp ->
            idMap[comp.id] = idx + 1
        }
        val childIdsByParent = LinkedHashMap<String, MutableList<Int>>()
        for (comp in components) {
            val pKey = comp.parentId
            val numId = idMap[comp.id] ?: continue
            if (pKey != null && idMap.containsKey(pKey)) {
                childIdsByParent.getOrPut(pKey) { mutableListOf() }.add(numId)
            }
        }
        return components.mapIndexed { idx, comp ->
            val numericId = idx + 1
            ComponentHint(
                id = numericId,
                key = comp.id,
                label = comp.label,
                description = comp.description,
                embeddedText = comp.embeddedText,
                bbox = comp.box,
                parentId = comp.parentId?.let { idMap[it] },
                childIds = childIdsByParent[comp.id] ?: emptyList(),
                role = comp.role,
                strokes = comp.strokes.map { it.toVectorStroke() }
            )
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(
            json: String,
            sourceWidth: Int? = null,
            sourceHeight: Int? = null
        ): StructuredTactileGraphic {
            val parsed = SimpleJsonParser(json).parseValue() as? Map<String, Any?>
                ?: error("Expected JSON object for StructuredTactileGraphic")
            return fromMap(parsed, sourceWidth, sourceHeight)
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(
            root: Map<String, Any?>,
            sourceWidth: Int? = null,
            sourceHeight: Int? = null
        ): StructuredTactileGraphic {
            val summary = (root["sceneSummary"] as? String)
                ?: (root["summary"] as? String)
                ?: ""
            val rawComponents = (root["components"] as? List<Map<String, Any?>>).orEmpty()
            val flattened = ArrayList<StructuredComponent>()

            fun parseBox(rc: Map<String, Any?>): NormalizedBox {
                val boxList = (rc["box"] as? List<Number>)
                if (boxList != null && boxList.size >= 4) {
                    val x0 = boxList[0].toFloat()
                    val y0 = boxList[1].toFloat()
                    val x1 = boxList[2].toFloat()
                    val y1 = boxList[3].toFloat()
                    return if ((x0 > 1.5f || y0 > 1.5f || x1 > 1.5f || y1 > 1.5f) && sourceWidth != null && sourceHeight != null) {
                        NormalizedBox.fromSourcePixels(x0.roundToInt(), y0.roundToInt(), x1.roundToInt(), y1.roundToInt(), sourceWidth, sourceHeight)
                    } else if (x0 > 1.5f || y0 > 1.5f || x1 > 1.5f || y1 > 1.5f) {
                        NormalizedBox(
                            ymin = (y0 / 1000f).coerceIn(0f, 1f),
                            xmin = (x0 / 1000f).coerceIn(0f, 1f),
                            ymax = (y1 / 1000f).coerceIn(0f, 1f),
                            xmax = (x1 / 1000f).coerceIn(0f, 1f)
                        )
                    } else {
                        NormalizedBox(
                            ymin = min(y0, y1).coerceIn(0f, 1f),
                            xmin = min(x0, x1).coerceIn(0f, 1f),
                            ymax = max(y0, y1).coerceIn(0f, 1f),
                            xmax = max(x0, x1).coerceIn(0f, 1f)
                        )
                    }
                }

                val bboxList = (rc["bbox"] as? List<Number>)
                if (bboxList != null && bboxList.size >= 4) {
                    val v0 = bboxList[0].toFloat()
                    val v1 = bboxList[1].toFloat()
                    val v2 = bboxList[2].toFloat()
                    val v3 = bboxList[3].toFloat()
                    return if (v0 > 1.5f || v1 > 1.5f || v2 > 1.5f || v3 > 1.5f) {
                        NormalizedBox.fromGemini1000(v0.roundToInt(), v1.roundToInt(), v2.roundToInt(), v3.roundToInt())
                    } else {
                        NormalizedBox(
                            ymin = v0.coerceIn(0f, 1f),
                            xmin = v1.coerceIn(0f, 1f),
                            ymax = v2.coerceIn(0f, 1f),
                            xmax = v3.coerceIn(0f, 1f)
                        )
                    }
                }
                return NormalizedBox(0f, 0f, 1f, 1f)
            }

            fun parseStrokes(rc: Map<String, Any?>): List<StructuredStroke> {
                val rawStrokes = (rc["strokes"] as? List<Map<String, Any?>>).orEmpty()
                return rawStrokes.mapNotNull { rs ->
                    val rawPts = (rs["points"] as? List<List<Number>>).orEmpty()
                    val pts = rawPts.mapNotNull { pair ->
                        if (pair.size >= 2) {
                            val px = pair[0].toFloat()
                            val py = pair[1].toFloat()
                            val nx = if (px > 1.5f && sourceWidth != null) px / (sourceWidth - 1).coerceAtLeast(1) else px
                            val ny = if (py > 1.5f && sourceHeight != null) py / (sourceHeight - 1).coerceAtLeast(1) else py
                            NormalizedPoint(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
                        } else null
                    }
                    if (pts.isNotEmpty()) {
                        StructuredStroke(
                            points = pts,
                            closed = (rs["closed"] as? Boolean) ?: false
                        )
                    } else null
                }
            }

            var autoIdx = 1
            fun visit(rc: Map<String, Any?>, inheritedParentId: String?) {
                val rawId = rc["id"]?.toString()?.takeIf { it.isNotBlank() } ?: "c${autoIdx++}"
                val label = (rc["label"] as? String)?.trim().orEmpty().ifEmpty { rawId }
                val parentId = (rc["parentId"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (rc["parentId"] as? Number)?.toString()
                    ?: inheritedParentId
                val role = ((rc["role"] as? String)?.trim()?.lowercase()).orEmpty().ifEmpty { "object" }
                val embeddedText = (rc["embeddedText"] as? String)?.trim().orEmpty()
                val description = (rc["description"] as? String)?.trim().orEmpty()
                val box = parseBox(rc)
                val strokes = parseStrokes(rc)

                flattened.add(
                    StructuredComponent(
                        id = rawId,
                        label = label,
                        parentId = parentId,
                        role = role,
                        box = box,
                        strokes = strokes,
                        embeddedText = embeddedText,
                        description = description
                    )
                )

                val children = (rc["children"] as? List<Map<String, Any?>>).orEmpty()
                for (child in children) {
                    visit(child, rawId)
                }
            }

            for (rc in rawComponents) {
                visit(rc, null)
            }

            return StructuredTactileGraphic(
                sceneSummary = summary,
                components = flattened
            )
        }
    }
}

/**
 * Combined output of [StrokeRenderer.render], bundling the rendered [TactileScene] with its
 * pre-stamped [HitGrid] and [ComponentGraph].
 */
data class StrokeRenderResult(
    val scene: TactileScene,
    val hitGrid: HitGrid,
    val graph: ComponentGraph
)

/**
 * Renders a [StructuredTactileGraphic] (`LONG_TERM_PLAN.md` §9) into 1-pin centerline outlines
 * on the 96×35 tactile canvas at any zoom/pan or component isolation viewport:
 * - Strokes are rendered as 1-pin centerlines (never doubled outlines).
 * - `role == "text"` and `role == "speech"` components are touch regions with no pins at 1x.
 * - Smaller children win on overlap in [HitGrid].
 * - Raised-pin fill is strictly `<= 25%` at every zoom level, dropping the smallest / least
 *   important strokes first.
 */
class StrokeRenderer(
    private val densityController: DensityController = DensityController(),
    private val sourceAspectRatio: Float = 666f / 205f
) {
    private data class CandidateStrokePins(
        val componentNumericId: Int,
        val role: String,
        val isRoot: Boolean,
        val isPanelBorder: Boolean,
        val arcLength: Float,
        val pins: IntArray
    )

    fun render(
        graphic: StructuredTactileGraphic,
        viewport: Viewport = Viewport()
    ): StrokeRenderResult {
        val scene = renderScene(graphic, viewport)
        return StrokeRenderResult(
            scene = scene,
            hitGrid = scene.buildHitGrid(haloRadius = 1),
            graph = scene.buildGraph()
        )
    }

    fun renderScene(
        graphic: StructuredTactileGraphic,
        viewport: Viewport = Viewport()
    ): TactileScene {
        val sceneViewport = viewport.toSceneViewport()
        if (graphic.components.isEmpty()) {
            return TactileScene(
                pinGrid = PinGrid(),
                components = emptyList(),
                rootComponentIds = emptyList(),
                viewport = sceneViewport
            )
        }

        // Map string IDs ("p1", "p2", ...) to deterministic integer IDs (1..N)
        val idByKey = LinkedHashMap<String, Int>()
        graphic.components.forEachIndexed { idx, sc ->
            idByKey[sc.id] = idx + 1
        }
        val childIdsByParent = LinkedHashMap<String, MutableList<Int>>()
        for (sc in graphic.components) {
            val pKey = sc.parentId
            val numId = idByKey[sc.id] ?: continue
            if (pKey != null && idByKey.containsKey(pKey)) {
                childIdsByParent.getOrPut(pKey) { mutableListOf() }.add(numId)
            }
        }

        val activeComponents = graphic.components.filter { sc ->
            val iso = viewport.isolatedComponentBox
            iso == null || (
                sc.box.xmax >= iso.xmin &&
                    sc.box.xmin <= iso.xmax &&
                    sc.box.ymax >= iso.ymin &&
                    sc.box.ymin <= iso.ymax
                )
        }

        val isoBox = viewport.isolatedComponentBox
        val visualChildrenInIso = if (isoBox != null) {
            activeComponents.filter { sc ->
                sc.parentId != null &&
                    sc.role.lowercase() !in setOf("text", "speech") &&
                    !(sc.box.xmin == isoBox.xmin && sc.box.xmax == isoBox.xmax && sc.box.ymin == isoBox.ymin && sc.box.ymax == isoBox.ymax)
            }
        } else {
            emptyList()
        }

        val visualUnionBox = if (visualChildrenInIso.isNotEmpty()) {
            NormalizedBox(
                ymin = visualChildrenInIso.minOf { it.box.ymin },
                xmin = visualChildrenInIso.minOf { it.box.xmin },
                ymax = visualChildrenInIso.maxOf { it.box.ymax },
                xmax = visualChildrenInIso.maxOf { it.box.xmax }
            )
        } else null

        fun projectNormX(normX: Float): Int {
            if (isoBox != null && visualUnionBox != null && visualUnionBox.width > 1e-4f && visualUnionBox.height > 1e-4f) {
                val targetRows = 29f
                val uWidthPx = visualUnionBox.width * sourceAspectRatio
                val uHeightPx = visualUnionBox.height
                val targetCols = (targetRows * (uWidthPx / uHeightPx)).coerceIn(14f, 64f)
                val startCol = (PinGrid.WIDTH - targetCols) * 0.5f
                val relX = (normX - visualUnionBox.xmin) / visualUnionBox.width
                return (startCol + relX * targetCols).roundToInt()
            }
            return sceneViewport.normalizedToPinX(normX)
        }

        fun projectNormY(normY: Float): Int {
            if (isoBox != null && visualUnionBox != null && visualUnionBox.width > 1e-4f && visualUnionBox.height > 1e-4f) {
                val targetRows = 29f
                val startRow = 2f
                val relY = (normY - visualUnionBox.ymin) / visualUnionBox.height
                return (startRow + relY * targetRows).roundToInt()
            }
            return sceneViewport.normalizedToPinY(normY)
        }

        fun projectBox(box: NormalizedBox, isRootPanel: Boolean): PinRect {
            if (isoBox != null && visualUnionBox != null) {
                if (isRootPanel) {
                    return PinRect(0, 0, PinGrid.WIDTH - 1, PinGrid.CANVAS_HEIGHT - 1)
                }
                val minX = projectNormX(box.xmin).coerceIn(0, PinGrid.WIDTH - 1)
                val maxX = projectNormX(box.xmax).coerceIn(minX, PinGrid.WIDTH - 1)
                val minY = projectNormY(box.ymin).coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
                val maxY = projectNormY(box.ymax).coerceIn(minY, PinGrid.CANVAS_HEIGHT - 1)
                return PinRect(minX, minY, maxX, maxY)
            }
            return box.toPinRect(sceneViewport)
        }

        val candidateStrokes = ArrayList<CandidateStrokePins>()
        val pinBoxByNumericId = HashMap<Int, PinRect>()
        val baseNormByNumericId = HashMap<Int, List<NormalizedPoint>>()

        for (sc in activeComponents) {
            val numId = idByKey[sc.id] ?: continue
            val roleLower = sc.role.lowercase()
            val isRootPanel = sc.parentId == null && roleLower == "panel"
            val pRect = projectBox(sc.box, isRootPanel)
            pinBoxByNumericId[numId] = pRect

            // Rule: role == "text" or "speech" components are touch regions with NO pins at 1x or isolated panel view
            if (roleLower == "text" || roleLower == "speech") {
                baseNormByNumericId[numId] = emptyList()
                continue
            }

            if (isRootPanel) {
                if (isoBox == null) {
                    val borderPins = VectorStrokeRasterizer.rasterizeRectBorderPins(pRect)
                    candidateStrokes.add(
                        CandidateStrokePins(
                            componentNumericId = numId,
                            role = roleLower,
                            isRoot = true,
                            isPanelBorder = true,
                            arcLength = (sc.box.width + sc.box.height) * 2f,
                            pins = borderPins
                        )
                    )
                }
                baseNormByNumericId[numId] = sc.strokes.flatMap { it.points }
                continue
            }

            // Rasterize each stroke of this component to 1-pin lines
            val allNormPts = ArrayList<NormalizedPoint>()
            for (stroke in sc.strokes) {
                allNormPts.addAll(stroke.points)
                if (stroke.points.isEmpty()) continue
                val rawCanvas = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
                if (stroke.points.size == 1) {
                    val px = projectNormX(stroke.points[0].x)
                    val py = projectNormY(stroke.points[0].y)
                    if (px in 0 until PinGrid.WIDTH && py in 0 until PinGrid.CANVAS_HEIGHT) {
                        rawCanvas[PinGrid.packCanvasCoord(px, py)] = true
                    }
                } else {
                    val count = stroke.points.size
                    val segs = if (stroke.closed && count > 2) count else count - 1
                    for (i in 0 until segs) {
                        val a = stroke.points[i]
                        val b = stroke.points[(i + 1) % count]
                        val x0 = projectNormX(a.x)
                        val y0 = projectNormY(a.y)
                        val x1 = projectNormX(b.x)
                        val y1 = projectNormY(b.y)
                        for ((px, py) in PinDrawingLayer.bresenhamLine(x0, y0, x1, y1)) {
                            if (px in 0 until PinGrid.WIDTH && py in 0 until PinGrid.CANVAS_HEIGHT) {
                                rawCanvas[PinGrid.packCanvasCoord(px, py)] = true
                            }
                        }
                    }
                }

                // Thin individual stroke to guarantee 1-pin centerline (no 2x2 blocks)
                val thinned = thinCanvasMaskTo1Pin(rawCanvas)
                val strokePins = IntArray(thinned.count { it })
                var k = 0
                for (idx in thinned.indices) {
                    if (thinned[idx]) strokePins[k++] = idx
                }
                if (strokePins.isNotEmpty()) {
                    candidateStrokes.add(
                        CandidateStrokePins(
                            componentNumericId = numId,
                            role = roleLower,
                            isRoot = sc.parentId == null,
                            isPanelBorder = false,
                            arcLength = stroke.arcLength().coerceAtLeast(0.001f),
                            pins = strokePins
                        )
                    )
                }
            }
            baseNormByNumericId[numId] = allNormPts
        }

        // Enforce <= 25% fill ceiling by dropping smallest / least-important strokes first
        val maxPins = densityController.maxRaisedPins
        // Sort from highest priority (keep first) to lowest priority (drop first)
        val prioritizedStrokes = candidateStrokes.sortedWith(
            compareByDescending<CandidateStrokePins> { it.isPanelBorder }
                .thenByDescending { strokeRolePriority(it.role, it.isRoot) }
                .thenByDescending { it.arcLength }
        )

        val keptStrokes = ArrayList<CandidateStrokePins>(prioritizedStrokes.size)
        val occupiedCanvas = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
        var occupiedCount = 0

        for (cs in prioritizedStrokes) {
            var newPinsAdded = 0
            for (packed in cs.pins) {
                if (packed in 0 until PinGrid.CANVAS_PIN_COUNT && !occupiedCanvas[packed]) {
                    newPinsAdded++
                }
            }
            if (occupiedCount + newPinsAdded <= maxPins || cs.isPanelBorder) {
                keptStrokes.add(cs)
                for (packed in cs.pins) {
                    if (packed in 0 until PinGrid.CANVAS_PIN_COUNT && !occupiedCanvas[packed]) {
                        occupiedCanvas[packed] = true
                        occupiedCount++
                    }
                }
            }
        }

        // Group kept pins by component and apply component-level 1-pin thinning on non-panel components
        // so adjacent strokes within the same figure never create 2x2 pin blocks
        val pinsByComp = LinkedHashMap<Int, LinkedHashSet<Int>>()
        for (cs in keptStrokes) {
            val set = pinsByComp.getOrPut(cs.componentNumericId) { LinkedHashSet() }
            for (packed in cs.pins) set.add(packed)
        }

        val finalGrid = PinGrid()
        val builtComponents = activeComponents.mapNotNull { sc ->
            val numId = idByKey[sc.id] ?: return@mapNotNull null
            val roleLower = sc.role.lowercase()
            val isRootPanel = sc.parentId == null && roleLower == "panel"
            val rawSet = pinsByComp[numId].orEmpty()

            val finalCompPins = if (!isRootPanel && rawSet.isNotEmpty()) {
                val mask = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
                for (p in rawSet) {
                    if (p in 0 until PinGrid.CANVAS_PIN_COUNT) mask[p] = true
                }
                val thinned = thinCanvasMaskTo1Pin(mask)
                val list = ArrayList<Int>(rawSet.size)
                for (idx in thinned.indices) {
                    if (thinned[idx]) list.add(idx)
                }
                list.toIntArray()
            } else {
                rawSet.toIntArray()
            }

            for (packed in finalCompPins) {
                finalGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
            }

            Component(
                id = numId,
                key = sc.id,
                label = sc.label,
                role = sc.role,
                description = sc.description,
                embeddedText = sc.embeddedText,
                bbox = sc.box,
                pinBox = pinBoxByNumericId[numId] ?: sc.box.toPinRect(sceneViewport),
                parentId = sc.parentId?.let { idByKey[it] },
                childIds = childIdsByParent[sc.id] ?: emptyList(),
                raisedPins = finalCompPins,
                baseNormalizedPins = baseNormByNumericId[numId] ?: emptyList()
            )
        }

        val rawScene = TactileScene(
            pinGrid = finalGrid,
            components = builtComponents,
            rootComponentIds = builtComponents.filter { it.parentId == null }.map { it.id },
            viewport = sceneViewport
        )
        return densityController.enforceLimit(rawScene)
    }

    private fun strokeRolePriority(role: String, isRoot: Boolean): Int {
        if (isRoot) return 100
        return when (role.lowercase()) {
            "panel", "container" -> 90
            "figure", "chamber", "vessel" -> 80
            "object", "valve" -> 60
            "arrow" -> 30
            "other" -> 20
            "text", "speech" -> 0
            else -> 40
        }
    }

    private fun thinCanvasMaskTo1Pin(mask: BooleanArray): BooleanArray {
        val w = PinGrid.WIDTH
        val h = PinGrid.CANVAS_HEIGHT
        val out = mask.copyOf()
        var changed = true
        while (changed) {
            changed = false
            for (y in 0 until h - 1) {
                for (x in 0 until w - 1) {
                    val i00 = y * w + x
                    val i10 = y * w + (x + 1)
                    val i01 = (y + 1) * w + x
                    val i11 = (y + 1) * w + (x + 1)
                    if (out[i00] && out[i10] && out[i01] && out[i11]) {
                        for (cand in intArrayOf(i11, i10, i01, i00)) {
                            val cx = cand % w
                            val cy = cand / w
                            if (canRemoveFrom2x2(out, w, h, cx, cy)) {
                                out[cand] = false
                                changed = true
                                break
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun canRemoveFrom2x2(grid: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        fun g(px: Int, py: Int): Boolean =
            px in 0 until w && py in 0 until h && grid[py * w + px]
        val p2 = g(x, y - 1)
        val p3 = g(x + 1, y - 1)
        val p4 = g(x + 1, y)
        val p5 = g(x + 1, y + 1)
        val p6 = g(x, y + 1)
        val p7 = g(x - 1, y + 1)
        val p8 = g(x - 1, y)
        val p9 = g(x - 1, y - 1)
        val ring = booleanArrayOf(p2, p3, p4, p5, p6, p7, p8, p9)
        var transitions = 0
        for (i in 0 until 8) {
            if (!ring[i] && ring[(i + 1) % 8]) transitions++
        }
        return transitions <= 1
    }
}
