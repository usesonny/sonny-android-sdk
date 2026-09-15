package com.usesonny.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Session state shared by the modal, embedded view and React Native adapter. */
internal class SonnyClient(
    val configuration: SonnyConfiguration,
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val send: (JSONObject) -> Unit,
) {
    private val namespace = "${configuration.siteId}:${configuration.appKey}:"
    private var ready = false
    private var bridgeLoaded = false
    private val pending = mutableListOf<JSONObject>()
    private var identity: JSONObject? = null
    private var attributes = JSONObject()
    private var pushToken: String? = read(namespace + "push")
    private var pushGeneration = (read(namespace + "push-generation")?.toLongOrNull() ?: System.currentTimeMillis())
        .also { write(namespace + "push-generation", it.toString()) }
    private var pushUnregistrations = read(namespace + "push-unregistrations")?.let { saved ->
        val array = JSONArray(saved)
        MutableList(array.length()) { array.getJSONObject(it) }
    } ?: mutableListOf()
    private var foreground = true
    private var open = false
    var onEvent: ((String, JSONObject) -> Unit)? = null
    var onUnreadChange: ((Int) -> Unit)? = null
    var unreadCount = 0
        private set
    var visitorId: String = read(namespace + "visitor") ?: UUID.randomUUID().toString().also { write(namespace + "visitor", it) }
        private set

    fun identify(jwt: String) {
        require(jwt.isNotBlank()) { "Identity JWT must not be empty" }
        identity = JSONObject().put("userJwt", jwt)
        command("identify", identity!!)
    }

    fun identify(email: String, name: String?, traits: Map<String, Any?>) {
        require(email.contains("@")) { "Email is invalid" }
        identity = JSONObject().put("email", email).put("name", name).put("attributes", JSONObject(traits))
        command("identify", identity!!)
    }

    fun setAttributes(values: Map<String, Any?>) {
        values.forEach { (key, value) -> attributes.put(key, value ?: JSONObject.NULL) }
        command("setAttributes", JSONObject(attributes.toString()))
    }

    fun registerPushToken(token: String) {
        require(token.isNotBlank()) { "Push token is empty" }
        if (pushToken != null && pushToken != token) {
            advancePushGeneration()
            queuePushUnregistrations()
        }
        pushToken = token
        write(namespace + "push", token)
        command("registerPushToken", JSONObject().put("token", token).put("platform", "android").put("generation", pushGeneration))
    }

    fun pageLoading() { ready = false; bridgeLoaded = false }
    fun setForeground(active: Boolean) {
        foreground = active
        command("setForeground", JSONObject().put("active", active))
        if (active) queuePushUnregistrations()
    }
    fun setOpen(value: Boolean) {
        updateOpen(value)
        command(if (value) "open" else "close")
    }

    private fun updateOpen(value: Boolean) {
        if (open == value) return
        open = value
        onEvent?.invoke(if (value) "open" else "close", JSONObject())
    }

    fun command(name: String, data: JSONObject = JSONObject(), immediate: Boolean = false) {
        val message = JSONObject().put("version", 1).put("command", name).put("data", data)
        if (ready || (immediate && bridgeLoaded)) send(message) else pending.add(message)
    }

    fun reset() {
        advancePushGeneration()
        pending.clear()
        identity = null
        attributes = JSONObject()
        pushToken = null
        write(namespace + "push", null)
        updateOpen(false)
        visitorId = UUID.randomUUID().toString()
        write(namespace + "visitor", visitorId)
        updateUnread(0)
        command("reset", JSONObject().put("visitorId", visitorId).put("pushUnregistrations", JSONArray(pushUnregistrations)), immediate = true)
    }

    private fun advancePushGeneration() {
        val nextGeneration = maxOf(pushGeneration + 1, System.currentTimeMillis())
        pushToken?.let { token ->
            val removals = (pushUnregistrations + JSONObject().put("id", UUID.randomUUID().toString())
                .put("visitorId", visitorId).put("token", token).put("platform", "android").put("generation", nextGeneration)).toMutableList()
            persistPushUnregistrations(removals)
            pushUnregistrations = removals
        }
        write(namespace + "push-generation", nextGeneration.toString())
        pushGeneration = nextGeneration
    }

    private fun queuePushUnregistrations() {
        val messages = pushUnregistrations.filter { removal ->
            pending.none { it.optString("command") == "unregisterPushToken" && it.optJSONObject("data")?.optString("id") == removal.optString("id") }
        }.map { JSONObject().put("version", 1).put("command", "unregisterPushToken").put("data", it) }
        if (ready) messages.forEach(send) else pending.addAll(0, messages)
    }

    private fun persistPushUnregistrations(removals: List<JSONObject>) {
        write(namespace + "push-unregistrations", if (removals.isEmpty()) null else JSONArray(removals).toString())
    }

    fun handlePush(payload: Map<String, String>): Boolean {
        val conversationId = payload["conversationId"]
        if (payload["sonny"] != "visitor" || payload["siteId"] != configuration.siteId
            || payload["appKey"] != configuration.appKey || payload["visitorId"] != visitorId
            || conversationId.isNullOrBlank()) return false
        command("openConversation", JSONObject().put("conversationId", conversationId))
        return true
    }

    private fun updateUnread(count: Int) {
        if (count == unreadCount) return
        unreadCount = count
        onUnreadChange?.invoke(count)
    }

    fun receive(event: JSONObject) {
        if (event.optInt("version") != 1) return
        when (event.optString("event")) {
            "pushUnregistered" -> {
                val id = event.optJSONObject("data")?.optString("id") ?: return
                val remaining = pushUnregistrations.filter { it.optString("id") != id }.toMutableList()
                try { persistPushUnregistrations(remaining); pushUnregistrations = remaining }
                catch (_: Exception) { onEvent?.invoke("error", JSONObject().put("code", "push_storage_failed")) }
                return
            }
            "open", "close" -> { updateOpen(event.optString("event") == "open"); return }
            "unread" -> updateUnread(event.optJSONObject("data")?.optInt("count", 0)?.coerceAtLeast(0) ?: 0)
            "ready" -> {
                bridgeLoaded = true
                ready = false
                if (pending.none { it.optString("command") == "reset" }) {
                    queuePushUnregistrations()
                }
                if (identity != null && pending.none { it.optString("command") == "identify" }) {
                    pending.add(0, JSONObject().put("version", 1).put("command", "identify").put("data", identity))
                }
                if (attributes.length() > 0 && pending.none { it.optString("command") == "setAttributes" }) {
                    command("setAttributes", JSONObject(attributes.toString()))
                }
                if (pushToken != null && pending.none { it.optString("command") == "registerPushToken" }) {
                    command("registerPushToken", JSONObject().put("token", pushToken).put("platform", "android").put("generation", pushGeneration))
                }
                send(JSONObject().put("version", 1).put("id", "initialize").put("command", "init")
                    .put("data", JSONObject().put("visitorId", visitorId).put("active", foreground).put("open", open)))
            }
            "result" -> if (event.optString("id") == "initialize") {
                ready = true
                val queued = pending.toList()
                pending.clear()
                queued.forEach(send)
            }
        }
        onEvent?.invoke(event.optString("event"), event.optJSONObject("data") ?: JSONObject())
    }
}
