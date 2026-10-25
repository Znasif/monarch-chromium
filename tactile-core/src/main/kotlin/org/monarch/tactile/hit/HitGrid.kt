package org.monarch.tactile.hit

import org.monarch.tactile.model.Component
import org.monarch.tactile.model.PinGrid

/**
 * Pre-stamped 96x40 O(1) component hit-test grid with a 1-pin touch halo.
 *
 * Designed for the 40Hz (25ms) physical pin polling loop on the Monarch:
 * - [lookup] performs a single bounds-checked [IntArray] read with zero heap allocations.
 * - Rows 0..34 hold stamped component IDs (or [NO_COMPONENT] = 0).
 * - Row 35 is the blank separator row ([NO_COMPONENT] = 0).
 * - Rows 36..39 are the Braille caption rows ([CAPTION_REGION] = -1).
 */
class HitGrid {
    companion object {
        const val NO_COMPONENT = 0
        const val CAPTION_REGION = -1

        private const val TIER_NONE = 0
        private const val TIER_HALO = 1
        private const val TIER_DIRECT = 2
    }

    val rawCells: IntArray = IntArray(PinGrid.WIDTH * PinGrid.HEIGHT)

    init {
        resetGrid()
    }

    private fun resetGrid() {
        for (i in 0 until PinGrid.CANVAS_PIN_COUNT) {
            rawCells[i] = NO_COMPONENT
        }
        val sepStart = PinGrid.SEPARATOR_ROW * PinGrid.WIDTH
        for (x in 0 until PinGrid.WIDTH) {
            rawCells[sepStart + x] = NO_COMPONENT
        }
        for (y in PinGrid.CAPTION_START_ROW until PinGrid.HEIGHT) {
            val rowOffset = y * PinGrid.WIDTH
            for (x in 0 until PinGrid.WIDTH) {
                rawCells[rowOffset + x] = CAPTION_REGION
            }
        }
    }

    /**
     * Stamps [components] and their [haloRadius]-pin touch halos onto rows 0..34.
     *
     * Precedence rules (Decision D-03 & D-04):
     * 1. Direct component pins always beat halo pins.
     * 2. Within the same tier (direct vs. direct, or halo vs. halo), the component with the
     *    smaller [Component.pinBox] area wins (so child sub-components win over parent containers).
     * 3. Ties in area are broken by later insertion order in [components].
     * 4. Stamping is strictly clamped to canvas rows 0..34; row 35 remains [NO_COMPONENT] (0)
     *    and rows 36..39 remain [CAPTION_REGION] (-1).
     */
    fun stampScene(components: List<Component>, haloRadius: Int = 1) {
        resetGrid()
        if (components.isEmpty()) return

        val safeHalo = haloRadius.coerceAtLeast(0)
        val bestTier = IntArray(PinGrid.CANVAS_PIN_COUNT)
        val bestArea = IntArray(PinGrid.CANVAS_PIN_COUNT) { Int.MAX_VALUE }
        val bestOrder = IntArray(PinGrid.CANVAS_PIN_COUNT) { -1 }

        fun tryStampCell(x: Int, y: Int, compId: Int, tier: Int, area: Int, order: Int) {
            if (x !in 0 until PinGrid.WIDTH || y !in 0 until PinGrid.CANVAS_HEIGHT) return
            val idx = y * PinGrid.WIDTH + x
            val curTier = bestTier[idx]
            val shouldReplace = when {
                tier > curTier -> true
                tier < curTier -> false
                area < bestArea[idx] -> true
                area == bestArea[idx] && order >= bestOrder[idx] -> true
                else -> false
            }
            if (shouldReplace) {
                rawCells[idx] = compId
                bestTier[idx] = tier
                bestArea[idx] = area
                bestOrder[idx] = order
            }
        }

        fun stampPinWithHalo(px: Int, py: Int, compId: Int, area: Int, order: Int) {
            if (px !in 0 until PinGrid.WIDTH || py !in 0 until PinGrid.CANVAS_HEIGHT) return
            tryStampCell(px, py, compId, TIER_DIRECT, area, order)
            if (safeHalo > 0) {
                val minY = (py - safeHalo).coerceAtLeast(0)
                val maxY = (py + safeHalo).coerceAtMost(PinGrid.CANVAS_HEIGHT - 1)
                val minX = (px - safeHalo).coerceAtLeast(0)
                val maxX = (px + safeHalo).coerceAtMost(PinGrid.WIDTH - 1)
                for (ny in minY..maxY) {
                    for (nx in minX..maxX) {
                        if (nx == px && ny == py) continue
                        tryStampCell(nx, ny, compId, TIER_HALO, area, order)
                    }
                }
            }
        }

        val hasAnyExplicitPins = components.any { it.raisedPins.isNotEmpty() }

        for (order in components.indices) {
            val comp = components[order]
            val area = comp.pinBox.area.coerceAtLeast(1)
            if (comp.raisedPins.isNotEmpty()) {
                for (packed in comp.raisedPins) {
                    if (packed !in 0 until PinGrid.CANVAS_PIN_COUNT) continue
                    val px = PinGrid.unpackX(packed)
                    val py = PinGrid.unpackY(packed)
                    stampPinWithHalo(px, py, comp.id, area, order)
                }
            } else if (!hasAnyExplicitPins && comp.pinBox.width > 0 && comp.pinBox.height > 0) {
                val minY = comp.pinBox.minY.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
                val maxY = comp.pinBox.maxY.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
                val minX = comp.pinBox.minX.coerceIn(0, PinGrid.WIDTH - 1)
                val maxX = comp.pinBox.maxX.coerceIn(0, PinGrid.WIDTH - 1)
                for (py in minY..maxY) {
                    for (px in minX..maxX) {
                        stampPinWithHalo(px, py, comp.id, area, order)
                    }
                }
            }
        }
    }

    /**
     * O(1) zero-allocation component lookup for pin coordinates (x in 0..95, y in 0..39).
     */
    fun lookup(x: Int, y: Int): Int {
        if (x < 0 || x >= PinGrid.WIDTH || y < 0 || y >= PinGrid.HEIGHT) {
            return NO_COMPONENT
        }
        return rawCells[y * PinGrid.WIDTH + x]
    }

    /**
     * Applies the Monarch fingertip width compensation (default -3 pins on X per AGENTS.md §2.4)
     * before performing an O(1) lookup.
     */
    fun lookupWithFingertipOffset(rawX: Int, rawY: Int, xOffset: Int = -3): Int {
        if (rawY < 0 || rawY >= PinGrid.HEIGHT) return NO_COMPONENT
        val adjustedX = (rawX + xOffset).coerceIn(0, PinGrid.WIDTH - 1)
        return lookup(adjustedX, rawY)
    }
}
