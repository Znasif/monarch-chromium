package org.monarch.tactile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.monarch.tactile.compile.ComponentHint
import org.monarch.tactile.compile.GrayscaleBitmap
import org.monarch.tactile.compile.JudgeResultWriter
import org.monarch.tactile.compile.RasterContourCompiler
import org.monarch.tactile.compile.Viewport
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.PinGrid
import kotlin.system.measureNanoTime

/**
 * Golden and structural tests for the "tactile" section of `test-sites/cases/xkcd-327.json`
 * using a synthetic 666x205 4-panel bitmap drawn with 3-4 px ink strokes.
 */
class Xkcd327TactileCompilerTest {

    companion object {
        const val SOURCE_W = 666
        const val SOURCE_H = 205

        private fun boxPx(x0: Int, y0: Int, x1: Int, y1: Int): NormalizedBox {
            return NormalizedBox.fromSourcePixels(x0, y0, x1, y1, SOURCE_W, SOURCE_H)
        }

        /**
         * Builds the 666x205 synthetic 4-panel line-art bitmap with 3-4 px ink strokes:
         * - 4 closed panel borders at [0,0,147,204], [165,0,301,204], [310,0,478,204], [489,0,665,204]
         * - Mom stick figure in all 4 panels (head outline with hair, phone mark at head, torso, two arms, two legs; ~92 px tall -> ~15 pins tall at 1x)
         * - Tables in Panel 1 and Panel 2 (tabletop horizontal line + two vertical legs, 3-4 px strokes, separated from Mom)
         * - Simulated hand-lettered dialogue glyphs in speech regions and tiny steam squiggles
         */
        fun buildSyntheticXkcd327Bitmap(): GrayscaleBitmap {
            val bmp = GrayscaleBitmap(SOURCE_W, SOURCE_H)

            // 1. Draw the 4 panel borders with 4 px ink strokes
            val panels = arrayOf(
                intArrayOf(0, 0, 147, 204),
                intArrayOf(165, 0, 301, 204),
                intArrayOf(310, 0, 478, 204),
                intArrayOf(489, 0, 665, 204)
            )
            for (p in panels) {
                bmp.drawThickRect(p[0], p[1], p[2], p[3], thickness = 4, luminance = 0)
            }

            // Helper to draw Mom as a stick figure with 3-4 px strokes (y spans ~96..188 = 92 px -> ~15 pins tall at 1x)
            fun drawMomStickFigure(centerX: Int, topY: Int = 96, bottomY: Int = 188, closeUp: Boolean = false) {
                val headR = if (closeUp) 16 else 12
                val headCy = topY + headR
                // Head outline (3 px stroke)
                bmp.drawThickCircle(centerX, headCy, headR, thickness = 3, luminance = 0)
                // Hair arc / bun at back-left of head (3 px stroke)
                bmp.drawThickLine(centerX - headR, headCy - 4, centerX - headR - 5, headCy + 6, thickness = 3, luminance = 0)
                // Phone mark held at right ear touching head outline (4 px stroke)
                bmp.drawThickLine(centerX + headR, headCy - 3, centerX + headR + 4, headCy + 5, thickness = 4, luminance = 0)

                // Torso line (4 px stroke)
                val neckY = headCy + headR
                val hipY = bottomY - (if (closeUp) 20 else 28)
                bmp.drawThickLine(centerX, neckY, centerX, hipY, thickness = 4, luminance = 0)

                // Two arms (3 px strokes): one reaching up to the phone, one forward
                val shoulderY = neckY + 8
                bmp.drawThickLine(centerX, shoulderY, centerX + headR + 3, headCy + 4, thickness = 3, luminance = 0)
                bmp.drawThickLine(centerX, shoulderY, centerX - 14, shoulderY + 16, thickness = 3, luminance = 0)

                // Two legs (3 px strokes)
                bmp.drawThickLine(centerX, hipY, centerX - 12, bottomY, thickness = 3, luminance = 0)
                bmp.drawThickLine(centerX, hipY, centerX + 12, bottomY, thickness = 3, luminance = 0)
            }

            // Helper to draw a table with 3-4 px strokes (tabletop + two vertical legs)
            fun drawTable(leftX: Int, rightX: Int, topY: Int = 148, bottomY: Int = 190, withMug: Boolean = true) {
                // Tabletop horizontal line (4 px stroke)
                bmp.drawThickLine(leftX, topY, rightX, topY, thickness = 4, luminance = 0)
                // Two vertical table legs (3 px strokes)
                bmp.drawThickLine(leftX + 4, topY, leftX + 4, bottomY, thickness = 3, luminance = 0)
                bmp.drawThickLine(rightX - 4, topY, rightX - 4, bottomY, thickness = 3, luminance = 0)
                if (withMug) {
                    val mugX = (leftX + rightX) / 2
                    bmp.drawThickLine(mugX, topY - 6, mugX, topY, thickness = 3, luminance = 0)
                    // Tiny isolated 3x3 steam speckle above mug (should be dropped at 1x)
                    bmp.setLuminance(mugX, topY - 14, 0)
                    bmp.setLuminance(mugX + 1, topY - 15, 0)
                }
            }

            // Helper to draw synthetic hand-lettered dialogue glyphs (small 4x6 character marks in speech regions)
            fun drawDialogueGlyphs(x0: Int, y0: Int, x1: Int, y1: Int) {
                var y = y0 + 4
                while (y + 5 <= y1) {
                    var x = x0 + 4
                    while (x + 4 <= x1) {
                        bmp.drawThickLine(x, y, x + 3, y + 4, thickness = 1, luminance = 0)
                        x += 9
                    }
                    y += 10
                }
            }

            // Panel 1 contents: Mom at x=42, Table at x=92..134 (clear gap x=58..91)
            drawDialogueGlyphs(10, 12, 138, 58)
            drawMomStickFigure(centerX = 42, topY = 96, bottomY = 188)
            drawTable(leftX = 92, rightX = 134, topY = 148, bottomY = 188, withMug = false)

            // Panel 2 contents: Mom at x=200, Table with mug at x=246..288 (clear gap x=216..245)
            drawDialogueGlyphs(174, 12, 292, 46)
            drawDialogueGlyphs(174, 54, 258, 78)
            drawMomStickFigure(centerX = 200, topY = 96, bottomY = 188)
            drawTable(leftX = 246, rightX = 288, topY = 148, bottomY = 188, withMug = true)

            // Panel 3 contents: Close-up of Mom on the phone (x=394, y=68..154) between top and bottom dialogue zones
            drawDialogueGlyphs(318, 12, 468, 58)
            drawMomStickFigure(centerX = 394, topY = 66, bottomY = 152, closeUp = true)
            drawDialogueGlyphs(322, 158, 468, 194)

            // Panel 4 contents: Mom holding mug and phone at x=575
            drawDialogueGlyphs(496, 12, 656, 58)
            drawMomStickFigure(centerX = 575, topY = 66, bottomY = 152, closeUp = false)
            drawDialogueGlyphs(500, 158, 656, 194)

            return bmp
        }

        fun buildXkcd327Hints(): List<ComponentHint> {
            return listOf(
                // Panel 1
                ComponentHint(1, "Panel 1", bbox = boxPx(0, 0, 147, 204), childIds = listOf(11, 12, 13), key = "p1", role = "panel"),
                ComponentHint(
                    11,
                    "School (voice on the phone)",
                    embeddedText = "Hi, this is your son's school. We're having some computer trouble.",
                    bbox = boxPx(8, 10, 139, 60),
                    parentId = 1,
                    key = "p1_speech_school",
                    role = "speech"
                ),
                ComponentHint(12, "Mom on the phone, holding a mug", bbox = boxPx(22, 92, 62, 190), parentId = 1, key = "p1_figure", role = "figure"),
                ComponentHint(13, "Table", bbox = boxPx(88, 140, 138, 192), parentId = 1, key = "p1_table", role = "object"),

                // Panel 2
                ComponentHint(2, "Panel 2", bbox = boxPx(165, 0, 301, 204), childIds = listOf(21, 22, 23, 24), key = "p2", role = "panel"),
                ComponentHint(
                    21,
                    "Mom",
                    embeddedText = "Oh, dear - did he break something?",
                    bbox = boxPx(172, 10, 294, 48),
                    parentId = 2,
                    key = "p2_speech_mom",
                    role = "speech"
                ),
                ComponentHint(
                    22,
                    "School (voice on the phone)",
                    embeddedText = "In a way-",
                    bbox = boxPx(172, 52, 260, 80),
                    parentId = 2,
                    key = "p2_speech_school",
                    role = "speech"
                ),
                ComponentHint(23, "Mom holding the phone", bbox = boxPx(180, 92, 220, 190), parentId = 2, key = "p2_figure", role = "figure"),
                ComponentHint(24, "Table with mug", bbox = boxPx(242, 138, 292, 192), parentId = 2, key = "p2_table_mug", role = "object"),

                // Panel 3
                ComponentHint(3, "Panel 3", bbox = boxPx(310, 0, 478, 204), childIds = listOf(31, 32, 33), key = "p3", role = "panel"),
                ComponentHint(
                    31,
                    "School (voice on the phone)",
                    embeddedText = "Did you really name your son Robert'); DROP TABLE Students;-- ?",
                    bbox = boxPx(316, 10, 470, 60),
                    parentId = 3,
                    key = "p3_speech_school",
                    role = "speech"
                ),
                ComponentHint(32, "Mom on the phone", bbox = boxPx(365, 64, 425, 154), parentId = 3, key = "p3_figure", role = "figure"),
                ComponentHint(
                    33,
                    "Mom",
                    embeddedText = "Oh, yes. Little Bobby Tables, we call him.",
                    bbox = boxPx(320, 156, 470, 196),
                    parentId = 3,
                    key = "p3_speech_mom",
                    role = "speech"
                ),

                // Panel 4
                ComponentHint(4, "Panel 4", bbox = boxPx(489, 0, 665, 204), childIds = listOf(41, 42, 43), key = "p4", role = "panel"),
                ComponentHint(
                    41,
                    "School (voice on the phone)",
                    embeddedText = "Well, we've lost this year's student records. I hope you're happy.",
                    bbox = boxPx(495, 10, 658, 60),
                    parentId = 4,
                    key = "p4_speech_school",
                    role = "speech"
                ),
                ComponentHint(42, "Mom on the phone, holding a mug", bbox = boxPx(550, 64, 602, 154), parentId = 4, key = "p4_figure", role = "figure"),
                ComponentHint(
                    43,
                    "Mom",
                    embeddedText = "And I hope you've learned to sanitize your database inputs.",
                    bbox = boxPx(498, 156, 658, 196),
                    parentId = 4,
                    key = "p4_speech_mom",
                    role = "speech"
                )
            )
        }
    }

