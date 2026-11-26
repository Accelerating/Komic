package com.komic.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/** Library covers survive process restarts independently of downloaded archive data. */
internal object CoverCache {
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(comic: Comic): String = MessageDigest.getInstance("SHA-256")
        .digest((comic.id + "\u0000" + comic.cover).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun file(context: Context, key: String) = File(File(context.filesDir, "covers"), "$key.jpg")
    fun cached(key: String): Bitmap? = memory.get(key)
    @Synchronized
    fun discard(context: Context, comic: Comic) {
        val key = key(comic)
        memory.remove(key)
        val target = file(context, key)
        Files.deleteIfExists(target.toPath())
        target.parentFile?.listFiles { _, name -> name.startsWith("$key-") && name.endsWith(".part") }
            ?.forEach { Files.deleteIfExists(it.toPath()) }
    }

    @Synchronized
    fun read(context: Context, key: String): Bitmap? {
        cached(key)?.let { return it }
        val file = file(context, key)
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap != null) memory.put(key, bitmap) else file.delete()
        return bitmap
    }

    @Synchronized
    fun ensure(context: Context, key: String, load: () -> Bitmap) {
        val target = file(context, key)
        if (target.isFile) return
        try {
            val source = load()
            val scale = minOf(1f, 512f / maxOf(source.width, source.height))
            val thumb = Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1), true)
            val dir = requireNotNull(target.parentFile)
            dir.mkdirs()
            val temp = File.createTempFile("$key-", ".part", dir)
            try {
                temp.outputStream().use { if (!thumb.compress(Bitmap.CompressFormat.JPEG, 88, it)) return }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                memory.put(key, thumb)
            } finally { temp.delete() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Cover generation is optional. Retry next time the book opens. */ }
    }
}
