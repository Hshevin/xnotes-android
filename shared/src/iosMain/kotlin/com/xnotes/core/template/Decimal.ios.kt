package com.xnotes.core.template

import kotlin.math.abs
import platform.posix.fma

actual open class TemplateFailure actual constructor(message: String?) : RuntimeException(message)

internal actual fun decimalThousandths(value: Double): String {
    require(value.isFinite())
    val magnitude = abs(value)
    var whole = magnitude.toLong()
    val fraction = magnitude - whole.toDouble()
    var thousandths = (fraction * 1000).toInt()
    if (fma(fraction, 1000.0, -thousandths - 0.5) >= 0.0) thousandths++
    if (thousandths == 1000) { whole++; thousandths = 0 }
    val sign = if (value < 0 && (whole != 0L || thousandths != 0)) "-" else ""
    val tail = if (thousandths == 0) "" else "." + thousandths.toString().padStart(3, '0').trimEnd('0')
    return "$sign$whole$tail"
}
