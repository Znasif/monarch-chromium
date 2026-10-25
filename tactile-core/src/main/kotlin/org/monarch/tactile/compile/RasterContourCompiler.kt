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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tier-1 on-device raster tactile compiler.
 *
 * Converts bitmap line drawings, diagrams, and illustrations into uncluttered 1-pin centerlines
 * on the 96x35 tactile canvas:
 * - Uses adaptive foreground extraction + fast sparse Zhang-Suen morphological skeletonization
 *   so 3-4 px (or thicker) ink strokes become single 1-pin centerlines, never doubled edges.
 * - Suppresses fine dialogue lettering and tiny steam/speckle noise at 1x overview.
 * - Re-rasterizes from source skeleton centerlines across zoom/pan viewports (1x to 8x) and
 *   isolated component views.
 */
class RasterContourCompiler(
    private val densityController: DensityController = DensityController()
) {
    private data class SkeletonBlob(
        val id: Int,
        val pixels: IntArray, // packed (y * width + x)
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val bboxWidth: Int get() = maxX - minX + 1
        val bboxHeight: Int get() = maxY - minY + 1
    }

    fun compile(
        bitmap: GrayscaleBitmap,
        hints: List<ComponentHint> = emptyList(),
        viewport: Viewport = Viewport()
    ): TactileScene {
        val w = bitmap.width
        val h = bitmap.height
        val sceneViewport = viewport.toSceneViewport()

        // 1. Extract binary foreground mask (ink strokes or adaptive gradient contours)
        val binaryMask = extractForegroundMask(bitmap)

        // 2. Thin thick strokes (e.g. 3-4 px) to 1-px centerlines in source space via sparse Zhang-Suen
        val skeletonMask = sparseZhangSuenThin(binaryMask, w, h)

        val maxW = (w - 1).coerceAtLeast(1).toFloat()
        val maxH = (h - 1).coerceAtLeast(1).toFloat()

        // Small isolated glyph threshold at 1x overview (suppresses hand-lettered text & tiny steam squiggles)
        val isOverview1x = viewport.zoom <= 1.01f && viewport.isolatedComponentBox == null
        val glyphMaxDimX = max(3, (w * 0.022f).roundToInt()) // ~14 px on 666 px width
        val glyphMaxDimY = max(3, (h * 0.070f).roundToInt()) // ~14 px on 205 px height

        if (hints.isEmpty()) {
            val blobs = extractConnectedBlobs(skeletonMask, w, h)
            return compileUnhinted(w, h, maxW, maxH, skeletonMask, blobs, isOverview1x, glyphMaxDimX, glyphMaxDimY, sceneViewport)
        }

        // Partition hints into panels, speech/text zones, and visual child components
        val hintById = hints.associateBy { it.id }
        val panelHints = hints.filter { it.role.equals("panel", ignoreCase = true) || (it.parentId == null && it.childIds.isNotEmpty()) }
        val speechHints = hints.filter { it.role.lowercase() in setOf("speech", "text") }

        // Build clean skeleton mask with speech lettering and panel-border halo excluded for interior figures
        val interiorSkeleton = skeletonMask.copyOf()

        // Clear any pixel outside top-level panel boxes if panel hints exist ("backgroundPins": "none")
        if (panelHints.isNotEmpty()) {
            val panelPxRects = panelHints.map { ph ->
                val x0 = (ph.bbox.xmin * maxW).roundToInt().coerceIn(0, w - 1)
                val x1 = (ph.bbox.xmax * maxW).roundToInt().coerceIn(0, w - 1)
                val y0 = (ph.bbox.ymin * maxH).roundToInt().coerceIn(0, h - 1)
                val y1 = (ph.bbox.ymax * maxH).roundToInt().coerceIn(0, h - 1)
                intArrayOf(x0, y0, x1, y1)
            }
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    if (!interiorSkeleton[rowOff + x]) continue
                    var insideAnyPanel = false
                    for (pr in panelPxRects) {
                        if (x in pr[0]..pr[2] && y in pr[1]..pr[3]) {
                            insideAnyPanel = true
                            break
                        }
                    }
                    if (!insideAnyPanel) {
                        interiorSkeleton[rowOff + x] = false
                    }
                }
            }

            // Strip panel border pixels from interiorSkeleton so panel borders aren't duplicated on children
            val borderMargin = 5
            for (pr in panelPxRects) {
                val x0 = pr[0]
                val y0 = pr[1]
                val x1 = pr[2]
                val y1 = pr[3]
                for (y in y0..y1) {
                    val rowOff = y * w
                    for (x in x0..(x0 + borderMargin).coerceAtMost(x1)) interiorSkeleton[rowOff + x] = false
                    for (x in (x1 - borderMargin).coerceAtLeast(x0)..x1) interiorSkeleton[rowOff + x] = false
                }
                for (x in x0..x1) {
                    for (y in y0..(y0 + borderMargin).coerceAtMost(y1)) interiorSkeleton[y * w + x] = false
                    for (y in (y1 - borderMargin).coerceAtLeast(y0)..y1) interiorSkeleton[y * w + x] = false
                }
            }
        }

        // Clear pixels inside speech/text zones ("textAsPins": "none")
        for (sh in speechHints) {
            val x0 = (sh.bbox.xmin * maxW).roundToInt().coerceIn(0, w - 1)
            val x1 = (sh.bbox.xmax * maxW).roundToInt().coerceIn(0, w - 1)
            val y0 = (sh.bbox.ymin * maxH).roundToInt().coerceIn(0, h - 1)
            val y1 = (sh.bbox.ymax * maxH).roundToInt().coerceIn(0, h - 1)
            for (y in y0..y1) {
                val rowOff = y * w
                for (x in x0..x1) {
                    interiorSkeleton[rowOff + x] = false
                }
            }
        }

        // Suppress small isolated lettering/steam blobs at 1x overview
        if (isOverview1x) {
            val interiorBlobs = extractConnectedBlobs(interiorSkeleton, w, h)
            for (blob in interiorBlobs) {
                if (blob.pixels.size <= 18 && blob.bboxWidth <= glyphMaxDimX && blob.bboxHeight <= glyphMaxDimY) {
                    for (idx in blob.pixels) {
                        interiorSkeleton[idx] = false
                    }
                }
            }
        }

        val pinGrid = PinGrid()
        val builtComponents = ArrayList<Component>(hints.size)
        val isolatedBox = viewport.isolatedComponentBox

        for (hint in hints) {
            // If isolating a single panel/component, skip unrelated panels and their children
            if (isolatedBox != null) {
                val overlapsIsolated = hint.bbox.iou(isolatedBox) > 0.05f ||
                    isolatedBox.containsBox(hint.bbox, tolerance = 0.02f) ||
                    hint.bbox.containsBox(isolatedBox, tolerance = 0.02f)
                if (!overlapsIsolated) {
                    builtComponents.add(
                        Component(
                            id = hint.id,
                            key = hint.key,
                            label = hint.label,
                            role = hint.role,
                            description = hint.description,
                            embeddedText = hint.embeddedText,
                            bbox = hint.bbox,
                            pinBox = hint.bbox.toPinRect(sceneViewport),
                            parentId = hint.parentId,
                            childIds = hint.childIds,
                            raisedPins = IntArray(0)
                        )
                    )
                    continue
                }
            }

            val pinBox = hint.bbox.toPinRect(sceneViewport)
            val roleLower = hint.role.lowercase()

            when {
                roleLower in setOf("speech", "text") -> {
                    // Dialogue zones are touch/navigation regions without raised pins at 1x or in panel isolation
                    builtComponents.add(
                        Component(
                            id = hint.id,
                            key = hint.key,
                            label = hint.label,
                            role = hint.role,
                            description = hint.description,
                            embeddedText = hint.embeddedText,
                            bbox = hint.bbox,
                            pinBox = pinBox,
                            parentId = hint.parentId,
                            childIds = hint.childIds,
                            raisedPins = IntArray(0)
                        )
                    )
                }
                roleLower == "panel" || (hint.parentId == null && hint.childIds.isNotEmpty() && hint.strokes.isEmpty()) -> {
                    // If this panel is the isolated component itself, its border sits on the outer edge (or is omitted so the figure fills the canvas)
                    val isIsolatedTarget = isolatedBox != null && hint.bbox.iou(isolatedBox) > 0.85f
                    val borderPins = if (isIsolatedTarget) {
                        IntArray(0)
                    } else {
                        projectBoxBorderIntoViewport(hint.bbox, sceneViewport)
                    }
                    val baseNorm = buildNormalizedBoxOutline(hint.bbox)
                    for (packed in borderPins) {
                        pinGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
                    }
                    builtComponents.add(
                        Component(
                            id = hint.id,
                            key = hint.key,
                            label = hint.label,
                            role = hint.role.ifEmpty { "panel" },
                            description = hint.description,
                            embeddedText = hint.embeddedText,
                            bbox = hint.bbox,
                            pinBox = pinBox,
                            parentId = hint.parentId,
                            childIds = hint.childIds,
                            raisedPins = borderPins,
                            baseNormalizedPins = baseNorm
                        )
                    )
                }
                hint.strokes.isNotEmpty() -> {
                    val packedSet = LinkedHashSet<Int>()
                    val baseNorm = ArrayList<NormalizedPoint>()
                    for (stroke in hint.strokes) {
                        baseNorm.addAll(stroke.points)
                        packedSet.addAll(VectorStrokeRasterizer.rasterizeStrokeToPins(stroke, sceneViewport))
                    }
                    val thinnedPins = thinComponentPinMask(packedSet)
                    for (packed in thinnedPins) {
                        pinGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
                    }
                    builtComponents.add(
                        Component(
                            id = hint.id,
                            key = hint.key,
                            label = hint.label,
                            role = hint.role,
                            description = hint.description,
                            embeddedText = hint.embeddedText,
                            bbox = hint.bbox,
                            pinBox = pinBox,
                            parentId = hint.parentId,
                            childIds = hint.childIds,
                            raisedPins = thinnedPins,
                            baseNormalizedPins = baseNorm
                        )
                    )
                }
                else -> {
                    // When isolating a panel that has speech zones above/below a figure, fit the visual
                    // figure bounds to ~30 rows tall (rows 2..31) with aspect-ratio-preserving horizontal centering.
                    val effectiveViewport = if (isolatedBox != null && hint.parentId != null) {
                        val visualSiblings = hints.filter {
                            it.parentId == hint.parentId && it.role.lowercase() !in setOf("speech", "text", "panel")
                        }
                        if (visualSiblings.isNotEmpty()) {
                            val visUnion = visualSiblings.map { it.bbox }.reduce { acc, b -> acc.union(b) }
                            val srcVisW = (visUnion.width * w).coerceAtLeast(1f)
                            val srcVisH = (visUnion.height * h).coerceAtLeast(1f)
                            val targetRows = 30f
                            val targetCols = (targetRows * (srcVisW / srcVisH) * 1.35f).coerceIn(20f, 76f)
                            val padXFrac = ((PinGrid.WIDTH - targetCols) * 0.5f) / PinGrid.WIDTH
                            val padYFrac = ((PinGrid.CANVAS_HEIGHT - targetRows) * 0.5f) / PinGrid.CANVAS_HEIGHT
                            val scaleW = visUnion.width / (1f - 2f * padXFrac).coerceAtLeast(0.1f)
                            val scaleH = visUnion.height / (1f - 2f * padYFrac).coerceAtLeast(0.1f)
                            val fittedBox = NormalizedBox(
                                ymin = visUnion.centerY - scaleH * 0.5f,
                                xmin = visUnion.centerX - scaleW * 0.5f,
                                ymax = visUnion.centerY + scaleH * 0.5f,
                                xmax = visUnion.centerX + scaleW * 0.5f
                            )
                            SceneViewport(isolatedBox = fittedBox)
                        } else {
                            sceneViewport
                        }
                    } else {
                        sceneViewport
                    }

                    // Extract skeleton centerline chains inside hint.bbox and project onto effectiveViewport
                    val (projectedPins, normPts) = projectSkeletonRegionToPins(
                        skeleton = interiorSkeleton,
                        w = w,
                        h = h,
                        region = hint.bbox,
                        viewport = effectiveViewport
                    )
                    for (packed in projectedPins) {
                        pinGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
                    }
                    builtComponents.add(
                        Component(
                            id = hint.id,
                            key = hint.key,
                            label = hint.label,
                            role = hint.role.ifEmpty { "figure" },
                            description = hint.description,
                            embeddedText = hint.embeddedText,
                            bbox = hint.bbox,
                            pinBox = hint.bbox.toPinRect(effectiveViewport),
                            parentId = hint.parentId,
                            childIds = hint.childIds,
                            raisedPins = projectedPins,
                            baseNormalizedPins = normPts
                        )
                    )
                }
            }
        }

        // Also guarantee that each panel's raisedPins includes its children's pins only on the global PinGrid
        // (HitGrid already prioritizes smaller child pinBox.area over parent panel)
        val rootIds = builtComponents.filter { it.parentId == null || !hintById.containsKey(it.parentId) }.map { it.id }

        val rawScene = TactileScene(
            pinGrid = pinGrid,
            components = builtComponents,
            rootComponentIds = rootIds,
            viewport = sceneViewport
        )
        return densityController.enforceLimit(rawScene)
    }

    private fun compileUnhinted(
        w: Int,
        h: Int,
        maxW: Float,
        maxH: Float,
        skeletonMask: BooleanArray,
        blobs: List<SkeletonBlob>,
        isOverview1x: Boolean,
        glyphMaxDimX: Int,
        glyphMaxDimY: Int,
        sceneViewport: SceneViewport
    ): TactileScene {
        val significantBlobs = blobs.filter { blob ->
            if (isOverview1x && blob.pixels.size <= 16 && blob.bboxWidth <= glyphMaxDimX && blob.bboxHeight <= glyphMaxDimY) {
                false
            } else {
                blob.pixels.size >= 4
            }
        }.sortedByDescending { it.bboxWidth + it.bboxHeight }

        val pinGrid = PinGrid()
        val components = ArrayList<Component>()
        var nextId = 1

        for (blob in significantBlobs.take(24)) {
            val bbox = NormalizedBox(
                ymin = (blob.minY / maxH).coerceIn(0f, 1f),
                xmin = (blob.minX / maxW).coerceIn(0f, 1f),
                ymax = (blob.maxY / maxH).coerceIn(0f, 1f),
                xmax = (blob.maxX / maxW).coerceIn(0f, 1f)
            )
            val blobMask = BooleanArray(w * h)
            for (idx in blob.pixels) blobMask[idx] = true

            val (projectedPins, normPts) = projectSkeletonRegionToPins(
                skeleton = blobMask,
                w = w,
                h = h,
                region = bbox,
                viewport = sceneViewport
            )
            if (projectedPins.isEmpty()) continue
            for (packed in projectedPins) {
                pinGrid.setCanvasPin(PinGrid.unpackX(packed), PinGrid.unpackY(packed), true)
            }
            val compId = nextId++
            components.add(
                Component(
                    id = compId,
                    key = "c$compId",
                    label = "Contour $compId",
                    role = "figure",
                    bbox = bbox,
                    pinBox = bbox.toPinRect(sceneViewport),
                    raisedPins = projectedPins,
                    baseNormalizedPins = normPts
                )
            )
        }

        val rawScene = TactileScene(
            pinGrid = pinGrid,
            components = components,
            rootComponentIds = components.map { it.id },
            viewport = sceneViewport
        )
        return densityController.enforceLimit(rawScene)
    }

    private fun extractForegroundMask(bitmap: GrayscaleBitmap): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h
        var minLum = 255
        var maxLum = 0
        var sumLum = 0L
        var darkCount = 0

        for (i in 0 until total) {
            val v = bitmap.pixels[i].toInt() and 0xFF
            if (v < minLum) minLum = v
            if (v > maxLum) maxLum = v
            sumLum += v
            if (v < 160) darkCount++
        }

        val meanLum = sumLum.toDouble() / total.coerceAtLeast(1)
        val darkRatio = darkCount.toDouble() / total.coerceAtLeast(1)

        val mask = BooleanArray(total)
        if (meanLum > 160.0 && darkRatio < 0.48 && (maxLum - minLum) >= 40) {
            // Line art / diagram / comic mode: threshold dark ink directly so stroke interiors are solid before skeletonization
            val thresh = ((minLum + maxLum) / 2).coerceIn(80, 190)
            for (i in 0 until total) {
                val v = bitmap.pixels[i].toInt() and 0xFF
                mask[i] = v < thresh
            }
        } else {
            // Continuous-tone image fallback: Sobel gradient magnitude threshold
            val mag = IntArray(total)
            var maxMag = 0
            for (y in 1 until h - 1) {
                val rPrev = (y - 1) * w
                val rCur = y * w
                val rNext = (y + 1) * w
                for (x in 1 until w - 1) {
                    val p00 = bitmap.pixels[rPrev + x - 1].toInt() and 0xFF
                    val p01 = bitmap.pixels[rPrev + x].toInt() and 0xFF
                    val p02 = bitmap.pixels[rPrev + x + 1].toInt() and 0xFF
                    val p10 = bitmap.pixels[rCur + x - 1].toInt() and 0xFF
                    val p12 = bitmap.pixels[rCur + x + 1].toInt() and 0xFF
                    val p20 = bitmap.pixels[rNext + x - 1].toInt() and 0xFF
                    val p21 = bitmap.pixels[rNext + x].toInt() and 0xFF
                    val p22 = bitmap.pixels[rNext + x + 1].toInt() and 0xFF

                    val gx = -p00 + p02 - 2 * p10 + 2 * p12 - p20 + p22
                    val gy = -p00 - 2 * p01 - p02 + p20 + 2 * p21 + p22
                    val g = abs(gx) + abs(gy)
                    mag[rCur + x] = g
                    if (g > maxMag) maxMag = g
                }
            }
            val edgeThresh = max(48, (maxMag * 0.28f).roundToInt())
            for (i in 0 until total) {
                mask[i] = mag[i] >= edgeThresh
            }
        }
        return mask
    }

    /**
     * Fast sparse Zhang-Suen morphological thinning (skeletonization).
     *
     * Reduces 3-4 px (or thicker) ink strokes to single 1-pixel centerlines while preserving
     * 8-connectivity, junctions, and line endpoints. Operates only on active foreground indices
     * so a 666x205 bitmap thins in ~2-4 ms on the JVM.
     */
    internal fun sparseZhangSuenThin(inputMask: BooleanArray, w: Int, h: Int): BooleanArray {
        val grid = inputMask.copyOf()
        var active = IntArray(grid.count { it })
        var activeCount = 0
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val idx = rowOff + x
                if (grid[idx]) {
                    active[activeCount++] = idx
                }
            }
        }

        val toRemove = IntArray(activeCount.coerceAtLeast(1))
        var maxIterations = 12

        while (maxIterations-- > 0 && activeCount > 0) {
            var changed = false

            for (subIter in 0..1) {
                var removeCount = 0
                for (i in 0 until activeCount) {
                    val idx = active[i]
                    val p2 = if (grid[idx - w]) 1 else 0
                    val p3 = if (grid[idx - w + 1]) 1 else 0
                    val p4 = if (grid[idx + 1]) 1 else 0
                    val p5 = if (grid[idx + w + 1]) 1 else 0
                    val p6 = if (grid[idx + w]) 1 else 0
                    val p7 = if (grid[idx + w - 1]) 1 else 0
                    val p8 = if (grid[idx - 1]) 1 else 0
                    val p9 = if (grid[idx - w - 1]) 1 else 0

                    val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                    if (b < 2 || b > 6) continue

                    var a = 0
                    if (p2 == 0 && p3 == 1) a++
                    if (p3 == 0 && p4 == 1) a++
                    if (p4 == 0 && p5 == 1) a++
                    if (p5 == 0 && p6 == 1) a++
                    if (p6 == 0 && p7 == 1) a++
                    if (p7 == 0 && p8 == 1) a++
                    if (p8 == 0 && p9 == 1) a++
                    if (p9 == 0 && p2 == 1) a++
                    if (a != 1) continue

                    val cond = if (subIter == 0) {
                        (p2 * p4 * p6 == 0) && (p4 * p6 * p8 == 0)
                    } else {
                        (p2 * p4 * p8 == 0) && (p2 * p6 * p8 == 0)
                    }
                    if (cond) {
                        toRemove[removeCount++] = idx
                    }
                }

                if (removeCount > 0) {
                    changed = true
                    for (r in 0 until removeCount) {
                        grid[toRemove[r]] = false
                    }
                    var writeIdx = 0
                    for (i in 0 until activeCount) {
                        val idx = active[i]
                        if (grid[idx]) {
                            active[writeIdx++] = idx
                        }
                    }
                    activeCount = writeIdx
                }
            }

            if (!changed) break
        }

        return grid
    }

    private fun extractConnectedBlobs(mask: BooleanArray, w: Int, h: Int): List<SkeletonBlob> {
        val visited = BooleanArray(w * h)
        val blobs = ArrayList<SkeletonBlob>()
        val queue = IntArray(w * h)
        var nextBlobId = 1

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val startIdx = rowOff + x
                if (!mask[startIdx] || visited[startIdx]) continue

                var qHead = 0
                var qTail = 0
                queue[qTail++] = startIdx
                visited[startIdx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (qHead < qTail) {
                    val cur = queue[qHead++]
                    val cx = cur % w
                    val cy = cur / w
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val nyMin = (cy - 1).coerceAtLeast(0)
                    val nyMax = (cy + 1).coerceAtMost(h - 1)
                    val nxMin = (cx - 1).coerceAtLeast(0)
                    val nxMax = (cx + 1).coerceAtMost(w - 1)
                    for (ny in nyMin..nyMax) {
                        val nRow = ny * w
                        for (nx in nxMin..nxMax) {
                            val nIdx = nRow + nx
                            if (mask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[qTail++] = nIdx
                            }
                        }
                    }
                }

                val pixels = queue.copyOfRange(0, qTail)
                blobs.add(SkeletonBlob(nextBlobId++, pixels, minX, minY, maxX, maxY))
            }
        }
        return blobs
    }

    private fun projectSkeletonRegionToPins(
        skeleton: BooleanArray,
        w: Int,
        h: Int,
        region: NormalizedBox,
        viewport: SceneViewport
    ): Pair<IntArray, List<NormalizedPoint>> {
        val maxW = (w - 1).coerceAtLeast(1).toFloat()
        val maxH = (h - 1).coerceAtLeast(1).toFloat()
        val rx0 = (region.xmin * maxW).roundToInt().coerceIn(0, w - 1)
        val rx1 = (region.xmax * maxW).roundToInt().coerceIn(0, w - 1)
        val ry0 = (region.ymin * maxH).roundToInt().coerceIn(0, h - 1)
        val ry1 = (region.ymax * maxH).roundToInt().coerceIn(0, h - 1)

        val normPoints = ArrayList<NormalizedPoint>()
        val rawPinSet = LinkedHashSet<Int>()

        // Project each skeleton pixel and connect 8-adjacent skeleton neighbors via 1-pin Bresenham
        // so zoomed/isolated views (>1x) remain continuous 1-pin lines without gaps.
        val neighborOffsets = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(1, 1),
            intArrayOf(-1, 1)
        )

        for (y in ry0..ry1) {
            val rowOff = y * w
            for (x in rx0..rx1) {
                val idx = rowOff + x
                if (!skeleton[idx]) continue
                val nx = x / maxW
                val ny = y / maxH
                normPoints.add(NormalizedPoint(nx, ny))

                val px0 = viewport.normalizedToPinX(nx)
                val py0 = viewport.normalizedToPinY(ny)
                if (px0 in 0 until PinGrid.WIDTH && py0 in 0 until PinGrid.CANVAS_HEIGHT) {
                    rawPinSet.add(PinGrid.packCanvasCoord(px0, py0))
                }

                for (off in neighborOffsets) {
                    val x2 = x + off[0]
                    val y2 = y + off[1]
                    if (x2 in rx0..rx1 && y2 in ry0..ry1 && skeleton[y2 * w + x2]) {
                        val nx2 = x2 / maxW
                        val ny2 = y2 / maxH
                        val px1 = viewport.normalizedToPinX(nx2)
                        val py1 = viewport.normalizedToPinY(ny2)
                        if (abs(px1 - px0) > 1 || abs(py1 - py0) > 1) {
                            for ((bx, by) in PinDrawingLayer.bresenhamLine(px0, py0, px1, py1)) {
                                if (bx in 0 until PinGrid.WIDTH && by in 0 until PinGrid.CANVAS_HEIGHT) {
                                    rawPinSet.add(PinGrid.packCanvasCoord(bx, by))
                                }
                            }
                        }
                    }
                }
            }
        }

        val thinnedPins = thinComponentPinMask(rawPinSet)
        return Pair(thinnedPins, normPoints)
    }

    /**
     * Runs a 1-pin centerline thinning pass on projected canvas pins so coordinate quantization
     * never creates 2-pin-thick parallel lines or 2x2 solid blocks.
     */
    private fun thinComponentPinMask(packedPins: Set<Int>): IntArray {
        if (packedPins.size <= 2) return packedPins.toIntArray()
        val mask = BooleanArray(PinGrid.CANVAS_PIN_COUNT)
        for (p in packedPins) {
            if (p in 0 until PinGrid.CANVAS_PIN_COUNT) {
                mask[p] = true
            }
        }
        val thinned = sparseZhangSuenThin(mask, PinGrid.WIDTH, PinGrid.CANVAS_HEIGHT)
        val out = IntArray(packedPins.size)
        var count = 0
        for (p in packedPins) {
            if (p in 0 until PinGrid.CANVAS_PIN_COUNT && thinned[p]) {
                out[count++] = p
            }
        }
        return if (count > 0) out.copyOf(count) else packedPins.toIntArray()
    }

    private fun projectBoxBorderIntoViewport(box: NormalizedBox, viewport: SceneViewport): IntArray {
        val rawX0 = viewport.normalizedToExactPinX(box.xmin)
        val rawX1 = viewport.normalizedToExactPinX(box.xmax)
        val rawY0 = viewport.normalizedToExactPinY(box.ymin)
        val rawY1 = viewport.normalizedToExactPinY(box.ymax)

        val minX = kotlin.math.ceil((min(rawX0, rawX1) - 1e-4f).toDouble()).toInt()
        val maxX = kotlin.math.floor((max(rawX0, rawX1) + 1e-4f).toDouble()).toInt()
        val minY = kotlin.math.ceil((min(rawY0, rawY1) - 1e-4f).toDouble()).toInt()
        val maxY = kotlin.math.floor((max(rawY0, rawY1) + 1e-4f).toDouble()).toInt()

        val set = LinkedHashSet<Int>()
        for (x in max(0, minX)..min(PinGrid.WIDTH - 1, maxX)) {
            if (minY in 0 until PinGrid.CANVAS_HEIGHT) set.add(PinGrid.packCanvasCoord(x, minY))
            if (maxY in 0 until PinGrid.CANVAS_HEIGHT) set.add(PinGrid.packCanvasCoord(x, maxY))
        }
        for (y in max(0, minY)..min(PinGrid.CANVAS_HEIGHT - 1, maxY)) {
            if (minX in 0 until PinGrid.WIDTH) set.add(PinGrid.packCanvasCoord(minX, y))
            if (maxX in 0 until PinGrid.WIDTH) set.add(PinGrid.packCanvasCoord(maxX, y))
        }
        return set.toIntArray()
    }

    private fun buildNormalizedBoxOutline(box: NormalizedBox): List<NormalizedPoint> {
        return listOf(
            NormalizedPoint(box.xmin, box.ymin),
            NormalizedPoint(box.xmax, box.ymin),
            NormalizedPoint(box.xmax, box.ymax),
            NormalizedPoint(box.xmin, box.ymax)
        )
    }
}
