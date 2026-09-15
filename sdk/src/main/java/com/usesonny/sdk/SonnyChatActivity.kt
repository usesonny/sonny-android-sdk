package com.usesonny.sdk

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.Closeable

class SonnyChatActivity : ComponentActivity() {
    private var subscription: Closeable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Sonny.host == null) { finish(); return }
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val chat = SonnyChatView(this)
        ViewCompat.setOnApplyWindowInsetsListener(chat) { view, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            val padding = insets.getInsets(types)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            // WebView must receive the updated zeroes to avoid applying these twice.
            WindowInsetsCompat.Builder(insets).setInsets(types, Insets.NONE).build()
        }
        setContentView(chat)
        subscription = Sonny.addListener { if (it.name == "close") finish() }
    }
    override fun onDestroy() {
        subscription?.close()
        super.onDestroy()
    }
}
