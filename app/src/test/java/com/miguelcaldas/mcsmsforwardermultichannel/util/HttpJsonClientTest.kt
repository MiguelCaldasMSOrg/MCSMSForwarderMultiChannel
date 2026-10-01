package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpJsonClientTest {
    @Test
    fun oversizedErrorsAreOmittedRatherThanLeakingATokenPrefix() {
        val body = "synthetic-secret-prefix".repeat(20)
        val result = HttpJsonClient.readErrorBody(ByteArrayInputStream(body.toByteArray()), 24)
        assertEquals("Provider error response exceeded the diagnostic limit; body omitted.", result)
        assertFalse(result.contains("synthetic"))
        assertEquals("exact", HttpJsonClient.readErrorBody(ByteArrayInputStream("exact".toByteArray()), 5))
    }

    @Test
    fun postsExactPayloadWithoutFollowingRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/redirected"))
            val payload = "{\"text\":\"synthetic\"}".toByteArray()
            val response = HttpJsonClient.postJson(server.url("/send").toString(), payload, 1_000, 1_000)
            assertEquals(302, response.statusCode)
            assertFalse(response.success)
            assertEquals(String(payload), server.takeRequest().body.readUtf8())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun totalCallDeadlineBoundsSlowResponseBodies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("delayed").setBodyDelay(1, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) {
                HttpJsonClient.postJson(server.url("/send").toString(), byteArrayOf(), 1_000, 1_000, callTimeoutMs = 50)
            }
            assertTrue(server.requestCount <= 1)
        }
    }
}