package com.usesonny.sdk

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

/** Embeddable chat view. Keep one active chat view per configured SDK. */
class SonnyChatView(private val activity: ComponentActivity) : FrameLayout(activity) {
    private val host = checkNotNull(Sonny.host) { "Call Sonny.configure first" }
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var multiple = false
    private var picker: ActivityResultLauncher<Array<String>>? = null
    private val chooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean = { callback, parameters ->
        pendingFiles?.onReceiveValue(null)
        pendingFiles = callback
        multiple = parameters.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val types = parameters.acceptTypes.filter { it.isNotBlank() }.toTypedArray()
        picker!!.launch(if (types.isEmpty()) arrayOf("*/*") else types)
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        picker = activity.activityResultRegistry.register("sonny-files-${UUID.randomUUID()}", ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val callback = pendingFiles
            pendingFiles = null
            callback?.onReceiveValue((if (multiple) uris else uris.take(1)).toTypedArray())
        }
        (host.webView.parent as? android.view.ViewGroup)?.removeView(host.webView)
        addView(host.webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        host.fileChooser = chooseFiles
        host.client.setOpen(true)
    }

    override fun onDetachedFromWindow() {
        val callback = pendingFiles
        pendingFiles = null
        callback?.onReceiveValue(null)
        if (host.fileChooser === chooseFiles) {
            host.fileChooser = null
            host.client.setOpen(false)
            removeView(host.webView)
        }
        picker?.unregister()
        picker = null
        super.onDetachedFromWindow()
    }
}
