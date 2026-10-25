package org.monarch.tactile.compile

import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.TactileScene
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 2D affine transformation matrix:
 * [ a  c  e ]
 * [ b  d  f ]
 * [ 0  0  1 ]
 */
internal data class Affine2D(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0
) {
    fun transformX(x: Double, y: Double): Double = a * x + c * y + e
    fun transformY(x: Double, y: Double): Double = b * x + d * y + f

    fun preConcatenate(other: Affine2D): Affine2D {
        return Affine2D(
            a = a * other.a + c * other.b,
            b = b * other.a + d * other.b,
            c = a * other.c + c * other.d,
            d = b * other.c + d * other.d,
            e = a * other.e + c * other.f + e,
            f = b * other.e + d * other.f + f
        )
    }

    companion object {
        val IDENTITY = Affine2D()

        fun parse(transformAttr: String?): Affine2D {
            if (transformAttr.isNullOrBlank()) return IDENTITY
            val regex = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
            var current = IDENTITY
            for (match in regex.findAll(transformAttr)) {
                val fn = match.groupValues[1].lowercase()
                val args = match.groupValues[2]
                    .trim()
                    .split(Regex("[\\s,]+"))
                    .filter { it.isNotEmpty() }
                    .mapNotNull { it.toDoubleOrNull() }
                if (args.isEmpty()) continue
                val next = when (fn) {
                    "translate" -> {
                        val tx = args.getOrElse(0) { 0.0 }
                        val ty = args.getOrElse(1) { 0.0 }
                        Affine2D(1.0, 0.0, 0.0, 1.0, tx, ty)
                    }
                    "scale" -> {
                        val sx = args.getOrElse(0) { 1.0 }
                        val sy = args.getOrElse(1) { sx }
                        Affine2D(sx, 0.0, 0.0, sy, 0.0, 0.0)
                    }
                    "rotate" -> {
                        val rad = args.getOrElse(0) { 0.0 } * PI / 180.0
                        val cosA = cos(rad)
                        val sinA = sin(rad)
                        val rot = Affine2D(cosA, sinA, -sinA, cosA, 0.0, 0.0)
                        if (args.size >= 3) {
                            val cx = args[1]
                            val cy = args[2]
                            Affine2D(1.0, 0.0, 0.0, 1.0, cx, cy)
                                .preConcatenate(rot)
                                .preConcatenate(Affine2D(1.0, 0.0, 0.0, 1.0, -cx, -cy))
                        } else {
                            rot
                        }
                    }
                    "skewx" -> {
                        val rad = args[0] * PI / 180.0
                        Affine2D(1.0, 0.0, tan(rad), 1.0, 0.0, 0.0)
                    }
                    "skewy" -> {
                        val rad = args[0] * PI / 180.0
                        Affine2D(1.0, tan(rad), 0.0, 1.0, 0.0, 0.0)
                    }
                    "matrix" -> {
                        if (args.size >= 6) {
                            Affine2D(args[0], args[1], args[2], args[3], args[4], args[5])
                        } else {
                            IDENTITY
                        }
                    }
                    else -> IDENTITY
                }
                current = current.preConcatenate(next)
            }
            return current
        }
    }
}

/**
 * Tier-1 SVG vector compiler.
 *
 * Parses `<svg>` geometry (`path`, `rect`, `circle`, `ellipse`, `line`, `polyline`, `polygon`,
 * nested `<g>` groups, `<switch systemLanguage>`, `viewBox`, and `transform` matrices) directly
 * into 1-pin outlines fitted to the 96x35 canvas, grouped into navigable [Component]s.
 */
