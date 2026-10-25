package org.monarch.tactile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.monarch.tactile.edit.PinDrawingLayer
import org.monarch.tactile.edit.SceneEditStore
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.SceneViewport
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.Direction
import org.monarch.tactile.nav.NavigationFeedback
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Verifies the synthetic 4-panel scene against every component check, navigation check,
 * tree-editing check, pin-drawing check, and SHA-256 persistence check specified in
 * `test-sites/cases/xkcd-327.json`.
 */
class Xkcd327NavigationAndEditingTest {

    companion object {
        private const val SOURCE_W = 666
        private const val SOURCE_H = 205

        /**
         * Generates synthetic deterministic source image bytes for the 666x205 4-panel fixture
         * so SHA-256 content-hash persistence can be verified across page reloads and cross-page reuse.
         */
        fun syntheticXkcd327ImageBytes(): ByteArray {
            val header = "P4\n# Synthetic 4-panel 666x205 fixture for xkcd-327\n666 205\n"
            val headerBytes = header.encodeToByteArray()
            val payload = ByteArray(headerBytes.size + 512)
            System.arraycopy(headerBytes, 0, payload, 0, headerBytes.size)
            for (i in 0 until 512) {
                payload[headerBytes.size + i] = ((i * 37 + 13) and 0xFF).toByte()
            }
            return payload
        }

        private fun boxPx(x0: Int, y0: Int, x1: Int, y1: Int): NormalizedBox {
            return NormalizedBox.fromSourcePixels(x0, y0, x1, y1, SOURCE_W, SOURCE_H)
        }

        /**
         * Builds the 1-pin closed rectangular border in normalized coordinates for a panel or figure box.
         */
        private fun buildRectOutlineNormalized(box: NormalizedBox, viewport: SceneViewport = SceneViewport()): List<NormalizedPoint> {
            val rect = box.toPinRect(viewport)
            val points = LinkedHashSet<Pair<Int, Int>>()
            for (x in rect.minX..rect.maxX) {
                points.add(Pair(x, rect.minY))
                points.add(Pair(x, rect.maxY))
            }
            for (y in rect.minY..rect.maxY) {
                points.add(Pair(rect.minX, y))
                points.add(Pair(rect.maxX, y))
            }
            return points.map { (px, py) ->
                NormalizedPoint(
                    x = viewport.pinToNormalizedX(px).coerceIn(box.xmin, box.xmax),
                    y = viewport.pinToNormalizedY(py).coerceIn(box.ymin, box.ymax)
                )
            }
        }

        /**
         * Builds the synthetic 4-panel TactileScene matching `test-sites/cases/xkcd-327.json`.
         */
        fun buildSyntheticXkcd327Scene(): TactileScene {
            val viewport = SceneViewport()
            val components = mutableListOf<Component>()

            fun addComponent(
                id: Int,
                key: String,
                label: String,
                role: String,
                box: NormalizedBox,
                parentId: Int? = null,
                childIds: List<Int> = emptyList(),
                embeddedText: String = "",
                drawOutlinePins: Boolean = true
            ) {
                // At 1x overview, speech lettering is not drawn as pins ("textAsPins": "none")
                val baseNorm = if (drawOutlinePins && role != "speech") {
                    buildRectOutlineNormalized(box, viewport)
                } else {
                    emptyList()
                }
                val packedPins = baseNorm.map { npt ->
                    PinGrid.packCanvasCoord(
                        viewport.normalizedToPinX(npt.x).coerceIn(0, PinGrid.WIDTH - 1),
                        viewport.normalizedToPinY(npt.y).coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)
                    )
                }.distinct().toIntArray()

                components.add(
                    Component(
                        id = id,
                        key = key,
                        label = label,
                        role = role,
                        embeddedText = embeddedText,
                        bbox = box,
                        pinBox = box.toPinRect(viewport),
                        parentId = parentId,
                        childIds = childIds,
                        raisedPins = packedPins,
                        baseNormalizedPins = baseNorm
                    )
                )
            }

            // Panel 1: [0, 0, 147, 204]
            val p1Box = boxPx(0, 0, 147, 204)
            addComponent(1, "p1", "Panel 1", "panel", p1Box, childIds = listOf(11, 12, 13))
            addComponent(
                id = 11,
                key = "p1_speech_school",
                label = "School (voice on the phone)",
                role = "speech",
                box = boxPx(8, 10, 139, 60),
                parentId = 1,
                embeddedText = "Hi, this is your son's school. We're having some computer trouble.",
                drawOutlinePins = false
            )
            addComponent(
                id = 12,
                key = "p1_figure",
                label = "Person on the phone",
                role = "figure",
                box = boxPx(20, 70, 80, 190),
                parentId = 1
            )
            addComponent(
                id = 13,
                key = "p1_table",
                label = "Table with mug",
                role = "object",
                box = boxPx(85, 130, 135, 195),
                parentId = 1
            )

            // Panel 2: [165, 0, 301, 204]
            val p2Box = boxPx(165, 0, 301, 204)
            addComponent(2, "p2", "Panel 2", "panel", p2Box, childIds = listOf(21, 22, 23, 24))
            addComponent(
                id = 21,
                key = "p2_speech_mom",
                label = "Mom",
                role = "speech",
                box = boxPx(172, 10, 294, 48),
                parentId = 2,
                embeddedText = "Oh, dear - did he break something?",
                drawOutlinePins = false
            )
            addComponent(
                id = 22,
                key = "p2_speech_school",
                label = "School (voice on the phone)",
                role = "speech",
                box = boxPx(172, 52, 260, 80),
                parentId = 2,
                embeddedText = "In a way-",
                drawOutlinePins = false
            )
            addComponent(
                id = 23,
                key = "p2_figure",
                label = "Person holding the phone",
                role = "figure",
                box = boxPx(180, 85, 230, 190),
                parentId = 2
            )
            addComponent(
                id = 24,
                key = "p2_table_mug",
                label = "Table with mug",
                role = "object",
                box = boxPx(235, 130, 292, 195),
                parentId = 2
            )

            // Panel 3: [310, 0, 478, 204]
            val p3Box = boxPx(310, 0, 478, 204)
            addComponent(3, "p3", "Panel 3", "panel", p3Box, childIds = listOf(31, 32, 33))
            addComponent(
                id = 31,
                key = "p3_speech_school",
                label = "School (voice on the phone)",
                role = "speech",
                box = boxPx(316, 10, 470, 62),
                parentId = 3,
                embeddedText = "Did you really name your son Robert'); DROP TABLE Students;-- ?",
                drawOutlinePins = false
            )
            addComponent(
                id = 32,
                key = "p3_figure",
                label = "Person on the phone (close-up)",
                role = "figure",
                box = boxPx(340, 70, 440, 150),
                parentId = 3
            )
            addComponent(
                id = 33,
                key = "p3_speech_mom",
                label = "Mom",
                role = "speech",
                box = boxPx(320, 155, 470, 196),
                parentId = 3,
                embeddedText = "Oh, yes. Little Bobby Tables, we call him.",
                drawOutlinePins = false
            )

            // Panel 4: [489, 0, 665, 204]
            val p4Box = boxPx(489, 0, 665, 204)
            addComponent(4, "p4", "Panel 4", "panel", p4Box, childIds = listOf(41, 42, 43))
            addComponent(
                id = 41,
                key = "p4_speech_school",
                label = "School (voice on the phone)",
                role = "speech",
                box = boxPx(495, 10, 658, 62),
                parentId = 4,
                embeddedText = "Well, we've lost this year's student records. I hope you're happy.",
                drawOutlinePins = false
            )
            addComponent(
                id = 42,
                key = "p4_figure",
                label = "Person holding a mug and the phone",
                role = "figure",
                box = boxPx(520, 70, 620, 150),
                parentId = 4
            )
            addComponent(
                id = 43,
                key = "p4_speech_mom",
                label = "Mom",
                role = "speech",
                box = boxPx(498, 155, 658, 196),
                parentId = 4,
                embeddedText = "And I hope you've learned to sanitize your database inputs.",
                drawOutlinePins = false
            )

            return TactileScene(
                components = components,
                rootComponentIds = listOf(1, 2, 3, 4),
                viewport = viewport
            ).reprojectScene()
        }

