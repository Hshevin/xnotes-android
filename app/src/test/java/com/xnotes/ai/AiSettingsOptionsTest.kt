package com.xnotes.ai

import java.nio.file.Files
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Model choice, thinking depth, output length and the user's own prompt, end to end below the UI. */
class AiSettingsOptionsTest {
    private val user = listOf(AiMessage("user", "问题"))

    @Test fun defaultsSendNoThinkingFieldAndNoUserPrompt() {
        val body = AiProtocol.request(AiConfig("https://example.com/v1", "m", "k"), user)
        assertEquals(4096, body.getInt("max_tokens"))
        listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking").forEach { assertFalse(it, body.has(it)) }
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        // The legacy overload still builds the same body.
        assertEquals(body.toString(), AiProtocol.request("m", user).toString())
    }

    @Test fun thinkingFieldFollowsFormatAndHost() {
        fun body(url: String, effort: String, format: String = AiThinking.AUTO) = AiProtocol.request(AiConfig(url, "m", "k", effort = effort, thinkingFormat = format), user)
        assertEquals("minimal", body("https://api.openai.com/v1", "minimal").getString("reasoning_effort"))
        assertEquals("high", body("https://api.openai.com/v1", "high").getString("reasoning_effort"))
        assertEquals("xhigh", body("https://api.openai.com/v1", "xhigh").getString("reasoning_effort"))
        assertEquals("none", body("https://api.openai.com/v1", "off").getString("reasoning_effort"))
        assertEquals("enabled", body("https://api.deepseek.com", AiThinking.ON).getJSONObject("thinking").getString("type"))
        assertEquals("enabled", body("https://api.deepseek.com", "low").getJSONObject("thinking").getString("type"))
        assertEquals("enabled", body("https://api.deepseek.com", "xhigh").getJSONObject("thinking").getString("type"))
        assertEquals("disabled", body("https://api.deepseek.com", "off").getJSONObject("thinking").getString("type"))
        assertFalse(body("https://api.deepseek.com", "high").has("reasoning_effort"))
        assertEquals("minimal", body("https://openrouter.ai/api/v1", "minimal").getJSONObject("reasoning").getString("effort"))
        assertEquals("medium", body("https://openrouter.ai/api/v1", "medium").getJSONObject("reasoning").getString("effort"))
        assertEquals("xhigh", body("https://openrouter.ai/api/v1", "xhigh").getJSONObject("reasoning").getString("effort"))
        assertFalse(body("https://openrouter.ai/api/v1", "off").getJSONObject("reasoning").getBoolean("enabled"))
        assertTrue(body("https://dashscope.aliyuncs.com/compatible-mode/v1", AiThinking.ON).getBoolean("enable_thinking"))
        assertFalse(body("https://dashscope.aliyuncs.com/compatible-mode/v1", "off").getBoolean("enable_thinking"))
        assertTrue(body("https://dashscope.aliyuncs.com/compatible-mode/v1", "high").getBoolean("enable_thinking"))
        assertTrue(body("https://dashscope.aliyuncs.com/compatible-mode/v1", "minimal").getBoolean("enable_thinking"))
        assertFalse("OpenAI must not emit invalid reasoning_effort='on'", body("https://api.openai.com/v1", AiThinking.ON).has("reasoning_effort"))
        // An explicit format overrides the host guess; a look-alike host is not DeepSeek.
        assertEquals("low", body("https://api.deepseek.com", "low", AiThinking.OPENAI).getString("reasoning_effort"))
        assertEquals("high", body("https://deepseek.com.example.net/v1", "high").getString("reasoning_effort"))
    }

