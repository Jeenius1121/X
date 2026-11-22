package com.arc.xmanager

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.arc.xmanager.ui.AppViewModel
import com.arc.xmanager.ui.ArcApp
import com.arc.xmanager.ui.ArcTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var webView: WebView

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = createWebView()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ArcTheme { ArcApp(vm, webView) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "arcx" && data.host == "callback") vm.handleCallback(data)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private fun isXHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        val allowed = listOf("x.com", "twitter.com", "t.co", "twimg.com", "appleid.apple.com", "accounts.google.com")
        return allowed.any { h == it || h.endsWith(".$it") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportMultipleWindows(false)
        // X fonctionne mal quand il détecte une WebView : on se présente comme Chrome mobile.
        settings.userAgentString = settings.userAgentString
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    if (isXHost(uri.host)) return false
                }
                // Liens externes : ouverts dans le navigateur.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        loadUrl(HOME)
    }

    companion object {
        const val HOME = "https://x.com/home"
    }
}
