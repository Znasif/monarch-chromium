# Step 0 Alignment Plan (`STEP0_PLAN.md`)

## A. Questions for the Human (Most Important First)

Until the human answers, all steps proceed using the conservative defaults below (also recorded in `docs/agent/DECISIONS.md`).

1. **GeckoView 158 vs. System WebView 109 Duplicate Accessibility Nodes on KeySoft**
   - *Question:* In `engine-test/` on the Monarch, GeckoView 158 exposes both a container node and a text node for each heading, link, and labeled input. Does KeySoft read each item twice during `H`/`L`/`B` quick-nav or skip the container node?
   - *Default:* Keep Steps 1–4 100% engine-neutral (`tactile-core`, `bridge`, `tier2`). In `app/`, default `BrowserEngine` to `SystemWebViewEngine` (clean 1:1 accessibility nodes) with a runtime toggle to `GeckoEngine`.

2. **Coordinate Space for `Component.bbox` Across `tactile-core`, `bridge`, and `tier2`**
   - *Question:* `gemini-4-argon` outputs `[ymin, xmin, ymax, xmax]` normalized to `0..1000`, whereas SVG/Canvas geometry uses viewBox/CSS pixels and `PinGrid`/`HitGrid` use integer pins (`0..95` × `0..34`). Which coordinate space should `Component.bbox` store?
   - *Default:* Store `bbox: NormalizedBox` (`ymin, xmin, ymax, xmax` in `0.0f..1.0f`, with `fromGemini1000(ymin, xmin, ymax, xmax)`) for lossless `1x..8x` viewport zoom/pan, plus `pinBox: PinRect` (`minX, minY, maxX, maxY` in `0..95` × `0..34`) for the active viewport.

3. **Precedence Rules in `HitGrid` for Overlapping Components and 1-Pin Halos**
   - *Question:* When two components (or their 1-pin halos) overlap on the 96×35 canvas, which component ID wins in `HitGrid.lookup(x, y)`?
   - *Default:* Direct component pins always beat 1-pin halo pins. Among competing direct pins (or competing halo pins), the smaller `pinBox.area` wins (favoring specific child components over parent containers), breaking ties by later insertion order.

4. **`HitGrid` Behavior on Separator Row (`35`) and Braille Caption Rows (`36..39`)**
   - *Question:* `HitGrid` wraps `IntArray(96 * 40)` (`3,840` entries), while tactile graphics use rows `0..34`, row `35` is blank, and rows `36..39` are the `BrailleCaptionRegion`. What should `HitGrid.lookup(x, y)` return for `y >= 35`?
   - *Default:* Clamp component and 1-pin halo stamping strictly to rows `0..34`. Return `0` (`NO_COMPONENT`) on row `35` and `-1` (`CAPTION_REGION`) on rows `36..39`.

5. **Density Reduction Policy When Raised Pins Exceed 25%**
   - *Question:* `LONG_TERM_PLAN.md` mandates fill `<= 25%` (`<= 840` of `3,360` canvas pins on rows `0..34`) and `AGENTS.md` targets `<= 20%–25%`. When an SVG or raster contour exceeds 25%, how should pins be shed without breaking 1-pin line continuity?
   - *Default:* Never dither or drop isolated pixels. For SVGs, sort sub-paths by bounding perimeter (descending) and drop smallest interior detail paths until fill `<= 25%`. For raster bitmaps, raise the adaptive Canny/Sobel threshold and prune shortest connected contour chains (`< 4` pins) until fill `<= 25%` (`<= 20%` overview target).

6. **DOM Mutation Strategy for Image-to-Button Promotion in `bridge/`**
   - *Question:* Replacing `<canvas>`, `<svg>`, or `<img>` nodes in-place with `<button>` elements can break page scripts holding DOM references. How should `bridge/` expose buttons?
   - *Default:* Keep the original element in the DOM with `aria-hidden="true"`, and insert `<button type="button" class="monarch-tactile-btn" data-monarch-target-id="...">Explore tactile image: <label></button>` immediately before it.

7. **Decorative and Tiny Image Filtering Thresholds in `bridge/`**
   - *Question:* What exact heuristics classify decorative/tiny images to skip during button promotion?
   - *Default:* Skip elements with `role="presentation"`, `role="none"`, `aria-hidden="true"`, explicit empty `alt=""` (with no `aria-label`, `<title>`, or `<figcaption>`), hidden display/visibility, or rendered width and height `< 32` px (unless an inline `<svg>` has `<title>` or `aria-label`).

