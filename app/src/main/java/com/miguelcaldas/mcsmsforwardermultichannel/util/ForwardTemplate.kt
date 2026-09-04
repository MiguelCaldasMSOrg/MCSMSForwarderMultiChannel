package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ForwardTemplate {
    const val KEY = "forwardTemplate"

    // Single-pass substitution of %s/%t/%m so tokens inside `message` are not re-expanded
    // and a literal `%` followed by any other character is left untouched.
    fun apply(template: String, source: String, timestampMillis: Long, message: String): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timestampMillis))
        val out = StringBuilder(template.length + message.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length) {
                when (template[i + 1]) {
                    's' -> { out.append(source); i += 2; continue }
                    't' -> { out.append(time); i += 2; continue }
                    'm' -> { out.append(message); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
