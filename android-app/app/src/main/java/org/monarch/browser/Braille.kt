package org.monarch.browser

import org.monarch.tactile.model.PinGrid

/**
 * Braille caption on rows 36-39. KeySoft's translator gives Unicode Braille in the
 * user's own table and grade (as in MonarchIO); the 6-dot table is the fallback.
 */
object Braille {
    private const val FIRST_ROW = 36
    private const val CELL_PITCH = 3  // two dot columns + one blank column
    private val decade = listOf("1", "12", "14", "145", "15", "124", "1245", "125", "24", "245")

    private fun dots(c: Char): String = when (c) {
        in 'a'..'j' -> decade[c - 'a']
        in 'k'..'t' -> decade[c - 'k'] + "3"
        'w' -> "2456"
        in 'u'..'z' -> decade["uvxyz".indexOf(c)] + "36"
        in '1'..'9' -> decade[c - '1']
        '0' -> decade[9]
        ',' -> "2"; '.' -> "256"; '?' -> "236"; '!' -> "235"; '\'' -> "3"; '-' -> "36"; ':' -> "25"
        else -> ""
    }

    /** Draws Unicode Braille cells (U+2800..U+28FF), 8-dot, three pins per cell. */
    fun writeUnicode(grid: PinGrid, cells: String) {
        grid.clearCaption()
        cells.take(PinGrid.WIDTH / CELL_PITCH).forEachIndexed { i, ch ->
            if (ch.code !in 0x2800..0x28FF) return@forEachIndexed
            val bits = ch.code - 0x2800
            // Unicode dot order: 1,2,3 left column; 4,5,6 right column; 7, 8 bottom row.
            val pos = listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 0 to 3, 1 to 3)
            pos.forEachIndexed { bit, (col, row) ->
                if (bits and (1 shl bit) != 0) grid.setCaptionPin(i * CELL_PITCH + col, FIRST_ROW + row, true)
            }
        }
    }

    fun writeCaption(grid: PinGrid, text: String) {
        grid.clearCaption()
        var cell = 0
        var inNumber = false
        val maxCells = PinGrid.WIDTH / CELL_PITCH
        for (raw in text.lowercase()) {
            if (cell >= maxCells) break
            if (raw.isDigit() && !inNumber) { cell = put(grid, cell, "3456"); inNumber = true }
            if (!raw.isDigit()) inNumber = false
            if (cell >= maxCells) break
            cell = put(grid, cell, dots(raw))
        }
    }

    private fun put(grid: PinGrid, cell: Int, dots: String): Int {
        val x = cell * CELL_PITCH
        for (d in dots) {
            val n = d - '1'
            grid.setCaptionPin(x + n / 3, FIRST_ROW + n % 3, true)
        }
        return cell + 1
    }
}