8. **Hot-Swap Focus Preservation When Tier 2 Replaces Tier 1 (`tier2/`)**
   - *Question:* When `GeminiArgonProvider` returns semantic components while the user is exploring Tier 1, how should focus and zoom state reconcile?
   - *Default:* Preserve active `Viewport(zoom, panX, panY)`, map the focused Tier-1 component ID to the Tier-2 component with highest bounding-box IoU (`>= 0.25`) or nearest centroid, rebuild `ComponentGraph` and `HitGrid` in-place, and mask Tier-1 contours to Tier-2 boxes.

---

## B. Ranked Risks and Unknowns (and Cheap Retirement)

1. **Sandbox Missing JDK, Linux `gradlew`, and Proxy CA Trust Clobbering (HIGH — Retired in Step 0)**
   - *Risk:* Fresh sandboxes lack Java, `engine-test/` only has `gradlew.bat`, Java ignores `HTTPS_PROXY` env vars, and installing `openjdk-21-jdk-headless` runs `update-ca-certificates`, wiping the sandbox's `mitmproxy` cert and breaking `git push` and Gradle wrapper downloads from `github.com`.
   - *Retirement (verified in Step 0):* Extract the proxy CA cert to `/usr/local/share/ca-certificates/mitmproxy.crt` before `apt-get install`, run `update-ca-certificates`, and export `JAVA_TOOL_OPTIONS` proxy flags (Section C & `AGENTS.md` §3).

2. **Playwright Headless Firefox Availability in Step 3 (HIGH)**
   - *Risk:* `google-chrome` (`156.0`) is pre-installed, but Firefox is not.
   - *Retirement:* Run `npx playwright install --with-deps firefox` in Step 3 using the sandbox proxy and test against local `file://` fixtures.

3. **SVG Path & Transform Parsing Within `< 35 ms` Budget (MEDIUM)**
   - *Risk:* Complex `<path d="...">` curves (`m,l,h,v,c,s,q,t,a,z`) and nested `<g transform="...">` matrices could exceed 35 ms or produce thick lines.
   - *Retirement:* Flatten Béziers/arcs at `0.5`-pin resolution, rasterize with integer 1-pin Bresenham onto `BooleanArray(96 * 35)`, and assert `< 35 ms` warm compile time in JUnit 5.

4. **Data Navigator Graph Dead-Ends on Nested Components (MEDIUM)**
   - *Risk:* Nearest-centroid edges can trap focus when parent containers overlap children.
   - *Retirement:* Restrict directional edges (`UP`, `DOWN`, `LEFT`, `RIGHT`) to siblings sharing the same `parentId` (quadrant cone + distance score); use `ENTER` for first child and `EXIT` for `parentId`.

5. **Canvas2D Draw-Call Log Growth on Animated Canvases (LOW)**
   - *Risk:* 60fps `<canvas>` loops could grow command arrays unboundedly.
   - *Retirement:* Reset a canvas's command buffer on full-canvas `clearRect` or resize, and cap at `2,048` commands per canvas.

---

## C. Module Plan for Steps 1–4

### Sandbox Build Setup (Verified in Step 0)

- **Bootstrap in fresh sandbox (Steps 1, 2, 4):**
  ```bash
  openssl s_client -proxy 10.100.0.1:8081 -connect github.com:443 -showcerts </dev/null 2>/dev/null \
    | awk '/BEGIN CERTIFICATE/{i++} i==2{print}' > /usr/local/share/ca-certificates/mitmproxy.crt
  apt-get update -qq && apt-get install -y --no-install-recommends openjdk-21-jdk-headless
  update-ca-certificates
  export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=10.100.0.1 -Dhttp.proxyPort=8081 -Dhttps.proxyHost=10.100.0.1 -Dhttps.proxyPort=8081"
  ```
- **Toolbar Versions:** OpenJDK `21.0.12.1` (targeting JVM 17 bytecode), Gradle `9.6.0` (with root POSIX `./gradlew` reusing `engine-test/gradle/wrapper/*`), Kotlin `2.4.20` (`kotlin("jvm") version "2.4.20"`), JUnit `5.11.4`.

---

### Step 1: `tactile-core` (`org.monarch.tactile.{model,hit,nav}`)

