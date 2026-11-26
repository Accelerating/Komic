package com.komic.reader

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DavTest {
    @Test fun browseAuthenticatedCollectionAndChild() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav/") { exchange ->
            val allowed = exchange.requestHeaders.getFirst("Authorization") == "Basic dTpw"
            val correct = exchange.requestMethod == "PROPFIND" && exchange.requestHeaders.getFirst("Depth") == "1"
            val status = if (!allowed) 401 else if (correct) 207 else 405
            val hrefs = if (exchange.requestURI.path == "/dav/") listOf("/dav/", "Books%201/", "/outside/", "https://example.com/dav/foreign/")
                else listOf("/dav/Books%201/", "01.cbz")
            val body = "<d:multistatus xmlns:d='DAV:'>" + hrefs.joinToString("") {
                val type = if (it.endsWith("/")) "<d:collection/>" else ""
                "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype>$type</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            } + "</d:multistatus>"
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val root = directoryUrl("http://127.0.0.1:${server.address.port}/dav".toHttpUrl())
            directoryCall(root, null).execute().use { assertEquals(401, it.code) }
            val folder = directoryCall(root, "Basic dTpw").execute().use {
                assertEquals(207, it.code)
                readListing(root, it.body!!.byteStream()).single()
            }
            assertTrue(folder.folder)
            assertEquals("Books 1", folder.name)
            directoryCall(folder.url, "Basic dTpw").execute().use {
                val file = readListing(folder.url, it.body!!.byteStream()).single()
                assertFalse(file.folder)
                assertEquals("01.cbz", file.name)
            }
        } finally { server.stop(0) }
    }
}
