package org.monarch.tactile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.monarch.tactile.compile.DensityController
import org.monarch.tactile.compile.GrayscaleBitmap
import org.monarch.tactile.compile.RasterContourCompiler
import org.monarch.tactile.compile.SvgCompiler
import org.monarch.tactile.compile.Viewport
import org.monarch.tactile.model.PinGrid
import kotlin.system.measureNanoTime

class SvgCompilerAndRasterTest {

    companion object {
        val HEART_DIAGRAM_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 663 652" width="663" height="652">
              <g id="pericardium" data-role="panel" aria-label="Pericardium">
                <path d="M 130 185 C 130 120, 575 120, 575 185 L 575 520 Q 352 610, 130 520 Z" />
              </g>
              <g id="vessels" data-role="figure" aria-label="Blood vessels">
                <g id="svc" aria-label="Superior vena cava">
                  <rect x="145" y="40" width="55" height="180" />
                </g>
                <g id="ivc" aria-label="Inferior vena cava">
                  <rect x="160" y="480" width="50" height="110" />
                </g>
                <g id="aorta" aria-label="Aorta">
                  <path d="M 250 220 C 250 30, 410 30, 410 220 L 370 220 C 370 80, 290 80, 290 220 Z" />
                </g>
                <g id="pulmonary_artery" aria-label="Pulmonary artery">
                  <polyline points="270,320 270,160 460,160 460,210" />
                </g>
              </g>
              <g id="chambers" data-role="figure" aria-label="Chambers">
                <g id="right_atrium" aria-label="Right atrium">
                  <ellipse cx="220" cy="310" rx="55" ry="65" />
                  <switch>
                    <text systemLanguage="de">Rechter Vorhof</text>
                    <text systemLanguage="fr">Oreillette droite</text>
                    <text systemLanguage="en">Right atrium</text>
                  </switch>
                </g>
                <g id="right_ventricle" aria-label="Right ventricle">
                  <polygon points="195,390 325,390 310,545 195,495" />
                </g>
                <g id="left_atrium" aria-label="Left atrium">
                  <circle cx="415" cy="265" r="48" />
                </g>
                <g id="left_ventricle" aria-label="Left ventricle">
                  <polygon points="355,340 520,340 490,545 355,545" />
                </g>
                <line id="septum" aria-label="Septum" x1="340" y1="340" x2="340" y2="545" />
              </g>
              <g id="valves" data-role="object" aria-label="Valves">
                <line id="mitral_valve" aria-label="Mitral valve" x1="370" y1="335" x2="450" y2="335" stroke-dasharray="4,4" />
                <line id="aortic_valve" aria-label="Aortic valve" x1="330" y1="310" x2="375" y2="310" stroke-dasharray="4,4" />
              </g>
            </svg>
        """.trimIndent()

        val GEOMETRY_PRIMITIVES_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
              <g id="translated_group" transform="translate(10, 5)">
                <title>Outer Box</title>
                <rect x="0" y="0" width="80" height="80" />
              </g>
              <g id="rotated_group" transform="translate(140, 50) scale(1.0, 1.0)">
                <circle id="ring" aria-label="Center Ring" cx="0" cy="0" r="30" />
                <path id="arc_wave" aria-label="Arc Wave" d="M -20 0 A 20 20 0 0 1 20 0 Q 0 20 -20 0 Z" />
              </g>
            </svg>
        """.trimIndent()
    }

    @Test
    fun `SvgCompiler parses primitives, transforms, viewBox, and systemLanguage switch within 35ms`() {
        val compiler = SvgCompiler()

        // Warm up JIT once
        compiler.compile(GEOMETRY_PRIMITIVES_SVG)
        compiler.compile(HEART_DIAGRAM_SVG)

        var primitivesScene = compiler.compile(GEOMETRY_PRIMITIVES_SVG)
        val primNs = measureNanoTime {
            primitivesScene = compiler.compile(GEOMETRY_PRIMITIVES_SVG)
        }
        val primMs = primNs / 1_000_000.0
        println("SvgCompiler primitives compile time: ${"%.2f".format(primMs)} ms")
        assertTrue(primMs < 35.0, "Expected compile time < 35 ms, got ${primMs}ms")
        assertTrue(primitivesScene.pinGrid.canvasRaisedCount() > 20)
        assertTrue(primitivesScene.pinGrid.canvasFillRatio() <= 0.25f)
        assertNotNull(primitivesScene.componentByLabel("Outer Box"))
        assertNotNull(primitivesScene.componentByLabel("Center Ring"))
        assertNotNull(primitivesScene.componentByLabel("Arc Wave"))

        var heartScene = compiler.compile(HEART_DIAGRAM_SVG)
        val heartNs = measureNanoTime {
            heartScene = compiler.compile(HEART_DIAGRAM_SVG)
        }
        val heartMs = heartNs / 1_000_000.0
        println("SvgCompiler heart diagram compile time: ${"%.2f".format(heartMs)} ms")
        assertTrue(heartMs < 35.0, "Expected heart SVG compile time < 35 ms, got ${heartMs}ms")
        assertTrue(heartScene.pinGrid.canvasFillRatio() <= 0.25f)

        // Verify English <switch systemLanguage="en"> was selected over German/French
        val rightAtrium = heartScene.componentByLabel("Right atrium")
        assertNotNull(rightAtrium)
        assertEquals("Right atrium", rightAtrium!!.embeddedText)

        // Dashed valve lines have minZoom=4.0f so they are suppressed at 1x and appear at 4x zoom
        val mitral1x = heartScene.componentByLabel("Mitral valve")!!
        assertEquals(0, mitral1x.raisedPins.size, "Dashed valve lines should be suppressed at 1x")

        val heart4x = compiler.compile(HEART_DIAGRAM_SVG, Viewport(zoom = 4.0f, panX = 0.45f, panY = 0.40f))
        assertTrue(heart4x.pinGrid.canvasFillRatio() <= 0.25f)
        val mitral4x = heart4x.componentByLabel("Mitral valve")!!
        assertTrue(mitral4x.raisedPins.isNotEmpty(), "Valve shapes appear at 4x zoom")

        // Isolating Left ventricle re-fits it to the 96x35 canvas with fill <= 0.25
        val lvBox = heartScene.componentByLabel("Left ventricle")!!.bbox
        val isolatedLv = compiler.compile(HEART_DIAGRAM_SVG, Viewport(isolatedComponentBox = lvBox))
        assertTrue(isolatedLv.pinGrid.canvasFillRatio() <= 0.25f)
        val lvIsolatedComp = isolatedLv.componentByLabel("Left ventricle")!!
        assertTrue(lvIsolatedComp.raisedPins.size > heartScene.componentByLabel("Left ventricle")!!.raisedPins.size)
    }