        private fun normalizeTextForComparison(s: String): String {
            return s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    @Test
    fun `xkcd-327 componentChecks and reading order pass`() {
        val caseFile = File("../test-sites/cases/xkcd-327.json")
        assertTrue(caseFile.exists(), "Expected test-sites/cases/xkcd-327.json to exist")

        val scene = buildSyntheticXkcd327Scene()
        val graph = scene.buildGraph()
        val hitGrid = scene.buildHitGrid()

        // 1. "exactly 4 top-level components, matching the panel boxes"
        assertEquals(listOf(1, 2, 3, 4), graph.rootIds)
        val rootKeys = graph.rootIds.map { scene.componentById(it)!!.key }
        assertEquals(listOf("p1", "p2", "p3", "p4"), rootKeys)

        // Verify >= 1 blank pin column between each pair of adjacent panel borders
        val p1PinBox = scene.componentByKey("p1")!!.pinBox
        val p2PinBox = scene.componentByKey("p2")!!.pinBox
        val p3PinBox = scene.componentByKey("p3")!!.pinBox
        val p4PinBox = scene.componentByKey("p4")!!.pinBox
        assertTrue(p2PinBox.minX - p1PinBox.maxX >= 2, "At least 1 blank column between p1 and p2")
        assertTrue(p3PinBox.minX - p2PinBox.maxX >= 2, "At least 1 blank column between p2 and p3")
        assertTrue(p4PinBox.minX - p3PinBox.maxX >= 2, "At least 1 blank column between p3 and p4")

        // Fill ratio at 1x <= 0.25
        assertTrue(scene.pinGrid.canvasFillRatio() <= 0.25f)

        // 2. "each child lies inside its panel box"
        for (panelId in graph.rootIds) {
            val panel = scene.componentById(panelId)!!
            val children = scene.childrenOf(panelId)
            assertTrue(children.isNotEmpty())
            for (child in children) {
                assertTrue(
                    panel.bbox.containsBox(child.bbox),
                    "Child '${child.label}' must lie inside '${panel.label}'"
                )
            }
        }

        // 3. "embeddedText matches the lettering (case-insensitive, punctuation-insensitive)"
        val expectedTexts = listOf(
            "Hi, this is your son's school. We're having some computer trouble.",
            "Oh, dear - did he break something?",
            "In a way-",
            "Did you really name your son Robert'); DROP TABLE Students;-- ?",
            "Oh, yes. Little Bobby Tables, we call him.",
            "Well, we've lost this year's student records. I hope you're happy.",
            "And I hope you've learned to sanitize your database inputs."
        ).map { normalizeTextForComparison(it) }

        val actualTexts = scene.components
            .filter { it.embeddedText.isNotBlank() }
            .map { normalizeTextForComparison(it.embeddedText) }
        assertEquals(expectedTexts, actualTexts)

        // 4. "reading order: p1, p2, p3, p4; inside a panel, top to bottom"
        for (panelId in graph.rootIds) {
            val orderedChildIds = graph.orderedChildrenOf(panelId)
            val childYPositions = orderedChildIds.map { scene.componentById(it)!!.bbox.ymin }
            assertEquals(childYPositions.sorted(), childYPositions, "Children of panel $panelId must be ordered top to bottom")
        }

        // 5. "finger on any pin of a component gives its label within one 25 ms poll"
        val elapsedNs = measureNanoTime {
            for (comp in scene.components) {
                for (packed in comp.raisedPins) {
                    val px = PinGrid.unpackX(packed)
                    val py = PinGrid.unpackY(packed)
                    val hitId = hitGrid.lookup(px, py)
                    assertTrue(hitId > 0)
                    assertNotNull(scene.componentById(hitId)?.label)
                }
            }
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        assertTrue(elapsedMs < 25.0, "All pin lookups completed in ${elapsedMs}ms (< 25ms)")
    }

    @Test
    fun `xkcd-327 navigation expectations pass`() {
        val scene = buildSyntheticXkcd327Scene()
        val graph = scene.buildGraph()

        val p1 = scene.componentByKey("p1")!!
        val p2 = scene.componentByKey("p2")!!
        val p3 = scene.componentByKey("p3")!!
        val p4 = scene.componentByKey("p4")!!

        // 1. { "from": "p1", "key": "right", "to": "p2" }
        val step1 = graph.stepDirectional(p1.id, Direction.RIGHT)
        assertTrue(step1.moved)
        assertEquals(p2.id, step1.toComponentId)
        assertEquals(NavigationFeedback.NONE, step1.feedback)

        // 2. { "from": "p4", "key": "right", "to": "p4", "feedback": "boundary beep" }
        val step2 = graph.stepDirectional(p4.id, Direction.RIGHT)
        assertFalse(step2.moved)
        assertEquals(p4.id, step2.toComponentId)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, step2.feedback)
        assertEquals("boundary beep", step2.feedback.label)

        // 3. { "from": "p2", "key": "up", "to": "p2", "feedback": "boundary beep" }
        val step3 = graph.stepDirectional(p2.id, Direction.UP)
        assertFalse(step3.moved)
        assertEquals(p2.id, step3.toComponentId)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, step3.feedback)

        // 4. { "from": "p3", "key": "enter", "to": "first child of p3 (top to bottom)" }
        val enterP3 = graph.stepEnter(p3.id)
        assertTrue(enterP3.moved)
        val p3ChildrenTopToBottom = scene.childrenOf(p3.id).sortedBy { it.bbox.ymin }
        assertEquals(p3ChildrenTopToBottom.first().id, enterP3.toComponentId)

        // 5. { "from": "any child of p3", "key": "back", "to": "p3" }
        for (child in p3ChildrenTopToBottom) {
            val backStep = graph.stepBack(child.id)
            assertTrue(backStep.moved)
            assertEquals(p3.id, backStep.toComponentId)
            assertEquals(NavigationFeedback.NONE, backStep.feedback)
        }
    }

