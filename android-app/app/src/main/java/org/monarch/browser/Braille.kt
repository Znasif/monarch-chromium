package org.monarch.browser

import org.monarch.tactile.model.PinGrid

/** Uncontracted 6-dot Braille for the caption rows 36-38 (row 39 stays blank). */
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
