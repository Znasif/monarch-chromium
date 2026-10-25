package org.monarch.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Structured tactile graphics, indexed by the SHA-256 of the image bytes so the same
 * image is never sent to the model twice. Lookup: app storage → bundled assets → Gemini.
 */
class GraphicStore(private val context: Context) {
    private val dir = File(context.filesDir, "graphics").apply { mkdirs() }

    fun load(imageBytes: ByteArray, title: String): String {
        val key = sha256(imageBytes)
        File(dir, "$key.json").takeIf { it.exists() }?.let { return it.readText() }
        runCatching { context.assets.open("graphics/$key.json").bufferedReader().readText() }.getOrNull()?.let { return it }
        val graphic = askGemini(imageBytes, title)
        File(dir, "$key.json").writeText(graphic)
        return graphic
    }

    private fun askGemini(imageBytes: ByteArray, title: String): String {
        require(BuildConfig.GEMINI_API_KEY.isNotEmpty()) { "No saved graphic for this image and no Gemini key in this build" }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT + if (title.isNotBlank()) "\n\nThe page describes this image as: $title" else ""))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/png")
                    .put("data", Base64.encodeToString(lowResPng(imageBytes), Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = connection.responseCode
        val text = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
        check(status < 400) { "Gemini error $status: ${text.take(200)}" }
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val answer = (0 until parts.length()).map { parts.getJSONObject(it) }
            .last { it.has("text") && !it.optBoolean("thought") }.getString("text")
        JSONObject(answer).getJSONArray("components")  // validate before saving
        return answer
    }

    /** 768 px copy on white (transparent images keep their black labels). */
    private fun lowResPng(bytes: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(source.width, source.height))
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(source, w, h, true), 0f, 0f, null)
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object {
        const val MODEL = "gemini-4-argon"
        const val MAX_SIDE = 768

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Same rules as scripts/tactile_graphic.py. */
        val PROMPT = """
            Turn this image into a tactile graphic for a blind reader using a 96x40 pin
            display. Return JSON {"components": [...]} where each component has id, label,
            parentId (null for top level), role (panel|figure|object|text|arrow|other),
            box [x0,y0,x1,y1], strokes [{"points": [[x,y],...], "closed": bool}],
            embeddedText and description.
            - Top level: the image's main regions (comic panels, figure panels, or one
              component for a single picture). Children: the figures, objects and text areas
              inside them, ordered left to right, top to bottom.
            - strokes: polylines tracing each ink line along its CENTER (one line per stroke,
              never the two edges of a thick line). Coordinates are normalized 0..1 of the
              image, origin top-left. A head is a closed loop of 8-12 points; a limb is 2-3
              points. Panel borders are closed rectangles.
            - Draw outlines only: no shading, hatching, texture or colour fill. Put meaningful
              colour into the label instead.
            - Lettering is never drawn: make a role "text" component with its box and the
              exact words in embeddedText (reading order), and no strokes.
            - Drop decoration that would clutter touch (signatures, watermarks, tiny marks).
            - box: [x0, y0, x1, y1] normalized. Children lie inside their parent's box.
            - label: short and specific; description: one sentence.
        """.trimIndent()
    }
}
