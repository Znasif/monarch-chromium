package org.monarch.tier2

import org.monarch.tactile.compile.DensityController
import org.monarch.tactile.compile.GrayscaleBitmap
import org.monarch.tactile.compile.RasterContourCompiler
import org.monarch.tactile.compile.StrokeRenderer
import org.monarch.tactile.compile.Viewport
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.TactileScene
import kotlin.math.hypot

/**
 * Hot-swaps an asynchronous [Tier2ComponentTree] into an active Tier-1 [TactileScene]
 * while preserving the user's active [Viewport], user-drawn/erased pins ([TactileScene.drawingLayer]),
 * and mapping the focused Tier-1 component ID to the best-matching Tier-2 component (`D-08`).
 */
object Tier2SceneMerger {

    fun hotSwap(
        tier1Scene: TactileScene,
        tier2Tree: Tier2ComponentTree,
        focusedComponentId: Int?,
        bitmap: GrayscaleBitmap? = null,
        viewport: Viewport = Viewport()
    ): Pair<TactileScene, Int?> {
        val densityController = DensityController()
        val hasStructuredStrokes = tier2Tree.structuredGraphic.components.any { it.strokes.isNotEmpty() }

        val baseMergedScene: TactileScene = when {
            hasStructuredStrokes -> {
                StrokeRenderer(densityController).renderScene(
                    graphic = tier2Tree.structuredGraphic,
                    viewport = viewport
                )
            }
            bitmap != null -> {
                RasterContourCompiler(densityController).compile(
                    bitmap = bitmap,
                    hints = tier2Tree.components,
                    viewport = viewport
                )
            }
            else -> {
                StrokeRenderer(densityController).renderScene(
                    graphic = tier2Tree.structuredGraphic,
                    viewport = viewport
                )
            }
        }

        // Preserve any user-drawn or user-erased pins from tier1Scene.drawingLayer,
        // remapping Tier-1 component IDs/keys to their reconciled Tier-2 counterparts
        val finalScene = if (!tier1Scene.drawingLayer.isEmpty()) {
            val remappedPoints = tier1Scene.drawingLayer.points.map { pt ->
                val mappedId = reconcileFocus(tier1Scene, baseMergedScene, pt.componentId)
                val mappedComp = mappedId?.let { baseMergedScene.componentById(it) }
                if (mappedComp != null) {
                    pt.copy(componentId = mappedComp.id, componentKey = mappedComp.key)
                } else {
                    val fallback = baseMergedScene.components.firstOrNull()
                    if (fallback != null) {
                        pt.copy(componentId = fallback.id, componentKey = fallback.key)
                    } else {
                        pt
                    }
                }
            }
            baseMergedScene.copy(
                drawingLayer = org.monarch.tactile.edit.PinDrawingLayer(remappedPoints)
            ).reprojectScene()
        } else {
            baseMergedScene
        }

        val mappedFocusId = reconcileFocus(
            tier1Scene = tier1Scene,
            tier2Scene = finalScene,
            focusedComponentId = focusedComponentId
        )

        return Pair(finalScene, mappedFocusId)
    }

    private fun reconcileFocus(
        tier1Scene: TactileScene,
        tier2Scene: TactileScene,
        focusedComponentId: Int?
    ): Int? {
        if (focusedComponentId == null || tier2Scene.components.isEmpty()) {
            return null
        }

        val oldComp = tier1Scene.componentById(focusedComponentId)
            ?: return tier2Scene.rootComponentIds.firstOrNull() ?: tier2Scene.components.first().id

        // 1. Exact key match if keys align (e.g., "p3" -> "p3")
        val exactKeyMatch = tier2Scene.components.firstOrNull {
            it.key.equals(oldComp.key, ignoreCase = true)
        }
        if (exactKeyMatch != null && oldComp.bbox.iou(exactKeyMatch.bbox) >= 0.25f) {
            return exactKeyMatch.id
        }

        // 2. Highest bounding-box IoU >= 0.25 (preferring same hierarchy depth: root vs child)
        val sameDepthCandidates = tier2Scene.components.filter {
            (it.parentId == null) == (oldComp.parentId == null)
        }.ifEmpty { tier2Scene.components }

        val bestByIou: Component? = sameDepthCandidates.maxByOrNull { cand ->
            oldComp.bbox.iou(cand.bbox)
        }
        if (bestByIou != null && oldComp.bbox.iou(bestByIou.bbox) >= 0.25f) {
            return bestByIou.id
        }

        val anyBestByIou = tier2Scene.components.maxByOrNull { cand ->
            oldComp.bbox.iou(cand.bbox)
        }
        if (anyBestByIou != null && oldComp.bbox.iou(anyBestByIou.bbox) >= 0.25f) {
            return anyBestByIou.id
        }

        // 3. Fallback: nearest bounding-box centroid
        return sameDepthCandidates.minByOrNull { cand ->
            hypot(
                (cand.bbox.centerX - oldComp.bbox.centerX).toDouble(),
                (cand.bbox.centerY - oldComp.bbox.centerY).toDouble()
            )
        }?.id
    }
}
