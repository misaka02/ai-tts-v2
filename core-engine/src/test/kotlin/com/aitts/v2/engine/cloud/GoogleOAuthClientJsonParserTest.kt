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
    fun `parses rclone conf drive section and authorized user json`() {
        val rclone = """
            [gdrive]
            type = drive
            client_id = 999-rclone.apps.googleusercontent.com
            client_secret = sec-rclone
            scope = drive
            token = {"access_token":"ya29.tok","token_type":"Bearer","refresh_token":"1//ref-tok"}
        """.trimIndent()
        val parsed = GoogleOAuthClientJsonParser.parse(rclone).getOrThrow()
        assertEquals("999-rclone.apps.googleusercontent.com", parsed.clientId)
        assertEquals("sec-rclone", parsed.clientSecret)
        assertEquals("1//ref-tok", parsed.refreshToken)
        assertEquals("ya29.tok", parsed.accessToken)
        assertEquals("rclone", parsed.clientType)

        val tokenJson = """{"client_id":"111.apps.googleusercontent.com","refresh_token":"1//r2"}"""
        val parsedTok = GoogleOAuthClientJsonParser.parse(tokenJson).getOrThrow()
        assertEquals("111.apps.googleusercontent.com", parsedTok.clientId)
        assertEquals("1//r2", parsedTok.refreshToken)
        assertEquals("token_json", parsedTok.clientType)
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
        val rcloneNoClient = "[gd]\ntype = drive\ntoken = {\"access_token\":\"a\",\"refresh_token\":\"r\"}"
        assertTrue(GoogleOAuthClientJsonParser.parse(rcloneNoClient).isFailure)
        assertTrue(GoogleOAuthClientJsonParser.parse("{}").isFailure)
        assertTrue(GoogleOAuthClientJsonParser.parse("not json").isFailure)
        assertTrue(GoogleOAuthClientJsonParser.parse("").isFailure)
    }
}