    @Test fun toggleOnlyProvidersExposeOnlyDefaultOffAndOnWhileUpgradingLegacyEfforts() {
        assertEquals(listOf(AiThinking.OFF, AiThinking.ON), AiThinking.defaultLevels(AiThinking.AUTO, "https://api.deepseek.com/v1").keys.toList())
        assertEquals(listOf(AiThinking.OFF, AiThinking.ON), AiThinking.defaultLevels(AiThinking.DEEPSEEK, "https://relay.example.com/v1").keys.toList())
        assertEquals(listOf(AiThinking.OFF, AiThinking.ON), AiThinking.defaultLevels(AiThinking.AUTO, "https://dashscope.aliyuncs.com/compatible-mode/v1").keys.toList())
        assertEquals(listOf(AiThinking.OFF, AiThinking.ON), AiThinking.defaultLevels(AiThinking.QWEN, "https://relay.example.com/v1").keys.toList())
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh"), AiThinking.defaultLevels(AiThinking.AUTO, "https://api.openai.com/v1").keys.toList())

        val ds = AiProvider("ds", "DeepSeek", "https://api.deepseek.com/v1", "sk-ds-12345678", listOf("deepseek-reasoner"))
        val oa = AiProvider("oa", "OpenAI", "https://api.openai.com/v1", "sk-oa-12345678", listOf("gpt-6.2"))
        val cfg = AiConfig(providers = listOf(ds, oa), provider = "ds", effort = "high").withValidDefault()
        assertEquals(listOf("", AiThinking.OFF, AiThinking.ON), cfg.availableEfforts("ds", "deepseek-reasoner"))
        assertEquals(AiThinking.LEVELS, cfg.availableEfforts("oa", "gpt-6.2"))
        // Legacy graded effort "high" normalizes to "on" for toggle-only DeepSeek.
        assertEquals(AiThinking.ON, cfg.effort)
        AiConfigStore.validate(cfg)

        // Switching a chat with "high" onto DeepSeek normalizes to "on"; switching a chat with "on" onto OpenAI normalizes to "".
        assertEquals(AiThinking.ON, AiChat(provider = "ds", model = "deepseek-reasoner", effort = "high").settings(cfg).effort)
        assertEquals("", AiChat(provider = "oa", model = "gpt-6.2", effort = AiThinking.ON).settings(cfg).effort)
        assertEquals(AiThinking.OFF, AiChat(provider = "oa", model = "gpt-6.2", effort = AiThinking.OFF).settings(cfg).effort)

        // Decoding a legacy config that saved effort="high" on DeepSeek upgrades cleanly to "on".
        val legacyJson = AiConfigStore.encode(cfg.copy(effort = "high"))
        assertEquals(AiThinking.ON, AiConfigStore.decode(legacyJson, "Default").effort)
    }

    @Test fun instructionsLeadAndKeepSearchGroundingRules() {
        val config = AiConfig("https://example.com/v1", "m", "k", instructions = "  用中文回答  ", maxTokens = 16000)
        val body = AiProtocol.request(config, listOf(AiMessage("user", "问题", searchContext = "资料")))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("用中文回答", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("外部不可信数据"))
        // With tools offered, the user's prompt still leads and the app's tool guidance follows it.
        val withTools = AiProtocol.request(config, listOf(AiMessage("user", "问题")), tools = AiWebTools.definitions()).getJSONArray("messages")
        assertEquals("用中文回答", withTools.getJSONObject(0).getString("content"))
        assertTrue(withTools.getJSONObject(1).getString("content").startsWith(AiWebTools.GUIDANCE))
        assertEquals(16000, body.getInt("max_tokens"))
        assertEquals("", config.forTools().instructions); assertEquals("", config.copy(effort = "high").forTools().effort)
    }

    @Test fun modelListParsingAndEndpoint() {
        assertEquals("https://example.com/api/v1/models", AiProtocol.modelsEndpoint("https://example.com/api/v1/"))
        assertEquals("https://example.com/v1/models", AiProtocol.modelsEndpoint("https://example.com/v1/chat/completions"))
        assertThrows(IllegalArgumentException::class.java) { AiProtocol.modelsEndpoint("http://example.com/v1") }
        assertEquals(listOf("a-model", "B-model"), AiProtocol.models("""{"data":[{"id":"B-model"},{"id":"a-model"},{"id":"a-model"},{"id":null},{"id":42},{}]}"""))
        assertThrows(IllegalStateException::class.java) { AiProtocol.models("""{"error":"nope"}""") }
    }

