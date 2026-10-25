package org.monarch.tactile.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Normalized point in [0.0, 1.0] image coordinates.
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

/**
 * Normalized bounding box in [0.0, 1.0] image coordinates.
 * Field order matches Gemini 4 Argon structured output: [ymin, xmin, ymax, xmax].
 */
data class NormalizedBox(
    val ymin: Float,
    val xmin: Float,
    val ymax: Float,
    val xmax: Float
) {
    val width: Float get() = (xmax - xmin).coerceAtLeast(0f)
    val height: Float get() = (ymax - ymin).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (xmin + xmax) * 0.5f
    val centerY: Float get() = (ymin + ymax) * 0.5f

    fun contains(normX: Float, normY: Float): Boolean {
        return normX in xmin..xmax && normY in ymin..ymax
    }

    fun containsBox(other: NormalizedBox, tolerance: Float = 1e-4f): Boolean {
        return other.xmin >= xmin - tolerance &&
            other.ymin >= ymin - tolerance &&
            other.xmax <= xmax + tolerance &&
            other.ymax <= ymax + tolerance
    }

    fun union(other: NormalizedBox): NormalizedBox {
        return NormalizedBox(
            ymin = min(ymin, other.ymin),
            xmin = min(xmin, other.xmin),
            ymax = max(ymax, other.ymax),
            xmax = max(xmax, other.xmax)
        )
    }

    fun iou(other: NormalizedBox): Float {
        val interXMin = max(xmin, other.xmin)
        val interYMin = max(ymin, other.ymin)
        val interXMax = min(xmax, other.xmax)
        val interYMax = min(ymax, other.ymax)
        val interW = (interXMax - interXMin).coerceAtLeast(0f)
        val interH = (interYMax - interYMin).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f
        val unionArea = area + other.area - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun toPinRect(viewport: SceneViewport = SceneViewport()): PinRect {
        val rawX0 = viewport.normalizedToExactPinX(xmin)
        val rawX1 = viewport.normalizedToExactPinX(xmax)
        val rawY0 = viewport.normalizedToExactPinY(ymin)
        val rawY1 = viewport.normalizedToExactPinY(ymax)

        val loX = min(rawX0, rawX1)
        val hiX = max(rawX0, rawX1)
        val loY = min(rawY0, rawY1)
        val hiY = max(rawY0, rawY1)

        val minX = if (hiX - loX >= 1.0f) {
            kotlin.math.ceil((loX - 1e-4f).toDouble()).toInt()
        } else {
            loX.roundToInt()
        }.coerceIn(0, PinGrid.WIDTH - 1)

        val maxX = if (hiX - loX >= 1.0f) {
            kotlin.math.floor((hiX + 1e-4f).toDouble()).toInt()
        } else {
            hiX.roundToInt()
        }.coerceIn(0, PinGrid.WIDTH - 1)

        val minY = if (hiY - loY >= 1.0f) {
            kotlin.math.ceil((loY - 1e-4f).toDouble()).toInt()
        } else {
            loY.roundToInt()
        }.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)

        val maxY = if (hiY - loY >= 1.0f) {
            kotlin.math.floor((hiY + 1e-4f).toDouble()).toInt()
        } else {
            hiY.roundToInt()
        }.coerceIn(0, PinGrid.CANVAS_HEIGHT - 1)

        return PinRect(
            minX = min(minX, maxX),
            minY = min(minY, maxY),
            maxX = max(minX, maxX),
            maxY = max(minY, maxY)
        )
    }

    companion object {
        fun fromGemini1000(ymin: Int, xmin: Int, ymax: Int, xmax: Int): NormalizedBox {
            return NormalizedBox(
                ymin = (ymin / 1000.0f).coerceIn(0f, 1f),
                xmin = (xmin / 1000.0f).coerceIn(0f, 1f),
                ymax = (ymax / 1000.0f).coerceIn(0f, 1f),
                xmax = (xmax / 1000.0f).coerceIn(0f, 1f)
            )
        }

        fun fromSourcePixels(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
            sourceWidth: Int,
            sourceHeight: Int
        ): NormalizedBox {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
            val maxW = (sourceWidth - 1).coerceAtLeast(1).toFloat()
            val maxH = (sourceHeight - 1).coerceAtLeast(1).toFloat()
            return NormalizedBox(
                ymin = (y0.toFloat() / maxH).coerceIn(0f, 1f),
                xmin = (x0.toFloat() / maxW).coerceIn(0f, 1f),
                ymax = (y1.toFloat() / maxH).coerceIn(0f, 1f),
                xmax = (x1.toFloat() / maxW).coerceIn(0f, 1f)
            )
        }
    }
}

