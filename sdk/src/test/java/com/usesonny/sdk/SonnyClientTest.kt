package com.usesonny.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SonnyClientTest {
    @Test fun `token rotation retires old token and foreground retries pending removals`() {
        val sent = mutableListOf<JSONObject>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), { null }, { _, _ -> }, sent::add)
        client.receive(JSONObject("""{"version":1,"event":"ready"}"""))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize"}"""))
        client.registerPushToken("token-a")
        client.registerPushToken("token-b")
        assertTrue(sent.any { it.optString("command") == "unregisterPushToken" && it.getJSONObject("data").optString("token") == "token-a" })
        client.reset()
        sent.clear()
        client.setForeground(true)
        val removals = sent.filter { it.optString("command") == "unregisterPushToken" }.map { it.getJSONObject("data").getString("token") }
        assertEquals(setOf("token-a", "token-b"), removals.toSet())
    }

    @Test fun `logout retries push removal after restart until acknowledged`() {
        val stored = mutableMapOf<String, String>()
        val sent = mutableListOf<JSONObject>()
        fun client() = SonnyClient(SonnyConfiguration("site-a", "key-a"), stored::get,
            { key, value -> if (value == null) stored.remove(key) else stored[key] = value }, sent::add)
        fun load(value: SonnyClient) {
            value.receive(JSONObject("""{"version":1,"event":"ready"}"""))
            value.receive(JSONObject("""{"version":1,"event":"result","id":"initialize"}"""))
        }
        val first = client()
        load(first)
        val visitor = first.visitorId
        first.registerPushToken("device-token")
        val generation = sent.last().getJSONObject("data").optLong("generation", -1)
        first.reset()
        sent.clear()
        val restarted = client()
        load(restarted)
        val removal = sent.firstOrNull { it.optString("command") == "unregisterPushToken" }?.getJSONObject("data")
        assertNotNull("Push removal must survive logout and process death", removal)
        assertEquals(visitor, removal!!.getString("visitorId"))
        assertEquals("device-token", removal.getString("token"))
        assertTrue(removal.getLong("generation") > generation)
        assertFalse(sent.any { it.optString("command") == "registerPushToken" })
        restarted.receive(JSONObject().put("version", 1).put("event", "pushUnregistered").put("data", JSONObject().put("id", removal.getString("id"))))
        sent.clear()
        load(client())
        assertFalse(sent.any { it.optString("command") == "unregisterPushToken" })
    }

    @Test fun `reload restores all attributes when an update arrives during loading`() {
        val sent = mutableListOf<JSONObject>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), { null }, { _, _ -> }, sent::add)
        fun load() {
            client.receive(JSONObject("""{"version":1,"event":"ready"}"""))
            client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize"}"""))
        }
        load()
        client.setAttributes(mapOf("plan" to "pro", "locale" to "en"))
        client.pageLoading()
        sent.clear()
        client.setAttributes(mapOf("locale" to "fr"))
        load()
        val restored = mutableMapOf<String, String>()
        sent.filter { it.optString("command") == "setAttributes" }.forEach { message ->
            val data = message.getJSONObject("data")
            data.keys().forEach { restored[it] = data.getString(it) }
        }
        assertEquals(mapOf("plan" to "pro", "locale" to "fr"), restored)
    }

    @Test fun `logout clears a visible initializing widget without waiting for initialization`() {
        val sent = mutableListOf<JSONObject>()
        val events = mutableListOf<String>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), { null }, { _, _ -> }, sent::add)
        client.onEvent = { name, _ -> events.add(name) }
        client.setOpen(true)
        client.receive(JSONObject("""{"version":1,"event":"ready"}"""))
        client.identify("old.jwt")
        client.reset()
        assertEquals("reset", sent.last().getString("command"))
        assertEquals(client.visitorId, sent.last().getJSONObject("data").getString("visitorId"))
        assertTrue("A native modal must close even before the widget has loaded", events.contains("close"))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize"}"""))
        assertFalse(sent.any { it.toString().contains("old.jwt") })
    }

    @Test fun `background configuration and push registration survive page loading until logout`() {
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<JSONObject>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, sent::add)
        client.setForeground(false)
        client.setAttributes(mapOf("plan" to "pro"))
        client.registerPushToken("device-token")
        client.pageLoading()
        client.receive(JSONObject("""{"version":1,"event":"ready","data":{}}"""))
        assertFalse(sent.first().getJSONObject("data").getBoolean("active"))
        assertFalse(sent.first().getJSONObject("data").getBoolean("open"))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize","data":{}}"""))
        assertTrue(sent.any { it.optString("command") == "registerPushToken" && it.getJSONObject("data").getString("token") == "device-token" })
        sent.clear()
        client.reset()
        client.pageLoading()
        client.receive(JSONObject("""{"version":1,"event":"ready","data":{}}"""))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize","data":{}}"""))
        assertFalse(sent.any { it.optString("command") == "registerPushToken" })
        assertFalse(sent.any { it.optString("command") == "setAttributes" })
    }

    @Test fun `renderer reload restores identity and only accepts push taps for this visitor and app`() {
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<JSONObject>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, sent::add)
        fun load() {
            client.receive(JSONObject("""{"version":1,"event":"ready","data":{}}"""))
            client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize","data":{}}"""))
        }
        load()
        client.identify("current.jwt")
        sent.clear()
        load()
        assertTrue(sent.any { it.optString("command") == "identify" && it.getJSONObject("data").optString("userJwt") == "current.jwt" })
        val payload = mapOf("sonny" to "visitor", "siteId" to "site-a", "appKey" to "key-a", "visitorId" to client.visitorId, "conversationId" to "conversation-a")
        assertFalse(client.handlePush(payload + ("appKey" to "another-key")))
        assertFalse(client.handlePush(payload + ("visitorId" to "old-visitor")))
        assertTrue(client.handlePush(payload))
        assertEquals("openConversation", sent.last().getString("command"))
        assertEquals("conversation-a", sent.last().getJSONObject("data").getString("conversationId"))
    }

    @Test fun `logout replaces the visitor and clears queued identity and unread before reconnect`() {
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<JSONObject>()
        val counts = mutableListOf<Int>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, sent::add)
        client.onUnreadChange = counts::add
        val oldVisitor = client.visitorId
        client.identify("old.jwt")
        client.receive(JSONObject("""{"version":1,"event":"unread","data":{"count":4}}"""))
        assertEquals(4, client.unreadCount)
        client.reset()
        assertNotEquals(oldVisitor, client.visitorId)
        assertEquals(listOf(4, 0), counts)
        client.receive(JSONObject("""{"version":1,"event":"ready","data":{}}"""))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize","data":{}}"""))
        assertFalse(sent.any { it.toString().contains("old.jwt") })
        assertEquals(client.visitorId, sent.first().getJSONObject("data").getString("visitorId"))
        val relaunched = SonnyClient(SonnyConfiguration("site-a", "key-a"), storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, {})
        assertEquals(client.visitorId, relaunched.visitorId)
    }

    @Test fun `identity waits for bridge initialization and is never persisted`() {
        val storage = mutableMapOf<String, String>()
        val sent = mutableListOf<JSONObject>()
        val client = SonnyClient(SonnyConfiguration("site-a", "key-a"), storage::get,
            { key, value -> if (value == null) storage.remove(key) else storage[key] = value }, sent::add)
        client.identify("signed.customer.jwt")
        assertTrue(sent.isEmpty())
        client.receive(JSONObject("""{"version":1,"event":"ready","data":{}}"""))
        assertEquals("init", sent.single().getString("command"))
        client.receive(JSONObject("""{"version":1,"event":"result","id":"initialize","data":{}}"""))
        assertEquals("identify", sent.last().getString("command"))
        assertEquals("signed.customer.jwt", sent.last().getJSONObject("data").getString("userJwt"))
        assertFalse(storage.values.contains("signed.customer.jwt"))
    }
}
