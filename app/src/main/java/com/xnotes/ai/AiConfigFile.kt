package com.xnotes.ai

import com.xnotes.format.JsonPull
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Both Android and the JVM's JSONObject accept non-JSON syntax. Check the whole document first. */
internal fun strictJsonContainer(text: String): Any {
    val parser = JsonPull(text.reader(), strict = true)
    val type = parser.peek()
    require(type == JsonPull.Token.BEGIN_OBJECT || type == JsonPull.Token.BEGIN_ARRAY) { "Expected JSON object or array" }
    parser.skipValue()
    require(parser.peek() == JsonPull.Token.END_DOCUMENT)
    return if (type == JsonPull.Token.BEGIN_OBJECT) JSONObject(text) else JSONArray(text)
}

object AiConfigFile {
    const val VERSION = 1
    const val REDACTED_KEY = "***REDACTED***"

    enum class ImportMode { MERGE, REPLACE }

    private fun invalid(field: String): Nothing =
        throw IllegalArgumentException(AiText.t(AiMsg.CONFIG_JSON_INVALID, field))

    private fun JSONObject.string(name: String, fallback: String = ""): String =
        if (has(name)) opt(name) as? String ?: invalid(name) else fallback

    private fun JSONObject.integer(name: String, fallback: Int): Int {
        if (!has(name)) return fallback
        val value = opt(name) as? Number ?: invalid(name)
        return value.toString().toIntOrNull() ?: invalid(name)
    }

    private fun JSONObject.bool(name: String, fallback: Boolean): Boolean =
        if (has(name)) opt(name) as? Boolean ?: invalid(name) else fallback

    private fun JSONObject.strings(name: String, fallback: List<String>): List<String> {
        if (!has(name)) return fallback
        val values = optJSONArray(name) ?: invalid(name)
        return (0 until values.length()).map { values.opt(it) as? String ?: invalid(name) }
    }

    private fun readLevels(json: JSONObject, fallback: Map<String, String>?): Map<String, String>? {
        if (!json.has("thinkingLevels")) return fallback
        if (json.isNull("thinkingLevels")) return null
        val levels = json.optJSONObject("thinkingLevels") ?: invalid("thinkingLevels")
        return levels.keys().asSequence().associateWith { level ->
            when (val value = levels.get(level)) {
                is String -> value // Stored payloads keep the exact text entered in the editor.
                is JSONObject -> value.toString()
                else -> throw IllegalArgumentException(AiText.t(AiMsg.CONFIG_THINKING_PAYLOAD_INVALID, level, "JSONObject"))
            }
        }
    }

    private fun writeLevels(levels: Map<String, String>, forStorage: Boolean) = JSONObject().apply {
        for ((level, payload) in levels) put(level, if (forStorage) payload else AiThinking.validatePayload(level, payload))
    }

    internal fun providerJson(p: AiProvider, forStorage: Boolean): JSONObject {
        val json = JSONObject().put("id", p.id).put("name", p.name.trim()).put("url", p.url.trim()).put("key", p.key.trim())
            .put("models", JSONArray(p.modelChoices)).put("thinkingFormat", p.thinkingFormat).put("imageModels", JSONArray(p.imageChoices))
        p.thinkingLevels?.let { json.put("thinkingLevels", writeLevels(it, forStorage)) }
        val overrides = JSONObject()
        for ((model, override) in p.modelOverrides) {
            val entry = JSONObject().put("tokenField", override.tokenField)
            override.thinkingLevels?.let { entry.put("thinkingLevels", writeLevels(it, forStorage)) }
            overrides.put(model, entry)
        }
        return json.put("modelOverrides", overrides)
    }

    internal fun readProvider(json: JSONObject, id: String, previous: AiProvider? = null): AiProvider {
        val overrides = if (json.has("modelOverrides")) {
            val entries = json.optJSONObject("modelOverrides") ?: invalid("modelOverrides")
            entries.keys().asSequence().associateWith { model ->
                val entry = entries.optJSONObject(model) ?: invalid("modelOverrides")
                AiModelOverride(entry.string("tokenField"), readLevels(entry, null))
            }
        } else previous?.modelOverrides.orEmpty()
        return AiProvider(
            id = id, name = json.string("name", previous?.name.orEmpty()), url = json.string("url", previous?.url.orEmpty()),
            key = json.string("key"), models = json.strings("models", previous?.models.orEmpty()),
            thinkingFormat = json.string("thinkingFormat", previous?.thinkingFormat ?: AiThinking.AUTO),
            imageModels = json.strings("imageModels", previous?.imageModels.orEmpty()),
            thinkingLevels = readLevels(json, previous?.thinkingLevels), modelOverrides = overrides,
        )
    }

    fun exportJson(config: AiConfig, includeKeys: Boolean = false, includeEffectiveThinking: Boolean = true): String {
        fun key(value: String) = if (includeKeys || value.isEmpty()) value else REDACTED_KEY
        val providers = AiConfigStore.providersOf(config)
        val list = JSONArray()
        for (p in providers) {
            val levels = p.thinkingLevels ?: if (includeEffectiveThinking) AiThinking.defaultLevels(p.thinkingFormat, p.url) else null
            list.put(providerJson(p.copy(key = key(p.key), thinkingLevels = levels), forStorage = false))
        }
        return JSONObject().put("version", VERSION)
            .put("defaultProvider", if (config.providers.isEmpty()) providers.firstOrNull()?.id.orEmpty() else config.provider)
            .put("defaultModel", config.model).put("defaultEffort", config.effort)
            .put("maxTokens", config.maxTokens).put("instructions", config.instructions)
            .put("searchByDefault", config.searchByDefault).put("attachPdfByDefault", config.attachPdfByDefault)
            .put("searchKey", key(config.searchKey)).put("imageProvider", config.imageProvider)
            .put("imageModel", config.imageModel).put("providers", list).toString(2)
    }

