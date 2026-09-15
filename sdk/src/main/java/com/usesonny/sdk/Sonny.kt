package com.usesonny.sdk

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import org.json.JSONObject
import java.io.Closeable

data class SonnyEvent(val name: String, val data: JSONObject)

/** Configure once in Application.onCreate. Notification permission belongs to your app. */
object Sonny {
    internal var host: SonnyWebViewHost? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(SonnyEvent) -> Unit>()
    private var watcher: Runnable? = null
    var unreadCount: Int = 0
        private set

    @MainThread
    fun configure(context: Context, siteId: String, appKey: String, origin: String = "https://www.usesonny.com") {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Configure Sonny on the main thread" }
        val configuration = SonnyConfiguration(siteId, appKey, origin)
        if (host?.client?.configuration == configuration) return
        check(host == null) { "Sonny is already configured for another app" }
        host = SonnyWebViewHost(context.applicationContext, configuration) { event ->
            if (event.name == "unread") unreadCount = event.data.optInt("count", 0)
            listeners.toList().forEach { it(event) }
        }
    }

    fun identify(jwt: String) = onMain { it.client.identify(jwt) }
    fun identify(email: String, name: String? = null, attributes: Map<String, Any?> = emptyMap()) = onMain {
        it.client.identify(email, name, attributes)
    }
    fun setAttributes(attributes: Map<String, Any?>) = onMain { it.client.setAttributes(attributes) }
    fun registerPushToken(token: String) = onMain { it.client.registerPushToken(token) }

    fun present(context: Context) = onMain {
        it.client.setOpen(true)
        context.startActivity(Intent(context, SonnyChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    fun dismiss() = onMain { it.client.setOpen(false) }
    fun reset() = onMain {
        watcher?.let(main::removeCallbacks)
        watcher = null
        it.client.reset()
    }

    /** Call when the user taps a notification. Other apps' and logged-out visitors' pushes return false. */
    @MainThread
    fun handlePush(context: Context, payload: Map<String, String>): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Handle a push tap on the main thread" }
        if (host?.client?.handlePush(payload) != true) return false
        present(context)
        return true
    }

    /** Call on the main thread and close the returned subscription when its UI is disposed. */
    @MainThread
    fun addListener(listener: (SonnyEvent) -> Unit): Closeable {
        listeners.add(listener)
        listener(SonnyEvent("unread", JSONObject().put("count", unreadCount)))
        return Closeable { main.post { listeners.remove(listener) } }
    }

    @MainThread
    fun watchAttributes(intervalMillis: Long = 1_000, getter: () -> Map<String, Any?>): Closeable {
        require(intervalMillis >= 250) { "Attribute interval must be at least 250ms" }
        watcher?.let(main::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (watcher !== this) return
                try { setAttributes(getter()) } catch (_: Exception) {
                    listeners.toList().forEach { it(SonnyEvent("error", JSONObject().put("code", "attributes_failed"))) }
                }
                main.postDelayed(this, intervalMillis)
            }
        }
        watcher = task
        main.post(task)
        return Closeable { main.post { if (watcher === task) { watcher = null; main.removeCallbacks(task) } } }
    }

    private fun onMain(action: (SonnyWebViewHost) -> Unit) {
        val run = { action(checkNotNull(host) { "Call Sonny.configure before using the SDK" }) }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }
}