    @Test
    fun `xkcd-327 overview1x tactile, strokes, drawings, and under 35ms compile time pass`() {
        val bitmap = buildSyntheticXkcd327Bitmap()
        val hints = buildXkcd327Hints()
        val compiler = RasterContourCompiler()

        // Warm up JIT
        compiler.compile(bitmap, hints)

        var scene1x = compiler.compile(bitmap, hints)
        val elapsedNs = measureNanoTime {
            scene1x = compiler.compile(bitmap, hints)
        }
        val elapsedMs = elapsedNs / 1_000_000.0
        println("RasterContourCompiler xkcd-327 (666x205) 1x compile time: ${"%.2f".format(elapsedMs)} ms")
        assertTrue(elapsedMs < 35.0, "Compile time per fixture must be < 35 ms on JVM, got ${elapsedMs}ms")

        // 1. maxFill <= 0.25
        val fill1x = scene1x.pinGrid.canvasFillRatio()
        println("xkcd-327 1x overview fill ratio: ${"%.3f".format(fill1x)} (${scene1x.pinGrid.canvasRaisedCount()} / 3360 pins)")
        assertTrue(fill1x in 0.05f..0.25f, "Expected fill in 0.05..0.25, got $fill1x")

        // 2. "panelBorders": "4 closed rectangles in left-to-right order, 1 pin wide"
        val panels = listOf("p1", "p2", "p3", "p4").map { scene1x.componentByKey(it)!! }
        for (panel in panels) {
            val rect = panel.pinBox
            // Top and bottom horizontal edges are solid 1-pin lines
            for (x in rect.minX..rect.maxX) {
                assertTrue(scene1x.pinGrid.getPin(x, rect.minY), "Top border missing at ($x, ${rect.minY}) for ${panel.key}")
                assertTrue(scene1x.pinGrid.getPin(x, rect.maxY), "Bottom border missing at ($x, ${rect.maxY}) for ${panel.key}")
            }
            // Left and right vertical edges are solid 1-pin lines
            for (y in rect.minY..rect.maxY) {
                assertTrue(scene1x.pinGrid.getPin(rect.minX, y), "Left border missing at (${rect.minX}, $y) for ${panel.key}")
                assertTrue(scene1x.pinGrid.getPin(rect.maxX, y), "Right border missing at (${rect.maxX}, $y) for ${panel.key}")
            }
        }

        // 3. "gutters": "at least 1 blank pin column between each pair of adjacent panel borders"
        // and "backgroundPins": "none outside panel interiors and borders"
        for (i in 0 until 3) {
            val leftPanel = panels[i].pinBox
            val rightPanel = panels[i + 1].pinBox
            assertTrue(
                rightPanel.minX - leftPanel.maxX >= 2,
                "Expected >= 1 blank pin column between ${panels[i].key} and ${panels[i + 1].key}"
            )
            for (gutterX in (leftPanel.maxX + 1) until rightPanel.minX) {
                for (y in 0 until PinGrid.CANVAS_HEIGHT) {
                    assertFalse(
                        scene1x.pinGrid.getPin(gutterX, y),
                        "Gutter column $gutterX between panels must have zero background pins (found pin at y=$y)"
                    )
                }
            }
        }

        // 4. "textAsPins": "none: dialogue lettering is not drawn as pins at 1x"
        val speechComps = scene1x.components.filter { it.role == "speech" }
        assertEquals(7, speechComps.size)
        for (sc in speechComps) {
            assertEquals(0, sc.raisedPins.size, "Speech component '${sc.label}' must have 0 raised pins at 1x")
            // Also verify interior of speech box (excluding panel border) is clear on pinGrid
            val sb = sc.pinBox
            for (y in (sb.minY + 1)..(sb.maxY - 1)) {
                for (x in (sb.minX + 1)..(sb.maxX - 1)) {
                    assertFalse(
                        scene1x.pinGrid.getPin(x, y),
                        "Speech interior at ($x, $y) in '${sc.label}' must not have raised lettering pins at 1x"
                    )
                }
            }
        }

        // 5. "strokes": "every ink stroke becomes ONE 1-pin centerline, never a doubled outline of the stroke's two edges"
        // Verify on all figure and table components that no 2x2 solid pin blocks exist (thinned 1-pin centerlines)
        for (comp in scene1x.components.filter { it.role in setOf("figure", "object") }) {
            assertTrue(comp.raisedPins.isNotEmpty(), "Expected raised centerline pins for ${comp.key}")
            val pinSet = comp.raisedPins.toSet()
            for (packed in comp.raisedPins) {
                val x = PinGrid.unpackX(packed)
                val y = PinGrid.unpackY(packed)
                val has2x2Block = PinGrid.packCanvasCoord(x + 1, y) in pinSet &&
                    PinGrid.packCanvasCoord(x, y + 1) in pinSet &&
                    PinGrid.packCanvasCoord(x + 1, y + 1) in pinSet
                assertFalse(has2x2Block, "Stroke in ${comp.key} at ($x,$y) must be a 1-pin centerline, not a thick 2x2 block")
            }
        }

        // 6. "drawings":
        // - Mom in each panel as a stick figure (~15 pins tall: 12..18 pins)
        for (figKey in listOf("p1_figure", "p2_figure", "p3_figure", "p4_figure")) {
            val fig = scene1x.componentByKey(figKey)!!
            val ys = fig.raisedPins.map { PinGrid.unpackY(it) }
            val figHeightPins = (ys.maxOrNull() ?: 0) - (ys.minOrNull() ?: 0) + 1
            assertTrue(
                figHeightPins in 12..18,
                "Expected Mom figure ($figKey) to be about 15 pins tall (12..18), got $figHeightPins"
            )
        }

        // - Panel 1 and Panel 2 tables clearly separate from Mom (>= 1 blank pin column between them)
        val p1MomMaxX = scene1x.componentByKey("p1_figure")!!.raisedPins.maxOf { PinGrid.unpackX(it) }
        val p1TableMinX = scene1x.componentByKey("p1_table")!!.raisedPins.minOf { PinGrid.unpackX(it) }
        assertTrue(
            p1TableMinX - p1MomMaxX >= 2,
            "Panel 1 table (minX=$p1TableMinX) must have >= 1 blank pin column from Mom (maxX=$p1MomMaxX)"
        )

        val p2MomMaxX = scene1x.componentByKey("p2_figure")!!.raisedPins.maxOf { PinGrid.unpackX(it) }
        val p2TableMinX = scene1x.componentByKey("p2_table_mug")!!.raisedPins.minOf { PinGrid.unpackX(it) }
        assertTrue(
            p2TableMinX - p2MomMaxX >= 2,
            "Panel 2 table (minX=$p2TableMinX) must have >= 1 blank pin column from Mom (maxX=$p2MomMaxX)"
        )
    }

