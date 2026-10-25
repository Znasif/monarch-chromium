package org.monarch.tier2

import org.monarch.tactile.compile.ComponentHint
import org.monarch.tactile.compile.StructuredComponent
import org.monarch.tactile.compile.StructuredStroke
import org.monarch.tactile.compile.StructuredTactileGraphic
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Optional surrounding page context passed alongside image bytes to Tier 2.
 */
data class PageContext(
    val pageTitle: String = "",
    val altOrCaption: String = "",
    val surroundingText: String = ""
)

/**
 * Semantic component tree and structured 1-pin centerline tactile graphic returned by a
 * [ComponentProvider] (`LONG_TERM_PLAN.md` §6 and §9).
 */
data class Tier2ComponentTree(
    val sceneSummary: String,
    val components: List<ComponentHint>,
    val structuredGraphic: StructuredTactileGraphic = StructuredTactileGraphic(
        sceneSummary = sceneSummary,
        components = components.map { hint ->
            val parentKey = hint.parentId?.let { pid ->
                components.firstOrNull { it.id == pid }?.key ?: "c$pid"
            }
            StructuredComponent(
                id = hint.key,
                label = hint.label,
                parentId = parentKey,
                role = hint.role.ifEmpty { "object" },
                box = hint.bbox,
                strokes = hint.strokes.map { vs -> StructuredStroke(vs.points, vs.closed) },
                embeddedText = hint.embeddedText,
                description = hint.description
            )
        }
    )
)

/**
 * Asynchronous, cancellable Tier-2 semantic decomposition provider interface.
 * Tier 1 never depends on [ComponentProvider]; when a provider succeeds, its result is
 * hot-swapped into the live view via [Tier2SceneMerger.hotSwap].
 */
interface ComponentProvider {
    fun decomposeAsync(
        imageBytes: ByteArray,
        mimeType: String,
        context: PageContext = PageContext()
    ): CompletableFuture<Tier2ComponentTree>
}

/**
 * Pluggable HTTP transport for the Gemini Interactions API so unit tests run deterministically
 * against recorded fixtures without live network calls or API keys.
 */
fun interface InteractionsHttpTransport {
    fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String
}

/**
 * Result of preprocessing an image before sending it to `gemini-4-argon`.
 */
data class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val compositedOnWhite: Boolean,
    val downscaled: Boolean
)

/**
 * Preprocesses raster images before sending them to Tier 2 (`LONG_TERM_PLAN.md` §9):
 * 1. Composites transparent backgrounds onto solid WHITE (`#FFFFFF`) so black strokes and
 *    labels on transparent PNGs/WebPs (e.g. `openstax-bio-heart`) never vanish on black.
 * 2. Downscales images whose longest side exceeds `768 px` while preserving aspect ratio.
 */
object ImagePreprocessor {
    const val MAX_LONGEST_SIDE_PX = 768

    fun prepareForTier2(
        imageBytes: ByteArray,
        mimeType: String,
        maxLongestSide: Int = MAX_LONGEST_SIDE_PX
    ): PreparedImage {
        if (imageBytes.isEmpty()) {
            return PreparedImage(imageBytes, mimeType, 0, 0, compositedOnWhite = false, downscaled = false)
        }

        val decoded: BufferedImage = try {
            ImageIO.read(ByteArrayInputStream(imageBytes))
        } catch (_: Exception) {
            null
        } ?: return PreparedImage(imageBytes, mimeType, 0, 0, compositedOnWhite = false, downscaled = false)

        val srcW = decoded.width
        val srcH = decoded.height
        val longest = max(srcW, srcH)
        val needsDownscale = longest > maxLongestSide && maxLongestSide > 0
        val hasAlpha = decoded.colorModel.hasAlpha()

        if (!needsDownscale && !hasAlpha) {
            return PreparedImage(
                bytes = imageBytes,
                mimeType = mimeType,
                width = srcW,
                height = srcH,
                compositedOnWhite = false,
                downscaled = false
            )
        }

        val scale = if (needsDownscale) maxLongestSide.toDouble() / longest.toDouble() else 1.0
        val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)

        val outImg = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        val g2d = outImg.createGraphics()
        try {
            g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g2d.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
            )
            // Always fill with solid WHITE before drawing so transparent backgrounds become white
            g2d.color = Color.WHITE
            g2d.fillRect(0, 0, dstW, dstH)
            g2d.drawImage(decoded, 0, 0, dstW, dstH, null)
        } finally {
            g2d.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(outImg, "png", baos)
        return PreparedImage(
            bytes = baos.toByteArray(),
            mimeType = "image/png",
            width = dstW,
            height = dstH,
            compositedOnWhite = hasAlpha,
            downscaled = needsDownscale
        )
    }
}
