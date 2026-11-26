package com.komic.reader

import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemoteZipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun readSelectedPageUsingRanges() = verify(true)
    @Test fun reopenDownloadedBookWithoutNetwork() = verify(false)

    private fun verify(ranges: Boolean) {
        val pages = List(80) { i -> ByteArray(160_000).also { Random(i.toLong()).nextBytes(it) } }
        val data = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                pages.forEachIndexed { i, page ->
                    zip.putNextEntry(ZipEntry("$i.jpg")); zip.write(page); zip.closeEntry()
                }
            }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val transferred = java.util.concurrent.atomic.AtomicLong()
        server.createContext("/book.cbz") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Basic test") {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext
            }
            requests.incrementAndGet()
            val range = exchange.requestHeaders.getFirst("Range")
            val partial = ranges && range != null
            val (first, last) = if (partial) range!!.removePrefix("bytes=").split('-').map { it.toInt() }
                else listOf(0, data.lastIndex)
            if (partial) exchange.responseHeaders.set("Content-Range", "bytes $first-$last/${data.size}")
            val count = last - first + 1
            transferred.addAndGet(count.toLong())
            exchange.sendResponseHeaders(if (partial) 206 else 200, count.toLong())
            exchange.responseBody.use { it.write(data, first, count) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/book.cbz".toHttpUrl()
        fun archive() = RemoteZip(url, "Basic test", temp.root, "test")
        try {
            val names = archive().read(true) { zip -> zip.entries.asSequence().map { it.name }.toList() }
            assertEquals(pages.size, names.size)
            if (ranges) assertTrue("index requested ${requests.get()} ranges", requests.get() <= 3)
            if (!ranges) server.stop(0)
            val bytes = archive().read(false) { zip -> zip.getInputStream(zip.getEntry(names[2])).use { it.readBytes() } }
            assertArrayEquals(pages[2], bytes)
            if (ranges) {
                assertTrue(transferred.get() < data.size)
                RemoteZip(url, "Basic test", temp.root, "test", reuse = true).use { session ->
                    fun page(i: Int) = session.read(false) { zip ->
                        zip.getInputStream(zip.getEntry(names[i])).use { it.readBytes() }
                    }
                    assertArrayEquals(pages[2], page(2))
                    val afterFirst = requests.get()
                    assertArrayEquals(pages[3], page(3))
                    assertArrayEquals(pages[2], page(2))
                    assertEquals("Adjacent entries in a cached range should need no HTTP requests", afterFirst, requests.get())
                }
            } else {
                assertEquals(data.size.toLong(), transferred.get())
                val cached = temp.root.listFiles()!!.single()
                val partial = java.io.File(temp.root, cached.name + "-pending.part").apply { writeText("partial") }
                val otherBook = temp.newFile("dav-other-book")
                val original = temp.newFile("original.cbz")
                RemoteZip.discard(temp.root, "test")
                assertFalse(cached.exists())
                assertFalse(partial.exists())
                assertTrue(otherBook.isFile)
                assertTrue(original.isFile)
                RemoteZip.discard(temp.root, "test") // safe to retry an interrupted batch
            }
        } finally { if (ranges) server.stop(0) }
    }
}