    @Test fun listModelsSendsKeyOnlyToModelsEndpoint() {
        var opened = ""
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://example.com/v1/models")) {
            var auth: String? = null
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = """{"data":[{"id":"vision"}]}""".byteInputStream()
            override fun setRequestProperty(key: String, value: String) { if (key == "Authorization") auth = value }
        }
        val models = AiConnection { opened = it; connection }.listModels("https://example.com/v1", "secret")
        assertEquals("https://example.com/v1/models", opened)
        assertEquals("Bearer secret", connection.auth)
        assertEquals("GET", connection.requestMethod)
        assertEquals(listOf("vision"), models)
    }

    @Test fun modelChoicesKeepOrderAndAlwaysHoldDefault() {
        assertEquals(listOf("a", "b"), AiConfig(model = "b", models = listOf("a", "b")).modelChoices)
        assertEquals(listOf("c", "a"), AiConfig(model = "c", models = listOf("a", " ", "a")).modelChoices)
        assertEquals(emptyList<String>(), AiConfig().modelChoices)
    }

    @Test fun chatPicksOverrideDefaultsAndRoundTrip() {
        val config = AiConfig("https://example.com/v1", "default", "k", effort = "low")
        assertEquals("default" to "low", AiChat().settings(config).let { it.model to it.effort })
        assertEquals("other" to "", AiChat(model = "other", effort = "").settings(config).let { it.model to it.effort })
        val dir = Files.createTempDirectory("ai-history").toFile()
        try {
            val store = AiHistoryStore(dir)
            val chat = AiChat(model = "other", effort = "high")
            store.write(chat); assertEquals(chat, store.readAll().first.single())
            for (extra in listOf("minimal", "xhigh")) {
                val extraChat = chat.copy(effort = extra)
                store.write(extraChat); assertEquals(extraChat, store.readAll().first.single())
            }
            // Records written before this field existed still load and follow the defaults.
            val legacy = java.io.File(dir, "${chat.id}.json")
            legacy.writeText(JSONObject(legacy.readText()).apply { remove("model"); remove("effort") }.toString())
            assertEquals(chat.copy(model = "", effort = null), store.readAll().first.single())
            // An illegal stored level (control char / newline) is dropped on read, and an unconfigured level is ignored rather than sent.
            legacy.writeText(JSONObject(legacy.readText()).put("effort", "bad\nlevel").toString())
            assertNull(store.readAll().first.single().effort)
            legacy.writeText(JSONObject(legacy.readText()).put("effort", "ultra").toString())
            val loadedUnknown = store.readAll().first.single()
            assertEquals("", loadedUnknown.settings(config).effort)
        } finally { dir.deleteRecursively() }
    }

    @Test fun sendUsesChatModelAndItsSettingsForTheAnswer() = runBlocking {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            val seen = mutableListOf<AiConfig>()
            val fake = object : AiTransport {
                override fun cancel() {}
                override fun search(key: String, query: String, question: String): List<AiWebSource> = emptyList()
                override fun fetch(key: String, url: String) = ""
                override suspend fun stream(config: AiConfig, messages: List<AiMessage>, delta: suspend (String) -> Unit) = error("not used")
                override suspend fun complete(config: AiConfig, messages: List<AiMessage>, tail: List<org.json.JSONObject>, tools: org.json.JSONArray?, toolChoice: String, delta: suspend (String) -> Unit): AiReply {
                    seen.add(config); delta("回答"); return AiReply("回答")
                }
            }
            val defaults = AiConfig("https://example.com/v1", "vision", "key", "search-key", effort = "low", instructions = "用中文")
            val sessions = AiSessions(AiHistoryStore(dir), scope, { defaults }, { fake })
            until { sessions.ready }
            val id = sessions.newChat()
            sessions.choose(id, model = "reasoner", effort = "high")
            sessions.choose(id, effort = "bogus") // ignored
            sessions.send(id, AiMessage("user", "这篇论文", listOf("YWJj")), true)
            until { !sessions.isRunning(id) }; sessions.flush()
            // Searching is part of the answering request now, so it carries the chat's model, thinking and prompt.
            assertEquals(1, seen.size)
            assertEquals("reasoner", seen.single().model)
            assertEquals("high" to "用中文", seen.single().effort to seen.single().instructions)
            assertEquals("reasoner", AiHistoryStore(dir).readAll().first.single().model)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }

    @Test fun saveRefusesMoreModelsThanTheLimitInsteadOfTrimming() {
        val base = AiConfig("https://example.com/v1", "m0", "k")
        AiConfigStore.validate(base.copy(models = (0 until AiProtocol.MAX_MODELS).map { "m$it" }))
        val error = assertThrows(IllegalArgumentException::class.java) {
            AiConfigStore.validate(base.copy(models = (0..AiProtocol.MAX_MODELS).map { "m$it" }))
        }
        assertTrue(error.message!!.contains(AiProtocol.MAX_MODELS.toString()))
    }

    @Test fun defaultInstructionsFollowUiLanguageAndFitTheLimit() {
        assertEquals(AiDefaults.INSTRUCTIONS_ZH, AiDefaults.instructions("zh-Hans-CN"))
        assertEquals(AiDefaults.INSTRUCTIONS_ZH, AiDefaults.instructions("zh-CN"))
        assertEquals(AiDefaults.INSTRUCTIONS_EN, AiDefaults.instructions("en-GB"))
        assertEquals(AiDefaults.INSTRUCTIONS_EN, AiDefaults.instructions("ja"))
        listOf(AiDefaults.INSTRUCTIONS_ZH, AiDefaults.INSTRUCTIONS_EN).forEach {
            assertTrue(it.length <= AiProtocol.MAX_INSTRUCTIONS)
            AiConfigStore.validate(AiConfig("https://example.com/v1", "m", "k", instructions = it))
        }
    }

    @Test fun newChatStartsWithConfiguredDefaults() = runBlocking {
        val dir = Files.createTempDirectory("sessions").toFile()
        val job = SupervisorJob(); val scope = CoroutineScope(coroutineContext + job)
        try {
            var defaults = AiConfig("https://example.com/v1", "m", "k", searchByDefault = true, attachPdfByDefault = true)
            val imported = mutableListOf<String>()
            val sessions = AiSessions(AiHistoryStore(dir), scope, { defaults }, preparePdf = { uri, name ->
                imported.add(uri); AiPdf(name ?: "a.pdf", pageCount = 1)
            })
            until { sessions.ready }
            val id = sessions.newChat(floatingOwner = "doc")
            assertEquals(true, sessions.searchEnabled[id])
            assertTrue(sessions.chats[id]!!.draft.search)
            sessions.offerCurrentPdf(id, "file:///a.pdf", "a.pdf")
            until { sessions.pdfAttachments[id] != null }
            assertEquals(listOf("file:///a.pdf"), imported)
            // A new chat from the same floating window carries the PDF on and attaches it again.
            sessions.chats[id] = sessions.chats.getValue(id).copy(turns = listOf(AiTurn(AiMessage("user", "question"), "answer", "已完成")))
            val next = sessions.newChat(floatingOwner = "doc")
            until { sessions.pdfAttachments[next] != null }
            assertEquals(2, imported.size)
            sessions.flush()
            assertTrue(AiHistoryStore(dir).readAll().first.first { it.id == id }.draft.search)
            // With the defaults off, the PDF is only offered and search starts off.
            sessions.chats[next] = sessions.chats.getValue(next).copy(turns = listOf(AiTurn(AiMessage("user", "question"), "answer", "已完成")))
            defaults = defaults.copy(searchByDefault = false, attachPdfByDefault = false)
            val plain = sessions.newChat(floatingOwner = "doc")
            assertNotEquals(true, sessions.searchEnabled[plain])
            assertEquals("file:///a.pdf" to "a.pdf", sessions.currentPdfs[plain])
            assertNull(sessions.importStates[plain]); assertNull(sessions.pdfAttachments[plain])
            assertEquals(2, imported.size)
        } finally { job.cancelAndJoin(); dir.deleteRecursively() }
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
}
