package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeoutException

/**
 * Posts forwarded SMS bodies to the Telegram Bot API on cached background
 * workers so overlapping messages do not block each other.
 *
 * Success is signalled to the caller via `onComplete(true|false)` so the
 * receiver can decide whether to record a forward stat. The bot token is
 * URL-encoded out of paranoia and never appears in log entries — only the
 * HTTP status code and (if present) Telegram's `description` field, with any
 * echoed copy of the token (including the URL form) redacted before logging.
 */
object TelegramChannel {
    private const val API_BASE = "https://api.telegram.org"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val COMPLETION_TIMEOUT_MS = 8_500L

    private val sendExecutor = cachedDaemonExecutor("tg-sender")

    fun send(context: Context, config: TelegramConfig, body: String, onComplete: (Boolean) -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (!config.hasCredentials) {
            LogUtils.addToLog(app, "SEND FAILED [Telegram] → missing config")
            onComplete(false)
            return false
        }
        return sendExecutor.executeWithDeadline(
            timeoutMs = COMPLETION_TIMEOUT_MS,
            block = { postSync(config, body) },
        ) { outcome ->
            var success = false
            try {
                val result = outcome.getOrNull()
                when {
                    result != null && result.success -> {
                        success = true
                        LogUtils.addToLog(app, "SEND OK [Telegram] → chat ${config.chatId} (HTTP ${result.statusCode})")
                    }
                    result != null -> {
                        val detail = result.errorSummary?.takeIf { it.isNotBlank() }?.let { " ${redactSecret(it, config.botToken)}" }.orEmpty()
                        LogUtils.addToLog(app, "SEND FAILED [Telegram] → chat ${config.chatId} (HTTP ${result.statusCode})$detail")
                    }
                    outcome.exceptionOrNull() is TimeoutException -> {
                        LogUtils.addToLog(app, "SEND FAILED [Telegram] → chat ${config.chatId} (completion timeout; delivery unknown)")
                    }
                    else -> {
                        // The bot token lives in the request URL, and HttpURLConnection exceptions
                        // (FileNotFoundException, SSL/IO errors) can embed that full URL — so redact
                        // both the raw token and its URL-encoded form before logging.
                        val msg = redactSecret(outcome.exceptionOrNull()?.message.orEmpty(), config.botToken)
                        LogUtils.addToLog(app, "SEND FAILED [Telegram] → chat ${config.chatId} (transport) $msg".trimEnd())
                    }
                }
            } finally {
                onComplete(success)
            }
        }
    }

    private data class Outcome(val statusCode: Int, val success: Boolean, val errorSummary: String?)

    /**
     * Removes the bot token from a string that is about to be logged. The token is part of every
     * request URL, so transport exceptions can leak it; both the raw token and its URL-encoded
     * form are replaced with a fixed placeholder.
     */
    private fun redactSecret(text: String, secret: String): String {
        if (secret.isBlank()) {
            return text
        }
        val encoded = runCatching { URLEncoder.encode(secret, "UTF-8") }.getOrDefault(secret)
        return text.replace(secret, "[redacted]").replace(encoded, "[redacted]")
    }

    private fun postSync(config: TelegramConfig, body: String): Outcome {
        // Bot tokens are of the form `{bot_id}:{secret}`; URL-encode just in case the
        // user accidentally pastes a token with stray padding characters.
        val encodedToken = URLEncoder.encode(config.botToken, "UTF-8")
        val url = "$API_BASE/bot$encodedToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", config.chatId)
            .put("text", body)
            .put("disable_web_page_preview", true)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val result = HttpJsonClient.postJson(url, payload, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        val summary = if (result.success) null else summarizeError(result.errorBody)
        return Outcome(result.statusCode, result.success, summary)
    }

    private fun summarizeError(raw: String): String {
        if (raw.isBlank()) {
            return ""
        }
        return runCatching {
            val obj = JSONObject(raw)
            val code = obj.optInt("error_code", -1)
            val desc = obj.optString("description")
            buildString {
                if (code != -1) {
                    append("code=").append(code).append(' ')
                }
                if (desc.isNotEmpty()) {
                    append("desc=").append(desc)
                }
            }.trim().take(240)
        }.getOrElse { raw.take(180) }
    }
}
