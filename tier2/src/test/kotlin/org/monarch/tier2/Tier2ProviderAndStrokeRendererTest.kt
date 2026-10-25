package org.monarch.tier2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.monarch.tactile.compile.JudgeResultWriter
import org.monarch.tactile.compile.StrokeRenderer
import org.monarch.tactile.compile.StructuredComponent
import org.monarch.tactile.compile.StructuredStroke
import org.monarch.tactile.compile.StructuredTactileGraphic
import org.monarch.tactile.compile.Viewport
import org.monarch.tactile.edit.SimpleJsonParser
import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.TactileScene
import org.monarch.tactile.nav.Direction
import org.monarch.tactile.nav.NavigationFeedback
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class Tier2ProviderAndStrokeRendererTest {

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("Missing test fixture: fixtures/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `GeminiArgonProvider builds valid Interactions API request without deprecated parameters`() {
        val provider = GeminiArgonProvider(
            apiKeyProvider = { "test-api-key-not-live" },
            transport = { _, _, _ -> error("Should not be called in buildRequestJson") },
            model = "gemini-4-argon",
            thinkingLevel = "low"
        )

        val dummyImage = ByteArray(32) { (it * 7).toByte() }
        val context = PageContext(
            pageTitle = "xkcd: Exploits of a Mom",
            altOrCaption = "Exploits of a Mom — Her daughter is named Help I'm trapped in a driver's license factory.",
            surroundingText = "Webcomic strip about SQL injection"
        )

        val reqJson = provider.buildRequestJson(dummyImage, "image/png", context)
        @Suppress("UNCHECKED_CAST")
        val parsed = SimpleJsonParser(reqJson).parseValue() as Map<String, Any?>

        assertEquals("gemini-4-argon", parsed["model"])

        @Suppress("UNCHECKED_CAST")
        val genConfig = parsed["generation_config"] as Map<String, Any?>
        assertEquals("low", genConfig["thinking_level"])

        // Must never include deprecated parameters
        for (forbidden in listOf("temperature", "top_p", "top_k", "thinking_budget", "cached_content")) {
            assertFalse(parsed.containsKey(forbidden), "Request root must not contain deprecated '$forbidden'")
            assertFalse(genConfig.containsKey(forbidden), "generation_config must not contain deprecated '$forbidden'")
        }

        @Suppress("UNCHECKED_CAST")
        val respFormat = parsed["response_format"] as Map<String, Any?>
        assertEquals("text", respFormat["type"])
        assertEquals("application/json", respFormat["mime_type"])
        assertNotNull(respFormat["schema"])

        // Companion model gemini-3.8-flash is allowed
        val flashProvider = GeminiArgonProvider(
            apiKeyProvider = { "key" },
            transport = { _, _, _ -> "{}" },
            model = GeminiArgonProvider.FAST_COMPANION_MODEL,
            thinkingLevel = "low"
        )
        assertEquals("gemini-3.8-flash", flashProvider.model)

        // Deprecated models (gemini-2.5-*, gemini-2.0-*, gemini-1.5-*) are rejected
        for (deprecated in listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro")) {
            assertThrows(IllegalArgumentException::class.java) {
                GeminiArgonProvider(
                    apiKeyProvider = { "key" },
                    transport = { _, _, _ -> "{}" },
                    model = deprecated
                )
            }
        }
    }

    @Test
    fun `ImagePreprocessor downscales images over 768px and composites transparent backgrounds onto white`() {
        val srcW = 1200
        val srcH = 900
        val img = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            // Transparent background (alpha = 0) with a black stroke in the center
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, srcW, srcH)
            g.color = Color.BLACK
            g.fillRect(500, 400, 200, 100)
        } finally {
            g.dispose()
        }
        val rawPng = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

        val prepared = ImagePreprocessor.prepareForTier2(rawPng, "image/png", maxLongestSide = 768)
        assertTrue(prepared.downscaled, "1200x900 image must be downscaled to <= 768 px longest side")
        assertTrue(prepared.compositedOnWhite, "ARGB image must be composited onto white")
        assertEquals(768, prepared.width)
        assertEquals(576, prepared.height)

        val decodedOut = ImageIO.read(ByteArrayInputStream(prepared.bytes))
        assertFalse(decodedOut.colorModel.hasAlpha(), "Output RGB image should have no alpha channel")

        // Corner pixel (was transparent) must now be solid WHITE (255, 255, 255)
        val cornerRgb = Color(decodedOut.getRGB(5, 5))
        assertEquals(255, cornerRgb.red)
        assertEquals(255, cornerRgb.green)
        assertEquals(255, cornerRgb.blue)

        // Center pixel (was black stroke) must remain black (0, 0, 0)
        val centerRgb = Color(decodedOut.getRGB(768 / 2, 576 / 2))
        assertTrue(centerRgb.red < 15 && centerRgb.green < 15 && centerRgb.blue < 15)
    }

    @Test
    fun `GeminiArgonProvider decomposeAsync parses recorded xkcd-327 fixture and supports cancellation`() {
        val recordedJson = readFixture("xkcd-327-argon-response.json")
        var capturedUrl = ""
        var capturedHeaders: Map<String, String> = emptyMap()

        val provider = GeminiArgonProvider(
            apiKeyProvider = { "recorded-fixture-token" },
            transport = { url, headers, _ ->
                capturedUrl = url
                capturedHeaders = headers
                recordedJson
            }
        )

        val future = provider.decomposeAsync(
            imageBytes = ByteArray(16) { 1 },
            mimeType = "image/png",
            context = PageContext(pageTitle = "xkcd 327")
        )
        val tree = future.get(5, TimeUnit.SECONDS)

        assertEquals(GeminiArgonProvider.INTERACTIONS_ENDPOINT, capturedUrl)
        assertEquals("recorded-fixture-token", capturedHeaders["x-goog-api-key"])
        assertTrue(tree.sceneSummary.contains("Exploits of a Mom"))
        assertEquals(17, tree.components.size)
        assertEquals(17, tree.structuredGraphic.components.size)

        // Test cancellability when transport is blocked
        val latch = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val slowProvider = GeminiArgonProvider(
                apiKeyProvider = { "token" },
                transport = { _, _, _ ->
                    latch.await(10, TimeUnit.SECONDS)
                    recordedJson
                },
                executor = pool
            )
            val cancellableFuture = slowProvider.decomposeAsync(ByteArray(8), "image/png")
            assertTrue(cancellableFuture.cancel(true))
            assertTrue(cancellableFuture.isCancelled)
        } finally {
            latch.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `StrokeRenderer renders recorded xkcd-327 structured graphic across 1x, isolatePanel3, 2x, and 4x`() {
        val recordedJson = readFixture("xkcd-327-argon-response.json")
        val provider = GeminiArgonProvider(
            apiKeyProvider = { "offline" },
            transport = { _, _, _ -> recordedJson }
        )
        val tree = provider.parseResponseJson(recordedJson)
        val renderer = StrokeRenderer(sourceAspectRatio = 666f / 205f)

        // 1. 1x Overview
        val render1x = renderer.render(tree.structuredGraphic, Viewport(zoom = 1.0f))
        val scene1x = render1x.scene
        val fill1x = scene1x.pinGrid.canvasFillRatio()
        println("StrokeRenderer xkcd-327 1x fill ratio: ${"%.3f".format(fill1x)} (${scene1x.pinGrid.canvasRaisedCount()} / 3360 pins)")
        assertTrue(fill1x in 0.05f..0.25f, "Expected 1x fill in 0.05..0.25, got $fill1x")

        // Verify 4 top-level panels with >= 1 blank pin column between adjacent panel borders
        val panels = listOf("p1", "p2", "p3", "p4").map { scene1x.componentByKey(it)!! }
        for (i in 0 until 3) {
            val leftMaxX = panels[i].pinBox.maxX
            val rightMinX = panels[i + 1].pinBox.minX
            assertTrue(rightMinX - leftMaxX >= 2, "Expected >= 1 blank column between ${panels[i].key} and ${panels[i + 1].key}")
            for (gx in (leftMaxX + 1) until rightMinX) {
                for (y in 0 until PinGrid.CANVAS_HEIGHT) {
                    assertFalse(scene1x.pinGrid.getPin(gx, y), "Gutter column $gx must be blank at row $y")
                }
            }
        }

        // Verify role == "text" components have NO raised pins at 1x while keeping exact embeddedText
        val textComps = scene1x.components.filter { it.role == "text" }
        assertEquals(7, textComps.size)
        for (tc in textComps) {
            assertEquals(0, tc.raisedPins.size, "Text component ${tc.key} must have 0 raised pins at 1x")
            assertTrue(tc.embeddedText.isNotBlank())
        }

        // Verify every figure and object stroke is a 1-pin centerline (no 2x2 solid blocks)
        for (comp in scene1x.components.filter { it.role in setOf("figure", "object") }) {
            assertTrue(comp.raisedPins.isNotEmpty(), "Expected raised pins for ${comp.key}")
            val pinSet = comp.raisedPins.toSet()
            for (packed in comp.raisedPins) {
                val x = PinGrid.unpackX(packed)
                val y = PinGrid.unpackY(packed)
                val has2x2 = PinGrid.packCanvasCoord(x + 1, y) in pinSet &&
                    PinGrid.packCanvasCoord(x, y + 1) in pinSet &&
                    PinGrid.packCanvasCoord(x + 1, y + 1) in pinSet
                assertFalse(has2x2, "Stroke in ${comp.key} at ($x,$y) must be 1-pin wide, not a 2x2 block")
            }
        }

        // Verify HitGrid child precedence and ComponentGraph navigation
        val p1 = scene1x.componentByKey("p1")!!
        val p2 = scene1x.componentByKey("p2")!!
        val p3 = scene1x.componentByKey("p3")!!
        val p4 = scene1x.componentByKey("p4")!!
        val p1Fig = scene1x.componentByKey("p1_figure")!!
        val figSamplePin = p1Fig.raisedPins.first()
        assertEquals(
            p1Fig.id,
            render1x.hitGrid.lookup(PinGrid.unpackX(figSamplePin), PinGrid.unpackY(figSamplePin)),
            "Smaller child figure must win on HitGrid lookup inside Panel 1"
        )
        assertEquals(p2.id, render1x.graph.stepDirectional(p1.id, Direction.RIGHT).toComponentId)
        assertEquals(NavigationFeedback.BOUNDARY_BEEP, render1x.graph.stepDirectional(p4.id, Direction.RIGHT).feedback)
        assertEquals(
            scene1x.componentByKey("p3_speech_school")!!.id,
            render1x.graph.stepEnter(p3.id).toComponentId
        )

        // 2. Isolate Panel 3 (re-fit to ~28-30 pins tall, fill <= 0.25)
        val isolatedP3 = renderer.renderScene(
            tree.structuredGraphic,
            Viewport(isolatedComponentBox = p3.bbox)
        )
        assertTrue(isolatedP3.pinGrid.canvasFillRatio() <= 0.25f)
        val isoFig = isolatedP3.componentByKey("p3_figure")!!
        val isoYs = isoFig.raisedPins.map { PinGrid.unpackY(it) }
        val isoHeight = (isoYs.maxOrNull() ?: 0) - (isoYs.minOrNull() ?: 0) + 1
        assertTrue(isoHeight in 15..33, "Expected isolated Panel 3 Mom height in 15..33 pins, got $isoHeight")
        assertEquals(0, isolatedP3.componentByKey("p3_speech_school")!!.raisedPins.size)
        assertEquals(0, isolatedP3.componentByKey("p3_speech_mom")!!.raisedPins.size)

        // 3. Zoom 2x and 4x re-rasterize from normalized strokes with fill <= 0.25
        val scene2x = renderer.renderScene(
            tree.structuredGraphic,
            Viewport(zoom = 2.0f, panX = 0.24f, panY = 0.20f)
        )
        val scene4x = renderer.renderScene(
            tree.structuredGraphic,
            Viewport(zoom = 4.0f, panX = 0.48f, panY = 0.25f)
        )
        assertTrue(scene2x.pinGrid.canvasFillRatio() <= 0.25f)
        assertTrue(scene4x.pinGrid.canvasFillRatio() <= 0.25f)

        // 4. Write JudgeResultWriter output for test-sites/judge.py grading
        val judgeJson = JudgeResultWriter.toResultJson(
            caseId = "xkcd-327",
            imageName = "comic.png",
            sourceWidth = 666,
            sourceHeight = 205,
            overviewScene = scene1x,
            views = mapOf(
                "overview1x" to scene1x.pinGrid,
                "isolate:p3" to isolatedP3.pinGrid,
                "zoom2x" to scene2x.pinGrid,
                "zoom4x" to scene4x.pinGrid
            ),
            buttons = listOf(
                "Explore tactile image: Exploits of a Mom — Her daughter is named Help I'm trapped in a driver's license factory." to
                    "https://imgs.xkcd.com/comics/exploits_of_a_mom.png"
            )
        )
        val outFile = File("build/judge-results/step4-xkcd-327-result.json")
        outFile.parentFile.mkdirs()
        outFile.writeText(judgeJson)
        assertTrue(outFile.exists())
    }

    @Test
    fun `StrokeRenderer drops smallest and lowest-priority strokes first when fill exceeds 25 percent`() {
        val strokes = mutableListOf<StructuredComponent>()
        // High-priority panel border
        strokes.add(
            StructuredComponent(
                id = "main_panel",
                label = "Main Panel",
                role = "panel",
                box = NormalizedBox(0.02f, 0.02f, 0.98f, 0.98f),
                strokes = listOf(
                    StructuredStroke(
                        points = listOf(
                            NormalizedPoint(0.02f, 0.02f),
                            NormalizedPoint(0.98f, 0.02f),
                            NormalizedPoint(0.98f, 0.98f),
                            NormalizedPoint(0.02f, 0.98f)
                        ),
                        closed = true
                    )
                )
            )
        )
        // High-priority figure with long stroke
        strokes.add(
            StructuredComponent(
                id = "hero_figure",
                label = "Hero Figure",
                parentId = "main_panel",
                role = "figure",
                box = NormalizedBox(0.1f, 0.1f, 0.9f, 0.4f),
                strokes = listOf(
                    StructuredStroke(
                        points = listOf(NormalizedPoint(0.15f, 0.15f), NormalizedPoint(0.15f, 0.85f)),
                        closed = false
                    )
                )
            )
        )
        // Flood with 30 low-priority "other" clutter components across rows
        for (i in 1..30) {
            val y = (i / 32.0f).coerceIn(0.05f, 0.95f)
            strokes.add(
                StructuredComponent(
                    id = "clutter_$i",
                    label = "Clutter $i",
                    parentId = "main_panel",
                    role = "other",
                    box = NormalizedBox((y - 0.01f).coerceAtLeast(0f), 0.05f, (y + 0.01f).coerceAtMost(1f), 0.95f),
                    strokes = listOf(
                        StructuredStroke(
                            points = listOf(NormalizedPoint(0.05f, y), NormalizedPoint(0.95f, y)),
                            closed = false
                        )
                    )
                )
            )
        }

        val graphic = StructuredTactileGraphic("Over-dense stress graphic", strokes)
        val scene = StrokeRenderer().renderScene(graphic)
        assertTrue(
            scene.pinGrid.canvasFillRatio() <= 0.25f,
            "Fill ratio must be capped at <= 0.25, got ${scene.pinGrid.canvasFillRatio()}"
        )
        // Panel and hero figure must be retained while low-priority clutter is shed
        assertTrue(scene.componentByKey("main_panel")!!.raisedPins.isNotEmpty())
        assertTrue(scene.componentByKey("hero_figure")!!.raisedPins.isNotEmpty())
        val emptyClutterCount = scene.components.count { it.key.startsWith("clutter_") && it.raisedPins.isEmpty() }
        assertTrue(emptyClutterCount > 0, "Expected low-priority clutter strokes to be dropped first")
    }

    @Test
    fun `Tier2SceneMerger hotSwap reconciles focus by IoU and preserves user pin drawings`() {
        // Build a coarse Tier-1 scene with 4 unnamed boxes
        val tier1Components = listOf(
            Component(id = 101, key = "coarse_1", label = "Region 1", bbox = NormalizedBox(0f, 0f, 1f, 0.22f)),
            Component(id = 102, key = "coarse_2", label = "Region 2", bbox = NormalizedBox(0f, 0.25f, 1f, 0.45f)),
            Component(id = 103, key = "coarse_3", label = "Region 3", bbox = NormalizedBox(0f, 0.46f, 1f, 0.72f)),
            Component(id = 104, key = "coarse_4", label = "Region 4", bbox = NormalizedBox(0f, 0.73f, 1f, 1.0f))
        )
        val tier1Base = TactileScene(
            components = tier1Components,
            rootComponentIds = listOf(101, 102, 103, 104)
        ).reprojectScene()

        // User draws a line on Region 2 (coarse Panel 2) and focuses on Region 3 (coarse Panel 3) while Tier 2 is in flight
        val tier1WithDrawing = tier1Base.drawPinLine(componentId = 102, x0 = 28, y0 = 15, x1 = 36, y1 = 15).scene

        val recordedJson = readFixture("xkcd-327-argon-response.json")
        val provider = GeminiArgonProvider(
            apiKeyProvider = { "offline" },
            transport = { _, _, _ -> recordedJson }
        )
        val tier2Tree = provider.parseResponseJson(recordedJson)

        val (mergedScene, newFocusedId) = Tier2SceneMerger.hotSwap(
            tier1Scene = tier1WithDrawing,
            tier2Tree = tier2Tree,
            focusedComponentId = 103
        )

        // Focus on coarse Region 3 (103) must map to Tier-2's "Panel 3" ("p3")
        assertNotNull(newFocusedId)
        val focusedComp = mergedScene.componentById(newFocusedId!!)!!
        assertEquals("p3", focusedComp.key)
        assertEquals("Panel 3", focusedComp.label)

        // User's drawn line at y=15, x=28..36 must remain raised after hotSwap
        for (x in 28..36) {
            assertTrue(mergedScene.pinGrid.getPin(x, 15), "User drawn pin ($x, 15) must survive Tier-2 hotSwap")
        }

        // Also verify wikipedia-heart recorded fixture parses and renders cleanly
        val heartJson = readFixture("wikipedia-heart-argon-response.json")
        val heartTree = provider.parseResponseJson(heartJson)
        val heartRender = StrokeRenderer(sourceAspectRatio = 663f / 652f).render(heartTree.structuredGraphic)
        assertTrue(heartRender.scene.pinGrid.canvasFillRatio() in 0.02f..0.25f)
        assertEquals(
            listOf("vessels", "chambers", "valves", "pericardium").toSet(),
            heartRender.scene.rootComponentIds.map { heartRender.scene.componentById(it)!!.key }.toSet()
        )
    }
}
