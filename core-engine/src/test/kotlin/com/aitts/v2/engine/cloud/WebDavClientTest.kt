package com.aitts.v2.engine.cloud

import com.aitts.v2.engine.network.HttpClientManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(HttpClientManager())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses PROPFIND multi-status directory and filters ebooks cleanly`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/books/</D:href>
                    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/books/Sub/</D:href>
                    <D:propstat><D:prop><D:displayname>Sub</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/books/novel.epub</D:href>
                    <D:propstat><D:prop><D:displayname>novel.epub</D:displayname><D:getcontentlength>4096</D:getcontentlength></D:prop></D:propstat>
                  </D:response>
                  <D:response>
                    <D:href>/dav/books/cover.jpg</D:href>
                    <D:propstat><D:prop><D:displayname>cover.jpg</D:displayname><D:getcontentlength>512</D:getcontentlength></D:prop></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent()
            )
        )
        val cfg = WebDavConfig(baseUrl = server.url("/dav").toString(), username = "u", password = "p", rootPath = "/books")
        val items = client.listFiles(cfg, ebooksOnly = true).getOrThrow()
        assertEquals(2, items.size)
        assertTrue(items[0].isFolder && items[0].name == "Sub")
        assertEquals("novel.epub", items[1].name)
        assertEquals(4096L, items[1].sizeBytes)
    }

    @Test
    fun `uploads and downloads files via PUT and GET`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello webdav"))
        val tmpDir = File("build/test-tmp/webdav").apply { mkdirs() }
        val uploadSrc = File(tmpDir, "up.txt").apply { writeText("content") }
        val dlDest = File(tmpDir, "down.txt")
        val cfg = WebDavConfig(baseUrl = server.url("/dav").toString(), username = "u", password = "p")

        assertTrue(client.uploadFile(cfg, "up.txt", uploadSrc).isSuccess)
        val downloaded = client.downloadFile(cfg, "down.txt", dlDest).getOrThrow()
        assertEquals("hello webdav", downloaded.readText())
    }

    @Test
    fun `encodes reserved URL characters and blocks crafted traversal displayname`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/books/book%20%231%3F.epub</D:href>
                    <D:propstat><D:prop><D:displayname>../../escape.epub</D:displayname><D:getcontentlength>512</D:getcontentlength></D:prop></D:propstat>
                  </D:response>
                </D:multistatus>
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val cfg = WebDavConfig(baseUrl = server.url("/dav").toString(), username = "u", password = "p", rootPath = "/books")
        val items = client.listFiles(cfg, ebooksOnly = true).getOrThrow()
        assertEquals(1, items.size)
        assertEquals("book #1?.epub", items[0].path)
        assertEquals("book #1?.epub", items[0].name)

        val dlDest = File(File("build/test-tmp/webdav").apply { mkdirs() }, "special.epub")
        assertTrue(client.downloadFile(cfg, items[0].path, dlDest).isSuccess)
        server.takeRequest()
        val getReq = server.takeRequest()
        assertEquals("/dav/books/book%20%231%3F.epub", getReq.requestUrl?.encodedPath)
    }
}
