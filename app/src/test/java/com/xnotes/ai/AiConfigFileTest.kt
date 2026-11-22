package com.xnotes.ai

import com.xnotes.ui.applyAiConfigImport
import com.xnotes.ui.readBoundedUtf8
import com.xnotes.ui.validateThinkingLevelEntry
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * OpenCode-style layered AI config file: full settings JSON export (keys redacted by default),
 * Merge & Replace import preserving local keys and provider IDs, per-provider and per-model
 * thinking-level request field overrides, and strict validation.
 */
class AiConfigFileTest {
    private val user = listOf(AiMessage("user", "问题"))

    private val relayProvider = AiProvider(
        id = "relay-1",
        name = "My Relay",
        url = "https://relay.example.com/v1",
        key = "sk-relay-secret-123456",
        models = listOf("gpt-6.2-astra", "claude-opus-5-5", "plain-model"),
        thinkingFormat = AiThinking.OPENAI,
        imageModels = listOf("gpt-image-1"),
        thinkingLevels = linkedMapOf(
            "low" to """{"reasoning_effort":"low"}""",
            "high" to """{"reasoning_effort":"high"}""",
            "xhigh" to """{"reasoning_effort":"xhigh"}""",
        ),
        modelOverrides = linkedMapOf(
            "gpt-6.2-astra" to AiModelOverride(
                tokenField = "max_completion_tokens",
                thinkingLevels = linkedMapOf(
                    "medium" to """{"reasoning_effort":"medium"}""",
                    "xhigh" to """{"reasoning_effort":"xhigh"}""",
                ),
            ),
            "claude-opus-5-5" to AiModelOverride(
                thinkingLevels = linkedMapOf(
                    "off" to """{"thinking":{"type":"disabled"}}""",
                    "budget-16k" to """{"thinking":{"type":"enabled","budget_tokens":16000}}""",
                ),
            ),
            "plain-model" to AiModelOverride(
                thinkingLevels = emptyMap(),
            ),
        ),
    )

    private fun baseConfig() = AiConfig(
        searchKey = "sk-search-secret-987654",
        effort = "xhigh",
        maxTokens = 8192,
        instructions = "Concise answers",
        searchByDefault = true,
        attachPdfByDefault = true,
        providers = listOf(relayProvider),
        provider = "relay-1",
        imageProvider = "relay-1",
        imageModel = "gpt-image-1",
    ).route("relay-1", "gpt-6.2-astra")

    @Test fun exportRedactsKeysByDefaultAndIncludesFullAiSettings() {
        val cfg = baseConfig()
        val redacted = AiConfigFile.exportJson(cfg, includeKeys = false, includeEffectiveThinking = true)
        assertFalse("provider key must not leak in redacted export", redacted.contains("sk-relay-secret-123456"))
        assertFalse("search key must not leak in redacted export", redacted.contains("sk-search-secret-987654"))
        val json = JSONObject(redacted)
        assertEquals(AiConfigFile.VERSION, json.getInt("version"))
        assertEquals("relay-1", json.getString("defaultProvider"))
        assertEquals("gpt-6.2-astra", json.getString("defaultModel"))
        assertEquals("xhigh", json.getString("defaultEffort"))
        assertEquals(8192, json.getInt("maxTokens"))
        assertEquals("Concise answers", json.getString("instructions"))
        assertTrue(json.getBoolean("searchByDefault"))
        assertTrue(json.getBoolean("attachPdfByDefault"))
        assertEquals(AiConfigFile.REDACTED_KEY, json.getString("searchKey"))
        assertEquals("relay-1", json.getString("imageProvider"))
        assertEquals("gpt-image-1", json.getString("imageModel"))
        val p0 = json.getJSONArray("providers").getJSONObject(0)
        assertEquals(AiConfigFile.REDACTED_KEY, p0.getString("key"))
        assertTrue(p0.has("thinkingLevels"))
        assertTrue(p0.has("modelOverrides"))
        assertFalse("legacy top-level mirror url should stay out of user config JSON", json.has("url"))

        val withKeys = AiConfigFile.exportJson(cfg, includeKeys = true, includeEffectiveThinking = true)
        assertTrue(withKeys.contains("sk-relay-secret-123456"))
        assertTrue(withKeys.contains("sk-search-secret-987654"))
    }

