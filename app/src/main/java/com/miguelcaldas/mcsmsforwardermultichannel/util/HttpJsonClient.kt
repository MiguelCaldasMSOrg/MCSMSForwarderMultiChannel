package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared bounded HTTP-JSON POST transport for the WhatsApp and Telegram channels.
 *
 * Owns connection pooling, total/write deadlines and bounded error reads. Each channel keeps its payload
 * building, provider-specific error summarizing, and secret redaction so this stays
 * free of any credential handling.
 */
object HttpJsonClient {
    private const val MAX_ERROR_BYTES = 64 * 1024
    private const val OVERSIZED_ERROR = "Provider error response exceeded the diagnostic limit; body omitted."
    private val client = OkHttpClient.Builder().connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    /**
     * Result of a POST. [errorBody] holds the raw response body **only when the request
     * failed** (non-2xx); on success it is empty. The caller decides how to summarize it.
     */
    data class Result(val statusCode: Int, val success: Boolean, val errorBody: String)

    /**
     * POSTs [payload] as `application/json` to [url] and returns the outcome. Throws on
     * transport failure (DNS, TLS, timeout) so the caller can distinguish a transport
     * error from an HTTP error response.
     */
    fun postJson(url: String, payload: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int, headers: Map<String, String> = emptyMap(), callTimeoutMs: Long = 8_000): Result {
        require(payload.size <= 1024 * 1024) { "Provider request exceeds the 1 MiB limit" }
        val transport = client.newBuilder().connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).callTimeout(callTimeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(url).header("Accept", "application/json").post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (name, value) ->
            request.header(name, value)
        }
        transport.newCall(request.build()).execute().use { response ->
            val error = if (response.isSuccessful) "" else readErrorBody(response.body.byteStream())
            return Result(response.code, response.isSuccessful, error)
        }
    }

    internal fun readErrorBody(stream: InputStream, maximumBytes: Int = MAX_ERROR_BYTES): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(4096, maximumBytes + 1))
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size() + 1))
            if (count < 0) {
                return output.toString(Charsets.UTF_8.name())
            }
            if (count > maximumBytes - output.size()) {
                return OVERSIZED_ERROR
            }
            output.write(buffer, 0, count)
        }
    }
}