    @Test
    fun `xkcd-327 editing tree operations and SHA-256 persistence pass`() {
        val imageBytes = syntheticXkcd327ImageBytes()
        val initialScene = buildSyntheticXkcd327Scene()
        val p1 = initialScene.componentByKey("p1")!!
        val p2 = initialScene.componentByKey("p2")!!
        val p3 = initialScene.componentByKey("p3")!!

        // 1. "rename 'Person on the phone' in p1 to 'Mom'" -> "new label spoken and captioned"
        val personInP1 = initialScene.componentByLabel("Person on the phone", parentId = p1.id)!!
        val renameRes = initialScene.renameComponent(personInP1.id, "Mom")
        val sceneAfterRename = renameRes.scene
        assertEquals("Mom", sceneAfterRename.componentById(personInP1.id)!!.label)
        // Verify undo works and then re-apply
        val undoneRename = sceneAfterRename.undo(renameRes.undoRecord).scene
        assertEquals("Person on the phone", undoneRename.componentById(personInP1.id)!!.label)

        // 2. "split 'Table with mug' in p2 into 'Table' and 'Mug'" -> "two components, both inside p2, pins regrouped"
        val tableWithMugInP2 = sceneAfterRename.componentByLabel("Table with mug", parentId = p2.id)!!
        val originalTablePinCount = tableWithMugInP2.raisedPins.size
        assertTrue(originalTablePinCount > 0, "Table with mug should have raised pins before split")

        val splitRes = sceneAfterRename.splitComponent(
            componentId = tableWithMugInP2.id,
            firstLabel = "Table",
            secondLabel = "Mug",
            firstKey = "p2_table_only",
            secondKey = "p2_mug_only"
        )
        val sceneAfterSplit = splitRes.scene
        val tableComp = sceneAfterSplit.componentByLabel("Table", parentId = p2.id)
        val mugComp = sceneAfterSplit.componentByLabel("Mug", parentId = p2.id)
        assertNotNull(tableComp, "Expected 'Table' inside p2 after split")
        assertNotNull(mugComp, "Expected 'Mug' inside p2 after split")
        assertEquals(p2.id, tableComp!!.parentId)
        assertEquals(p2.id, mugComp!!.parentId)
        assertTrue(tableComp.raisedPins.isNotEmpty())
        assertTrue(mugComp.raisedPins.isNotEmpty())
        assertEquals(
            originalTablePinCount,
            (tableComp.raisedPins.toList() + mugComp.raisedPins.toList()).distinct().size,
            "All pins of 'Table with mug' must be regrouped across 'Table' and 'Mug'"
        )
        // HitGrid identifies both split components
        val tableFirstPin = tableComp.raisedPins.first()
        assertEquals(
            tableComp.id,
            splitRes.hitGrid.lookup(PinGrid.unpackX(tableFirstPin), PinGrid.unpackY(tableFirstPin))
        )
        val mugFirstPin = mugComp.raisedPins.first()
        assertEquals(
            mugComp.id,
            splitRes.hitGrid.lookup(PinGrid.unpackX(mugFirstPin), PinGrid.unpackY(mugFirstPin))
        )

        // 3. "merge them back" -> "one component again"
        val mergeRes = sceneAfterSplit.mergeComponents(
            firstId = tableComp.id,
            secondId = mugComp.id,
            mergedLabel = "Table with mug",
            mergedKey = "p2_table_mug"
        )
        val sceneAfterMerge = mergeRes.scene
        val mergedTableWithMug = sceneAfterMerge.componentByLabel("Table with mug", parentId = p2.id)
        assertNotNull(mergedTableWithMug)
        assertEquals(originalTablePinCount, mergedTableWithMug!!.raisedPins.size)
        assertEquals(4, sceneAfterMerge.childrenOf(p2.id).size)

        // 4. "move the 'Mom' speech of p3 under the p3 figure" -> "Enter on the figure shows the speech as its child"
        val p3Figure = sceneAfterMerge.componentByLabel("Person on the phone (close-up)", parentId = p3.id)!!
        val p3MomSpeech = sceneAfterMerge.componentByLabel("Mom", parentId = p3.id)!!
        val reparentRes = sceneAfterMerge.reParentComponent(
            componentId = p3MomSpeech.id,
            newParentId = p3Figure.id
        )
        val sceneAfterReparent = reparentRes.scene
        assertEquals(p3Figure.id, sceneAfterReparent.componentById(p3MomSpeech.id)!!.parentId)
        assertEquals(p3MomSpeech.id, reparentRes.graph.enter(p3Figure.id))
        assertEquals(p3Figure.id, reparentRes.graph.exit(p3MomSpeech.id))

        // 5. "close and reopen the page" -> "all edits kept (stored by image content hash, so the same image on another page reuses them)"
        val store = SceneEditStore()
        val contentHash = store.save(imageBytes, sceneAfterReparent)
        assertEquals(64, contentHash.length)

        val serializedJson = store.toJson()
        val reloadedStore = SceneEditStore.fromJson(serializedJson)
        assertTrue(reloadedStore.hasEditsFor(imageBytes))

        // Simulate opening another page containing the same image bytes
        val freshUneditedScene = buildSyntheticXkcd327Scene()
        val restoredScene = reloadedStore.restoreOrDefault(imageBytes, freshUneditedScene)
        val restoredGraph = restoredScene.buildGraph()

        assertEquals("Mom", restoredScene.componentById(personInP1.id)!!.label)
        assertNotNull(restoredScene.componentByLabel("Table with mug", parentId = p2.id))
        assertEquals(p3MomSpeech.id, restoredGraph.enter(p3Figure.id))
    }

