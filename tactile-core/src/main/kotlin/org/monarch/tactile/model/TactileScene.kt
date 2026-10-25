package org.monarch.tactile.model

import org.monarch.tactile.edit.PinDrawingLayer
import org.monarch.tactile.hit.HitGrid
import org.monarch.tactile.nav.ComponentGraph

data class UndoRecord(
    val previousScene: TactileScene,
    val description: String
)

data class SceneEditResult(
    val scene: TactileScene,
    val undoRecord: UndoRecord,
    val hitGrid: HitGrid,
    val graph: ComponentGraph
)

/**
 * Immutable tactile scene containing the 96x40 [PinGrid], hierarchical [Component] list,
 * root component IDs, and user [PinDrawingLayer].
 *
 * Every tree edit ([renameComponent], [splitComponent], [mergeComponents], [reParentComponent])
 * and pin edit ([drawPinLine], [erasePinLine]) returns a new [SceneEditResult] with an updated
 * immutable [TactileScene], an [UndoRecord], and freshly rebuilt [HitGrid] and [ComponentGraph].
 */
data class TactileScene(
    val width: Int = PinGrid.WIDTH,
    val height: Int = PinGrid.HEIGHT,
    val pinGrid: PinGrid = PinGrid(),
    val components: List<Component> = emptyList(),
    val rootComponentIds: List<Int> = emptyList(),
    val drawingLayer: PinDrawingLayer = PinDrawingLayer(),
    val viewport: SceneViewport = SceneViewport()
) {
    fun componentById(id: Int): Component? = components.firstOrNull { it.id == id }

    fun componentByKey(key: String): Component? = components.firstOrNull { it.key == key }

    fun componentByLabel(label: String, parentId: Int? = null): Component? {
        return components.firstOrNull { comp ->
            comp.label.equals(label, ignoreCase = true) &&
                (parentId == null || comp.parentId == parentId)
        }
    }

    fun childrenOf(parentId: Int): List<Component> {
        val parent = componentById(parentId)
        val byId = components.associateBy { it.id }
        val explicit = parent?.childIds?.mapNotNull { byId[it] } ?: emptyList()
        val byParentRef = components.filter { it.parentId == parentId }
        return (explicit + byParentRef).distinctBy { it.id }
    }

    fun buildHitGrid(haloRadius: Int = 1): HitGrid {
        val grid = HitGrid()
        grid.stampScene(components, haloRadius)
        return grid
    }

    fun buildGraph(): ComponentGraph {
        return ComponentGraph.build(components)
    }

    private fun nextComponentId(): Int {
        return (components.maxOfOrNull { it.id } ?: 0) + 1
    }

    /**
     * Renames [componentId] to [newLabel], returning a new immutable scene, undo record,
     * and rebuilt [HitGrid] + [ComponentGraph].
     */
    fun renameComponent(componentId: Int, newLabel: String): SceneEditResult {
        val target = requireNotNull(componentById(componentId)) {
            "Component id=$componentId not found"
        }
        val updatedComponents = components.map { comp ->
            if (comp.id == componentId) comp.copy(label = newLabel) else comp
        }
        val nextScene = copy(components = updatedComponents)
        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "rename ${target.label} -> $newLabel"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Splits [componentId] horizontally at [splitNormalizedX] (defaults to horizontal midpoint)
     * into two sibling components ([firstLabel] and [secondLabel]) inside the same parent,
     * regrouping its pins and bounding boxes.
     */
    fun splitComponent(
        componentId: Int,
        firstLabel: String,
        secondLabel: String,
        splitNormalizedX: Float? = null,
        firstKey: String? = null,
        secondKey: String? = null
    ): SceneEditResult {
        val target = requireNotNull(componentById(componentId)) {
            "Component id=$componentId not found"
        }
        val splitX = (splitNormalizedX ?: target.bbox.centerX).coerceIn(target.bbox.xmin, target.bbox.xmax)
        val splitPinX = viewport.normalizedToPinX(splitX)

        val firstBox = NormalizedBox(
            ymin = target.bbox.ymin,
            xmin = target.bbox.xmin,
            ymax = target.bbox.ymax,
            xmax = splitX
        )
        val secondBox = NormalizedBox(
            ymin = target.bbox.ymin,
            xmin = splitX,
            ymax = target.bbox.ymax,
            xmax = target.bbox.xmax
        )

        val firstId = nextComponentId()
        val secondId = firstId + 1
        val key1 = firstKey ?: "${target.key}_1"
        val key2 = secondKey ?: "${target.key}_2"

        val firstPins = target.raisedPins.filter { packed -> PinGrid.unpackX(packed) <= splitPinX }.toIntArray()
        val secondPins = target.raisedPins.filter { packed -> PinGrid.unpackX(packed) > splitPinX }.toIntArray()

        val firstBaseNorm = target.baseNormalizedPins.filter { pt -> pt.x <= splitX }
        val secondBaseNorm = target.baseNormalizedPins.filter { pt -> pt.x > splitX }

        val firstComp = Component(
            id = firstId,
            key = key1,
            label = firstLabel,
            role = target.role,
            description = target.description,
            embeddedText = target.embeddedText,
            bbox = firstBox,
            pinBox = firstBox.toPinRect(viewport),
            parentId = target.parentId,
            childIds = emptyList(),
            raisedPins = firstPins,
            baseNormalizedPins = firstBaseNorm
        )
        val secondComp = Component(
            id = secondId,
            key = key2,
            label = secondLabel,
            role = target.role,
            description = target.description,
            embeddedText = "",
            bbox = secondBox,
            pinBox = secondBox.toPinRect(viewport),
            parentId = target.parentId,
            childIds = emptyList(),
            raisedPins = secondPins,
            baseNormalizedPins = secondBaseNorm
        )

        val updatedComponents = ArrayList<Component>(components.size + 1)
        for (comp in components) {
            when {
                comp.id == componentId -> {
                    updatedComponents.add(firstComp)
                    updatedComponents.add(secondComp)
                }
                comp.id == target.parentId -> {
                    val newChildren = ArrayList<Int>(comp.childIds.size + 1)
                    var replaced = false
                    for (cid in comp.childIds) {
                        if (cid == componentId) {
                            newChildren.add(firstId)
                            newChildren.add(secondId)
                            replaced = true
                        } else {
                            newChildren.add(cid)
                        }
                    }
                    if (!replaced) {
                        newChildren.add(firstId)
                        newChildren.add(secondId)
                    }
                    updatedComponents.add(comp.copy(childIds = newChildren))
                }
                else -> updatedComponents.add(comp)
            }
        }

        val updatedRoots = if (target.parentId == null) {
            val list = ArrayList<Int>(rootComponentIds.size + 1)
            for (rid in rootComponentIds) {
                if (rid == componentId) {
                    list.add(firstId)
                    list.add(secondId)
                } else {
                    list.add(rid)
                }
            }
            list
        } else {
            rootComponentIds
        }

        val remappedDrawing = drawingLayer.remapComponent(componentId) { pt ->
            if (pt.normX <= splitX) Pair(firstId, key1) else Pair(secondId, key2)
        }

        val nextScene = copy(
            components = updatedComponents,
            rootComponentIds = updatedRoots,
            drawingLayer = remappedDrawing
        ).reprojectScene()

        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "split ${target.label} -> $firstLabel, $secondLabel"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Merges [firstId] and [secondId] into a single component with label [mergedLabel],
     * combining their bounding boxes, pins, and children.
     */
    fun mergeComponents(
        firstId: Int,
        secondId: Int,
        mergedLabel: String,
        mergedKey: String? = null
    ): SceneEditResult {
        require(firstId != secondId) { "Cannot merge a component with itself (id=$firstId)" }
        val first = requireNotNull(componentById(firstId)) { "Component id=$firstId not found" }
        val second = requireNotNull(componentById(secondId)) { "Component id=$secondId not found" }

        val mergedId = first.id
        val newKey = mergedKey ?: first.key
        val mergedBox = first.bbox.union(second.bbox)
        val mergedPins = (first.raisedPins.toList() + second.raisedPins.toList()).distinct().toIntArray()
        val mergedBaseNorm = (first.baseNormalizedPins + second.baseNormalizedPins).distinct()
        val mergedChildIds = (first.childIds + second.childIds)
            .filter { it != firstId && it != secondId }
            .distinct()

        val mergedComp = first.copy(
            id = mergedId,
            key = newKey,
            label = mergedLabel,
            bbox = mergedBox,
            pinBox = mergedBox.toPinRect(viewport),
            childIds = mergedChildIds,
            raisedPins = mergedPins,
            baseNormalizedPins = mergedBaseNorm
        )

        val updatedComponents = ArrayList<Component>(components.size - 1)
        for (comp in components) {
            when (comp.id) {
                firstId -> updatedComponents.add(mergedComp)
                secondId -> {
                    // Omitted: merged into firstId
                }
                first.parentId -> {
                    val newChildren = comp.childIds
                        .map { if (it == secondId) mergedId else it }
                        .distinct()
                    updatedComponents.add(comp.copy(childIds = newChildren))
                }
                else -> {
                    if (comp.parentId == secondId) {
                        updatedComponents.add(comp.copy(parentId = mergedId))
                    } else {
                        updatedComponents.add(comp)
                    }
                }
            }
        }

        val updatedRoots = rootComponentIds
            .map { if (it == secondId) mergedId else it }
            .distinct()

        val remappedDrawing = drawingLayer.remapComponent(secondId) { Pair(mergedId, newKey) }

        val nextScene = copy(
            components = updatedComponents,
            rootComponentIds = updatedRoots,
            drawingLayer = remappedDrawing
        ).reprojectScene()

        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "merge ${first.label} + ${second.label} -> $mergedLabel"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Moves [componentId] under [newParentId] (or to root level if [newParentId] is null).
     */
    fun reParentComponent(componentId: Int, newParentId: Int?): SceneEditResult {
        require(componentId != newParentId) { "Component cannot be its own parent (id=$componentId)" }
        val target = requireNotNull(componentById(componentId)) { "Component id=$componentId not found" }
        if (newParentId != null) {
            requireNotNull(componentById(newParentId)) { "New parent id=$newParentId not found" }
        }
        val oldParentId = target.parentId

        val updatedComponents = components.map { comp ->
            when (comp.id) {
                componentId -> comp.copy(parentId = newParentId)
                oldParentId -> comp.copy(childIds = comp.childIds.filter { it != componentId })
                newParentId -> {
                    val newChildren = if (componentId in comp.childIds) comp.childIds else comp.childIds + componentId
                    comp.copy(childIds = newChildren)
                }
                else -> comp
            }
        }

        val updatedRoots = when {
            oldParentId == null && newParentId != null -> rootComponentIds.filter { it != componentId }
            oldParentId != null && newParentId == null -> (rootComponentIds + componentId).distinct()
            else -> rootComponentIds
        }

        val nextScene = copy(
            components = updatedComponents,
            rootComponentIds = updatedRoots
        )

        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "reparent ${target.label} -> $newParentId"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Draws a continuous 1-pin line from (x0, y0) to (x1, y1) in current viewport pin coordinates,
     * storing it in normalized coordinates attached to [componentId].
     */
    fun drawPinLine(componentId: Int, x0: Int, y0: Int, x1: Int, y1: Int): SceneEditResult {
        val target = requireNotNull(componentById(componentId)) { "Component id=$componentId not found" }
        val updatedLayer = drawingLayer.drawLineInPinSpace(
            componentId = componentId,
            componentKey = target.key,
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            viewport = viewport
        )
        val nextScene = copy(drawingLayer = updatedLayer).reprojectScene()
        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "draw line ($x0,$y0)..($x1,$y1) on ${target.label}"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Erases pins along the segment from (x0, y0) to (x1, y1) in current viewport pin coordinates.
     */
    fun erasePinLine(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        componentId: Int = 0,
        recordBaseErase: Boolean = false
    ): SceneEditResult {
        val compKey = componentById(componentId)?.key ?: ""
        val updatedLayer = drawingLayer.eraseLineInPinSpace(
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            viewport = viewport,
            componentId = componentId,
            componentKey = compKey,
            recordBaseErase = recordBaseErase
        )
        val nextScene = copy(drawingLayer = updatedLayer).reprojectScene()
        return SceneEditResult(
            scene = nextScene,
            undoRecord = UndoRecord(this, "erase line ($x0,$y0)..($x1,$y1)"),
            hitGrid = nextScene.buildHitGrid(),
            graph = nextScene.buildGraph()
        )
    }

    /**
     * Re-projects the scene (including base component pins and user-drawn normalized pins)
     * onto a new [SceneViewport] (zoom and pan).
     */
    fun withViewport(newViewport: SceneViewport): TactileScene {
        return copy(viewport = newViewport).reprojectScene()
    }

    /**
     * Reverts an edit operation using [undoRecord].
     */
    fun undo(undoRecord: UndoRecord): SceneEditResult {
        val restored = undoRecord.previousScene
        return SceneEditResult(
            scene = restored,
            undoRecord = UndoRecord(this, "undo: ${undoRecord.description}"),
            hitGrid = restored.buildHitGrid(),
            graph = restored.buildGraph()
        )
    }

    /**
     * Re-projects all component bounding boxes, base normalized pins, and user [PinDrawingLayer]
     * strokes into [PinGrid] and [Component.raisedPins] for the active [viewport].
     */
    fun reprojectScene(): TactileScene {
        val newGrid = PinGrid()
        val erasedProjected = HashSet<Int>()
        for (pt in drawingLayer.points) {
            if (pt.isErased) {
                val px = viewport.normalizedToPinX(pt.normX)
                val py = viewport.normalizedToPinY(pt.normY)
                if (px in 0 until PinGrid.WIDTH && py in 0 until PinGrid.CANVAS_HEIGHT) {
                    erasedProjected.add(PinGrid.packCanvasCoord(px, py))
                }
            }
        }

        val updatedComponents = components.map { comp ->
            val projectedPinBox = comp.bbox.toPinRect(viewport)
            val basePacked = LinkedHashSet<Int>()

            if (comp.baseNormalizedPins.isNotEmpty()) {
                for (npt in comp.baseNormalizedPins) {
                    val px = viewport.normalizedToPinX(npt.x)
                    val py = viewport.normalizedToPinY(npt.y)
                    if (px in 0 until PinGrid.WIDTH && py in 0 until PinGrid.CANVAS_HEIGHT) {
                        val packed = PinGrid.packCanvasCoord(px, py)
                        if (packed !in erasedProjected) {
                            basePacked.add(packed)
                        }
                    }
                }
            } else if (viewport == SceneViewport()) {
                for (packed in comp.raisedPins) {
                    if (packed in 0 until PinGrid.CANVAS_PIN_COUNT && packed !in erasedProjected) {
                        basePacked.add(packed)
                    }
                }
            }

            val drawnPacked = drawingLayer.projectRaisedPinsForComponent(comp.id, comp.key, viewport)
            for (packed in drawnPacked) {
                if (packed !in erasedProjected) {
                    basePacked.add(packed)
                }
            }

            for (packed in basePacked) {
                val px = PinGrid.unpackX(packed)
                val py = PinGrid.unpackY(packed)
                newGrid.setCanvasPin(px, py, true)
            }

            comp.copy(
                pinBox = projectedPinBox,
                raisedPins = basePacked.toIntArray()
            )
        }

        return copy(
            pinGrid = newGrid,
            components = updatedComponents
        )
    }
}
