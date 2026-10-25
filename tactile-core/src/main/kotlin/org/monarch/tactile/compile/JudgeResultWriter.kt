package org.monarch.tactile.compile

import org.monarch.tactile.model.PinGrid
import org.monarch.tactile.model.TactileScene
import kotlin.math.roundToInt

/**
 * Formats compiled [TactileScene]s into the JSON result schema consumed by `test-sites/judge.py`.
 */
object JudgeResultWriter {
    fun toPinRowStrings(pinGrid: PinGrid): List<String> {
        val bytes = pinGrid.toByteGrid()
        return bytes.map { row ->
            val sb = StringBuilder(PinGrid.WIDTH)
            for (x in 0 until PinGrid.WIDTH) {
                sb.append(if (row[x].toInt() != 0) '1' else '0')
            }
            sb.toString()
        }
    }

    fun toResultJson(
        caseId: String,
        imageName: String,
        sourceWidth: Int,
        sourceHeight: Int,
        overviewScene: TactileScene,
        views: Map<String, PinGrid>,
        buttons: List<Pair<String, String>> = emptyList()
    ): String {
        val maxW = (sourceWidth - 1).coerceAtLeast(1)
        val maxH = (sourceHeight - 1).coerceAtLeast(1)
        val byId = overviewScene.components.associateBy { it.id }

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"caseId\": ").append(jsonEscape(caseId)).append(",\n")
        sb.append("  \"image\": ").append(jsonEscape(imageName)).append(",\n")
        sb.append("  \"textView\": {\n    \"buttons\": [")
        sb.append(
            buttons.joinToString(", ") { (label, src) ->
                "{\"label\": ${jsonEscape(label)}, \"src\": ${jsonEscape(src)}}"
            }
        )
        sb.append("]\n  },\n")

        sb.append("  \"components\": [\n")
        for ((idx, comp) in overviewScene.components.withIndex()) {
            val x0 = (comp.bbox.xmin * maxW).roundToInt()
            val y0 = (comp.bbox.ymin * maxH).roundToInt()
            val x1 = (comp.bbox.xmax * maxW).roundToInt()
            val y1 = (comp.bbox.ymax * maxH).roundToInt()
            val parentKey = comp.parentId?.let { byId[it]?.key ?: it.toString() }

            sb.append("    {")
            sb.append("\"id\": ").append(jsonEscape(comp.key)).append(", ")
            sb.append("\"label\": ").append(jsonEscape(comp.label)).append(", ")
            sb.append("\"box\": [").append("$x0, $y0, $x1, $y1").append("], ")
            sb.append("\"parentId\": ").append(if (parentKey != null) jsonEscape(parentKey) else "null").append(", ")
            sb.append("\"embeddedText\": ").append(jsonEscape(comp.embeddedText))
            sb.append("}").append(if (idx < overviewScene.components.size - 1) "," else "").append("\n")
        }
        sb.append("  ],\n")

        sb.append("  \"pins\": {\n")
        val viewEntries = views.entries.toList()
        for ((vIdx, entry) in viewEntries.withIndex()) {
            val rows = toPinRowStrings(entry.value)
            sb.append("    ").append(jsonEscape(entry.key)).append(": [\n")
            for ((rIdx, row) in rows.withIndex()) {
                sb.append("      ").append(jsonEscape(row))
                    .append(if (rIdx < rows.size - 1) "," else "").append("\n")
            }
            sb.append("    ]").append(if (vIdx < viewEntries.size - 1) "," else "").append("\n")
        }
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
