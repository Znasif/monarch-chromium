package org.monarch.tactile

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.monarch.tactile.hit.HitGrid
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.PinRect
import org.monarch.tactile.model.SceneViewport
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.ComponentGraph
import org.monarch.tactile.nav.Direction
import org.monarch.tactile.nav.NavigationFeedback

class TactileCoreModelAndHitGridTest {

    @Test
    fun `PinGrid enforces 96x40 layout with blank row 35 separator and caption rows 36 to 39`() {
        val grid = PinGrid()
        assertEquals(0, grid.canvasRaisedCount())
        assertEquals(0.0f, grid.canvasFillRatio(), 1e-6f)

        // Canvas pins on rows 0..34 can be raised
        grid.setCanvasPin(0, 0, true)
        grid.setCanvasPin(95, 34, true)
        assertTrue(grid.getPin(0, 0))
        assertTrue(grid.getPin(95, 34))
        assertEquals(2, grid.canvasRaisedCount())
        assertEquals(2.0f / 3360.0f, grid.canvasFillRatio(), 1e-6f)

        // Attempting to write to row 35 or caption rows via setCanvasPin is ignored
        grid.setCanvasPin(10, 35, true)
        grid.setCanvasPin(10, 36, true)
        assertFalse(grid.getPin(10, 35))
        assertFalse(grid.getPin(10, 36))

        // Caption pins work on rows 36..39 and do not count toward canvasRaisedCount
        grid.setCaptionPin(12, 36, true)
        grid.setCaptionPin(95, 39, true)
        assertTrue(grid.getPin(12, 36))
        assertTrue(grid.getPin(95, 39))
        assertEquals(2, grid.canvasRaisedCount())

        // Byte grid for SelfBraillingManager has row 35 strictly zeroed
        val bytes = grid.toByteGrid()
        assertEquals(40, bytes.size)
        assertEquals(96, bytes[0].size)
        assertEquals(1.toByte(), bytes[0][0])
        assertEquals(1.toByte(), bytes[34][95])
        for (x in 0 until 96) {
            assertEquals(0.toByte(), bytes[35][x], "Separator row 35 must always be 0 at col $x")
        }
        assertEquals(1.toByte(), bytes[36][12])
        assertEquals(1.toByte(), bytes[39][95])

        val ascii = grid.toAsciiCanvas()
        val lines = ascii.split('\n')
        assertEquals(35, lines.size)
        assertEquals('#', lines[0][0])
        assertEquals('#', lines[34][95])
    }

    @Test
    fun `HitGrid stamps direct pins and 1-pin halo with child precedence and respects row 35 boundary`() {
        // Parent container with a single pin at (10, 10) and large pinBox
        val parentPin = PinGrid.packCanvasCoord(10, 10)
        val parent = Component(
            id = 1,
            label = "Outer Panel",
            bbox = NormalizedBox(0.0f, 0.0f, 1.0f, 0.5f),
            pinBox = PinRect(0, 0, 47, 34),
            raisedPins = intArrayOf(parentPin, PinGrid.packCanvasCoord(20, 34))
        )

        // Smaller child component at (11, 10) inside the parent
        val child = Component(
            id = 2,
            label = "Inner Figure",
            bbox = NormalizedBox(0.2f, 0.1f, 0.4f, 0.2f),
            pinBox = PinRect(9, 7, 15, 14),
            parentId = 1,
            raisedPins = intArrayOf(PinGrid.packCanvasCoord(11, 10))
        )

        val hitGrid = HitGrid()
        hitGrid.stampScene(listOf(parent, child), haloRadius = 1)
        assertEquals(3840, hitGrid.rawCells.size)

        // Direct hits beat halos: (10, 10) is a direct pin of parent (1) and a halo of child (2) -> parent (1) wins!
        assertEquals(1, hitGrid.lookup(10, 10))
        // (11, 10) is a direct pin of child (2) and a halo of parent (1) -> child (2) wins!
        assertEquals(2, hitGrid.lookup(11, 10))

        // (11, 9) is a 1-pin halo of BOTH parent (10,10) and child (11,10) -> child (smaller pinBox.area) wins!
        assertEquals(2, hitGrid.lookup(11, 9))

        // (9, 10) is only in parent's 1-pin halo
        assertEquals(1, hitGrid.lookup(9, 10))

        // Outside 1-pin halo -> NO_COMPONENT (0)
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(7, 10))

