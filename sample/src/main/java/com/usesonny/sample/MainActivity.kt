package com.usesonny.sample

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.usesonny.sdk.Sonny
import com.usesonny.sdk.SonnyChatView
import java.io.Closeable

class MainActivity : ComponentActivity() {
    private var subscription: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = getSharedPreferences("sample", MODE_PRIVATE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            val padding = insets.getInsets(types)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsetsCompat.Builder(insets).setInsets(types, Insets.NONE).build()
        }
        setContentView(scroll)
        layout.addView(TextView(this).apply { text = "Sonny visitor SDK"; textSize = 25f })
        layout.addView(TextView(this).apply { text = "Use an Android app key from your widget settings. Identity tokens must come from your backend." })
        fun input(hint: String, initial: String = "") = EditText(this).apply {
            this.hint = hint; setText(initial); isSingleLine = true
            layout.addView(this)
        }
        val origin = input("Sonny origin", settings.getString("origin", "https://www.usesonny.com").orEmpty())
        val site = input("Site ID", settings.getString("siteId", "").orEmpty())
        val app = input("App key", settings.getString("appKey", "").orEmpty())
        val status = TextView(this)
        fun action(title: String, callback: () -> Unit) {
            layout.addView(Button(this).apply { text = title; setOnClickListener {
                try { callback() } catch (error: Exception) { status.text = error.message ?: "Action failed" }
            } })
        }
        action("Configure") {
            Sonny.configure(applicationContext, site.text.toString(), app.text.toString(), origin.text.toString())
            settings.edit().putString("siteId", site.text.toString()).putString("appKey", app.text.toString()).putString("origin", origin.text.toString()).apply()
            status.text = "Configured. You can chat anonymously."
        }
        val jwt = input("Identity JWT (preferred)").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val email = input("Email for legacy identity")
        val name = input("Name")
        action("Identify") {
            if (jwt.text.isNotBlank()) Sonny.identify(jwt.text.toString())
            else Sonny.identify(email.text.toString(), name.text.toString())
        }
        action("Set sample attributes") { Sonny.setAttributes(mapOf("plan" to "sample", "platform" to "android")) }
        action("Open support") { Sonny.present(this) }
        val inline = FrameLayout(this).apply { visibility = View.GONE }
        action("Toggle embedded chat") {
            if (inline.childCount == 0) { inline.addView(SonnyChatView(this)); inline.visibility = View.VISIBLE }
            else { inline.removeAllViews(); inline.visibility = View.GONE }
        }
        val pushToken = input("FCM token supplied by your app").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        action("Register push token") { Sonny.registerPushToken(pushToken.text.toString()) }
        action("Log out") { Sonny.reset(); jwt.text.clear(); email.text.clear(); name.text.clear(); pushToken.text.clear() }
        layout.addView(status)
        layout.addView(inline, LinearLayout.LayoutParams(-1, 900))
        subscription = Sonny.addListener { event ->
            status.text = when (event.name) {
                "unread" -> "Unread: ${event.data.optInt("count")}"
                "identityRequired" -> "Your backend must supply a fresh identity JWT."
                "error" -> "SDK error: ${event.data.optString("code")}"
                else -> status.text
            }
        }
        handlePushIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handlePushIntent(intent) }
    private fun handlePushIntent(intent: Intent) {
        val extras = intent.extras ?: return
        val payload = extras.keySet().associateWith { extras.getString(it).orEmpty() }
        if (payload["sonny"] == "visitor") Sonny.handlePush(this, payload)
    }
    override fun onDestroy() { subscription?.close(); super.onDestroy() }
}