    @Test fun replaceImportPreservesLocalKeysWhenRedactedAndRoundTripsOverrides() {
        val existing = baseConfig()
        val redacted = AiConfigFile.exportJson(existing, includeKeys = false, includeEffectiveThinking = true)
        val edited = JSONObject(redacted).put("maxTokens", 12000).toString(2)
        val parsed = AiConfigFile.parseAndApply(edited, existing, defaultInstructions = "Default", mode = AiConfigFile.ImportMode.REPLACE)
        assertEquals(12000, parsed.maxTokens)
        assertEquals("sk-relay-secret-123456", parsed.providerOf("relay-1")!!.key)
        assertEquals("sk-search-secret-987654", parsed.searchKey)
        assertEquals("max_completion_tokens", parsed.providerOf("relay-1")!!.modelOverrides["gpt-6.2-astra"]!!.tokenField)
        assertEquals(
            setOf("off", "budget-16k"),
            parsed.providerOf("relay-1")!!.modelOverrides["claude-opus-5-5"]!!.thinkingLevels!!.keys,
        )
        assertEquals(emptyMap<String, String>(), parsed.providerOf("relay-1")!!.modelOverrides["plain-model"]!!.thinkingLevels)
        // Explicit empty searchKey clears it rather than keeping the old one.
        val clearedSearch = JSONObject(redacted).put("searchKey", "").toString()
        val afterClear = AiConfigFile.parseAndApply(clearedSearch, existing, "Default", AiConfigFile.ImportMode.REPLACE)
        assertEquals("", afterClear.searchKey)
    }

    @Test fun mergeImportKeepsUnmentionedProvidersUpdatesMatchingIdsAndAssignsNewIds() {
        val existing = baseConfig()
        val incoming = """
            {
              "version": 1,
              "providers": [
                {
                  "id": "relay-1",
                  "name": "Relay Updated",
                  "url": "https://relay.example.com/v1",
                  "key": "",
                  "models": ["gpt-6.2-astra", "claude-opus-5-5"]
                },
                {
                  "name": "Second Provider",
                  "url": "https://api.openai.com/v1",
                  "key": "sk-openai-new-999999",
                  "models": ["gpt-6.2-sol"]
                }
              ]
            }
        """.trimIndent()
        val merged = AiConfigFile.parseAndApply(incoming, existing, "Default", AiConfigFile.ImportMode.MERGE)
        assertEquals(2, merged.providers.size)
        val updatedRelay = merged.providerOf("relay-1")!!
        assertEquals("Relay Updated", updatedRelay.name)
        assertEquals("sk-relay-secret-123456", updatedRelay.key)
        val added = merged.providers.first { it.id != "relay-1" }
        assertTrue(added.id.isNotBlank())
        assertEquals("Second Provider", added.name)
        assertEquals("sk-openai-new-999999", added.key)
        // Unmentioned top-level fields stay from existing in MERGE mode.
        assertEquals(8192, merged.maxTokens)
        assertEquals("sk-search-secret-987654", merged.searchKey)
    }

