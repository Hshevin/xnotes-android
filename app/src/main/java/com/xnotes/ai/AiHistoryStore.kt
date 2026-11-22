package com.xnotes.ai

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class AiDraft(val text: String = "", val images: List<String> = emptyList(), val pdf: AiPdf? = null, val search: Boolean = false)

data class AiTurn(val user: AiMessage, val answer: String = "", val state: String = "生成中", val error: String = "",
                  val progress: String = "", val steps: List<String> = emptyList(), val rawError: String = "")
data class AiChat(val id: String = UUID.randomUUID().toString(), val title: String = "新会话", val source: String = "", val updated: Long = System.currentTimeMillis(), val turns: List<AiTurn> = emptyList(), val sourceUri: String = "", val draft: AiDraft = AiDraft(), val sourceDocumentId: String = "",
    /**
     * The model and thinking level picked in this chat; blank / null follow the settings defaults.
     * [provider] is the ID of the provider [model] goes through; blank in chats from before providers.
     */
    val model: String = "", val effort: String? = null, val provider: String = "",
    /** Other documents' keys whose screenshots joined this chat (split screen); the chat stays with its source. */
    val relatedDocuments: List<String> = emptyList(),
    /** When the chat was moved to Trash; 0 while it is in history. */
    val trashedAt: Long = 0L) {
    /** The key the chat's source document is known by: registry ID, else URI, else title — as editors compute it. */
    val documentKey: String get() = sourceDocumentId.ifBlank { sourceUri.ifBlank { source } }
    /** What this chat sends with: its own picks over the defaults in [config]. */
    fun settings(config: AiConfig): AiConfig {
        val effort = effort ?: config.effort
        val route = when {
            model.isBlank() -> config.provider to config.model
            provider.isNotBlank() -> provider to model
            // Picked before providers existed: whichever provider lists the model, else the default.
            else -> (config.ownerOf(model) ?: config.provider) to model
        }
        val routed = config.route(route.first, route.second)
        return routed.copy(effort = AiThinking.normalizeEffort(effort, routed.availableEfforts()))
    }
    /** False when this chat's picked provider has been deleted, so it must pick again before sending. */
    fun routable(config: AiConfig): Boolean = config.providers.isEmpty() || config.providerOf(settings(config).provider) != null
    fun context(): List<AiMessage> = turns.filter { it.state == "已完成" }.flatMap { listOf(it.user, AiMessage("assistant", it.answer)) }
    fun recover(): AiChat = copy(turns = turns.map { if (it.state == "生成中") it.copy(state = "已中断", error = AiText.t(AiMsg.RECOVERED_INTERRUPTED)) else it })
    /**
     * Shown in [owner]'s history: started from that document (its registry ID, or for older/unsaved ones
     * its URI or title), or holding a screenshot taken from it.
     */
    fun belongsTo(owner: String): Boolean = owner.isNotBlank() &&
        (sourceDocumentId == owner || sourceDocumentId.isBlank() && (sourceUri == owner || sourceUri.isBlank() && source == owner) || owner in relatedDocuments)
    fun matches(query: String): Boolean = title.contains(query, true) || source.contains(query, true) || turns.any { it.user.text.contains(query, true) || it.answer.contains(query, true) }
}

