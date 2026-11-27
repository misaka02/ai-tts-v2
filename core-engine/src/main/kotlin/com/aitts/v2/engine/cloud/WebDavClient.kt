package com.aitts.v2.engine.cloud

import com.aitts.v2.engine.network.HttpClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-JVM OkHttp WebDAV client (PROPFIND/GET/PUT/MKCOL/MOVE/DELETE)
 * compatible with Alist, Nextcloud, OwnCloud, Synology, and Jianguoyun.
 */
class WebDavClient(private val httpManager: HttpClientManager) {

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildTargetUrl(config, "")
            val req = baseBuilder(config, url)
                .header("Depth", "0")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            httpManager.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 207) {
                    throw IOException("WebDAV 连接失败 (${resp.code}): ${resp.message}")
                }
            }
        }
    }

    suspend fun listFiles(
        config: WebDavConfig,
        relativePath: String = "",
        ebooksOnly: Boolean = false
    ): Result<List<CloudFileNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = buildTargetUrl(config, relativePath, trailingSlash = true)
            val req = baseBuilder(config, targetUrl)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA))
                .build()
            val xmlBytes = httpManager.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 207) {
                    throw IOException("WebDAV 列目录失败 (${resp.code}): ${resp.message}")
                }
                resp.body?.bytes() ?: throw IOException("WebDAV 返回空响应")
            }
            parsePropfindResponse(xmlBytes, targetUrl, relativePath)
                .filter { !ebooksOnly || it.isFolder || it.isSupportedEbook }
                .sortedWith(compareByDescending<CloudFileNode> { it.isFolder }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun createFolder(config: WebDavConfig, parentPath: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = name.trim().trim('/')
                if (clean.isEmpty()) throw IOException("文件夹名称不能为空")
                val combined = listOf(parentPath.trim('/'), clean).filter { it.isNotEmpty() }.joinToString("/")
                val url = buildTargetUrl(config, combined, trailingSlash = true)
                val req = baseBuilder(config, url).method("MKCOL", ByteArray(0).toRequestBody(null)).build()
                httpManager.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 201 && resp.code != 405) {
                        throw IOException("创建文件夹失败 (${resp.code})")
                    }
                }
            }
        }

    suspend fun renameItem(config: WebDavConfig, itemPath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanNew = newName.trim().trim('/')
                if (cleanNew.isEmpty()) throw IOException("新名称不能为空")
                val parent = itemPath.trim('/').substringBeforeLast('/', "")
                val destRel = listOf(parent, cleanNew).filter { it.isNotEmpty() }.joinToString("/")
                val srcUrl = buildTargetUrl(config, itemPath)
                val destUrl = buildTargetUrl(config, destRel)
                val req = baseBuilder(config, srcUrl)
                    .header("Destination", destUrl)
                    .header("Overwrite", "T")
                    .method("MOVE", ByteArray(0).toRequestBody(null))
                    .build()
                httpManager.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                        throw IOException("重命名失败 (${resp.code})")
                    }
                }
            }
        }

    suspend fun deleteItem(config: WebDavConfig, itemPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildTargetUrl(config, itemPath)
                val req = baseBuilder(config, url).delete().build()
                httpManager.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 204) throw IOException("删除失败 (${resp.code})")
                }
            }
        }

    suspend fun downloadFile(
        config: WebDavConfig,
        itemPath: String,
        outputFile: File,
        maxBytes: Long = 200L * 1024 * 1024,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildTargetUrl(config, itemPath)
            val req = baseBuilder(config, url).get().build()
            httpManager.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败 (${resp.code})")
                val body = resp.body ?: throw IOException("空响应体")
                val totalLen = body.contentLength()
                outputFile.parentFile?.mkdirs()
                val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buf)
                                if (count <= 0) break
                                copied += count
                                if (copied > maxBytes) throw IOException("文件超出大小上限")
                                out.write(buf, 0, count)
                                onProgress(copied, totalLen)
                            }
                        }
                    }
                    if (!tmp.renameTo(outputFile)) {
                        tmp.copyTo(outputFile, overwrite = true)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
                outputFile
            }
        }
    }

    suspend fun uploadFile(config: WebDavConfig, targetPath: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) throw IOException("本地文件不存在: ${file.name}")
                val url = buildTargetUrl(config, targetPath)
                val req = baseBuilder(config, url).put(file.asRequestBody("application/octet-stream".toMediaType())).build()
                httpManager.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                        throw IOException("上传失败 (${resp.code})")
                    }
                }
            }
        }

    private fun baseBuilder(config: WebDavConfig, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
        }
        return builder
    }

    private fun buildTargetUrl(config: WebDavConfig, relativePath: String, trailingSlash: Boolean = false): String {
        val base = config.baseUrl.trim().trimEnd('/').toHttpUrl()
        val builder = base.newBuilder()
        val segments = listOf(config.rootPath, relativePath)
            .flatMap { it.split('/') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (seg in segments) {
            if (seg == "." || seg == "..") throw IOException("非法 WebDAV 路径段")
            builder.addPathSegment(seg)
        }
        if (trailingSlash) builder.addPathSegment("")
        return builder.build().toString()
    }

    private fun parsePropfindResponse(xmlBytes: ByteArray, requestUrl: String, currentRelPath: String): List<CloudFileNode> {
        val doc = parseDavXmlDocument(xmlBytes)
        val reqPath = runCatching { URI(requestUrl).path.trimEnd('/') }.getOrDefault("")
        val responses = doc.getElementsByTagNameNS("*", "response")
        return (0 until responses.length).mapNotNull { i ->
            (responses.item(i) as? Element)?.let { parseResponseNode(it, reqPath, currentRelPath) }
        }
    }

    private fun parseDavXmlDocument(xmlBytes: ByteArray) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
        }.parse(ByteArrayInputStream(xmlBytes))

    private fun parseResponseNode(el: Element, reqPath: String, currentRelPath: String): CloudFileNode? {
        val hrefRaw = firstElementText(el, "href") ?: return null
        val rawPath = runCatching { URI(hrefRaw).rawPath ?: URI(hrefRaw).path }.getOrNull() ?: hrefRaw
        val decodedHref = runCatching { URLDecoder.decode(rawPath, Charsets.UTF_8.name()) }.getOrDefault(rawPath).trimEnd('/')
        if (decodedHref.isEmpty() || decodedHref == reqPath) return null
        if (reqPath.isNotEmpty() && !decodedHref.startsWith("$reqPath/")) return null
        val rawSeg = decodedHref.substringAfterLast('/')
        if (!isSafeSingleSegment(rawSeg)) return null
        val isFolder = el.getElementsByTagNameNS("*", "collection").length > 0
        val displaySafe = firstElementText(el, "displayname").orEmpty().takeIf(::isSafeSingleSegment)
        val size = firstElementText(el, "getcontentlength")?.toLongOrNull() ?: 0L
        val modMs = firstElementText(el, "getlastmodified")?.let { parseHttpDate(it) } ?: 0L
        val mime = firstElementText(el, "getcontenttype").orEmpty()
        val relChild = listOf(currentRelPath.trim('/'), rawSeg).filter { it.isNotEmpty() }.joinToString("/")
        return CloudFileNode(relChild, displaySafe ?: rawSeg, relChild, isFolder, size, modMs, mime)
    }

    private fun isSafeSingleSegment(seg: String): Boolean {
        if (seg.isBlank() || seg == "." || seg == "..") return false
        return '/' !in seg && '\\' !in seg
    }

    private fun firstElementText(parent: Element, localName: String): String? {
        val list = parent.getElementsByTagNameNS("*", localName)
        return if (list.length > 0) list.item(0)?.textContent?.trim() else null
    }

    private fun parseHttpDate(raw: String): Long = runCatching {
        ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:"><D:prop>
            <D:displayname/><D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:getcontenttype/>
            </D:prop></D:propfind>"""
    }
}
