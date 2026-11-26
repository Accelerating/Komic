package com.komic.reader

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.channels.NonWritableChannelException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val BLOCK = 128 * 1024

internal class RemoteZip(private val url: HttpUrl, private val auth: String?, cache: File, id: String, private val reuse: Boolean = false) : java.io.Closeable {
    private var retained: ZipFile? = null

    override fun close() { retained?.close(); retained = null }

    private fun <T> consume(zip: ZipFile, action: (ZipFile) -> T): T {
        if (!reuse) return zip.use(action)
        retained = zip
        return try { action(zip) } catch (e: Throwable) { close(); throw e }
    }
    private val file = archiveFile(cache, id)

    companion object {
        private fun archiveFile(cache: File, id: String) = File(cache, "dav-" +
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) })

        fun discard(cache: File, id: String) {
            val archive = archiveFile(cache, id)
            Files.deleteIfExists(archive.toPath())
            cache.listFiles { _, name -> name.startsWith(archive.name + "-") && name.endsWith(".part") }
                ?.forEach { Files.deleteIfExists(it.toPath()) }
        }
    }
    private fun get(range: String?, validator: String? = null): Response {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        auth?.let { request.header("Authorization", it) }
        range?.let { request.header("Range", it) }
        validator?.let { request.header("If-Range", it) }
        return davClient.newCall(request.build()).execute().also {
            if (!it.isSuccessful && !(range != null && it.code == 416)) {
                it.close()
                throw LibraryError(R.string.remote_failed, it.code)
            }
        }
    }

    fun <T> read(refresh: Boolean, progress: (Long, Long) -> Unit = { _, _ -> }, indexProgress: (Int) -> Unit = {}, action: (ZipFile) -> T): T {
        if (!refresh) {
            retained?.let { zip ->
                try { return action(zip) } catch (e: IOException) {
                    close()
                    if (!rangeUnavailable(e)) throw e
                }
            }
            if (file.isFile) return consume(ZipFile.builder().setFile(file).get(), action)
        }
        val started = System.nanoTime()
        var requests = 0
        val descriptor = get("bytes=0-0").use { response ->
            if (response.code == 200) return cacheFull(response, progress, action)
            val length = Regex("bytes 0-0/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()
            if (response.code != 206 || length == null || length <= 0) null else
                length to (response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified"))
        }
        if (descriptor != null) {
            val (length, validator) = descriptor
            val channel = RangeChannel(length, if (reuse) 1024 * 1024 else BLOCK) { start, end ->
                if (refresh) {
                    indexProgress(++requests)
                    if (requests > 1 && System.nanoTime() - started > 20_000_000_000L) throw RangeUnavailable()
                }
                get("bytes=$start-$end", validator).use { response ->
                    if (response.code != 206 || response.header("Content-Range") != "bytes $start-$end/$length") throw RangeUnavailable()
                    val data = ByteArray((end - start + 1).toInt())
                    java.io.DataInputStream(requireNotNull(response.body).byteStream()).readFully(data)
                    data
                }
            }
            try {
                val result = consume(ZipFile.builder()
                    // Central directory entries already contain the names. Resolve each data offset only when its page is opened.
                    .setIgnoreLocalFileHeader(true).setSeekableByteChannel(channel).get(), action)
                if (refresh) file.delete()
                return result
            } catch (e: IOException) {
                if (!rangeUnavailable(e)) throw e
            }
        }
        return get(null).use { cacheFull(it, progress, action) }
    }

    private fun native(context: android.content.Context, file: File) = NativeComic(context) {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun indexNative(context: android.content.Context, progress: (Long, Long) -> Unit): List<String> =
        get(null).use { response -> download(response, progress) { part ->
            native(context, part).use { it.pages() }.also {
                if (it.isEmpty()) throw LibraryError(R.string.no_images)
            }
        } }

    fun nativeReader(context: android.content.Context): NativeComic {
        if (!file.isFile) indexNative(context) { _, _ -> }
        return native(context, file)
    }

    private fun <T> cacheFull(response: Response, progress: (Long, Long) -> Unit, action: (ZipFile) -> T): T =
        download(response, progress) { ZipFile.builder().setFile(it).get().use(action) }

    private fun <T> download(response: Response, progress: (Long, Long) -> Unit, action: (File) -> T): T {
        if (response.code != 200) throw LibraryError(R.string.incomplete_response)
        val part = File.createTempFile(file.name + "-", ".part", file.parentFile)
        try {
            val body = requireNotNull(response.body)
            val total = body.contentLength()
            var bytes = 0L
            body.byteStream().use { input -> part.outputStream().use { out ->
                val buffer = ByteArray(BLOCK)
                while (true) {
                    progress(bytes, total)
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    bytes += count
                }
            } }
            if (total >= 0 && bytes != total) throw LibraryError(R.string.incomplete_download)
            val result = action(part)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return result
        } finally { part.delete() }
    }
}

private class RangeUnavailable : IOException()
private fun rangeUnavailable(e: IOException) = generateSequence<Throwable>(e) { it.cause }.any { it is RangeUnavailable }

private class RangeChannel(private val length: Long, private val blockSize: Int, val fetch: (Long, Long) -> ByteArray) : SeekableByteChannel {
    private var cursor = 0L
    // Keep recent ranges for image bounds, decode and adjacent pages.
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }
    private var opened = true
    override fun size() = length
    override fun isOpen() = opened
    override fun close() { opened = false; blocks.clear() }
    override fun position() = cursor
    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        cursor = newPosition
        return this
    }
    override fun read(dst: ByteBuffer): Int {
        if (!dst.hasRemaining()) return 0
        if (cursor >= length) return -1
        val start = cursor / blockSize * blockSize
        val block = blocks.getOrPut(start) { fetch(start, minOf(length - 1, start + blockSize - 1)) }
        val index = (cursor - start).toInt()
        val n = minOf(dst.remaining(), block.size - index)
        dst.put(block, index, n)
        cursor += n
        return n
    }
    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}