/** Each chat is one atomically replaced file, including its image attachments. No API credentials. */
class AiHistoryStore(private val directory: File, private val resources: com.xnotes.storage.PdfResources? = null) {
    private var referencesReady = false
    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.json")
    }
    fun write(chat: AiChat) {
        check(directory.isDirectory || directory.mkdirs()) { AiText.t(AiMsg.HISTORY_DIR_CREATE_FAILED) }
        val destination = file(chat.id)
        val temp = File(directory, "${chat.id}.tmp")
        val turns = JSONArray()
        chat.turns.forEach { turn -> turns.put(JSONObject().put("user", turn.user.text).put("images", JSONArray(turn.user.images))
            .put("pdf", pdfJson(turn.user.pdf))
            .put("sources", turn.user.searchContext).put("answer", turn.answer).put("state", turn.state).put("error", turn.error)
            .put("rawError", turn.rawError).put("progress", turn.progress).put("steps", JSONArray(turn.steps))) }
        val json = JSONObject().put("version", 1).put("id", chat.id).put("title", chat.title).put("source", chat.source).put("updated", chat.updated).put("sourceUri", chat.sourceUri).put("sourceDocumentId", chat.sourceDocumentId).put("model", chat.model).put("provider", chat.provider).put("effort", chat.effort ?: JSONObject.NULL).put("related", JSONArray(chat.relatedDocuments)).put("trashedAt", chat.trashedAt).put("turns", turns).put("draft", JSONObject().put("text", chat.draft.text).put("images", JSONArray(chat.draft.images)).put("pdf", pdfJson(chat.draft.pdf)).put("search", chat.draft.search))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024 * 1024) { AiText.t(AiMsg.CHAT_TOO_LARGE) }
        val ids = (chat.turns.mapNotNull { it.user.pdf?.id } + listOfNotNull(chat.draft.pdf?.id)).filter { it.isNotBlank() }.toSet()
        resources?.let { it.pin("chat:${chat.id}", it.references("chat:${chat.id}") + ids) }
        try {
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            resources?.pin("chat:${chat.id}", ids)
        } finally { temp.delete() }
    }
    fun readAll(): Pair<List<AiChat>, Int> {
        var damaged = 0
        val files = if (!directory.exists()) emptyArray() else {
            check(directory.isDirectory) { AiText.t(AiMsg.HISTORY_DIR_UNREADABLE) }
            checkNotNull(directory.listFiles { f -> f.extension == "json" }) { AiText.t(AiMsg.HISTORY_DIR_UNREADABLE) }
        }
        val chats = files.mapNotNull { f ->
            try {
                require(f.length() <= 64L * 1024 * 1024)
                val json = JSONObject(f.readText())
                require(json.getInt("version") == 1)
                val id = json.getString("id"); require(file(id).name == f.name)
                val turns = json.getJSONArray("turns")
                AiChat(id, json.getString("title"), json.optString("source", ""), json.getLong("updated"), (0 until turns.length()).map { i ->
                    val t = turns.getJSONObject(i)
                    AiTurn(AiMessage("user", t.getString("user"), readImages(t), t.optString("sources", ""), readPdf(t.optJSONObject("pdf"))), t.getString("answer"), t.getString("state"), t.optString("error", ""), t.optString("progress", ""), t.optJSONArray("steps")?.let { steps -> (0 until steps.length()).map { steps.getString(it) } } ?: emptyList(),
                        rawError = (t.opt("rawError") as? String).orEmpty())
                }, json.optString("sourceUri", ""), json.optJSONObject("draft")?.let { d -> AiDraft(d.optString("text", ""), readImages(d), readPdf(d.optJSONObject("pdf")), d.optBoolean("search", false)) } ?: AiDraft(), json.optString("sourceDocumentId", ""),
                    model = (json.opt("model") as? String).orEmpty(), provider = (json.opt("provider") as? String).orEmpty(), effort = (json.opt("effort") as? String)?.takeIf(AiThinking::isValidLevelName),
                    relatedDocuments = json.optJSONArray("related")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String } } ?: emptyList(),
                    trashedAt = json.optLong("trashedAt", 0L).coerceAtLeast(0L))
            } catch (_: Exception) { damaged++; null }
        }
        if (damaged == 0 && resources != null) try {
            for (chat in chats) resources.pin("chat:${chat.id}",(chat.turns.mapNotNull { it.user.pdf?.id } + listOfNotNull(chat.draft.pdf?.id)).filter { it.isNotBlank() }.toSet())
            val present=chats.map { "chat:${it.id}" }.toSet()
            for(owner in resources.registry.owners("chat:")-present) resources.pin(owner,emptySet())
            for(owner in resources.registry.owners("undo:")) resources.pin(owner,emptySet())
            referencesReady=true
            resources.collect()
        } catch (_: Exception) { referencesReady=false; damaged++ }
        return chats to damaged
    }
    fun delete(id: String) {
        resources?.let { it.pin("undo:$id", it.references("chat:$id")) }
        Files.deleteIfExists(file(id).toPath())
        resources?.pin("chat:$id", emptySet())
    }
    fun forgetDeleted(id: String) { resources?.pin("undo:$id", emptySet()); if(referencesReady) resources?.collect() }
}

/** Records before multi-screenshot questions kept one optional `image`; both forms load. */
private fun readImages(json: JSONObject): List<String> = json.optJSONArray("images")?.let { list -> (0 until list.length()).map { list.getString(it) } }
    ?: listOfNotNull(json.opt("image") as? String)
private fun pdfJson(pdf: AiPdf?): Any = pdf?.let { JSONObject().put("name", it.name).put("id", it.id).put("pageCount", it.pageCount).put("text", it.text).put("pages", JSONArray(it.pageImages)) } ?: JSONObject.NULL
private fun readPdf(pdf: JSONObject?): AiPdf? = pdf?.let {
    val pages = it.optJSONArray("pages") ?: JSONArray()
    AiPdf(it.getString("name"), it.optString("text", ""), (0 until pages.length()).map { index -> pages.getString(index) }, it.optString("id", ""), it.optInt("pageCount", pages.length()))
}
