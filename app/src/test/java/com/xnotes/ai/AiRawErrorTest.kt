package com.xnotes.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Full raw error diagnostics: failed turns keep a concise one-line error summary while storing
 * a masked, bounded raw response + request summary that expands on tap and persists across reloads.
 */
class AiRawErrorTest {
    private val user = listOf(AiMessage("user", "解释这段"))
    private val apiKey = "sk-live-secret-key-0123456789"
    private val searchKey = "sk-search-secret-9876543210"

    private class Fake(val status: Int, val body: String) : HttpURLConnection(URL("https://relay.example.com/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun disconnect() {}
        override fun connect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun formatRawErrorPrettyPrintsJsonMasksAllKeysAndBoundsLength() {
        val raw = """{"error":{"message":"Rejected key $apiKey and $searchKey","param":"reasoning_effort","code":"unsupported_value"}}"""
        val formatted = AiProtocol.formatRawError(
            status = 400,
            endpoint = "https://relay.example.com/v1/chat/completions",
            model = "gpt-6.2-astra",
            thinkingPayload = org.json.JSONObject("""{"reasoning_effort":"none"}"""),
            hasTools = true,
            rawBody = raw,
            keysToMask = listOf(apiKey, searchKey),
        )
        assertTrue(formatted.contains("HTTP 400"))
        assertTrue(formatted.contains("gpt-6.2-astra"))
        assertTrue(formatted.contains("reasoning_effort"))
        assertTrue(formatted.contains("\"code\": \"unsupported_value\""))
        assertFalse(formatted.contains(apiKey))
        assertFalse(formatted.contains(searchKey))
        assertTrue(formatted.contains("***"))

        val huge = AiProtocol.formatRawError(
            status = 502,
            endpoint = "https://relay.example.com/v1/chat/completions",
            model = "m",
            thinkingPayload = null,
            hasTools = false,
            rawBody = "<html>" + "x".repeat(AiProtocol.RAW_ERROR_MAX * 2) + "</html>",
        )
        assertTrue(huge.length <= AiProtocol.RAW_ERROR_MAX)
        assertTrue(huge.endsWith("…"))
    }

    @Test fun failedRequestPersistsBothSummaryAndRawErrorInTurn() = runBlocking {
        val dir = Files.createTempDirectory("ai-raw-error").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        try {
            val errorJson = """{"error":{"message":"Quota exceeded for $apiKey","type":"rate_limit_error","code":"insufficient_quota"}}"""
            val config = AiConfig("https://relay.example.com/v1", "gpt-6.2-astra", apiKey, searchKey)
            val sessions = AiSessions(
                AiHistoryStore(dir),
                scope,
                { config },
                { AiConnection { Fake(429, errorJson) } },
            )
            withTimeout(5000) { while (!sessions.ready) delay(5) }
            val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "hello"), false)
            withTimeout(5000) { while (sessions.isRunning(id)) delay(5) }
            sessions.flush()

            val turn = sessions.chats.getValue(id).turns.single()
            assertEquals("生成失败", turn.state)
            assertTrue("summary line should stay concise", turn.error.contains("429") && turn.error.contains("Quota exceeded"))
            assertFalse("summary must mask key", turn.error.contains(apiKey))
            assertTrue("rawError should include full JSON code field", turn.rawError.contains("insufficient_quota"))
            assertTrue("rawError should include request metadata", turn.rawError.contains("gpt-6.2-astra"))
            assertFalse("rawError must mask key", turn.rawError.contains(apiKey))

            val reloaded = AiHistoryStore(dir).readAll().first.single().turns.single()
            assertEquals(turn.rawError, reloaded.rawError)
            assertEquals(turn.error, reloaded.error)
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
        }
    }

    @Test fun thinkingFallbackRecordsInitialRawRefusalOnSuccessfulTurn() = runBlocking {
        val dir = Files.createTempDirectory("ai-fallback-raw").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        try {
            val refusal = """{"error":{"message":"Unsupported value: 'none' is not supported with this model.","param":"reasoning_effort","code":"unsupported_value"}}"""
            val okStream = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}\n\ndata: [DONE]\n"
            val queue = ArrayDeque(listOf(Fake(400, refusal), Fake(200, okStream)))
            val config = AiConfig("https://relay.example.com/v1", "gpt-6.2-fallback-raw", apiKey, effort = AiThinking.OFF)
            val sessions = AiSessions(
                AiHistoryStore(dir),
                scope,
                { config },
                { AiConnection { queue.removeFirst() } },
            )
            withTimeout(5000) { while (!sessions.ready) delay(5) }
            val id = sessions.newChat()
            sessions.send(id, AiMessage("user", "hello"), false)
            withTimeout(5000) { while (sessions.isRunning(id)) delay(5) }
            sessions.flush()

            val turn = sessions.chats.getValue(id).turns.single()
            assertEquals("已完成", turn.state)
            assertEquals("完成", turn.answer)
            assertTrue(turn.error.contains("默认"))
            assertTrue("fallback turn should keep original refusal JSON for inspection", turn.rawError.contains("unsupported_value"))
            assertTrue(turn.rawError.contains("reasoning_effort"))
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
        }
    }

    @Test fun customThinkingFieldRefusalTriggersFallback() = runBlocking {
        val refusal = """{"error":{"message":"Unknown parameter: 'thinking_budget'","param":"thinking_budget"}}"""
        val okStream = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]\n"
        val queue = ArrayDeque(listOf(Fake(400, refusal), Fake(200, okStream)))
        val used = mutableListOf<Fake>()
        val client = AiConnection { queue.removeFirst().also { used += it } }
        val p = AiProvider(
            id = "custom-p",
            name = "Custom",
            url = "https://relay.example.com/v1",
            key = apiKey,
            models = listOf("custom-m-1"),
            thinkingLevels = mapOf("deep" to """{"thinking_budget": 12000}"""),
        )
        val cfg = AiConfig(providers = listOf(p), provider = "custom-p", effort = "deep").route("custom-p", "custom-m-1")
        val reply = client.complete(cfg, user, emptyList(), null, "auto") {}
        assertEquals("好", reply.content)
        assertTrue(reply.thinkingDropped)
        assertTrue(reply.thinkingFallbackDetail.contains("thinking_budget"))
        assertTrue(org.json.JSONObject(used[0].sent.toString()).has("thinking_budget"))
        assertFalse(org.json.JSONObject(used[1].sent.toString()).has("thinking_budget"))
    }
}
