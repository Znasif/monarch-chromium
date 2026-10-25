package org.monarch.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Browser screen: address bar plus WebView; KeySoft reads the page, images get tactile buttons. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private var userAgent = ""
    private val bridge by lazy { assets.open("bridge.js").bufferedReader().readText() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = EditText(this).apply {
            hint = "Web address"
            contentDescription = "Web address"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "MonarchBridge")
            userAgent = settings.userAgentString
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    address.setText(url)
                    view.evaluateJavascript(bridge, null)
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(address, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        go(intent.dataString ?: home())
    }

    private fun home(): String =
        if (runCatching { assets.open("demo/index.html").close() }.isSuccess) "file:///android_asset/demo/index.html"
        else "https://xkcd.com/327/"

    private fun go(input: String) {
        val text = input.trim()
        web.loadUrl(if ("://" in text) text else "https://$text")
        web.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    inner class Bridge {
        @JavascriptInterface
        fun openImage(src: String, title: String) {
            thread {
                val bytes = runCatching { download(src) }.getOrNull()
                runOnUiThread {
                    if (bytes == null) {
                        web.announceForAccessibility("Could not load this image")
                        return@runOnUiThread
                    }
                    PendingImage.bytes = bytes
                    PendingImage.title = title
                    startActivity(Intent(this@BrowserActivity, TactileActivity::class.java))
                }
            }
        }
    }

    private fun download(src: String): ByteArray = when {
        src.startsWith("file:///android_asset/") -> assets.open(src.removePrefix("file:///android_asset/")).readBytes()
        src.startsWith("data:") -> Base64.decode(src.substringAfter(","), Base64.DEFAULT)
        else -> (URL(src).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(src)?.let { setRequestProperty("Cookie", it) }
            connectTimeout = 15_000
            readTimeout = 30_000
            inputStream.use { it.readBytes() }
        }
    }
}
