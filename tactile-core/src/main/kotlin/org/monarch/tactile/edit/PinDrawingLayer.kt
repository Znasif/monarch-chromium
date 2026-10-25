package org.monarch.tactile.edit

import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.SceneViewport
import kotlin.math.abs
import kotlin.math.max

/**
 * A single user-drawn or user-erased pin stored in normalized [0.0, 1.0] image coordinates
 * and attached to a specific component ([componentId] / [componentKey]).
 */
data class AttachedNormalizedPin(
    val componentId: Int,
    val componentKey: String,
    val normX: Float,
    val normY: Float,
    val isErased: Boolean = false
)

/**
 * Immutable layer of user-drawn raised/erased pins in normalized image coordinates.
 * Because coordinates are stored in normalized image space, drawings automatically scale
 * and translate whenever the [SceneViewport] zooms or pans.
 */
data class PinDrawingLayer(
    val points: List<AttachedNormalizedPin> = emptyList()
) {
    fun isEmpty(): Boolean = points.isEmpty()

    /**
     * Draws a 1-pin continuous line from (x0, y0) to (x1, y1) in current viewport pin coordinates
     * and attaches every pin along the line to [componentId] ([componentKey]).
     */
    fun drawLineInPinSpace(
        componentId: Int,
        componentKey: String,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        viewport: SceneViewport
    ): PinDrawingLayer {
        val rasterPins = bresenhamLine(x0, y0, x1, y1)
        val newPoints = points.toMutableList()
        for ((px, py) in rasterPins) {
            if (px !in 0 until PinGrid.WIDTH || py !in 0 until PinGrid.CANVAS_HEIGHT) continue
            val nx = viewport.pinToNormalizedX(px).coerceIn(0f, 1f)
            val ny = viewport.pinToNormalizedY(py).coerceIn(0f, 1f)
            // Replace any prior point at the same projected cell for this component
            newPoints.removeAll { existing ->
                viewport.normalizedToPinX(existing.normX) == px &&
                    viewport.normalizedToPinY(existing.normY) == py
            }
            newPoints.add(
                AttachedNormalizedPin(
                    componentId = componentId,
                    componentKey = componentKey,
                    normX = nx,
                    normY = ny,
                    isErased = false
                )
            )
        }
        return PinDrawingLayer(newPoints)
    }

    /**
     * Erases pins along the line from (x0, y0) to (x1, y1) in current viewport pin coordinates.
     * Drops matching user-drawn raised pins and records erase markers if [recordBaseErase] is true.
     */
    fun eraseLineInPinSpace(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        viewport: SceneViewport,
        componentId: Int = 0,
        componentKey: String = "",
        recordBaseErase: Boolean = false
    ): PinDrawingLayer {
        val targetCells = bresenhamLine(x0, y0, x1, y1).toSet()
        val updated = points.filterNot { pt ->
            !pt.isErased &&
                Pair(viewport.normalizedToPinX(pt.normX), viewport.normalizedToPinY(pt.normY)) in targetCells
        }.toMutableList()

        if (recordBaseErase) {
            for ((px, py) in targetCells) {
                if (px !in 0 until PinGrid.WIDTH || py !in 0 until PinGrid.CANVAS_HEIGHT) continue
                updated.add(
                    AttachedNormalizedPin(
                        componentId = componentId,
                        componentKey = componentKey,
                        normX = viewport.pinToNormalizedX(px).coerceIn(0f, 1f),
                        normY = viewport.pinToNormalizedY(py).coerceIn(0f, 1f),
                        isErased = true
                    )
                )
            }
        }
        return PinDrawingLayer(updated)
    }

    /**
     * Reassigns drawn pins when a component is split or merged.
     */
    fun remapComponent(
        oldComponentId: Int,
        selector: (AttachedNormalizedPin) -> Pair<Int, String>
    ): PinDrawingLayer {
        val remapped = points.map { pt ->
            if (pt.componentId == oldComponentId) {
                val (newId, newKey) = selector(pt)
                pt.copy(componentId = newId, componentKey = newKey)
            } else {
                pt
            }
        }
        return PinDrawingLayer(remapped)
    }

    /**
     * Projects active user-drawn raised pins for [componentId] into packed canvas indices (y * 96 + x)
     * under [viewport].
     */
    fun projectRaisedPinsForComponent(
        componentId: Int,
        componentKey: String,
        viewport: SceneViewport
    ): IntArray {
        val packedSet = linkedSetOf<Int>()
        for (pt in points) {
            if (!pt.isErased && (pt.componentId == componentId || (componentKey.isNotEmpty() && pt.componentKey == componentKey))) {
                val px = viewport.normalizedToPinX(pt.normX)
                val py = viewport.normalizedToPinY(pt.normY)
                if (px in 0 until PinGrid.WIDTH && py in 0 until PinGrid.CANVAS_HEIGHT) {
                    packedSet.add(PinGrid.packCanvasCoord(px, py))
                }
            }
        }
        return packedSet.toIntArray()
    }

    companion object {
        fun bresenhamLine(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> {
            val result = ArrayList<Pair<Int, Int>>(max(abs(x1 - x0), abs(y1 - y0)) + 1)
            var cx = x0
            var cy = y0
            val dx = abs(x1 - x0)
            val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var err = dx + dy

            while (true) {
                result.add(Pair(cx, cy))
                if (cx == x1 && cy == y1) break
                val e2 = 2 * err
                if (e2 >= dy) {
                    err += dy
                    cx += sx
                }
                if (e2 <= dx) {
                    err += dx
                    cy += sy
                }
            }
            return result
        }
    }
}
