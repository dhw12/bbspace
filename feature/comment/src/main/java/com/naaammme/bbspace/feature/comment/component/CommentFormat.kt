package com.naaammme.bbspace.feature.comment.component

import java.util.Locale

internal fun Long.formatCount(): String {
    return when {
        this >= 100_000_000L -> formatDecimal(this / 100_000_000f, "亿")
        this >= 10_000L -> formatDecimal(this / 10_000f, "万")
        else -> toString()
    }
}

private fun formatDecimal(
    value: Float,
    suffix: String
): String {
    val text = String.format(Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')
    return "$text$suffix"
}
