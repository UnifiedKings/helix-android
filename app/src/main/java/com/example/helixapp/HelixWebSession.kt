package com.example.helixapp

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Keeps embedded Helix pages on the same server-side session as the native API client.
 * The cookie remains HttpOnly from page JavaScript's point of view; Android injects it
 * into WebView's cookie store before navigation.
 */
object HelixWebSession {
    fun sync(context: Context) {
        val baseUrl = HelixPrefs.getBaseUrl(context).trimEnd('/')
        val token = HelixPrefs.getSessionToken(context)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        if (token.isNullOrBlank()) {
            clear(context)
            return
        }

        val secure = if (baseUrl.startsWith("https://", ignoreCase = true)) "; Secure" else ""
        cookies.setCookie(
            baseUrl,
            "mr_session=$token; Path=/; HttpOnly; SameSite=Lax$secure",
        )
        cookies.flush()
    }

    fun clear(context: Context) {
        val baseUrl = HelixPrefs.getBaseUrl(context).trimEnd('/')
        val cookies = CookieManager.getInstance()
        cookies.setCookie(
            baseUrl,
            "mr_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
        )
        cookies.flush()
    }
}

/**
 * Building block for future web-backed settings/station/lobby screens.
 * It deliberately does not expose a JavaScript bridge; embedded Helix pages communicate
 * with Helix over their normal same-origin HTTP/WebSocket APIs.
 */
@Composable
fun HelixWebScreen(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val baseUrl = HelixPrefs.getBaseUrl(context).trimEnd('/')
    val normalizedPath = if (path.startsWith('/')) path else "/$path"
    val targetUrl = "$baseUrl$normalizedPath"

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            HelixWebSession.sync(ctx)
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                loadUrl(targetUrl)
            }
        },
        update = { webView ->
            HelixWebSession.sync(context)
            if (webView.url != targetUrl) webView.loadUrl(targetUrl)
        },
    )
}