    // Compare the actual chat endpoint, including its path. Host case and the default HTTPS port are immaterial.
    private fun endpointIdentity(url: String): Pair<String, String> {
        val uri = URI(AiProtocol.endpoint(url)).normalize()
        val port = if (uri.port == -1) 443 else uri.port
        return (uri.host.lowercase() + ":" + port) to uri.rawPath
    }

    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() && a.keys().asSequence().all { k -> b.has(k) && jsonEquals(a.opt(k), b.opt(k)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { i -> jsonEquals(a.opt(i), b.opt(i)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble() && a.toString() == b.toString()
        else -> a == b
    }

    private fun sameThinkingLevels(a: Map<String, String>, b: Map<String, String>): Boolean {
        if (a.size != b.size || a.keys != b.keys) return false
        return a.entries.all { (level, payload) ->
            val expected = b[level] ?: return@all false
            runCatching {
                jsonEquals(AiThinking.validatePayload(level, payload), AiThinking.validatePayload(level, expected))
            }.getOrDefault(false)
        }
    }

    private fun hasExpandedDefaults(provider: AiProvider): Boolean {
        val levels = provider.thinkingLevels ?: return false
        val defaults = AiThinking.defaultLevels(provider.thinkingFormat, provider.url)
        if (sameThinkingLevels(levels, defaults)) return true
        if (!AiThinking.isToggleOnly(provider.thinkingFormat, provider.url)) return false
        // Older exports expanded toggle defaults into one off and five identical enabled payloads.
        val legacy = AiThinking.LEVELS.filter { it.isNotEmpty() }.associateWith { level ->
            defaults.getValue(if (level == AiThinking.OFF) AiThinking.OFF else AiThinking.ON)
        }
        return sameThinkingLevels(levels, legacy)
    }

    fun parseAndApply(rawJson: String, existing: AiConfig, defaultInstructions: String, mode: ImportMode = ImportMode.REPLACE): AiConfig {
        val json = try {
            strictJsonContainer(rawJson) as? JSONObject
        } catch (e: com.xnotes.format.JsonPullException) {
            throw IllegalArgumentException(AiText.t(AiMsg.CONFIG_JSON_INVALID, e.message.orEmpty()))
        } catch (_: Exception) {
            invalid("JSONObject")
        } ?: invalid("JSONObject")
        val version = json.integer("version", VERSION)
        require(version == VERSION) { AiText.t(AiMsg.CONFIG_JSON_VERSION_UNSUPPORTED, version) }
        val merging = mode == ImportMode.MERGE
        val localProviders = AiConfigStore.providersOf(existing)
        val base = if (merging) existing else AiConfig(instructions = defaultInstructions)
        val providers = linkedMapOf<String, AiProvider>()
        if (merging) localProviders.associateByTo(providers) { it.id }

        if (json.has("providers")) {
            val entries = json.optJSONArray("providers") ?: invalid("providers")
            require(entries.length() <= AiConfigStore.MAX_PROVIDERS) {
                AiText.t(AiMsg.CONFIG_TOO_MANY_PROVIDERS, AiConfigStore.MAX_PROVIDERS)
            }
            val seen = mutableSetOf<String>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: invalid("providers")
                val id = if (entry.has("id")) entry.string("id") else UUID.randomUUID().toString()
                require(id.isNotBlank() && seen.add(id)) { AiText.t(AiMsg.CONFIG_PROVIDERS_INVALID) }
                val previous = localProviders.firstOrNull { it.id == id }
                val parsed = readProvider(entry, id, if (merging) previous else null)
                // Viewing or editing unrelated JSON fields should not freeze expanded default levels.
                val p = if (previous?.thinkingLevels == null && hasExpandedDefaults(parsed)) parsed.copy(thinkingLevels = null) else parsed
                providers[id] = if (p.key.isEmpty() || p.key == REDACTED_KEY) {
                    val endpoint = endpointIdentity(p.url)
                    val host = endpoint.first
                    // Never silently hand a saved provider key to a different host just because the JSON reuses an ID.
                    val key = previous?.takeIf {
                        it.key.isNotEmpty() && runCatching { endpointIdentity(it.url).first }.getOrNull() == host
                    }?.key ?: localProviders.singleOrNull {
                        runCatching { endpointIdentity(it.url) }.getOrNull() == endpoint
                    }?.key
                    p.copy(key = key.orEmpty())
                } else p
            }
        }
        val searchKey = json.string("searchKey", base.searchKey)
        val imported = base.copy(
            providers = providers.values.toList(), provider = json.string("defaultProvider", base.provider),
            model = json.string("defaultModel", base.model), effort = json.string("defaultEffort", base.effort),
            maxTokens = json.integer("maxTokens", base.maxTokens), instructions = json.string("instructions", base.instructions),
            searchByDefault = json.bool("searchByDefault", base.searchByDefault),
            attachPdfByDefault = json.bool("attachPdfByDefault", base.attachPdfByDefault),
            searchKey = if (searchKey == REDACTED_KEY) existing.searchKey else searchKey,
            imageProvider = json.string("imageProvider", base.imageProvider), imageModel = json.string("imageModel", base.imageModel),
        )
        val routed = imported.withValidDefault()
        // Validate an explicit selection against the imported route before returning its normalized value.
        AiConfigStore.validate(if (json.has("defaultEffort")) routed.copy(effort = imported.effort) else routed)
        return routed
    }
}
