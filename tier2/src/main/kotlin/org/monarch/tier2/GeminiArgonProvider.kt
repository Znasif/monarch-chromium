package org.monarch.tier2

import org.monarch.tactile.compile.StructuredTactileGraphic
import org.monarch.tactile.edit.SimpleJsonParser
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Tier-2 semantic component and 1-pin centerline stroke provider backed by `gemini-4-argon`
 * (or companion `gemini-3.8-flash`) via the Gemini Interactions API REST endpoint
 * (`POST https://generativelanguage.googleapis.com/v1beta/interactions`).
 *
 * Follows `/workspace/ref/skills/gemini-api-dev.md` and `docs/LONG_TERM_PLAN.md` §6 & §9:
 * - Uses `thinking_level` (`"low"` by default for interactive use).
 * - Never sends deprecated parameters (`temperature`, `top_p`, `top_k`, `thinking_budget`,
 *   or `cached_content`) and rejects deprecated `gemini-2.5-*`, `gemini-2.0-*`, `gemini-1.5-*` models.
 * - Preprocesses input images to `<= 768 px` longest side composited onto a white background.
 * - Requests structured JSON output matching the §9 structured tactile graphic schema.
 */
class GeminiArgonProvider(
    private val apiKeyProvider: () -> String,
    private val transport: InteractionsHttpTransport,
    val model: String = DEFAULT_MODEL,
    val thinkingLevel: String = DEFAULT_THINKING_LEVEL,
    private val maxLongestSidePx: Int = ImagePreprocessor.MAX_LONGEST_SIDE_PX,
    private val executor: Executor = ForkJoinPool.commonPool()
) : ComponentProvider {

    init {
        val m = model.trim().lowercase()
        require(
            !m.startsWith("gemini-2.5") &&
                !m.startsWith("gemini-2.0") &&
                !m.startsWith("gemini-1.5")
        ) {
            "Deprecated Gemini model '$model' is forbidden. Use '$DEFAULT_MODEL' or '$FAST_COMPANION_MODEL'."
        }
        require(thinkingLevel in VALID_THINKING_LEVELS) {
            "thinking_level must be one of $VALID_THINKING_LEVELS, got '$thinkingLevel'"
        }
    }

    /**
     * Builds the Interactions API JSON request payload for [imageBytes] and [context].
     */
    fun buildRequestJson(
        imageBytes: ByteArray,
        mimeType: String,
        context: PageContext = PageContext()
    ): String {
        val prepared = ImagePreprocessor.prepareForTier2(
            imageBytes = imageBytes,
            mimeType = mimeType,
            maxLongestSide = maxLongestSidePx
        )
        val b64Data = Base64.getEncoder().encodeToString(prepared.bytes)
        val promptText = buildPromptText(context, prepared)

        return buildString {
            append("{\n")
            append("  \"model\": \"").append(jsonEscape(model)).append("\",\n")
            append("  \"input\": [\n")
            append("    {\n")
            append("      \"type\": \"text\",\n")
            append("      \"text\": \"").append(jsonEscape(promptText)).append("\"\n")
            append("    },\n")
            append("    {\n")
            append("      \"type\": \"image\",\n")
            append("      \"data\": \"").append(b64Data).append("\",\n")
            append("      \"mime_type\": \"").append(jsonEscape(prepared.mimeType)).append("\"\n")
            append("    }\n")
            append("  ],\n")
            append("  \"generation_config\": {\n")
            append("    \"thinking_level\": \"").append(jsonEscape(thinkingLevel)).append("\"\n")
            append("  },\n")
            append("  \"response_format\": {\n")
            append("    \"type\": \"text\",\n")
            append("    \"mime_type\": \"application/json\",\n")
            append("    \"schema\": ").append(STRUCTURED_TACTILE_GRAPHIC_SCHEMA_JSON).append("\n")
            append("  }\n")
            append("}")
        }
    }

    /**
     * Parses an Interactions API JSON response (or direct structured JSON payload) into a
     * [Tier2ComponentTree] containing both [StructuredTactileGraphic] strokes and [ComponentHint]s.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseResponseJson(responseJson: String): Tier2ComponentTree {
        val root = SimpleJsonParser(responseJson).parseValue() as? Map<String, Any?>
            ?: error("Expected JSON object from Interactions API")

        val structuredPayloadJson: String? = extractModelOutputText(root)
        val graphic = if (structuredPayloadJson != null) {
            StructuredTactileGraphic.fromJson(structuredPayloadJson)
        } else if (root.containsKey("components")) {
            StructuredTactileGraphic.fromMap(root)
        } else {
            error("Interactions API response contains neither model_output text nor components array")
        }

        val hints = graphic.toComponentHints()
        return Tier2ComponentTree(
            sceneSummary = graphic.sceneSummary,
            components = hints,
            structuredGraphic = graphic
        )
    }

    override fun decomposeAsync(
        imageBytes: ByteArray,
        mimeType: String,
        context: PageContext
    ): CompletableFuture<Tier2ComponentTree> {
        val future = CompletableFuture<Tier2ComponentTree>()
        executor.execute {
            if (future.isCancelled) return@execute
            try {
                val requestBody = buildRequestJson(imageBytes, mimeType, context)
                if (future.isCancelled) return@execute
                val apiKey = apiKeyProvider()
                val headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "x-goog-api-key" to apiKey
                )
                val responseBody = transport.postJson(INTERACTIONS_ENDPOINT, headers, requestBody)
                if (future.isCancelled) return@execute
                val tree = parseResponseJson(responseBody)
                future.complete(tree)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractModelOutputText(root: Map<String, Any?>): String? {
        // 1. Check convenience property `output_text`
        val directOutputText = (root["output_text"] as? String)?.trim()
        if (!directOutputText.isNullOrEmpty()) {
            return directOutputText
        }

        // 2. Traverse Interactions API `steps` array for trailing `model_output` steps
        val steps = (root["steps"] as? List<Map<String, Any?>>).orEmpty()
        if (steps.isNotEmpty()) {
            val textParts = ArrayList<String>()
            for (step in steps) {
                val stepType = (step["type"] as? String).orEmpty()
                if (stepType == "model_output") {
                    val contentList = (step["content"] as? List<Map<String, Any?>>).orEmpty()
                    for (item in contentList) {
                        if (item["type"] == "text") {
                            val t = (item["text"] as? String)?.trim()
                            if (!t.isNullOrEmpty()) {
                                textParts.add(t)
                            }
                        }
                    }
                }
            }
            if (textParts.isNotEmpty()) {
                return textParts.last()
            }
        }

        return null
    }

    private fun buildPromptText(context: PageContext, prepared: PreparedImage): String {
        return buildString {
            append(
                "Decompose this image into a structured tactile graphic for a 96x35 Braille pin array " +
                    "(HumanWare Monarch). Return normalized 0..1 coordinates (origin top-left). " +
                    "Rules: (1) Colour is never a texture; regions are 1-pin outlines only and colour meaning " +
                    "goes into labels. (2) Every visual stroke is a 1-pin centerline polyline. " +
                    "(3) Text/speech components have role='text', embeddedText in reading order, and empty strokes."
            )
            if (prepared.width > 0 && prepared.height > 0) {
                append(" Image size: ${prepared.width}x${prepared.height}.")
            }
            if (context.pageTitle.isNotBlank()) {
                append(" Page title: ${context.pageTitle.trim()}.")
            }
            if (context.altOrCaption.isNotBlank()) {
                append(" Caption/alt: ${context.altOrCaption.trim()}.")
            }
            if (context.surroundingText.isNotBlank()) {
                append(" Context: ${context.surroundingText.trim()}.")
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-4-argon"
        const val FAST_COMPANION_MODEL = "gemini-3.8-flash"
        const val DEFAULT_THINKING_LEVEL = "low"
        const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

        val VALID_THINKING_LEVELS = setOf("low", "medium", "high")

        /**
         * JSON Schema for the structured tactile graphic (`LONG_TERM_PLAN.md` §9).
         */
        val STRUCTURED_TACTILE_GRAPHIC_SCHEMA_JSON: String = """
            {
              "type": "object",
              "properties": {
                "sceneSummary": { "type": "string" },
                "components": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "string" },
                      "label": { "type": "string" },
                      "parentId": { "type": ["string", "null"] },
                      "role": {
                        "type": "string",
                        "enum": ["panel", "figure", "object", "text", "arrow", "other"]
                      },
                      "box": {
                        "type": "array",
                        "items": { "type": "number" },
                        "minItems": 4,
                        "maxItems": 4
                      },
                      "strokes": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "points": {
                              "type": "array",
                              "items": {
                                "type": "array",
                                "items": { "type": "number" },
                                "minItems": 2,
                                "maxItems": 2
                              }
                            },
                            "closed": { "type": "boolean" }
                          },
                          "required": ["points", "closed"],
                          "additionalProperties": false
                        }
                      },
                      "embeddedText": { "type": "string" },
                      "description": { "type": "string" }
                    },
                    "required": ["id", "label", "parentId", "role", "box", "strokes", "embeddedText", "description"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["components"],
              "additionalProperties": false
            }
        """.trimIndent()

        private fun jsonEscape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
