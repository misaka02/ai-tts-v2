package com.aitts.v2.engine.cloud

import com.aitts.v2.engine.network.HttpClientManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoogleDriveClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `builds PKCE auth URL, exchanges code, auto-refreshes expired token, and downloads ebook`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val client = GoogleDriveClient(
                httpManager = HttpClientManager(),
                authBaseUrl = "$baseUrl/o/oauth2/v2/auth",
                tokenUrl = "$baseUrl/token",
                apiBaseUrl = baseUrl
            )

            val pkce = client.generatePkce()
            val url = client.buildAuthorizationUrl("cid-123", "http://127.0.0.1:43210/callback", pkce)
            assertTrue(url.contains("client_id=cid-123"))
            assertTrue(url.contains("code_challenge_method=S256"))
            assertTrue(java.net.URLDecoder.decode(url, "UTF-8").contains(GoogleDriveClient.DRIVE_SCOPE))

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"access_token":"acc-1","refresh_token":"ref-1","expires_in":3600}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"user":{"displayName":"Reader User","emailAddress":"reader@example.com"}}"""
                )
            )

            val auth = client.exchangeAuthCode(
                clientId = "cid-123",
                clientSecret = "sec-456",
                code = "code-abc",
                codeVerifier = pkce.codeVerifier,
                redirectUri = "http://127.0.0.1:43210/callback"
            ).getOrThrow()
            assertEquals("acc-1", auth.accessToken)
            assertEquals("ref-1", auth.refreshToken)
            assertEquals("reader@example.com", auth.accountEmail)

            // Simulate 401 -> automatic refresh -> retry listing with ebooksOnly filter
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"acc-2","expires_in":3600}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "files": [
                        {"id":"f1","name":"My Books","mimeType":"application/vnd.google-apps.folder"},
                        {"id":"f2","name":"novel.epub","mimeType":"application/epub+zip","size":"1024"},
                        {"id":"f3","name":"photo.png","mimeType":"image/png","size":"2048"}
                      ]
                    }
                    """.trimIndent()
                )
            )

            var refreshedToken = ""
            val page = client.listFiles(auth, folderId = "root", ebooksOnly = true) { updated ->
                refreshedToken = updated.accessToken
            }.getOrThrow()
            assertEquals("acc-2", refreshedToken)
            assertEquals(2, page.items.size)
            assertTrue(page.items[0].isFolder)
            assertEquals("novel.epub", page.items[1].name)

            // Download file with refreshed config
            server.enqueue(MockResponse().setResponseCode(200).setBody("EPUB-CONTENT-BYTES"))
            val outFile = File(tempFolder.newFolder("dl"), "novel.epub")
            val downloaded = client.downloadFile(auth.copy(accessToken = "acc-2"), "f2", outFile).getOrThrow()
            assertEquals("EPUB-CONTENT-BYTES", downloaded.readText())
        } finally {
            server.shutdown()
        }
    }
}
