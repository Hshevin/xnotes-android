package com.xnotes.core.template

actual open class TemplateFailure actual constructor(message: String?) : RuntimeException(message) {
    override fun fillInStackTrace(): Throwable = this
}

internal actual fun decimalThousandths(value: Double): String = java.math.BigDecimal(value)
    .setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
