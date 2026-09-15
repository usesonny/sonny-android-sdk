package com.usesonny.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLEncoder

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
internal class SonnyWebViewHost(
    private val context: Context,
    private val configuration: SonnyConfiguration,
    private val onEvent: (SonnyEvent) -> Unit,
) : DefaultLifecycleObserver {
    private val preferences = EncryptedSharedPreferences.create(context, "sonny_sdk",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val client = SonnyClient(configuration, { preferences.getString(it, null) }, { key, value ->
        val edit = preferences.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        check(edit.commit()) { "Could not persist Sonny visitor state" }
    }) { command ->
        webView.evaluateJavascript("window.SonnyBridge.receive(${JSONObject.quote(command.toString())});", null)
    }
    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    private val offlineUrl = "https://appassets.androidplatform.net/assets/sonny-offline.html?retry=" + URLEncoder.encode(configuration.embedUrl, "UTF-8")
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    lateinit var webView: WebView
        private set

    init {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Update Android System WebView to use Sonny" }
        client.onUnreadChange = { onEvent(SonnyEvent("unread", JSONObject().put("count", it))) }
        client.onEvent = { name, data ->
            if (name == "openExternal") openExternal(data.optString("url"))
            if (name != "unread") onEvent(SonnyEvent(name, data))
        }
        client.setForeground(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        webView = createWebView()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        webView.loadUrl(configuration.embedUrl, mapOf("x-sonny-sdk" to "android/${BuildConfig.SDK_VERSION}"))
    }

    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString += " SonnySDK/android/${BuildConfig.SDK_VERSION}"
        WebViewCompat.addWebMessageListener(this, "AndroidBridge", setOf(configuration.origin)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (configuration.acceptsBridgeMessage(sourceOrigin.toString(), isMainFrame)) {
                try { client.receive(JSONObject(message.data ?: "{}")) } catch (_: Exception) {
                    onEvent(SonnyEvent("error", JSONObject().put("code", "invalid_bridge_event")))
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, parameters: FileChooserParams): Boolean =
                fileChooser?.invoke(callback, parameters) ?: false
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) { client.pageLoading() }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (url == configuration.embedUrl || url == offlineUrl) return false
                openExternal(url)
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() != offlineUrl) view.loadUrl(offlineUrl)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) view.loadUrl(offlineUrl)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val parent = view.parent as? ViewGroup
                val layout = view.layoutParams
                parent?.removeView(view)
                view.destroy()
                client.pageLoading()
                webView = createWebView()
                parent?.addView(webView, layout)
                webView.loadUrl(configuration.embedUrl)
                return true
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) { client.setForeground(true) }
    override fun onStop(owner: LifecycleOwner) { client.setForeground(false) }

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https", "mailto", "tel")) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { onEvent(SonnyEvent("error", JSONObject().put("code", "no_link_handler"))) }
    }
}