class SvgCompiler(
    private val densityController: DensityController = DensityController(),
    private val preferredLanguage: String = "en"
) {
    private data class ViewBoxSpec(
        val minX: Double,
        val minY: Double,
        val width: Double,
        val height: Double
    ) {
        fun normalizeX(x: Double): Float {
            if (width <= 0.0) return 0f
            return ((x - minX) / width).toFloat().coerceIn(0f, 1f)
        }

        fun normalizeY(y: Double): Float {
            if (height <= 0.0) return 0f
            return ((y - minY) / height).toFloat().coerceIn(0f, 1f)
        }
    }

    private data class ParsedSvgNode(
        val id: Int,
        val key: String,
        val label: String,
        val role: String,
        val description: String,
        val embeddedText: String,
        val parentId: Int?,
        val strokes: MutableList<VectorStroke> = mutableListOf(),
        val childIds: MutableList<Int> = mutableListOf(),
        var explicitBox: NormalizedBox? = null
    )

    fun compile(svgXml: String, viewport: Viewport = Viewport()): TactileScene {
        val doc = parseXmlDocument(svgXml)
        val svgRoot = doc.documentElement
        val viewBox = parseViewBox(svgRoot)

        val nodes = mutableListOf<ParsedSvgNode>()
        val rootIds = mutableListOf<Int>()
        var nextId = 1

        fun allocateId(): Int = nextId++

        fun walkElement(
            el: Element,
            parentTransform: Affine2D,
            parentComponentId: Int?
        ) {
            val tag = el.tagName.substringAfterLast(':').lowercase()
            if (tag in setOf("defs", "style", "script", "clippath", "mask", "lineargradient", "radialgradient", "marker", " title", "desc")) {
                return
            }
            if (el.getAttribute("display").equals("none", ignoreCase = true) ||
                el.getAttribute("visibility").equals("hidden", ignoreCase = true)
            ) {
                return
            }

            val localTransform = Affine2D.parse(el.getAttribute("transform"))
            val currentTransform = if (tag == "svg") parentTransform else parentTransform.preConcatenate(localTransform)

            if (tag == "switch") {
                val chosen = selectSwitchChild(el)
                if (chosen != null) {
                    walkElement(chosen, currentTransform, parentComponentId)
                }
                return
            }

            if (tag == "svg") {
                val children = childElements(el)
                for (child in children) {
                    walkElement(child, currentTransform, parentComponentId)
                }
                return
            }

            if (tag == "g") {
                val gLabel = extractElementLabel(el)
                val childEls = childElements(el)
                val shapeChildren = childEls.filter { isShapeTag(it.tagName.substringAfterLast(':').lowercase()) }
                val groupChildren = childEls.filter { it.tagName.substringAfterLast(':').lowercase() in setOf("g", "switch") }

                if (gLabel != null || (parentComponentId == null && (shapeChildren.isNotEmpty() || groupChildren.isNotEmpty()))) {
                    val compId = allocateId()
                    val key = el.getAttribute("id").ifBlank { "g$compId" }
                    val role = el.getAttribute("data-role").ifBlank {
                        if (parentComponentId == null) "panel" else "figure"
                    }
                    val embedded = collectChildTextContent(el)
                    val node = ParsedSvgNode(
                        id = compId,
                        key = key,
                        label = gLabel ?: "Group $compId",
                        role = role,
                        description = extractChildTextByTag(el, "desc"),
                        embeddedText = embedded,
                        parentId = parentComponentId
                    )
                    nodes.add(node)
                    if (parentComponentId == null) {
                        rootIds.add(compId)
                    } else {
                        nodes.firstOrNull { it.id == parentComponentId }?.childIds?.add(compId)
                    }

                    for (child in childEls) {
                        val cTag = child.tagName.substringAfterLast(':').lowercase()
                        if (cTag in setOf("title", "desc", "text")) continue
                        val childHasOwnLabel = extractElementLabel(child) != null
                        if (isShapeTag(cTag) && !childHasOwnLabel) {
                            val strokeList = extractStrokesFromShape(child, cTag, currentTransform, viewBox)
                            node.strokes.addAll(strokeList)
                        } else {
                            walkElement(child, currentTransform, compId)
                        }
                    }
                } else {
                    for (child in childEls) {
                        walkElement(child, currentTransform, parentComponentId)
                    }
                }
                return
            }

            if (isShapeTag(tag)) {
                val strokes = extractStrokesFromShape(el, tag, currentTransform, viewBox)
                if (strokes.isEmpty()) return
                val compId = allocateId()
                val key = el.getAttribute("id").ifBlank { "$tag$compId" }
                val label = extractElementLabel(el) ?: "${tag.replaceFirstChar { it.uppercase() }} $compId"
                val role = el.getAttribute("data-role").ifBlank {
                    if (parentComponentId == null) "figure" else "object"
                }
                val node = ParsedSvgNode(
                    id = compId,
                    key = key,
                    label = label,
                    role = role,
                    description = extractChildTextByTag(el, "desc"),
                    embeddedText = "",
                    parentId = parentComponentId,
                    strokes = strokes.toMutableList()
                )
                nodes.add(node)
                if (parentComponentId == null) {
                    rootIds.add(compId)
                } else {
                    nodes.firstOrNull { it.id == parentComponentId }?.childIds?.add(compId)
                }
            }
        }

        walkElement(svgRoot, Affine2D.IDENTITY, null)

        val sceneViewport = viewport.toSceneViewport()
        val builtComponents = mutableListOf<Component>()

        // Compute bounding boxes bottom-up so parent groups encompass child components
        val nodesById = nodes.associateBy { it.id }

        fun computeNodeBox(node: ParsedSvgNode): NormalizedBox {
            node.explicitBox?.let { return it }
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY

            for (stroke in node.strokes) {
                for (pt in stroke.points) {
                    minX = min(minX, pt.x)
                    minY = min(minY, pt.y)
                    maxX = max(maxX, pt.x)
                    maxY = max(maxY, pt.y)
                }
            }
            for (cid in node.childIds) {
                val childNode = nodesById[cid] ?: continue
                val cBox = computeNodeBox(childNode)
                minX = min(minX, cBox.xmin)
                minY = min(minY, cBox.ymin)
                maxX = max(maxX, cBox.xmax)
                maxY = max(maxY, cBox.ymax)
            }

            val resolved = if (minX.isFinite() && minY.isFinite()) {
                NormalizedBox(
                    ymin = minY.coerceIn(0f, 1f),
                    xmin = minX.coerceIn(0f, 1f),
                    ymax = maxY.coerceIn(0f, 1f),
                    xmax = maxX.coerceIn(0f, 1f)
                )
            } else {
                NormalizedBox(0f, 0f, 1f, 1f)
            }
            node.explicitBox = resolved
            return resolved
        }

        val pinGrid = PinGrid()
        for (node in nodes) {
            val bbox = computeNodeBox(node)
            val pinBox = bbox.toPinRect(sceneViewport)
            val packedPins = LinkedHashSet<Int>()
            val baseNormPoints = ArrayList<NormalizedPoint>()

            if (node.role.lowercase() !in setOf("speech", "text")) {
                for (stroke in node.strokes) {
                    for (pt in stroke.points) {
                        baseNormPoints.add(pt)
                    }
                    val strokePins = VectorStrokeRasterizer.rasterizeStrokeToPins(stroke, sceneViewport)
                    packedPins.addAll(strokePins)
                }
            }

            for (packed in packedPins) {
                pinGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
            }

            builtComponents.add(
                Component(
                    id = node.id,
                    key = node.key,
                    label = node.label,
                    role = node.role,
                    description = node.description,
                    embeddedText = node.embeddedText,
                    bbox = bbox,
                    pinBox = pinBox,
                    parentId = node.parentId,
                    childIds = node.childIds.toList(),
                    raisedPins = packedPins.toIntArray(),
                    baseNormalizedPins = baseNormPoints
                )
            )
        }

        // Remove empty group containers that have neither strokes, children, nor text
        val validIds = nodes.filter {
            it.strokes.isNotEmpty() || it.childIds.isNotEmpty() || it.embeddedText.isNotEmpty()
        }.map { it.id }.toSet()
        val finalComponents = builtComponents.filter { it.id in validIds }.map { comp ->
            comp.copy(childIds = comp.childIds.filter { it in validIds })
        }
        val finalRoots = rootIds.filter { it in validIds }

        val rawScene = TactileScene(
            pinGrid = pinGrid,
            components = finalComponents,
            rootComponentIds = finalRoots,
            viewport = sceneViewport
        )

        return densityController.enforceLimit(rawScene)
    }

    private fun parseXmlDocument(svgXml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(svgXml.trim())))
    }

    private fun parseViewBox(svgEl: Element): ViewBoxSpec {
        val vbAttr = svgEl.getAttribute("viewBox").trim()
        if (vbAttr.isNotEmpty()) {
            val parts = vbAttr.split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
            if (parts.size >= 4 && parts[2] > 0.0 && parts[3] > 0.0) {
                return ViewBoxSpec(parts[0], parts[1], parts[2], parts[3])
            }
        }
        val w = parseSvgLength(svgEl.getAttribute("width"), 100.0)
        val h = parseSvgLength(svgEl.getAttribute("height"), 100.0)
        return ViewBoxSpec(0.0, 0.0, if (w > 0.0) w else 100.0, if (h > 0.0) h else 100.0)
    }

    private fun parseSvgLength(raw: String?, defaultVal: Double): Double {
        if (raw.isNullOrBlank()) return defaultVal
        val cleaned = raw.trim().replace(Regex("[a-zA-Z%]+$"), "")
        return cleaned.toDoubleOrNull() ?: defaultVal
    }

    private fun selectSwitchChild(switchEl: Element): Element? {
        val children = childElements(switchEl)
        var fallback: Element? = null
        for (child in children) {
            val sysLang = child.getAttribute("systemLanguage").trim().lowercase()
            if (sysLang.isEmpty()) {
                if (fallback == null) fallback = child
            } else {
                val langs = sysLang.split(Regex("[\\s,]+")).map { it.substringBefore('-') }
                if (preferredLanguage.lowercase() in langs) {
                    return child
                }
            }
        }
        return fallback ?: children.firstOrNull()
    }

    private fun isShapeTag(tag: String): Boolean {
        return tag in setOf("rect", "circle", "ellipse", "line", "polyline", "polygon", "path")
    }

    private fun extractElementLabel(el: Element): String? {
        val aria = el.getAttribute("aria-label").trim()
        if (aria.isNotEmpty()) return aria

        val dataLabel = el.getAttribute("data-label").trim()
        if (dataLabel.isNotEmpty()) return dataLabel

        val titleText = extractChildTextByTag(el, "title")
        if (titleText.isNotEmpty()) return titleText

        val directText = collectDirectTextChildren(el)
        if (directText.isNotEmpty()) return directText

        val idAttr = el.getAttribute("id").trim()
        if (idAttr.isNotEmpty() && !idAttr.matches(Regex("^(path|rect|circle|line|g|svg|layer)\\d*$", RegexOption.IGNORE_CASE))) {
            return idAttr.replace('_', ' ').replace('-', ' ')
        }
        return null
    }

    private fun extractChildTextByTag(el: Element, tagName: String): String {
        val children = childElements(el)
        for (c in children) {
            if (c.tagName.substringAfterLast(':').equals(tagName, ignoreCase = true)) {
                val t = c.textContent?.trim().orEmpty()
                if (t.isNotEmpty()) return t
            }
        }
        return ""
    }

    private fun collectDirectTextChildren(el: Element): String {
        val texts = mutableListOf<String>()
        for (c in childElements(el)) {
            val cTag = c.tagName.substringAfterLast(':').lowercase()
            if (cTag == "text") {
                val t = c.textContent?.trim().orEmpty()
                if (t.isNotEmpty()) texts.add(t)
            } else if (cTag == "switch") {
                val chosen = selectSwitchChild(c)
                if (chosen != null) {
                    val t = chosen.textContent?.trim().orEmpty()
                    if (t.isNotEmpty()) texts.add(t)
                }
            }
        }
        return texts.joinToString(" ")
    }

    private fun collectChildTextContent(el: Element): String {
        return collectDirectTextChildren(el)
    }

    private fun childElements(parent: Node): List<Element> {
        val list = mutableListOf<Element>()
        val nl = parent.childNodes
        for (i in 0 until nl.length) {
            val item = nl.item(i)
            if (item is Element) list.add(item)
        }
        return list
    }

    private fun extractStrokesFromShape(
        el: Element,
        tag: String,
        transform: Affine2D,
        viewBox: ViewBoxSpec
    ): List<VectorStroke> {
        val isDashedLeader = el.getAttribute("stroke-dasharray").isNotBlank() ||
            el.getAttribute("style").contains("stroke-dasharray", ignoreCase = true)
        val minZoom = if (isDashedLeader) 4.0f else 1.0f
        val salience = if (isDashedLeader) 0.2f else 1.0f

        fun toNorm(x: Double, y: Double): NormalizedPoint {
            val tx = transform.transformX(x, y)
            val ty = transform.transformY(x, y)
            return NormalizedPoint(viewBox.normalizeX(tx), viewBox.normalizeY(ty))
        }

        return when (tag) {
            "rect" -> {
                val x = parseSvgLength(el.getAttribute("x"), 0.0)
                val y = parseSvgLength(el.getAttribute("y"), 0.0)
                val w = parseSvgLength(el.getAttribute("width"), 0.0)
                val h = parseSvgLength(el.getAttribute("height"), 0.0)
                if (w <= 0.0 || h <= 0.0) return emptyList()
                val pts = listOf(
                    toNorm(x, y),
                    toNorm(x + w, y),
                    toNorm(x + w, y + h),
                    toNorm(x, y + h)
                )
                listOf(VectorStroke(points = pts, closed = true, salience = salience, minZoom = minZoom))
            }
            "circle" -> {
                val cx = parseSvgLength(el.getAttribute("cx"), 0.0)
                val cy = parseSvgLength(el.getAttribute("cy"), 0.0)
                val r = parseSvgLength(el.getAttribute("r"), 0.0)
                if (r <= 0.0) return emptyList()
                listOf(sampleEllipse(cx, cy, r, r, ::toNorm, salience, minZoom))
            }
            "ellipse" -> {
                val cx = parseSvgLength(el.getAttribute("cx"), 0.0)
                val cy = parseSvgLength(el.getAttribute("cy"), 0.0)
                val rx = parseSvgLength(el.getAttribute("rx"), 0.0)
                val ry = parseSvgLength(el.getAttribute("ry"), 0.0)
                if (rx <= 0.0 || ry <= 0.0) return emptyList()
                listOf(sampleEllipse(cx, cy, rx, ry, ::toNorm, salience, minZoom))
            }
            "line" -> {
                val x1 = parseSvgLength(el.getAttribute("x1"), 0.0)
                val y1 = parseSvgLength(el.getAttribute("y1"), 0.0)
                val x2 = parseSvgLength(el.getAttribute("x2"), 0.0)
                val y2 = parseSvgLength(el.getAttribute("y2"), 0.0)
                listOf(
                    VectorStroke(
                        points = listOf(toNorm(x1, y1), toNorm(x2, y2)),
                        closed = false,
                        salience = salience,
                        minZoom = minZoom
                    )
                )
            }
            "polyline", "polygon" -> {
                val rawPts = parseCoordinatePairs(el.getAttribute("points"))
                if (rawPts.isEmpty()) return emptyList()
                listOf(
                    VectorStroke(
                        points = rawPts.map { (px, py) -> toNorm(px, py) },
                        closed = (tag == "polygon"),
                        salience = salience,
                        minZoom = minZoom
                    )
                )
            }
            "path" -> {
                val d = el.getAttribute("d")
                parseSvgPathData(d, ::toNorm, salience, minZoom)
            }
            else -> emptyList()
        }
    }

    private fun sampleEllipse(
        cx: Double,
        cy: Double,
        rx: Double,
        ry: Double,
        toNorm: (Double, Double) -> NormalizedPoint,
        salience: Float,
        minZoom: Float
    ): VectorStroke {
        val steps = 48
        val pts = ArrayList<NormalizedPoint>(steps)
        for (i in 0 until steps) {
            val theta = (2.0 * PI * i) / steps
            val x = cx + rx * cos(theta)
            val y = cy + ry * sin(theta)
            pts.add(toNorm(x, y))
        }
        return VectorStroke(points = pts, closed = true, salience = salience, minZoom = minZoom)
    }

    private fun parseCoordinatePairs(pointsAttr: String?): List<Pair<Double, Double>> {
        if (pointsAttr.isNullOrBlank()) return emptyList()
        val nums = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
            .findAll(pointsAttr)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        val pairs = ArrayList<Pair<Double, Double>>(nums.size / 2)
        var i = 0
        while (i + 1 < nums.size) {
            pairs.add(Pair(nums[i], nums[i + 1]))
            i += 2
        }
        return pairs
    }

    private fun parseSvgPathData(
        d: String?,
        toNorm: (Double, Double) -> NormalizedPoint,
        salience: Float,
        minZoom: Float
    ): List<VectorStroke> {
        if (d.isNullOrBlank()) return emptyList()
        val tokenRegex = Regex("([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)")
        val tokens = tokenRegex.findAll(d).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()

        val strokes = mutableListOf<VectorStroke>()
        var currentPoints = mutableListOf<NormalizedPoint>()
        var curX = 0.0
        var curY = 0.0
        var startX = 0.0
        var startY = 0.0
        var prevCtrlX = 0.0
        var prevCtrlY = 0.0
        var prevCmd = ' '

        fun flushStroke(closed: Boolean) {
            if (currentPoints.size >= 2) {
                strokes.add(
                    VectorStroke(
                        points = currentPoints.toList(),
                        closed = closed,
                        salience = salience,
                        minZoom = minZoom
                    )
                )
            }
            currentPoints = mutableListOf()
        }

        var idx = 0
        var cmd = 'M'

        fun hasNextNumber(): Boolean {
            if (idx >= tokens.size) return false
            val t = tokens[idx]
            return t.length != 1 || !t[0].isLetter()
        }

        fun nextNumber(): Double {
            return tokens[idx++].toDouble()
        }

        while (idx < tokens.size) {
            val tok = tokens[idx]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                idx++
            }

            when (cmd) {
                'M', 'm' -> {
                    flushStroke(false)
                    if (!hasNextNumber()) continue
                    val nx = nextNumber()
                    val ny = nextNumber()
                    curX = if (cmd == 'm') curX + nx else nx
                    curY = if (cmd == 'm') curY + ny else ny
                    startX = curX
                    startY = curY
                    currentPoints.add(toNorm(curX, curY))
                    prevCtrlX = curX
                    prevCtrlY = curY
                    prevCmd = cmd
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    while (hasNextNumber()) {
                        val nx = nextNumber()
                        val ny = nextNumber()
                        curX = if (cmd == 'l') curX + nx else nx
                        curY = if (cmd == 'l') curY + ny else ny
                        currentPoints.add(toNorm(curX, curY))
                        prevCtrlX = curX
                        prevCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'H', 'h' -> {
                    while (hasNextNumber()) {
                        val nx = nextNumber()
                        curX = if (cmd == 'h') curX + nx else nx
                        currentPoints.add(toNorm(curX, curY))
                        prevCtrlX = curX
                        prevCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'V', 'v' -> {
                    while (hasNextNumber()) {
                        val ny = nextNumber()
                        curY = if (cmd == 'v') curY + ny else ny
                        currentPoints.add(toNorm(curX, curY))
                        prevCtrlX = curX
                        prevCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'C', 'c' -> {
                    while (hasNextNumber()) {
                        val x1 = if (cmd == 'c') curX + nextNumber() else nextNumber()
                        val y1 = if (cmd == 'c') curY + nextNumber() else nextNumber()
                        val x2 = if (cmd == 'c') curX + nextNumber() else nextNumber()
                        val y2 = if (cmd == 'c') curY + nextNumber() else nextNumber()
                        val x = if (cmd == 'c') curX + nextNumber() else nextNumber()
                        val y = if (cmd == 'c') curY + nextNumber() else nextNumber()
                        sampleCubicBezier(curX, curY, x1, y1, x2, y2, x, y, currentPoints, toNorm)
                        curX = x
                        curY = y
                        prevCtrlX = x2
                        prevCtrlY = y2
                        prevCmd = cmd
                    }
                }
                'S', 's' -> {
                    while (hasNextNumber()) {
                        val x1 = if (prevCmd in listOf('C', 'c', 'S', 's')) 2.0 * curX - prevCtrlX else curX
                        val y1 = if (prevCmd in listOf('C', 'c', 'S', 's')) 2.0 * curY - prevCtrlY else curY
                        val x2 = if (cmd == 's') curX + nextNumber() else nextNumber()
                        val y2 = if (cmd == 's') curY + nextNumber() else nextNumber()
                        val x = if (cmd == 's') curX + nextNumber() else nextNumber()
                        val y = if (cmd == 's') curY + nextNumber() else nextNumber()
                        sampleCubicBezier(curX, curY, x1, y1, x2, y2, x, y, currentPoints, toNorm)
                        curX = x
                        curY = y
                        prevCtrlX = x2
                        prevCtrlY = y2
                        prevCmd = cmd
                    }
                }
                'Q', 'q' -> {
                    while (hasNextNumber()) {
                        val x1 = if (cmd == 'q') curX + nextNumber() else nextNumber()
                        val y1 = if (cmd == 'q') curY + nextNumber() else nextNumber()
                        val x = if (cmd == 'q') curX + nextNumber() else nextNumber()
                        val y = if (cmd == 'q') curY + nextNumber() else nextNumber()
                        sampleQuadraticBezier(curX, curY, x1, y1, x, y, currentPoints, toNorm)
                        curX = x
                        curY = y
                        prevCtrlX = x1
                        prevCtrlY = y1
                        prevCmd = cmd
                    }
                }
                'T', 't' -> {
                    while (hasNextNumber()) {
                        val x1 = if (prevCmd in listOf('Q', 'q', 'T', 't')) 2.0 * curX - prevCtrlX else curX
                        val y1 = if (prevCmd in listOf('Q', 'q', 'T', 't')) 2.0 * curY - prevCtrlY else curY
                        val x = if (cmd == 't') curX + nextNumber() else nextNumber()
                        val y = if (cmd == 't') curY + nextNumber() else nextNumber()
                        sampleQuadraticBezier(curX, curY, x1, y1, x, y, currentPoints, toNorm)
                        curX = x
                        curY = y
                        prevCtrlX = x1
                        prevCtrlY = y1
                        prevCmd = cmd
                    }
                }
                'A', 'a' -> {
                    while (hasNextNumber()) {
                        val rx = abs(nextNumber())
                        val ry = abs(nextNumber())
                        val xAxisRot = nextNumber()
                        val largeArc = nextNumber() != 0.0
                        val sweep = nextNumber() != 0.0
                        val x = if (cmd == 'a') curX + nextNumber() else nextNumber()
                        val y = if (cmd == 'a') curY + nextNumber() else nextNumber()
                        sampleArc(curX, curY, rx, ry, xAxisRot, largeArc, sweep, x, y, currentPoints, toNorm)
                        curX = x
                        curY = y
                        prevCtrlX = curX
                        prevCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'Z', 'z' -> {
                    curX = startX
                    curY = startY
                    flushStroke(true)
                    prevCtrlX = curX
                    prevCtrlY = curY
                    prevCmd = cmd
                }
                else -> {
                    idx++
                }
            }
        }
        flushStroke(false)
        return strokes
    }

    private fun sampleCubicBezier(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
        out: MutableList<NormalizedPoint>,
        toNorm: (Double, Double) -> NormalizedPoint
    ) {
        val steps = 16
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val u = 1.0 - t
            val bx = u * u * u * x0 + 3.0 * u * u * t * x1 + 3.0 * u * t * t * x2 + t * t * t * x3
            val by = u * u * u * y0 + 3.0 * u * u * t * y1 + 3.0 * u * t * t * y2 + t * t * t * y3
            out.add(toNorm(bx, by))
        }
    }

    private fun sampleQuadraticBezier(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        out: MutableList<NormalizedPoint>,
        toNorm: (Double, Double) -> NormalizedPoint
    ) {
        val steps = 12
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val u = 1.0 - t
            val bx = u * u * x0 + 2.0 * u * t * x1 + t * t * x2
            val by = u * u * y0 + 2.0 * u * t * y1 + t * t * y2
            out.add(toNorm(bx, by))
        }
    }

    private fun sampleArc(
        x0: Double,
        y0: Double,
        rxIn: Double,
        ryIn: Double,
        xAxisRotationDeg: Double,
        largeArcFlag: Boolean,
        sweepFlag: Boolean,
        x: Double,
        y: Double,
        out: MutableList<NormalizedPoint>,
        toNorm: (Double, Double) -> NormalizedPoint
    ) {
        if ((x0 == x && y0 == y) || rxIn == 0.0 || ryIn == 0.0) {
            out.add(toNorm(x, y))
            return
        }
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        val phi = xAxisRotationDeg * PI / 180.0
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        var rxSq = rx * rx
        var rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p

        val radiiCheck = x1pSq / rxSq + y1pSq / rySq
        if (radiiCheck > 1.0) {
            val scale = sqrt(radiiCheck)
            rx *= scale
            ry *= scale
            rxSq = rx * rx
            rySq = ry * ry
        }

        val sign = if (largeArcFlag == sweepFlag) -1.0 else 1.0
        val num = (rxSq * rySq - rxSq * y1pSq - rySq * x1pSq).coerceAtLeast(0.0)
        val den = rxSq * y1pSq + rySq * x1pSq
        val coef = sign * sqrt(if (den > 0.0) num / den else 0.0)
        val cxp = coef * ((rx * y1p) / ry)
        val cyp = coef * (-(ry * x1p) / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0

        fun vecAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = hypot(ux, uy) * hypot(vx, vy)
            val cosA = (if (len > 0.0) dot / len else 1.0).coerceIn(-1.0, 1.0)
            val ang = acos(cosA)
            return if (ux * vy - uy * vx < 0.0) -ang else ang
        }

        val theta1 = vecAngle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = vecAngle(
            (x1p - cxp) / rx,
            (y1p - cyp) / ry,
            (-x1p - cxp) / rx,
            (-y1p - cyp) / ry
        )
        if (!sweepFlag && dTheta > 0.0) dTheta -= 2.0 * PI
        if (sweepFlag && dTheta < 0.0) dTheta += 2.0 * PI

        val steps = max(8, ceil(abs(dTheta) / (PI / 12.0)).toInt())
        for (i in 1..steps) {
            val theta = theta1 + (dTheta * i) / steps
            val cosT = cos(theta)
            val sinT = sin(theta)
            val px = cosPhi * rx * cosT - sinPhi * ry * sinT + cx
            val py = sinPhi * rx * cosT + cosPhi * ry * sinT + cy
            out.add(toNorm(px, py))
        }
    }
}
