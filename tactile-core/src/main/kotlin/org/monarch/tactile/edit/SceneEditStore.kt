package org.monarch.tactile.edit

import org.monarch.tactile.model.Component
import org.monarch.tactile.model.NormalizedBox
import org.monarch.tactile.model.NormalizedPoint
import org.monarch.tactile.model.TactileScene
import java.security.MessageDigest

data class PersistedSceneRecord(
    val imageSha256: String,
    val rootComponentIds: List<Int>,
    val components: List<Component>,
    val drawingLayer: PinDrawingLayer
)

/**
 * Persists user component-tree edits and normalized pin drawings keyed by the SHA-256 hash
 * of the source image bytes.
 *
 * When the user closes and reopens a page—or encounters the identical image bytes on a
 * different webpage—[restoreOrDefault] matches the SHA-256 digest and reapplies all tree
 * edits and pin drawings.
 */
class SceneEditStore(
    private val recordsByHash: MutableMap<String, PersistedSceneRecord> = linkedMapOf()
) {
    fun save(imageBytes: ByteArray, scene: TactileScene): String {
        val hash = sha256Hex(imageBytes)
        recordsByHash[hash] = PersistedSceneRecord(
            imageSha256 = hash,
            rootComponentIds = scene.rootComponentIds.toList(),
            components = scene.components.map { comp ->
                // Ensure baseNormalizedPins captures current raisedPins if baseNormalizedPins was empty
                val baseNorm = if (comp.baseNormalizedPins.isNotEmpty()) {
                    comp.baseNormalizedPins
                } else {
                    val drawnPacked = scene.drawingLayer
                        .projectRaisedPinsForComponent(comp.id, comp.key, scene.viewport)
                        .toSet()
                    comp.raisedPins
                        .filter { it !in drawnPacked }
                        .map { packed ->
                            val px = org.monarch.tactile.model.PinGrid.unpackX(packed)
                            val py = org.monarch.tactile.model.PinGrid.unpackY(packed)
                            NormalizedPoint(
                                x = scene.viewport.pinToNormalizedX(px),
                                y = scene.viewport.pinToNormalizedY(py)
                            )
                        }
                }
                comp.copy(baseNormalizedPins = baseNorm)
            },
            drawingLayer = scene.drawingLayer
        )
        return hash
    }

    fun hasEditsFor(imageBytes: ByteArray): Boolean {
        return recordsByHash.containsKey(sha256Hex(imageBytes))
    }

    fun getRecord(imageBytes: ByteArray): PersistedSceneRecord? {
        return recordsByHash[sha256Hex(imageBytes)]
    }

    fun restoreOrDefault(imageBytes: ByteArray, defaultScene: TactileScene): TactileScene {
        val record = recordsByHash[sha256Hex(imageBytes)] ?: return defaultScene
        return defaultScene.copy(
            components = record.components,
            rootComponentIds = record.rootComponentIds,
            drawingLayer = record.drawingLayer
        ).reprojectScene()
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n  \"records\": [\n")
        val entries = recordsByHash.values.toList()
        for ((rIdx, rec) in entries.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"imageSha256\": ").append(jsonString(rec.imageSha256)).append(",\n")
            sb.append("      \"rootComponentIds\": ").append(rec.rootComponentIds.joinToString(",", "[", "]")).append(",\n")
            sb.append("      \"components\": [\n")
            for ((cIdx, comp) in rec.components.withIndex()) {
                sb.append("        {\n")
                sb.append("          \"id\": ").append(comp.id).append(",\n")
                sb.append("          \"key\": ").append(jsonString(comp.key)).append(",\n")
                sb.append("          \"label\": ").append(jsonString(comp.label)).append(",\n")
                sb.append("          \"role\": ").append(jsonString(comp.role)).append(",\n")
                sb.append("          \"description\": ").append(jsonString(comp.description)).append(",\n")
                sb.append("          \"embeddedText\": ").append(jsonString(comp.embeddedText)).append(",\n")
                sb.append("          \"bbox\": [")
                    .append(comp.bbox.ymin).append(",")
                    .append(comp.bbox.xmin).append(",")
                    .append(comp.bbox.ymax).append(",")
                    .append(comp.bbox.xmax).append("],\n")
                sb.append("          \"parentId\": ").append(comp.parentId?.toString() ?: "null").append(",\n")
                sb.append("          \"childIds\": ").append(comp.childIds.joinToString(",", "[", "]")).append(",\n")
                sb.append("          \"baseNormalizedPins\": [")
                sb.append(comp.baseNormalizedPins.joinToString(",") { pt -> "[${pt.x},${pt.y}]" })
                sb.append("]\n")
                sb.append("        }").append(if (cIdx < rec.components.size - 1) "," else "").append("\n")
            }
            sb.append("      ],\n")
            sb.append("      \"drawingPoints\": [\n")
            for ((pIdx, pt) in rec.drawingLayer.points.withIndex()) {
                sb.append("        {")
                sb.append("\"componentId\":").append(pt.componentId).append(",")
                sb.append("\"componentKey\":").append(jsonString(pt.componentKey)).append(",")
                sb.append("\"normX\":").append(pt.normX).append(",")
                sb.append("\"normY\":").append(pt.normY).append(",")
                sb.append("\"isErased\":").append(pt.isErased)
                sb.append("}").append(if (pIdx < rec.drawingLayer.points.size - 1) "," else "").append("\n")
            }
            sb.append("      ]\n")
            sb.append("    }").append(if (rIdx < entries.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                hex.append( Character.forDigit(v ushr 4, 16))
                hex.append(Character.forDigit(v and 0x0F, 16))
            }
            return hex.toString()
        }

        private fun jsonString(value: String): String {
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

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): SceneEditStore {
            val root = SimpleJsonParser(json).parseValue() as Map<String, Any?>
            val rawRecords = (root["records"] as? List<Map<String, Any?>>).orEmpty()
            val map = linkedMapOf<String, PersistedSceneRecord>()

            for (rawRec in rawRecords) {
                val hash = rawRec["imageSha256"] as String
                val rootIds = (rawRec["rootComponentIds"] as List<Number>).map { it.toInt() }
                val rawComps = (rawRec["components"] as List<Map<String, Any?>>)
                val components = rawComps.map { rc ->
                    val id = (rc["id"] as Number).toInt()
                    val key = (rc["key"] as? String) ?: "c$id"
                    val label = rc["label"] as String
                    val role = (rc["role"] as? String).orEmpty()
                    val description = (rc["description"] as? String).orEmpty()
                    val embeddedText = (rc["embeddedText"] as? String).orEmpty()
                    val bboxArr = rc["bbox"] as List<Number>
                    val bbox = NormalizedBox(
                        ymin = bboxArr[0].toFloat(),
                        xmin = bboxArr[1].toFloat(),
                        ymax = bboxArr[2].toFloat(),
                        xmax = bboxArr[3].toFloat()
                    )
                    val parentId = (rc["parentId"] as? Number)?.toInt()
                    val childIds = ((rc["childIds"] as? List<Number>).orEmpty()).map { it.toInt() }
                    val rawBasePins = (rc["baseNormalizedPins"] as? List<List<Number>>).orEmpty()
                    val baseNormalizedPins = rawBasePins.map { pair ->
                        NormalizedPoint(pair[0].toFloat(), pair[1].toFloat())
                    }
                    Component(
                        id = id,
                        key = key,
                        label = label,
                        role = role,
                        description = description,
                        embeddedText = embeddedText,
                        bbox = bbox,
                        pinBox = bbox.toPinRect(),
                        parentId = parentId,
                        childIds = childIds,
                        baseNormalizedPins = baseNormalizedPins
                    )
                }

                val rawPoints = (rawRec["drawingPoints"] as? List<Map<String, Any?>>).orEmpty()
                val drawingPoints = rawPoints.map { rp ->
                    AttachedNormalizedPin(
                        componentId = (rp["componentId"] as Number).toInt(),
                        componentKey = (rp["componentKey"] as? String).orEmpty(),
                        normX = (rp["normX"] as Number).toFloat(),
                        normY = (rp["normY"] as Number).toFloat(),
                        isErased = (rp["isErased"] as? Boolean) ?: false
                    )
                }

                map[hash] = PersistedSceneRecord(
                    imageSha256 = hash,
                    rootComponentIds = rootIds,
                    components = components,
                    drawingLayer = PinDrawingLayer(drawingPoints)
                )
            }

            return SceneEditStore(map)
        }
    }
}

