package org.tilecast.player.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Test
import org.tilecast.player.network.ManifestItem

class RuntimeHostSessionTest {
    private val items = listOf(
        ManifestItem("i1", "a1", "v1", "image", 10_000, "contain", "none", false, 0.5f, deliveryPolicy = "cache"),
    )

    private class Sink {
        val boundaries = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        var progress = 0
    }

    private fun session(
        sink: Sink,
        rendererGeneration: Long = 1,
        onRemoteWebChanged: (List<RemoteWebHostManager.Surface>) -> Unit = {},
    ): RuntimeHostSession =
        RuntimeHostSession(
            "0.25.0", "webview/1", rendererGeneration, items, "act1",
            onBoundary = { id, asset -> sink.boundaries.add(id to asset) },
            onError = { sink.errors.add(it) },
            onProgress = { sink.progress++ },
            onRemoteWebChanged = onRemoteWebChanged,
        )

    private fun presentationMessage(activation: String = "act1", generation: Long = 1) =
        RuntimeBridgeProtocol.parseHostMessage(
            """{"type":"presentation","activation":{"activationId":"$activation","generation":$generation},"presentation":{"state":"playing","items":[]}}""",
        ).getOrThrow()

    @Test fun offerBumpsStateAndServesBundle() {
        val session = RuntimeHostSessionTest.Sink().let { session(it) }
        val first = session.offer(presentationMessage())
        assertEquals(1L, first.stateGeneration)
        val reply = session.handlePageMessage("""{"type":"host-state","wantGeneration":-1}""", 1, null)!!
        val bundle = Json.parseToJsonElement(reply).jsonObject
        assertEquals(1, bundle["generation"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "playing",
            bundle["messages"]!!.jsonArray[0].jsonObject["presentation"]!!.jsonObject["state"]!!.jsonPrimitive.content,
        )
    }

    @Test fun pluginUpdatePreservesActivationAndRejectsForeignGeneration() {
        val session = session(Sink())
        session.offer(presentationMessage("act1", 17))
        assertTrue(session.matchesActivation("act1", 17))
        assertFalse(session.matchesActivation("act1", 18))
        assertFalse(session.matchesActivation("act2", 17))
        session.offer(RuntimeBridgeProtocol.parseHostMessage("""{"type":"plugins","plugins":[],"clockOffsetMs":50}""").getOrThrow())
        assertTrue(session.matchesActivation("act1", 17))
        session.offer(presentationMessage("act2", 18))
        assertFalse(session.matchesActivation("act1", 17))
        assertTrue(session.matchesActivation("act2", 18))
        session.offer(presentationMessage("act2", 2_147_483_648L))
        assertTrue(session.matchesActivation("act2", 2_147_483_648L))
    }

    @Test fun rejectsStaleRendererCallbacks() {
        val sink = Sink()
        val session = session(sink)
        assertNull(session.handlePageMessage("""{"type":"host-state","wantGeneration":-1}""", 9, null))
        session.offer(presentationMessage())
        assertNull(
            session.handlePageMessage("""{"type":"evidence","activationId":"act1","itemId":"i1","kind":"image-shown"}""", 9, null),
        )
        assertTrue(sink.progress == 0)
    }

    @Test fun sharedCrashPolicyRejectsDeadRendererCallbacks() {
        val sink = Sink()
        val crashPolicy = RuntimeCrashPolicy()
        val session = RuntimeHostSession(
            "0.25.0", "webview/1", 1, items, "act1",
            onBoundary = { id, asset -> sink.boundaries.add(id to asset) },
            onError = { sink.errors.add(it) },
            onProgress = { sink.progress++ },
            crashPolicy = crashPolicy,
        )
        session.offer(presentationMessage())
        crashPolicy.onRendererGone()
        assertEquals(2L, crashPolicy.onRecreated())

        session.handlePageMessage(
            """{"type":"evidence","activationId":"act1","itemId":"i1","kind":"image-shown"}""",
            1,
            null,
        )
        assertEquals(0, sink.progress)
    }

    @Test fun routesEvidenceToNativePipeline() {
        val sink = Sink()
        val session = session(sink)
        session.offer(presentationMessage())
        session.handlePageMessage(
            """{"type":"evidence","activationId":"act1","itemId":"i1","kind":"item-started"}""", 1, null,
        )
        assertEquals(listOf("i1" to "a1"), sink.boundaries)
        session.handlePageMessage(
            """{"type":"playback-error","activationId":"act1","message":"boom"}""", 1, null,
        )
        assertEquals(listOf("boom"), sink.errors)
    }

    @Test fun activationChangeResetsRemoteSurfaces() {
        val sink = Sink()
        val session = session(sink)
        session.offer(presentationMessage("act1"))
        val created = session.handlePageMessage(
            """{"type":"host-call","id":"c1","call":"remoteWeb.create","payload":{
            "surfaceId":"s1",
            "content":{"kind":"page","url":"https://example.com/","allowedHosts":["example.com"],"javascriptEnabled":true,"domStorageEnabled":true,"cookiePolicy":"first_party","userAgent":"","zoomPercent":100,"scrollX":0,"scrollY":0,"backgroundColor":"#000000"},
            "viewport":{"x":0,"y":0,"width":100,"height":100,"deviceScale":1},
            "muted":false,"visible":true}}""".trimIndent().replace("\n", ""),
            1, null,
        )!!
        val result = Json.parseToJsonElement(created).jsonObject
        assertEquals(true, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "host-layer",
            result["result"]!!.jsonObject["kind"]!!.jsonPrimitive.content,
        )
        val rotated = session.offer(presentationMessage("act2", 2))
        assertEquals(listOf("s1"), rotated.removedSurfaces)
        assertTrue(session.remoteWeb.snapshot().isEmpty())
    }

    @Test fun remoteHostCallsPublishCreateReloadVisibilityAndDestroy() {
        val changes = mutableListOf<List<RemoteWebHostManager.Surface>>()
        val session = session(Sink(), onRemoteWebChanged = { changes.add(it) })
        session.offer(presentationMessage())
        val create = """{"type":"host-call","id":"c1","call":"remoteWeb.create","payload":{
            "surfaceId":"site",
            "content":{"kind":"page","url":"http://172.17.37.60/","allowedHosts":["172.17.37.60"],"javascriptEnabled":true,"domStorageEnabled":true,"cookiePolicy":"disabled","userAgent":"","zoomPercent":100,"scrollX":0,"scrollY":0,"backgroundColor":"#000000"},
            "viewport":{"x":12,"y":15,"width":400,"height":200,"deviceScale":2},
            "muted":true,"visible":false}}""".trimIndent().replace("\n", "")
        val reply = session.handlePageMessage(create, 1, null)!!
        assertEquals("true", Json.parseToJsonElement(reply).jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("site", changes.last().single().surfaceId)
        assertEquals(800, changes.last().single().viewportPx.width)
        assertFalse(changes.last().single().visible)
        session.handlePageMessage(
            """{"type":"host-call","id":"c2","call":"remoteWeb.setVisible","payload":{"surfaceId":"site","visible":true}}""",
            1, null,
        )
        assertTrue(changes.last().single().visible)
        session.handlePageMessage(
            """{"type":"host-call","id":"c3","call":"remoteWeb.reload","payload":{"surfaceId":"site"}}""",
            1, null,
        )
        assertEquals(1, changes.last().single().reloadCount)
        session.handlePageMessage(
            """{"type":"host-call","id":"c4","call":"remoteWeb.destroy","payload":{"surfaceId":"site"}}""",
            1, null,
        )
        assertTrue(changes.last().isEmpty())
    }

    @Test fun replacementRebindsEvidenceToNewActivationAndItems() {
        val sink = Sink()
        val session = session(sink)
        session.offer(presentationMessage("act1", 1))
        val replacementItems = listOf(
            ManifestItem("i2", "a2", "v2", "image", 10_000, "contain", "none", false, 0.5f, deliveryPolicy = "cache"),
        )
        session.updatePresentation(replacementItems, "act2")
        session.offer(presentationMessage("act2", 2))

        session.handlePageMessage(
            """{"type":"evidence","activationId":"act2","itemId":"i2","kind":"item-started"}""", 1, null,
        )
        assertEquals(listOf("i2" to "a2"), sink.boundaries)

        session.handlePageMessage(
            """{"type":"evidence","itemId":"i2","kind":"item-started"}""", 1, null,
        )
        assertEquals(1, sink.boundaries.size)
    }

    @Test fun commandReplayIsAcknowledgedOnlyAfterDeliveredState() {
        val session = RuntimeHostSessionTest.Sink().let { session(it) }
        val command = RuntimeBridgeProtocol.parseHostMessage(
            """{"type":"command","command":"skip-item"}""",
        ).getOrThrow()
        session.offer(command)

        val first = session.handlePageMessage(
            """{"type":"host-state","wantGeneration":-1}""", 1, null,
        )!!
        val firstMessages = Json.parseToJsonElement(first).jsonObject["messages"]!!.jsonArray
        assertTrue(firstMessages.any { it.jsonObject["type"]?.jsonPrimitive?.content == "command" })

        // No delivery acknowledgement: the command remains pending.
        val retry = session.handlePageMessage(
            """{"type":"host-state","wantGeneration":-1}""", 1, null,
        )!!
        assertTrue(
            Json.parseToJsonElement(retry).jsonObject["messages"]!!.jsonArray.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "command"
            },
        )

        session.responseDelivered(retry)
        val afterAck = session.handlePageMessage(
            """{"type":"host-state","wantGeneration":-1}""", 1, null,
        )!!
        assertFalse(
            Json.parseToJsonElement(afterAck).jsonObject["messages"]!!.jsonArray.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "command"
            },
        )
    }

    @Test fun unknownCallsAreRefusedWithoutState() {
        val session = RuntimeHostSessionTest.Sink().let { session(it) }
        val reply = session.handlePageMessage(
            """{"type":"host-call","id":"c9","call":"invoke","payload":{}}""", 1, null,
        )!!
        val result = Json.parseToJsonElement(reply).jsonObject
        assertEquals(false, result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unsupported_call", result["code"]!!.jsonPrimitive.contentOrNull)
    }
}