```kotlin
package org.monarch.tactile.model

data class NormalizedBox(val ymin: Float, val xmin: Float, val ymax: Float, val xmax: Float) {
    val width: Float get() = (xmax - xmin).coerceAtLeast(0f)
    val height: Float get() = (ymax - ymin).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (xmin + xmax) * 0.5f
    val centerY: Float get() = (ymin + ymax) * 0.5f
    companion object {
        fun fromGemini1000(ymin: Int, xmin: Int, ymax: Int, xmax: Int): NormalizedBox
    }
}

data class PinRect(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = (maxX - minX + 1).coerceAtLeast(0)
    val height: Int get() = (maxY - minY + 1).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
}

data class Component(
    val id: Int,
    val label: String,
    val description: String = "",
    val embeddedText: String = "",
    val bbox: NormalizedBox,
    val pinBox: PinRect,
    val parentId: Int? = null,
    val childIds: List<Int> = emptyList(),
    val raisedPins: IntArray = IntArray(0) // packed (y * 96 + x) indices in 0..3359
)

class PinGrid {
    companion object {
        const val WIDTH = 96
        const val HEIGHT = 40
        const val CANVAS_HEIGHT = 35 // rows 0..34
        const val SEPARATOR_ROW = 35
        const val CAPTION_START_ROW = 36 // rows 36..39
    }
    fun getPin(x: Int, y: Int): Boolean
    fun setCanvasPin(x: Int, y: Int, raised: Boolean)
    fun setCaptionPin(x: Int, y: Int, raised: Boolean)
    fun clearCanvas()
    fun clearAll()
    fun canvasRaisedCount(): Int
    fun canvasFillRatio(): Float
    fun toByteGrid(): Array<ByteArray>
    fun toAsciiCanvas(): String
}

data class TactileScene(
    val width: Int = PinGrid.WIDTH,
    val height: Int = PinGrid.HEIGHT,
    val pinGrid: PinGrid,
    val components: List<Component>,
    val rootComponentIds: List<Int>
) {
    fun componentById(id: Int): Component?
}
```
```kotlin
package org.monarch.tactile.hit

import org.monarch.tactile.model.Component

class HitGrid {
    companion object {
        const val NO_COMPONENT = 0
        const val CAPTION_REGION = -1
    }
    val rawCells: IntArray // size 96 * 40 = 3840
    fun stampScene(components: List<Component>, haloRadius: Int = 1)
    fun lookup(x: Int, y: Int): Int // O(1), zero allocation
    fun lookupWithFingertipOffset(rawX: Int, rawY: Int, xOffset: Int = -3): Int
}
```
```kotlin
package org.monarch.tactile.nav

import org.monarch.tactile.model.Component

enum class Direction { UP, DOWN, LEFT, RIGHT }

data class GraphNode(
    val componentId: Int,
    val directionalEdges: Map<Direction, Int>,
    val enterChildId: Int?,
    val exitParentId: Int?
)

class ComponentGraph private constructor(
    private val nodes: Map<Int, GraphNode>,
    val rootIds: List<Int>
) {
    fun node(componentId: Int): GraphNode?
    fun move(fromComponentId: Int, direction: Direction): Int?
    fun enter(fromComponentId: Int): Int?
    fun exit(fromComponentId: Int): Int?
    companion object {
        fun build(components: List<Component>): ComponentGraph
    }
}
```
- **Step 1 Pass/Fail Command:** `./gradlew :tactile-core:test --no-daemon`

---

### Step 2: Tier-1 Compiler (`org.monarch.tactile.compile`)

```kotlin
package org.monarch.tactile.compile

import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.TactileScene

data class Viewport(
    val zoom: Float = 1.0f, // 1.0f .. 8.0f
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val isolatedComponentBox: NormalizedBox? = null
)

data class GrayscaleBitmap(val width: Int, val height: Int, val pixels: ByteArray)

data class ComponentHint(
    val id: Int,
    val label: String,
    val description: String = "",
    val embeddedText: String = "",
    val bbox: NormalizedBox,
    val parentId: Int? = null,
    val childIds: List<Int> = emptyList()
)

class DensityController(val maxFillRatio: Float = 0.25f, val targetOverviewRatio: Float = 0.20f) {
    fun enforceLimit(scene: TactileScene): TactileScene
}

class SvgCompiler(private val densityController: DensityController = DensityController()) {
    fun compile(svgXml: String, viewport: Viewport = Viewport()): TactileScene
}

class RasterContourCompiler(private val densityController: DensityController = DensityController()) {
    fun compile(
        bitmap: GrayscaleBitmap,
        hints: List<ComponentHint> = emptyList(),
        viewport: Viewport = Viewport()
    ): TactileScene
}
```
- **Step 2 Pass/Fail Command:** `./gradlew :tactile-core:test --no-daemon` (golden ASCII/PBM tests, fill `<= 25%` across `1x..8x`, `< 35 ms` compile time per fixture).

