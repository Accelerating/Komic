package com.komic.reader

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ArchiveReadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val samples = listOf("sample.cbr", "sample-rar5.rar", "sample.cb7")

    private fun checkPages(source: NativeComic) {
        assertEquals(listOf("2.png", "10.png"), source.pages())
        for (width in listOf(2, 10, 2)) {
            assertEquals(width, source.image("$width.png", 320).bitmap.width)
        }
    }

    @Test fun localArchivePages() {
        for (sample in samples) {
            val file = File.createTempFile("comic-test-", sample, context.cacheDir)
            try {
                instrumentation.context.assets.open(sample).use { input -> file.outputStream().use { input.copyTo(it) } }
                context.localArchive(Uri.fromFile(file)).use(::checkPages)
            } finally { file.delete() }
        }
    }

    @Test fun remoteArchiveReopensFromCache() {
        for (sample in samples) {
            val bytes = instrumentation.context.assets.open(sample).use { it.readBytes() }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val url = "http://127.0.0.1:${server.localPort}/$sample".toHttpUrl()
                val remote = RemoteZip(url, null, context.cacheDir, url.toString())
                val responder = thread {
                    server.accept().use { socket ->
                        val request = socket.getInputStream().bufferedReader()
                        while (!request.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                        }
                    }
                }
                try {
                    assertEquals(listOf("2.png", "10.png"), remote.indexNative(context) { _, _ -> })
                    responder.join()
                    server.close()
                    remote.nativeReader(context).use(::checkPages)
                } finally { RemoteZip.discard(context.cacheDir, url.toString()) }
            }
        }
    }
}