/**
 * Integer bounding box in pin coordinates on the 96x35 tactile canvas.
 */
data class PinRect(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
) {
    val width: Int get() = (maxX - minX + 1).coerceAtLeast(0)
    val height: Int get() = (maxY - minY + 1).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f

    fun contains(x: Int, y: Int): Boolean {
        return x in minX..maxX && y in minY..maxY
    }

    fun union(other: PinRect): PinRect {
        return PinRect(
            minX = min(minX, other.minX),
            minY = min(minY, other.minY),
            maxX = max(maxX, other.maxX),
            maxY = max(maxY, other.maxY)
        )
    }
}

/**
 * Viewport transformation mapping normalized [0.0, 1.0] coordinates onto the 96x35 tactile canvas.
 */
data class SceneViewport(
    val zoom: Float = 1.0f,
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val canvasWidth: Int = PinGrid.WIDTH,
    val canvasHeight: Int = PinGrid.CANVAS_HEIGHT,
    val isolatedBox: NormalizedBox? = null
) {
    init {
        require(zoom >= 1.0f) { "Viewport zoom must be >= 1.0, got $zoom" }
    }

    fun normalizedToExactPinX(normX: Float): Float {
        val localX = if (isolatedBox != null && isolatedBox.width > 0f) {
            (normX - isolatedBox.xmin) / isolatedBox.width
        } else {
            (normX - panX) * zoom
        }
        return localX * (canvasWidth - 1)
    }

    fun normalizedToExactPinY(normY: Float): Float {
        val localY = if (isolatedBox != null && isolatedBox.height > 0f) {
            (normY - isolatedBox.ymin) / isolatedBox.height
        } else {
            (normY - panY) * zoom
        }
        return localY * (canvasHeight - 1)
    }

    fun normalizedToPinX(normX: Float): Int {
        return normalizedToExactPinX(normX).roundToInt()
    }

    fun normalizedToPinY(normY: Float): Int {
        return normalizedToExactPinY(normY).roundToInt()
    }

    fun pinToNormalizedX(pinX: Int): Float {
        val fracX = pinX.toFloat() / (canvasWidth - 1).coerceAtLeast(1).toFloat()
        return if (isolatedBox != null && isolatedBox.width > 0f) {
            isolatedBox.xmin + fracX * isolatedBox.width
        } else {
            panX + (fracX / zoom)
        }
    }

    fun pinToNormalizedY(pinY: Int): Float {
        val fracY = pinY.toFloat() / (canvasHeight - 1).coerceAtLeast(1).toFloat()
        return if (isolatedBox != null && isolatedBox.height > 0f) {
            isolatedBox.ymin + fracY * isolatedBox.height
        } else {
            panY + (fracY / zoom)
        }
    }
}

/**
 * Semantic component in a tactile scene.
 */
data class Component(
    val id: Int,
    val label: String,
    val description: String = "",
    val embeddedText: String = "",
    val bbox: NormalizedBox,
    val pinBox: PinRect = bbox.toPinRect(),
    val parentId: Int? = null,
    val childIds: List<Int> = emptyList(),
    val raisedPins: IntArray = IntArray(0),
    val key: String = "c$id",
    val role: String = "",
    val baseNormalizedPins: List<NormalizedPoint> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Component) return false
        return id == other.id &&
            label == other.label &&
            description == other.description &&
            embeddedText == other.embeddedText &&
            bbox == other.bbox &&
            pinBox == other.pinBox &&
            parentId == other.parentId &&
            childIds == other.childIds &&
            raisedPins.contentEquals(other.raisedPins) &&
            key == other.key &&
            role == other.role &&
            baseNormalizedPins == other.baseNormalizedPins
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + label.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + embeddedText.hashCode()
        result = 31 * result + bbox.hashCode()
        result = 31 * result + pinBox.hashCode()
        result = 31 * result + (parentId ?: 0)
        result = 31 * result + childIds.hashCode()
        result = 31 * result + raisedPins.contentHashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + baseNormalizedPins.hashCode()
        return result
    }
}
