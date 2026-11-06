package com.xnotes.ai

import com.xnotes.core.template.TNode
import com.xnotes.core.template.TemplateKind
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplateReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes an `.xtemplate` through the configured AI provider, in place of copying the guide into another
 * AI assistant. The bundled guide (templates/AI.md) is the system prompt, with a note that the reply
 * comes back through the API: one JSON block, shapes and text only, since nothing here can run code
 * or make pictures. Each reply is read as the library would read it and checked; one that cannot be
 * used goes back to the model with the reason, a few times, before the attempt fails.
 */
object AiTemplateGen {
    const val MAX_ATTEMPTS = 3
    /** Output room for a template and a reasoning model's thinking, whatever the chat's own limit. */
    const val MIN_OUTPUT_TOKENS = 16000
    const val MAX_REQUEST = 2000

    /** Overrides the guide's "What to hand back", which assumes a chat assistant that makes files. */
    private fun inApp(kind: TemplateKind) = """

## In-app generation (this replaces "What to hand back")

You are being called by the xnotes app itself, through its API. You cannot run code, create files or
make pictures, and the app reads your reply directly.

- Make a ${if (kind == TemplateKind.COVER) "cover template, with `\"kind\": \"cover\"`" else "page template"}.
- Reply with the complete template JSON in exactly one ```json code block. Outside the block write at
  most one short sentence saying what you chose, in the language of the request.
- Draw it with shapes and text only: no `image` nodes and no package.
- Write the `name`, `description`, parameter labels and any text on the page in the language of the
  request.
"""

    /** The system prompt: [guide] and the in-app note. */
    fun instructions(guide: String, kind: TemplateKind): String = guide.trimEnd() + "\n" + inApp(kind)

    /** The template in [reply]: its last ```json block, else its last fenced block, else its outermost braces. */
    fun extract(reply: String): String? {
        val blocks = Regex("```([A-Za-z]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(reply).toList()
        blocks.lastOrNull { it.groupValues[1].equals("json", ignoreCase = true) }?.let { return it.groupValues[2].trim() }
        blocks.lastOrNull { it.groupValues[2].trimStart().startsWith("{") }?.let { return it.groupValues[2].trim() }
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start in 0 until end) reply.substring(start, end + 1) else null
    }

    /**
     * [text] when it is a usable template of [kind]; otherwise an [IllegalArgumentException] whose
     * message tells the model what to fix. The reader drops what it cannot use without a word, so
     * the nodes it kept are counted against those written.
     */
    fun check(text: String, kind: TemplateKind): String {
        val template = try { TemplateReader.read(text) } catch (e: TemplateFormatException) {
            throw IllegalArgumentException("It is not a valid xtemplate: ${e.message}.")
        }
        val json = JSONObject(text)
        val written = mutableListOf<JSONObject>()
        collect(json.optJSONArray("items"), written)
        require(written.none { it.optString("type") == "image" }) {
            "It uses an `image` node, but a template made here cannot carry a picture. Draw it with shapes and text only."
        }
        require(template.kind == kind) {
            if (kind == TemplateKind.COVER) "It must be a cover template: set `\"kind\": \"cover\"` at the top level."
            else "It must be a page template: leave out `\"kind\": \"cover\"`."
        }
        val kept = count(template.root.children)
        require(kept > 0) { "It draws nothing: `items` holds no node the format defines." }
        require(kept >= written.size) {
            "${written.size - kept} of its ${written.size} nodes cannot be used and would be dropped. Use only the node types, " +
                "properties, units, functions and names the specification defines, and check every expression."
        }
        return text
    }

    private fun collect(nodes: JSONArray?, into: MutableList<JSONObject>) {
        if (nodes == null) return
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            into += node
            collect(node.optJSONArray("children"), into)
        }
    }

    private fun count(nodes: List<TNode>): Int = nodes.sumOf { node ->
        1 + when (node) {
            is TNode.Group -> count(node.children)
            is TNode.Repeat -> count(node.children)
            else -> 0
        }
    }

    /** What the user sees while a template is made. */
    sealed interface Stage {
        /** Text arriving: [chars] so far, in [attempt] of [MAX_ATTEMPTS]. */
        data class Writing(val attempt: Int, val chars: Int) : Stage
        data class Checking(val attempt: Int) : Stage
    }

    /**
     * A checked template of [kind] for [request], from [config]'s default provider and model with
     * [guide] as the system prompt. Throws [IllegalStateException] with a user-facing reason when
     * no usable template came back.
     */
    suspend fun generate(config: AiConfig, client: AiTransport, guide: String, request: String, kind: TemplateKind, stage: suspend (Stage) -> Unit = {}): String {
        val ask = request.trim()
        require(ask.isNotEmpty()) { AiText.t(AiMsg.TEMPLATE_GEN_EMPTY) }
        require(ask.length <= MAX_REQUEST) { AiText.t(AiMsg.TEMPLATE_GEN_TOO_LONG, MAX_REQUEST) }
        require(config.url.isNotBlank() && config.key.isNotBlank() && config.model.isNotBlank()) { AiText.t(AiMsg.PROVIDER_NOT_CONFIGURED) }
        val settings = config.copy(instructions = instructions(guide, kind), maxTokens = maxOf(config.maxTokens, MIN_OUTPUT_TOKENS))
        val messages = mutableListOf(AiMessage("user", ask))
        var reason = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            val reply = StringBuilder()
            stage(Stage.Writing(attempt, 0))
            client.stream(settings, messages) { reply.append(it); stage(Stage.Writing(attempt, reply.length)) }
            stage(Stage.Checking(attempt))
            val text = reply.toString()
            reason = try {
                val json = extract(text) ?: throw IllegalArgumentException("No template JSON was found in the reply.")
                return check(json, kind)
            } catch (e: IllegalArgumentException) { e.message.orEmpty() }
            catch (e: org.json.JSONException) { "It is not valid JSON: ${e.message}." }
            messages += AiMessage("assistant", text.ifBlank { "(empty reply)" })
            messages += AiMessage("user", "That template cannot be used. $reason Reply with the complete corrected template in one ```json block.")
        }
        throw IllegalStateException(AiText.t(AiMsg.TEMPLATE_GEN_FAILED, reason))
    }
}