---

### Step 3: Browser JS (`bridge/`)

- **`bridge/src/bridge.js`**: Plain document-start script exposing `window.__monarchBridge`:
  - `promoteImages(root = document)`: promotes meaningful `<img>`, `<svg>`, `<canvas>`, `<figure>`, `[role="img"]` to focusable `<button>` elements (`"Explore tactile image: <label>"`) while skipping decorative/tiny nodes.
  - `installCanvasInterceptor(win = window)`: records `CanvasRenderingContext2D` paths, rects, arcs, text, and transforms per `<canvas>`.
  - `captureElementGeometry(targetId)`: serializes inline SVG or recorded Canvas2D geometry + bounding boxes into JSON matching `tactile-core`.
- **Step 3 Pass/Fail Command:** `cd bridge && npm test` (Playwright headless `chromium` and `firefox` on `bridge/test/fixtures/*.html`).

---

### Step 4: Tier-2 Provider (`org.monarch.tier2`)

```kotlin
package org.monarch.tier2

import org.monarch.tactile.compile.ComponentHint
import org.monarch.tactile.compile.GrayscaleBitmap
import org.monarch.tactile.compile.Viewport
import org.monarch.tactile.model.TactileScene
import java.util.concurrent.CompletableFuture

data class PageContext(val pageTitle: String = "", val altOrCaption: String = "", val surroundingText: String = "")
data class Tier2ComponentTree(val sceneSummary: String, val components: List<ComponentHint>)

interface ComponentProvider {
    fun decomposeAsync(
        imageBytes: ByteArray,
        mimeType: String,
        context: PageContext = PageContext()
    ): CompletableFuture<Tier2ComponentTree>
}

fun interface InteractionsHttpTransport {
    fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String
}

class GeminiArgonProvider(
    private val apiKeyProvider: () -> String,
    private val transport: InteractionsHttpTransport,
    private val model: String = "gemini-4-argon",
    private val thinkingLevel: String = "low"
) : ComponentProvider {
    fun buildRequestJson(imageBytes: ByteArray, mimeType: String, context: PageContext): String
    fun parseResponseJson(responseJson: String): Tier2ComponentTree
    override fun decomposeAsync(imageBytes: ByteArray, mimeType: String, context: PageContext): CompletableFuture<Tier2ComponentTree>
}

object Tier2SceneMerger {
    fun hotSwap(
        tier1Scene: TactileScene,
        tier2Tree: Tier2ComponentTree,
        focusedComponentId: Int?,
        bitmap: GrayscaleBitmap? = null,
        viewport: Viewport = Viewport()
    ): Pair<TactileScene, Int?>
}
```
- **Step 4 Pass/Fail Command:** `./gradlew :tier2:test --no-daemon` (recorded JSON fixtures only; zero live API calls; `thinking_level: "low"`, no deprecated parameters).

---

## D. Disagreements with `LONG_TERM_PLAN.md`

None on architecture. Two minor build refinements:
1. **Separate Root JVM Gradle Project from `engine-test/`:** Since the Linux sandbox has no Android SDK, root `settings.gradle.kts` includes pure-JVM `:tactile-core` and `:tier2`, leaving `engine-test/` standalone for device builds.
2. **Dual `NormalizedBox` + `PinRect` on `Component`:** Keeps Gemini's normalized `[ymin, xmin, ymax, xmax]` alongside integer pin bounds for `O(1)` stamping.

---

## E. Estimated Size of Each Step

| Step | Scope | Files | Prod Lines | Test Lines | Tests |
|---|---|---:|---:|---:|---:|
| **Step 1** | Root Gradle + `tactile-core` (`model`, `hit`, `nav`) | 10 | ~450 | ~350 | ~22 JUnit 5 |
| **Step 2** | `tactile-core/compile` (`SvgCompiler`, `RasterContourCompiler`, golden tests) | 9 | ~650 | ~400 | ~18 JUnit 5 |
| **Step 3** | `bridge/` (`bridge.js`, fixtures, Playwright Chromium + Firefox) | 8 | ~380 | ~320 | ~14 × 2 browsers |
| **Step 4** | `tier2/` (`ComponentProvider`, `GeminiArgonProvider`, `Tier2SceneMerger`) | 8 | ~420 | ~300 | ~15 JUnit 5 |
| **Total** | Steps 1–4 | **35** | **~1,900** | **~1,370** | **~69** |
