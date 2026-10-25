package org.monarch.tactile.model

/**
 * Represents the physical 96x40 pin array of the HumanWare Monarch:
 * - Rows 0..34 (96x35 = 3,360 pins): Tactile graphics canvas
 * - Row 35 (96x1): Blank separator row (always 0)
 * - Rows 36..39 (96x4): Braille caption region (32 Braille cells across 96 columns)
 */
class PinGrid(
    private val pins: BooleanArray = BooleanArray(WIDTH * HEIGHT)
) {
    companion object {
        const val WIDTH = 96
        const val HEIGHT = 40
        const val CANVAS_HEIGHT = 35 // rows 0..34
        const val SEPARATOR_ROW = 35
        const val CAPTION_START_ROW = 36 // rows 36..39
        const val CANVAS_PIN_COUNT = WIDTH * CANVAS_HEIGHT // 3,360

        fun packCanvasCoord(x: Int, y: Int): Int = y * WIDTH + x
        fun unpackX(packed: Int): Int = packed % WIDTH
        fun unpackY(packed: Int): Int = packed / WIDTH
    }

    init {
        require(pins.size == WIDTH * HEIGHT) {
            "PinGrid backing array must have size ${WIDTH * HEIGHT}, got ${pins.size}"
        }
        // Enforce blank separator row 35 invariant
        clearSeparatorRow()
    }

    private fun clearSeparatorRow() {
        val rowStart = SEPARATOR_ROW * WIDTH
        for (x in 0 until WIDTH) {
            pins[rowStart + x] = false
        }
    }

    fun getPin(x: Int, y: Int): Boolean {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return false
        if (y == SEPARATOR_ROW) return false
        return pins[y * WIDTH + x]
    }

    fun setCanvasPin(x: Int, y: Int, raised: Boolean) {
        if (x !in 0 until WIDTH || y !in 0 until CANVAS_HEIGHT) return
        pins[y * WIDTH + x] = raised
    }

    fun setCaptionPin(x: Int, y: Int, raised: Boolean) {
        if (x !in 0 until WIDTH || y !in CAPTION_START_ROW until HEIGHT) return
        pins[y * WIDTH + x] = raised
    }

    fun clearCanvas() {
        for (i in 0 until CANVAS_PIN_COUNT) {
            pins[i] = false
        }
    }

    fun clearCaption() {
        for (y in CAPTION_START_ROW until HEIGHT) {
            val rowOffset = y * WIDTH
            for (x in 0 until WIDTH) {
                pins[rowOffset + x] = false
            }
        }
    }

    fun clearAll() {
        pins.fill(false)
    }

    fun canvasRaisedCount(): Int {
        var count = 0
        for (i in 0 until CANVAS_PIN_COUNT) {
            if (pins[i]) count++
        }
        return count
    }

    fun canvasFillRatio(): Float {
        return canvasRaisedCount().toFloat() / CANVAS_PIN_COUNT.toFloat()
    }

    fun toByteGrid(): Array<ByteArray> {
        return Array(HEIGHT) { y ->
            ByteArray(WIDTH) { x ->
                if (y != SEPARATOR_ROW && pins[y * WIDTH + x]) 1.toByte() else 0.toByte()
            }
        }
    }

    fun toAsciiCanvas(raisedChar: Char = '#', flatChar: Char = '.'): String {
        val sb = StringBuilder(CANVAS_HEIGHT * (WIDTH + 1))
        for (y in 0 until CANVAS_HEIGHT) {
            val rowOffset = y * WIDTH
            for (x in 0 until WIDTH) {
                sb.append(if (pins[rowOffset + x]) raisedChar else flatChar)
            }
            if (y < CANVAS_HEIGHT - 1) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    fun copy(): PinGrid = PinGrid(pins.copyOf())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinGrid) return false
        return pins.contentEquals(other.pins)
    }

    override fun hashCode(): Int = pins.contentHashCode()
}