    @Test
    fun `xkcd-327 isolatePanel3 and 2x and 4x zoom re-rasterization pass`() {
        val bitmap = buildSyntheticXkcd327Bitmap()
        val hints = buildXkcd327Hints()
        val compiler = RasterContourCompiler()

        val scene1x = compiler.compile(bitmap, hints)
        val p3 = scene1x.componentByKey("p3")!!
        val p3Fig1x = scene1x.componentByKey("p3_figure")!!

        // 1. "isolatePanel3": Panel 3 alone re-fit to the 96x35 canvas (Mom about 30 pins tall);
        // two dialogue zones are separate touch regions without pins; fill <= 0.25
        val isolatedP3Scene = compiler.compile(
            bitmap = bitmap,
            hints = hints,
            viewport = Viewport(isolatedComponentBox = p3.bbox)
        )
        assertTrue(isolatedP3Scene.pinGrid.canvasFillRatio() <= 0.25f)

        val p3FigIsolated = isolatedP3Scene.componentByKey("p3_figure")!!
        assertTrue(
            p3FigIsolated.raisedPins.size > p3Fig1x.raisedPins.size,
            "Isolated Panel 3 figure should have more detail pins (${p3FigIsolated.raisedPins.size}) than 1x (${p3Fig1x.raisedPins.size})"
        )
        val isoYs = p3FigIsolated.raisedPins.map { PinGrid.unpackY(it) }
        val isoHeightPins = (isoYs.maxOrNull() ?: 0) - (isoYs.minOrNull() ?: 0) + 1
        assertTrue(
            isoHeightPins in 14..34,
            "Isolated Panel 3 Mom figure height ($isoHeightPins pins) scales up on the 96x35 canvas"
        )

        val p3Speech1 = isolatedP3Scene.componentByKey("p3_speech_school")!!
        val p3Speech2 = isolatedP3Scene.componentByKey("p3_speech_mom")!!
        assertEquals(0, p3Speech1.raisedPins.size, "Top speech zone in isolated Panel 3 has no pins")
        assertEquals(0, p3Speech2.raisedPins.size, "Bottom speech zone in isolated Panel 3 has no pins")

        // 2. Zoom levels 2x and 4x re-rasterize from source skeleton centerlines, keeping fill <= 0.25
        val scene2x = compiler.compile(
            bitmap = bitmap,
            hints = hints,
            viewport = Viewport(zoom = 2.0f, panX = 0.24f, panY = 0.20f)
        )
        val scene4x = compiler.compile(
            bitmap = bitmap,
            hints = hints,
            viewport = Viewport(zoom = 4.0f, panX = 0.46f, panY = 0.25f)
        )
        assertTrue(scene2x.pinGrid.canvasFillRatio() <= 0.25f, "2x zoom fill must be <= 0.25")
        assertTrue(scene4x.pinGrid.canvasFillRatio() <= 0.25f, "4x zoom fill must be <= 0.25")

        val p2Fig1xPins = scene1x.componentByKey("p2_figure")!!.raisedPins.size
        val p2Fig2xPins = scene2x.componentByKey("p2_figure")!!.raisedPins.size
        assertTrue(p2Fig2xPins > p2Fig1xPins, "2x zoom re-rasterizes figure with more detail pins ($p2Fig2xPins > $p2Fig1xPins)")

        // 3. Verify JudgeResultWriter exports valid JSON matching test-sites/judge.py schema
        val resultJson = JudgeResultWriter.toResultJson(
            caseId = "xkcd-327",
            imageName = "comic.png",
            sourceWidth = SOURCE_W,
            sourceHeight = SOURCE_H,
            overviewScene = scene1x,
            views = mapOf(
                "overview1x" to scene1x.pinGrid,
                "isolate:p3" to isolatedP3Scene.pinGrid,
                "zoom2x" to scene2x.pinGrid,
                "zoom4x" to scene4x.pinGrid
            ),
            buttons = listOf("Explore tactile image: Exploits of a Mom" to "https://imgs.xkcd.com/comics/exploits_of_a_mom.png")
        )
        assertTrue(resultJson.contains("\"caseId\": \"xkcd-327\""))
        assertTrue(resultJson.contains("\"overview1x\""))
        assertTrue(resultJson.contains("\"isolate:p3\""))

        // 4. Verify readable golden ASCII pin dump files in src/test/resources/golden/
        assertGoldenAsciiPinDump("xkcd-327-overview1x.txt", scene1x.pinGrid.toAsciiCanvas())
        assertGoldenAsciiPinDump("xkcd-327-isolate-p3.txt", isolatedP3Scene.pinGrid.toAsciiCanvas())

        val heartScene = org.monarch.tactile.compile.SvgCompiler().compile(SvgCompilerAndRasterTest.HEART_DIAGRAM_SVG)
        assertGoldenAsciiPinDump("heart-overview1x.txt", heartScene.pinGrid.toAsciiCanvas())
    }

    private fun assertGoldenAsciiPinDump(fileName: String, actualAscii: String) {
        val goldenDir = java.io.File("src/test/resources/golden")
        if (!goldenDir.exists()) goldenDir.mkdirs()
        val file = java.io.File(goldenDir, fileName)
        if (!file.exists()) {
            file.writeText(actualAscii + "\n")
        }
        val expected = file.readText().trimEnd()
        assertEquals(expected, actualAscii.trimEnd(), "Golden ASCII pin dump mismatch for $fileName")
    }
}
