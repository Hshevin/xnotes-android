package com.xnotes.core.model

/** Physical indices remain zero based; labels never change document page order. */
class PageNumbering(private val count: Int, start: Int, private val firstNumber: Int = 1) {
    val start = start.takeIf { it in 0 until count } ?: 0
    val hasBodyOffset: Boolean get() = count > 0 && (start > 0 || firstNumber != 1)

    fun label(index: Int): String = if (index < start) roman(index + 1) else (index.toLong() - start + firstNumber).toString()

    fun isBodyFirst(index: Int): Boolean = count > 0 && index == start && label(index) == "1"

    fun resolveAbsolute(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it in 1..count }?.minus(1)

    fun resolve(raw: String): Int? {
        val text = raw.trim()
        val number = text.toIntOrNull()
        if (number != null) {
            if (number <= 0) return null
            val index = number.toLong() + start - firstNumber
            return index.takeIf { it in start.toLong() until count.toLong() }?.toInt()
        }
        return (0 until start).firstOrNull { label(it).equals(text, ignoreCase = true) }
    }

    fun frontRange(): String? = when {
        count <= 0 || start <= 0 -> null
        start == 1 -> "i"
        else -> "i–${roman(start)}"
    }

    fun bodyRange(): String? = when {
        count <= 0 -> null
        start == count - 1 -> label(start)
        else -> "${label(start)}–${label(count - 1)}"
    }

    fun absoluteRange(): String? = when {
        count <= 0 -> null
        count == 1 -> "1"
        else -> "1–$count"
    }

    fun validRangeLabel(byBody: Boolean): String =
        if (!byBody) absoluteRange().orEmpty()
        else listOfNotNull(frontRange(), bodyRange()).joinToString(" · ")

    companion object {
        fun roman(number: Int): String {
            var n = number
            return buildString {
                for ((value, symbol) in listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd",
                    100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x",
                    9 to "ix", 5 to "v", 4 to "iv", 1 to "i")) {
                    repeat(n / value) { append(symbol) }
                    n %= value
                }
            }
        }
    }
}