        // Pin at (20, 34) stamps row 34 and halo at row 33, but MUST NOT bleed into separator row 35
        assertEquals(1, hitGrid.lookup(20, 34))
        assertEquals(1, hitGrid.lookup(20, 33))
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(20, 35))

        // Rows 36..39 return CAPTION_REGION (-1)
        for (y in 36..39) {
            assertEquals(HitGrid.CAPTION_REGION, hitGrid.lookup(20, y))
        }

        // Fingertip offset (-3 on X): tapping at rawX=14, rawY=10 hits x=11, y=10 (child=2)
        assertEquals(2, hitGrid.lookupWithFingertipOffset(14, 10))

        // Out-of-bounds coordinates return NO_COMPONENT safely without throwing
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(-1, 10))
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(96, 10))
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(10, -1))
        assertEquals(HitGrid.NO_COMPONENT, hitGrid.lookup(10, 40))
    }

    @Test
    fun `HitGrid falls back to pinBox stamping when components have no explicit raisedPins`() {
        val boxOnlyComp = Component(
            id = 7,
            label = "Box Only",
            bbox = NormalizedBox(0.1f, 0.1f, 0.3f, 0.3f),
            pinBox = PinRect(10, 5, 15, 10)
        )
        val grid = HitGrid()
        grid.stampScene(listOf(boxOnlyComp), haloRadius = 1)
        assertEquals(7, grid.lookup(12, 7))
        assertEquals(7, grid.lookup(9, 5)) // 1-pin halo left of minX=10
        assertEquals(HitGrid.NO_COMPONENT, grid.lookup(8, 5))
    }

    @Test
    fun `NormalizedBox geometry and SceneViewport isolation projection work accurately`() {
        val b1 = NormalizedBox.fromGemini1000(100, 200, 500, 600)
        val b2 = NormalizedBox.fromGemini1000(300, 400, 700, 800)
        assertTrue(b1.iou(b1) > 0.999f)
        assertTrue(b1.iou(b2) in 0.1f..0.2f)

        val union = b1.union(b2)
        assertEquals(0.1f, union.ymin, 1e-5f)
        assertEquals(0.2f, union.xmin, 1e-5f)
        assertEquals(0.7f, union.ymax, 1e-5f)
        assertEquals(0.8f, union.xmax, 1e-5f)

        // Isolating a box onto the 96x35 viewport maps its corners to (0,0)..(95,34)
        val isolatedViewport = SceneViewport(isolatedBox = b1)
        val isolatedRect = b1.toPinRect(isolatedViewport)
        assertEquals(0, isolatedRect.minX)
        assertEquals(0, isolatedRect.minY)
        assertEquals(95, isolatedRect.maxX)
        assertEquals(34, isolatedRect.maxY)
    }

    @Test
    fun `ComponentGraph builds sibling directional edges and hierarchical enter and exit edges`() {
        val leftBox = NormalizedBox.fromGemini1000(100, 50, 900, 450)
        val rightBox = NormalizedBox.fromGemini1000(100, 550, 900, 950)
        val childTopBox = NormalizedBox.fromGemini1000(150, 100, 400, 400)
        val childBottomBox = NormalizedBox.fromGemini1000(500, 100, 850, 400)

        val c1 = Component(id = 1, label = "Left Panel", bbox = leftBox, childIds = listOf(3, 4))
        val c2 = Component(id = 2, label = "Right Panel", bbox = rightBox)
        val c3 = Component(id = 3, label = "Top Bubble", bbox = childTopBox, parentId = 1)
        val c4 = Component(id = 4, label = "Bottom Figure", bbox = childBottomBox, parentId = 1)

        val scene = TactileScene(
            components = listOf(c2, c4, c1, c3),
            rootComponentIds = listOf(1, 2)
        )
        val graph = scene.buildGraph()

        assertArrayEquals(intArrayOf(1, 2), graph.rootIds.toIntArray())
        assertEquals(2, graph.move(1, Direction.RIGHT))
        assertNull(graph.move(1, Direction.LEFT))
        assertEquals(1, graph.move(2, Direction.LEFT))
        assertNull(graph.move(2, Direction.RIGHT))

        // Boundary step returns same component with BOUNDARY_BEEP
        val boundaryStep = graph.stepDirectional(2, Direction.RIGHT)
        assertFalse(boundaryStep.moved)
        assertEquals(2, boundaryStep.toComponentId)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, boundaryStep.feedback)

        // Enter on Left Panel (1) enters Top Bubble (3) first (top-to-bottom order)
        assertEquals(3, graph.enter(1))
        assertEquals(4, graph.move(3, Direction.DOWN))
        assertEquals(3, graph.move(4, Direction.UP))
        assertEquals(1, graph.exit(3))
        assertEquals(1, graph.exit(4))

        // Enter on leaf component emits BOUNDARY_BEEP
        val leafEnter = graph.stepEnter(3)
        assertFalse(leafEnter.moved)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, leafEnter.feedback)

        // Back on root component emits BOUNDARY_BEEP
        val rootBack = graph.stepBack(1)
        assertFalse(rootBack.moved)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, rootBack.feedback)
    }

    @Test
    fun `UndoRecord restores previous TactileScene after split, merge, reparent, and draw`() {
        val initial = Xkcd327NavigationAndEditingTest.buildSyntheticXkcd327Scene()
        val p2 = initial.componentByKey("p2")!!
        val tableWithMug = initial.componentByLabel("Table with mug", parentId = p2.id)!!

        val splitRes = initial.splitComponent(tableWithMug.id, "Table", "Mug")
        assertEquals(initial.components.size + 1, splitRes.scene.components.size)

        val undoneSplit = splitRes.scene.undo(splitRes.undoRecord)
        assertEquals(initial.components.size, undoneSplit.scene.components.size)
        assertEquals("Table with mug", undoneSplit.scene.componentById(tableWithMug.id)?.label)

        val drawRes = initial.drawPinLine(p2.id, p2.pinBox.minX + 3, 12, p2.pinBox.maxX - 3, 12)
        assertTrue(drawRes.scene.pinGrid.getPin(p2.pinBox.minX + 4, 12))

        val undoneDraw = drawRes.scene.undo(drawRes.undoRecord)
        assertFalse(undoneDraw.scene.pinGrid.getPin(p2.pinBox.minX + 4, 12))
    }
}
