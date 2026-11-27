package com.aitts.v2.engine.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleOAuthClientJsonParserTest {

    @Test
    fun `parses installed desktop OAuth client json`() {
        val raw = """
            {
              "installed": {
                "client_id": "12345-abc.apps.googleusercontent.com",
                "project_id": "demo",
                "client_secret": "GOCSPX-secret123",
                "redirect_uris": ["http://localhost"]
              }
            }
        """.trimIndent()
        val parsed = GoogleOAuthClientJsonParser.parse(raw).getOrThrow()
        assertEquals("12345-abc.apps.googleusercontent.com", parsed.clientId)
        assertEquals("GOCSPX-secret123", parsed.clientSecret)
        assertEquals("installed", parsed.clientType)
    }

    @Test
    fun `rejects web OAuth json incompatible with loopback PKCE`() {
        val raw = """
            {
              "web": {
                "client_id": "99999-xyz.apps.googleusercontent.com",
                "redirect_uris": ["https://example.com/callback"]
              }
            }
        """.trimIndent()
        val res = GoogleOAuthClientJsonParser.parse(raw)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message.orEmpty().contains("桌面应用"))
    }

    @Test
    fun `rejects invalid or missing client_id json cleanly`() {
        assertTrue(GoogleOAuthClientJsonParser.parse("{}").isFailure)
        assertTrue(GoogleOAuthClientJsonParser.parse("not json").isFailure)
        assertTrue(GoogleOAuthClientJsonParser.parse("").isFailure)
    }
}