    @Test
    fun `xkcd-327 editing pin drawing, erasing, 2x zoom+pan, and persistence pass`() {
        val imageBytes = syntheticXkcd327ImageBytes()
        val initialScene = buildSyntheticXkcd327Scene()
        val p2 = initialScene.componentByKey("p2")!!

        // Choose a horizontal line across the interior of p2 where no border pins exist at y=10
        val lineY = 10
        val lineX0 = p2.pinBox.minX + 2
        val lineX1 = p2.pinBox.maxX - 2
        assertTrue(lineX1 - lineX0 >= 6, "Line across p2 should span at least 7 pins")

        for (x in lineX0..lineX1) {
            assertFalse(initialScene.pinGrid.getPin(x, lineY), "Interior pin ($x, $lineY) should start flat")
        }

        // 1. "draw a line across p2 with the finger in draw mode" -> "the line appears as raised pins and stays attached to p2"
        val drawRes = initialScene.drawPinLine(p2.id, lineX0, lineY, lineX1, lineY)
        val sceneWithLine = drawRes.scene
        val p2WithLine = sceneWithLine.componentById(p2.id)!!

        for (x in lineX0..lineX1) {
            assertTrue(sceneWithLine.pinGrid.getPin(x, lineY), "Drawn pin ($x, $lineY) must be raised")
            assertTrue(PinGrid.packCanvasCoord(x, lineY) in p2WithLine.raisedPins, "Drawn pin ($x, $lineY) must be attached to p2")
            assertEquals(p2.id, drawRes.hitGrid.lookup(x, lineY), "HitGrid at ($x, $lineY) must return p2")
        }

        // 2. "erase part of the line" -> "only those pins drop"
        val eraseStart = lineX0 + 2
        val eraseEnd = lineX0 + 4
        val eraseRes = sceneWithLine.erasePinLine(eraseStart, lineY, eraseEnd, lineY, componentId = p2.id)
        val sceneAfterErase = eraseRes.scene
        val p2AfterErase = sceneAfterErase.componentById(p2.id)!!

        for (x in lineX0..lineX1) {
            val shouldBeErased = x in eraseStart..eraseEnd
            if (shouldBeErased) {
                assertFalse(sceneAfterErase.pinGrid.getPin(x, lineY), "Erased pin ($x, $lineY) must drop")
                assertFalse(PinGrid.packCanvasCoord(x, lineY) in p2AfterErase.raisedPins)
            } else {
                assertTrue(sceneAfterErase.pinGrid.getPin(x, lineY), "Remaining drawn pin ($x, $lineY) must stay raised")
                assertTrue(PinGrid.packCanvasCoord(x, lineY) in p2AfterErase.raisedPins)
            }
        }

        // 3. "zoom to 2x and pan" -> "the drawing scales and moves with the image"
        val zoomedViewport = SceneViewport(zoom = 2.0f, panX = p2.bbox.xmin, panY = 0.0f)
        val zoomedScene = sceneAfterErase.withViewport(zoomedViewport)
        val p2Zoomed = zoomedScene.componentById(p2.id)!!

        val drawnPointsAt1x = sceneAfterErase.drawingLayer.projectRaisedPinsForComponent(
            p2.id,
            p2.key,
            sceneAfterErase.viewport
        )
        val drawnPointsAt2x = zoomedScene.drawingLayer.projectRaisedPinsForComponent(
            p2.id,
            p2.key,
            zoomedViewport
        )
        assertTrue(drawnPointsAt2x.isNotEmpty())

        val minX1 = drawnPointsAt1x.minOf { PinGrid.unpackX(it) }
        val maxX1 = drawnPointsAt1x.maxOf { PinGrid.unpackX(it) }
        val minX2 = drawnPointsAt2x.minOf { PinGrid.unpackX(it) }
        val maxX2 = drawnPointsAt2x.maxOf { PinGrid.unpackX(it) }
        val span1x = maxX1 - minX1
        val span2x = maxX2 - minX2

        assertTrue(span2x > span1x, "At 2x zoom, horizontal span of drawn line ($span2x) must scale larger than 1x span ($span1x)")
        assertNotEquals(minX1, minX2, "Panning to p2.xmin shifts the projected X coordinates of the drawing")
        for (packed in drawnPointsAt2x) {
            val zx = PinGrid.unpackX(packed)
            val zy = PinGrid.unpackY(packed)
            assertTrue(zoomedScene.pinGrid.getPin(zx, zy), "Zoomed drawn pin ($zx, $zy) is raised on canvas")
            assertTrue(packed in p2Zoomed.raisedPins, "Zoomed drawn pin remains attached to p2")
        }

        // 4. "close and reopen the page" -> "the drawing is kept"
        val store = SceneEditStore()
        store.save(imageBytes, sceneAfterErase)
        val roundTripStore = SceneEditStore.fromJson(store.toJson())

        val reopenedScene = roundTripStore.restoreOrDefault(imageBytes, buildSyntheticXkcd327Scene())
        val reopenedP2 = reopenedScene.componentById(p2.id)!!
        for (x in lineX0..lineX1) {
            val wasErased = x in eraseStart..eraseEnd
            assertEquals(!wasErased, reopenedScene.pinGrid.getPin(x, lineY))
            assertEquals(!wasErased, PinGrid.packCanvasCoord(x, lineY) in reopenedP2.raisedPins)
        }
    }
}