    @Test fun layeredThinkingLevelsAndTokenFieldApplyInPrecedenceOrder() {
        val cfg = baseConfig()
        // 1. Model override wins over provider levels and default tokenField.
        val gptBody = AiProtocol.request(cfg.route("relay-1", "gpt-6.2-astra").copy(effort = "xhigh"), user)
        assertTrue(gptBody.has("max_completion_tokens"))
        assertFalse(gptBody.has("max_tokens"))
        assertEquals("xhigh", gptBody.getString("reasoning_effort"))
        assertEquals(listOf("", "medium", "xhigh"), cfg.availableEfforts("relay-1", "gpt-6.2-astra"))

        // 2. Claude custom level name ("budget-16k") merges nested JSON object; default tokenField remains max_tokens.
        val claudeBody = AiProtocol.request(cfg.route("relay-1", "claude-opus-5-5").copy(effort = "budget-16k"), user)
        assertTrue(claudeBody.has("max_tokens"))
        assertFalse(claudeBody.has("max_completion_tokens"))
        assertEquals("enabled", claudeBody.getJSONObject("thinking").getString("type"))
        assertEquals(16000, claudeBody.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(listOf("", "off", "budget-16k"), cfg.availableEfforts("relay-1", "claude-opus-5-5"))

        // 3. Model with empty thinkingLevels map exposes only "" (default) and sends no thinking field even if effort was set.
        assertEquals(listOf(""), cfg.availableEfforts("relay-1", "plain-model"))
        val plainBody = AiProtocol.request(cfg.route("relay-1", "plain-model").copy(effort = "high"), user)
        assertFalse(plainBody.has("reasoning_effort"))
        assertFalse(plainBody.has("thinking"))

        // 4. Model without override inherits provider-level thinkingLevels.
        val inheritedCfg = cfg.copy(
            providers = listOf(relayProvider.copy(models = relayProvider.models + "other-model")),
        )
        assertEquals(listOf("", "low", "high", "xhigh"), inheritedCfg.availableEfforts("relay-1", "other-model"))
        val otherBody = AiProtocol.request(inheritedCfg.route("relay-1", "other-model").copy(effort = "low"), user)
        assertEquals("low", otherBody.getString("reasoning_effort"))
        // Effort not in provider's custom map sends nothing.
        val offBody = AiProtocol.request(inheritedCfg.route("relay-1", "other-model").copy(effort = AiThinking.OFF), user)
        assertFalse(offBody.has("reasoning_effort"))
    }

    @Test fun storeEncodeDecodeRoundTripsCustomThinkingLevelsAndModelOverrides() {
        val cfg = baseConfig()
        val encoded = AiConfigStore.encode(cfg)
        val decoded = AiConfigStore.decode(encoded, "Default")
        val p = decoded.providerOf("relay-1")!!
        assertEquals(relayProvider.thinkingLevels, p.thinkingLevels)
        assertEquals(relayProvider.modelOverrides, p.modelOverrides)
        // A provider with null thinkingLevels stays null (not turned into emptyMap).
        val bare = AiConfig(
            providers = listOf(AiProvider("p1", "Bare", "https://api.openai.com/v1", "sk-12345678", listOf("gpt-6.2"))),
            provider = "p1",
        ).route("p1", "gpt-6.2")
        val bareDecoded = AiConfigStore.decode(AiConfigStore.encode(bare), "Default")
        assertNull(bareDecoded.providerOf("p1")!!.thinkingLevels)
        assertTrue(bareDecoded.providerOf("p1")!!.modelOverrides.isEmpty())
    }

    @Test fun validationRejectsBrokenJsonReservedKeysBadVersionAndInvalidTokenField() {
        val existing = baseConfig()
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigFile.parseAndApply("{not json", existing, "Default")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigFile.parseAndApply("""{"version": 99, "providers": []}""", existing, "Default")
        }
        // Reserved protocol keys inside a custom thinking payload must be refused.
        for (reserved in listOf("model", "messages", "stream", "tools", "tool_choice")) {
            assertThrows("reserved key $reserved must be rejected", IllegalArgumentException::class.java) {
                AiThinking.validatePayload("high", """{"$reserved": "evil"}""")
            }
        }
        // Non-object payload (array or primitive) must be rejected.
        assertThrows(IllegalArgumentException::class.java) {
            AiThinking.validatePayload("high", """["high"]""")
        }
        // Invalid tokenField must be rejected by validateProvider.
        val badToken = relayProvider.copy(modelOverrides = mapOf("gpt-6.2-astra" to AiModelOverride(tokenField = "max_out")))
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigStore.validateProvider(badToken)
        }
        // Too many custom thinking levels must be rejected.
        val tooMany = (0..AiThinking.MAX_CUSTOM_LEVELS).associate { "lvl$it" to """{"reasoning_effort":"high"}""" }
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigStore.validateProvider(relayProvider.copy(thinkingLevels = tooMany))
        }
    }

    @Test fun crossHostImportWithRedactedKeyDoesNotLeakSavedKeyWhileSameHostPathChangePreservesIt() {
        val existing = baseConfig()
        // Same provider id, different host, redacted key -> must NOT copy relay-1's key to the new host.
        val crossHost = """
            {
              "version": 1,
              "providers": [
                {
                  "id": "relay-1",
                  "name": "Hijacked Relay",
                  "url": "https://other-host.example.com/v1",
                  "key": "${AiConfigFile.REDACTED_KEY}",
                  "models": ["gpt-6.2-astra"]
                }
              ]
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigFile.parseAndApply(crossHost, existing, "Default", AiConfigFile.ImportMode.MERGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigFile.parseAndApply(crossHost, existing, "Default", AiConfigFile.ImportMode.REPLACE)
        }

        // Same host with an updated path preserves the existing key.
        val sameHostPathChange = """
            {
              "version": 1,
              "providers": [
                {
                  "id": "relay-1",
                  "name": "Relay v2",
                  "url": "https://relay.example.com/v2",
                  "key": "${AiConfigFile.REDACTED_KEY}",
                  "models": ["gpt-6.2-astra"]
                }
              ]
            }
        """.trimIndent()
        val updated = AiConfigFile.parseAndApply(sameHostPathChange, existing, "Default", AiConfigFile.ImportMode.MERGE)
        assertEquals("sk-relay-secret-123456", updated.providerOf("relay-1")!!.key)
        assertEquals("https://relay.example.com/v2", updated.providerOf("relay-1")!!.url)
    }

    @Test fun editingUnrelatedJsonFieldsDoesNotFreezeInheritedDefaultThinkingLevels() {
        val bare = AiConfig(
            providers = listOf(
                AiProvider(
                    id = "p1",
                    name = "OpenAI",
                    url = "https://api.openai.com/v1",
                    key = "sk-openai-12345678",
                    models = listOf("gpt-6.2"),
                    thinkingFormat = AiThinking.OPENAI,
                    thinkingLevels = null,
                ),
            ),
            provider = "p1",
            maxTokens = 4096,
        ).route("p1", "gpt-6.2")

        val exported = AiConfigFile.exportJson(bare, includeKeys = false, includeEffectiveThinking = true)
        assertTrue("exported JSON should display expanded levels", JSONObject(exported).getJSONArray("providers").getJSONObject(0).has("thinkingLevels"))

        // Edit only maxTokens and re-apply: provider should still keep thinkingLevels == null so future format switches propagate.
        val editedTokens = JSONObject(exported).put("maxTokens", 8192).toString(2)
        val reapplied = AiConfigFile.parseAndApply(editedTokens, bare, "Default", AiConfigFile.ImportMode.REPLACE)
        assertEquals(8192, reapplied.maxTokens)
        assertNull("untouched expanded default thinkingLevels must stay null", reapplied.providerOf("p1")!!.thinkingLevels)

        // Modifying a level inside thinkingLevels DOES materialize explicit thinkingLevels.
        val editedLevels = JSONObject(exported).apply {
            getJSONArray("providers").getJSONObject(0).getJSONObject("thinkingLevels")
                .put("custom", JSONObject("""{"reasoning_effort":"high"}"""))
        }.toString(2)
        val customized = AiConfigFile.parseAndApply(editedLevels, bare, "Default", AiConfigFile.ImportMode.REPLACE)
        assertNotNull(customized.providerOf("p1")!!.thinkingLevels)
        assertTrue("custom" in customized.providerOf("p1")!!.thinkingLevels!!)
    }

    @Test fun boundedReaderStopsAtCapAndMergeImportPreservesUnsavedJsonEdits() {
        assertEquals("hello", readBoundedUtf8(ByteArrayInputStream("hello".toByteArray()), maxBytes = 5))
        assertNull(readBoundedUtf8(ByteArrayInputStream("hello!".toByteArray()), maxBytes = 5))

        val existing = baseConfig()
        val rendered = AiConfigFile.exportJson(existing, includeKeys = false, includeEffectiveThinking = true)
        // User edits maxTokens in the JSON text without saving yet, then merges another provider file.
        val unsavedJson = JSONObject(rendered).put("maxTokens", 16000).toString(2)
        val incoming = """
            {
              "version": 1,
              "providers": [
                {
                  "name": "Extra",
                  "url": "https://api.deepseek.com/v1",
                  "key": "sk-ds-new-999999",
                  "models": ["deepseek-reasoner"]
                }
              ]
            }
        """.trimIndent()
        val merged = applyAiConfigImport(incoming, unsavedJson, rendered, existing, "Default", AiConfigFile.ImportMode.MERGE)
        assertEquals("unsaved maxTokens edit must survive merge import", 16000, merged.maxTokens)
        assertEquals(2, merged.providers.size)

        // Invalid unsaved JSON must abort merge import rather than silently discarding user edits.
        assertThrows(IllegalArgumentException::class.java) {
            applyAiConfigImport(incoming, "{broken", rendered, existing, "Default", AiConfigFile.ImportMode.MERGE)
        }
    }

    @Test fun thinkingLevelEditorRejectsDuplicateNamesOnAddAndRename() {
        val existingKeys = setOf("low", "high")
        val dupAdd = validateThinkingLevelEntry("high", """{"reasoning_effort":"high"}""", existingKeys, editingKey = null, duplicateError = "dup")
        assertTrue(dupAdd.isFailure)
        assertEquals("dup", dupAdd.exceptionOrNull()!!.message)

        val dupRename = validateThinkingLevelEntry("high", """{"reasoning_effort":"high"}""", existingKeys, editingKey = "low", duplicateError = "dup")
        assertTrue(dupRename.isFailure)
        assertEquals("dup", dupRename.exceptionOrNull()!!.message)

        // Updating an existing level under its own name succeeds.
        val selfEdit = validateThinkingLevelEntry("low", """{"reasoning_effort":"minimal"}""", existingKeys, editingKey = "low", duplicateError = "dup")
        assertTrue(selfEdit.isSuccess)
        assertEquals("low", selfEdit.getOrThrow().first)

        // Renaming to a fresh name succeeds.
        val freshRename = validateThinkingLevelEntry("ultra", """{"reasoning_effort":"xhigh"}""", existingKeys, editingKey = "low", duplicateError = "dup")
        assertTrue(freshRename.isSuccess)
        assertEquals("ultra", freshRename.getOrThrow().first)
    }
}