    @Test
    fun `SvgCompiler golden ASCII canvas output matches expected 1-pin geometry`() {
        val simpleBoxAndDiagonalSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 95 34">
              <rect id="frame" aria-label="Outer Frame" x="0" y="0" width="95" height="34" />
              <line id="divider" aria-label="Center Divider" x1="47" y1="0" x2="47" y2="34" />
            </svg>
        """.trimIndent()

        val scene = SvgCompiler().compile(simpleBoxAndDiagonalSvg)
        val asciiLines = scene.pinGrid.toAsciiCanvas().lines()
        assertEquals(35, asciiLines.size)
        // Top and bottom rows are solid '#'
        assertEquals("#".repeat(96), asciiLines.first())
        assertEquals("#".repeat(96), asciiLines.last())
        // Interior rows have '#' at col 0, col 47, and col 95 only
        for (y in 1..33) {
            val row = asciiLines[y]
            assertEquals('#', row[0])
            assertEquals('#', row[47])
            assertEquals('#', row[95])
            assertEquals(3, row.count { it == '#' }, "Row $y must have exactly 3 raised pins")
        }
        assertTrue(scene.pinGrid.canvasFillRatio() <= 0.25f)
    }

    @Test
    fun `DensityController enforces 25 percent ceiling even on ultra-dense inputs`() {
        val denseSvg = buildString {
            append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 96 35'>")
            append("<g id='main_panel' data-role='panel' aria-label='Main Panel'><rect x='0' y='0' width='95' height='34'/></g>")
            for (y in 1..33) {
                append("<line id='clutter_$y' data-role='squiggle' aria-label='Clutter $y' x1='1' y1='$y' x2='94' y2='$y'/>")
            }
            append("</svg>")
        }
        val scene = SvgCompiler(DensityController(maxFillRatio = 0.25f)).compile(denseSvg)
        assertTrue(
            scene.pinGrid.canvasFillRatio() <= 0.25f,
            "Expected fill <= 0.25, got ${scene.pinGrid.canvasFillRatio()}"
        )
        assertTrue(scene.pinGrid.canvasRaisedCount() <= 840)
        // Main panel outline is preserved while clutter lines are dropped first
        val mainPanel = scene.componentByLabel("Main Panel")!!
        assertTrue(mainPanel.raisedPins.isNotEmpty(), "High-priority panel border must be kept")
    }

    @Test
    fun `RasterContourCompiler PBM round-trip and thick stroke skeletonization produce 1-pin centerline`() {
        // Create a 40x20 PBM bitmap with a 4-pixel-thick horizontal bar at y=8..11, x=4..35
        val bmp = GrayscaleBitmap(40, 20)
        bmp.drawThickLine(4, 9, 35, 9, thickness = 4, luminance = 0)

        val pbm = bmp.toPbmAscii()
        val parsed = GrayscaleBitmap.fromPbmAscii(pbm)
        assertEquals(40, parsed.width)
        assertEquals(20, parsed.height)

        val compiler = RasterContourCompiler()
        val scene = compiler.compile(parsed)
        assertTrue(scene.pinGrid.canvasFillRatio() <= 0.25f)

        // Check vertical cross-sections across the middle of the canvas:
        // A 4-px stroke must become a SINGLE 1-pin centerline (1 raised pin in the column),
        // NOT 2 raised pins tracing the top and bottom edges of the stroke!
        for (col in 20..70) {
            var raisedInCol = 0
            for (row in 0 until PinGrid.CANVAS_HEIGHT) {
                if (scene.pinGrid.getPin(col, row)) raisedInCol++
            }
            assertEquals(1, raisedInCol, "Column $col should have exactly 1 centerline pin, not doubled edges")
        }
    }
}
