package com.aitts.v2.reader

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Serves the active local custom TTF/OTF font file on 127.0.0.1 for Readium WebView @font-face.
 */
class LocalFontHttpServer {

    private val lock = Any()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val workerExecutor = Executors.newFixedThreadPool(4)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activePath: String = ""

    @Volatile
    private var activeToken: String = ""

    @Volatile
    private var activeFile: File? = null

    fun serveFont(filePath: String): String? {
        val clean = filePath.trim()
        if (clean.isEmpty()) return null
        val file = File(clean)
        if (!file.isFile || file.length() <= 0L) return null
        synchronized(lock) {
            val socket = ensureServerLocked() ?: return null
            if (activePath != clean || activeFile?.lastModified() != file.lastModified()) {
                val ext = file.extension.lowercase().ifBlank { "ttf" }
                val hash = sha1Short("${clean}:${file.lastModified()}")
                activePath = clean
                activeFile = file
                activeToken = "$hash.$ext"
            }
            return "http://127.0.0.1:${socket.localPort}/font/$activeToken"
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
            activeFile = null
            activePath = ""
            activeToken = ""
        }
    }

    private fun ensureServerLocked(): ServerSocket? {
        serverSocket?.takeIf { !it.isClosed }?.let { return it }
        val sock = runCatching { ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return null
        serverSocket = sock
        acceptExecutor.execute { acceptLoop(sock) }
        return sock
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (!sock.isClosed) {
            val client = runCatching { sock.accept() }.getOrNull() ?: break
            workerExecutor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 1500
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val reqLine = runCatching { reader.readLine() }.getOrNull().orEmpty()
            val path = reqLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
            val target = activeFile
            val token = activeToken
            val validRequest = token.isNotBlank() && path == "/font/$token"
            val hasFile = target != null && target.isFile
            if (!validRequest || !hasFile) {
                val hdr = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(hdr.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                return
            }
            val bytes = runCatching { target.readBytes() }.getOrNull() ?: return
            val mime = when (target.extension.lowercase()) {
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "font/ttf"
            }
            val hdr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: public, max-age=3600\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(hdr.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    private fun sha1Short(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
}