/**
 * Minimal recursive-descent JSON parser for pure-JVM serialization without third-party dependencies.
 */
class SimpleJsonParser(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) error("Unexpected end of JSON")
        return when (val ch = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected character '$ch' at position $pos")
        }
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) {
            pos++
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        pos++ // '{'
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return map
        }
        while (pos < src.length) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            check(src[pos] == ':') { "Expected ':' at $pos" }
            pos++
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            } else {
                error("Expected ',' or '}' at $pos")
            }
        }
        error("Unterminated JSON object")
    }

    private fun parseArray(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // '['
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return list
        }
        while (pos < src.length) {
            list.add(parseValue())
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            } else {
                error("Expected ',' or ']' at $pos")
            }
        }
        error("Unterminated JSON array")
    }

    private fun parseString(): String {
        check(src[pos] == '"') { "Expected '\"' at $pos" }
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            if (ch == '"') return sb.toString()
            if (ch == '\\') {
                check(pos < src.length) { "Unterminated escape at $pos" }
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
        }
        error("Unterminated string literal")
    }

    private fun parseBoolean(): Boolean {
        return if (src.startsWith("true", pos)) {
            pos += 4
            true
        } else if (src.startsWith("false", pos)) {
            pos += 5
            false
        } else {
            error("Invalid boolean token at $pos")
        }
    }

    private fun parseNull(): Any? {
        if (src.startsWith("null", pos)) {
            pos += 4
            return null
        }
        error("Invalid null token at $pos")
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] in listOf('-', '+', '.', 'e', 'E'))) {
            pos++
        }
        val token = src.substring(start, pos)
        return if ('.' in token || 'e' in token || 'E' in token) {
            token.toDouble()
        } else {
            token.toLong()
        }
    }
}
