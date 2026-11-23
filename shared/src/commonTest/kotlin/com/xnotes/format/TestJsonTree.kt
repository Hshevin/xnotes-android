package com.xnotes.format

internal class TestJsonTree private constructor(private val value: Any?) {
    fun field(name: String) = (value as Map<*, *>)[name] as TestJsonTree
    fun item(index: Int) = (value as List<*>)[index] as TestJsonTree
    fun has(name: String) = (value as Map<*, *>).containsKey(name)
    fun string(name: String) = field(name).value as String
    val size get() = (value as List<*>).size

    companion object {
        fun parse(text: String): TestJsonTree {
            val reader = JsonPull(text)
            fun node(): TestJsonTree = TestJsonTree(when (reader.peek()) {
                JsonPull.Token.BEGIN_OBJECT -> buildMap { reader.beginObject(); while (reader.hasNext()) put(reader.nextName(), node()); reader.endObject() }
                JsonPull.Token.BEGIN_ARRAY -> buildList { reader.beginArray(); while (reader.hasNext()) add(node()); reader.endArray() }
                JsonPull.Token.STRING -> reader.nextString()
                JsonPull.Token.NUMBER -> reader.nextNumberLiteral()
                JsonPull.Token.BOOLEAN -> reader.nextBoolean()
                JsonPull.Token.NULL -> { reader.nextNull(); null }
                else -> error("Expected a JSON value")
            })
            return node().also { check(reader.peek() == JsonPull.Token.END_DOCUMENT) }
        }
    }
}
