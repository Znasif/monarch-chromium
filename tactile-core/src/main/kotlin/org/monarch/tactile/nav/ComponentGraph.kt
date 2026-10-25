package org.monarch.tactile.nav

import org.monarch.tactile.model.Component
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Direction {
    UP, DOWN, LEFT, RIGHT
}

enum class NavigationFeedback(val label: String) {
    NONE("none"),
    BOUNDARY_BEEP("boundary beep")
}

data class NavigationStep(
    val fromComponentId: Int,
    val toComponentId: Int,
    val moved: Boolean,
    val feedback: NavigationFeedback
)

data class GraphNode(
    val componentId: Int,
    val directionalEdges: Map<Direction, Int>,
    val enterChildId: Int?,
    val exitParentId: Int?
)

/**
 * Generalized Data Navigator spatial & hierarchical component graph.
 *
 * - Directional edges ([Direction.UP], [Direction.DOWN], [Direction.LEFT], [Direction.RIGHT])
 *   connect sibling components sharing the same [Component.parentId].
 * - Hierarchical edges ([enter] / [exit]) drill into the first child in reading order
 *   (top-to-bottom, left-to-right) and step back out to the parent component.
 */
class ComponentGraph private constructor(
    private val nodes: Map<Int, GraphNode>,
    val rootIds: List<Int>,
    private val childrenByParent: Map<Int, List<Int>>
) {
    fun node(componentId: Int): GraphNode? = nodes[componentId]

    fun orderedChildrenOf(parentId: Int): List<Int> = childrenByParent[parentId] ?: emptyList()

    fun move(fromComponentId: Int, direction: Direction): Int? {
        return nodes[fromComponentId]?.directionalEdges?.get(direction)
    }

    fun enter(fromComponentId: Int): Int? {
        return nodes[fromComponentId]?.enterChildId
    }

    fun exit(fromComponentId: Int): Int? {
        return nodes[fromComponentId]?.exitParentId
    }

    /**
     * Executes a directional navigation step, staying on [fromComponentId] and signaling
     * [NavigationFeedback.BOUNDARY_BEEP] when no edge exists in [direction].
     */
    fun stepDirectional(fromComponentId: Int, direction: Direction): NavigationStep {
        val target = move(fromComponentId, direction)
        return if (target != null) {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = target,
                moved = true,
                feedback = NavigationFeedback.NONE
            )
        } else {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = fromComponentId,
                moved = false,
                feedback = NavigationFeedback.BOUNDARY_BEEP
            )
        }
    }

    /**
     * Drills into the first child of [fromComponentId] (ordered top-to-bottom, left-to-right),
     * or emits [NavigationFeedback.BOUNDARY_BEEP] if [fromComponentId] is a leaf.
     */
    fun stepEnter(fromComponentId: Int): NavigationStep {
        val child = enter(fromComponentId)
        return if (child != null) {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = child,
                moved = true,
                feedback = NavigationFeedback.NONE
            )
        } else {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = fromComponentId,
                moved = false,
                feedback = NavigationFeedback.BOUNDARY_BEEP
            )
        }
    }

    /**
     * Steps out from a child component to its parent, or emits [NavigationFeedback.BOUNDARY_BEEP]
     * if [fromComponentId] is already a top-level root component.
     */
    fun stepBack(fromComponentId: Int): NavigationStep {
        val parent = exit(fromComponentId)
        return if (parent != null) {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = parent,
                moved = true,
                feedback = NavigationFeedback.NONE
            )
        } else {
            NavigationStep(
                fromComponentId = fromComponentId,
                toComponentId = fromComponentId,
                moved = false,
                feedback = NavigationFeedback.BOUNDARY_BEEP
            )
        }
    }

    companion object {
        /**
         * Sorts components in natural reading order:
         * - For side-by-side components in the same horizontal band (vertical overlap > 50% of
         *   smaller height and distinct horizontal positions), orders left-to-right by `xmin`.
         * - Otherwise (e.g. stacked components inside a panel), orders top-to-bottom by `ymin`,
         *   breaking ties left-to-right by `xmin`.
         */
        fun sortReadingOrder(components: List<Component>, topToBottomFirst: Boolean = false): List<Component> {
            return components.sortedWith { a, b ->
                if (!topToBottomFirst) {
                    val overlapY = max(0f, min(a.bbox.ymax, b.bbox.ymax) - max(a.bbox.ymin, b.bbox.ymin))
                    val minH = min(a.bbox.height, b.bbox.height).coerceAtLeast(1e-4f)
                    val sameRowBand = (overlapY / minH) > 0.5f && abs(a.bbox.centerX - b.bbox.centerX) > 1e-3f
                    if (sameRowBand) {
                        val cmpX = a.bbox.xmin.compareTo(b.bbox.xmin)
                        if (cmpX != 0) return@sortedWith cmpX
                    }
                }
                val cmpY = a.bbox.ymin.compareTo(b.bbox.ymin)
                if (cmpY != 0) return@sortedWith cmpY
                val cmpX = a.bbox.xmin.compareTo(b.bbox.xmin)
                if (cmpX != 0) return@sortedWith cmpX
                a.id.compareTo(b.id)
            }
        }

        fun build(components: List<Component>): ComponentGraph {
            if (components.isEmpty()) {
                return ComponentGraph(emptyMap(), emptyList(), emptyMap())
            }

            val byId = components.associateBy { it.id }

            // Group children by parentId; also include explicit childIds references
            val rawChildrenMap = mutableMapOf<Int, MutableSet<Component>>()
            val topLevelSet = mutableSetOf<Component>()

            for (comp in components) {
                val pId = comp.parentId
                if (pId != null && byId.containsKey(pId)) {
                    rawChildrenMap.getOrPut(pId) { linkedSetOf() }.add(comp)
                } else {
                    topLevelSet.add(comp)
                }
                for (cid in comp.childIds) {
                    val child = byId[cid]
                    if (child != null) {
                        rawChildrenMap.getOrPut(comp.id) { linkedSetOf() }.add(child)
                    }
                }
            }

            val orderedRoots = sortReadingOrder(topLevelSet.toList(), topToBottomFirst = false)
            val orderedChildrenByParent = mutableMapOf<Int, List<Component>>()
            for ((parentId, childSet) in rawChildrenMap) {
                // Inside a parent container (such as a comic panel or figure), reading order is top-to-bottom first
                orderedChildrenByParent[parentId] = sortReadingOrder(childSet.toList(), topToBottomFirst = true)
            }

            // Compute directional edges within each sibling group
            val directionalMap = mutableMapOf<Int, Map<Direction, Int>>()
            computeSiblingDirectionalEdges(orderedRoots, directionalMap)
            for (siblings in orderedChildrenByParent.values) {
                computeSiblingDirectionalEdges(siblings, directionalMap)
            }

            val nodes = mutableMapOf<Int, GraphNode>()
            for (comp in components) {
                val children = orderedChildrenByParent[comp.id] ?: emptyList()
                val resolvedParentId = comp.parentId ?: rawChildrenMap.entries.firstOrNull { (_, set) ->
                    set.any { it.id == comp.id }
                }?.key
                nodes[comp.id] = GraphNode(
                    componentId = comp.id,
                    directionalEdges = directionalMap[comp.id] ?: emptyMap(),
                    enterChildId = children.firstOrNull()?.id,
                    exitParentId = resolvedParentId
                )
            }

            return ComponentGraph(
                nodes = nodes,
                rootIds = orderedRoots.map { it.id },
                childrenByParent = orderedChildrenByParent.mapValues { (_, list) -> list.map { it.id } }
            )
        }

        private fun computeSiblingDirectionalEdges(
            siblings: List<Component>,
            outMap: MutableMap<Int, Map<Direction, Int>>
        ) {
            if (siblings.size <= 1) {
                for (s in siblings) outMap[s.id] = emptyMap()
                return
            }

            for (source in siblings) {
                val edges = mutableMapOf<Direction, Int>()
                for (dir in Direction.entries) {
                    var bestCandidateId: Int? = null
                    var bestScore = Float.POSITIVE_INFINITY

                    for (cand in siblings) {
                        if (cand.id == source.id) continue
                        val score = directionalCandidateScore(source, cand, dir) ?: continue
                        if (score < bestScore) {
                            bestScore = score
                            bestCandidateId = cand.id
                        }
                    }

                    if (bestCandidateId != null) {
                        edges[dir] = bestCandidateId
                    }
                }
                outMap[source.id] = edges
            }
        }

        private fun directionalCandidateScore(
            source: Component,
            candidate: Component,
            direction: Direction
        ): Float? {
            val sx = source.bbox.centerX
            val sy = source.bbox.centerY
            val cx = candidate.bbox.centerX
            val cy = candidate.bbox.centerY
            val dx = cx - sx
            val dy = cy - sy

            val overlapX = max(0f, min(source.bbox.xmax, candidate.bbox.xmax) - max(source.bbox.xmin, candidate.bbox.xmin))
            val overlapY = max(0f, min(source.bbox.ymax, candidate.bbox.ymax) - max(source.bbox.ymin, candidate.bbox.ymin))

            return when (direction) {
                Direction.RIGHT -> {
                    if (dx <= 1e-4f) return null
                    if (overlapY <= 0f && abs(dy) > dx * 1.25f) return null
                    val edgeGap = (candidate.bbox.xmin - source.bbox.xmax).coerceAtLeast(0f)
                    edgeGap + dx * 0.5f + abs(dy) * 2.0f
                }
                Direction.LEFT -> {
                    if (dx >= -1e-4f) return null
                    val absDx = -dx
                    if (overlapY <= 0f && abs(dy) > absDx * 1.25f) return null
                    val edgeGap = (source.bbox.xmin - candidate.bbox.xmax).coerceAtLeast(0f)
                    edgeGap + absDx * 0.5f + abs(dy) * 2.0f
                }
                Direction.DOWN -> {
                    if (dy <= 1e-4f) return null
                    if (overlapX <= 0f && abs(dx) > dy * 1.25f) return null
                    val edgeGap = (candidate.bbox.ymin - source.bbox.ymax).coerceAtLeast(0f)
                    edgeGap + dy * 0.5f + abs(dx) * 2.0f
                }
                Direction.UP -> {
                    if (dy >= -1e-4f) return null
                    val absDy = -dy
                    if (overlapX <= 0f && abs(dx) > absDy * 1.25f) return null
                    val edgeGap = (source.bbox.ymin - candidate.bbox.ymax).coerceAtLeast(0f)
                    edgeGap + absDy * 0.5f + abs(dx) * 2.0f
                }
            }
        }
    }
}
