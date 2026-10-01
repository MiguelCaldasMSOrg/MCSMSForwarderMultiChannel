package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ForwardTemplate {
    const val KEY = "forwardTemplate"
    internal const val MAX_OUTPUT_CHARS = 256 * 1024

    // Single-pass substitution of %s/%t/%m so tokens inside `message` are not re-expanded
    // and a literal `%` followed by any other character is left untouched.
    fun apply(template: String, source: String, timestampMillis: Long, message: String): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timestampMillis))
        val out = StringBuilder(minOf(template.length, MAX_OUTPUT_CHARS))
        fun append(value: String) {
            require(value.length <= MAX_OUTPUT_CHARS - out.length) { "Forwarded message exceeds 256 Ki characters" }
            out.append(value)
        }
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length) {
                when (template[i + 1]) {
                    's' -> { append(source); i += 2; continue }
                    't' -> { append(time); i += 2; continue }
                    'm' -> { append(message); i += 2; continue }
                }
            }
            require(out.length < MAX_OUTPUT_CHARS) { "Forwarded message exceeds 256 Ki characters" }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
