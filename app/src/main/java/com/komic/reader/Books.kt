package com.komic.reader

import okhttp3.HttpUrl.Companion.toHttpUrl
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.io.InputStream

data class Comic(val id: String, val title: String, val pages: List<String>, val page: Int, val opened: Long, val pageFraction: Float = 0f) {
    val cover get() = pages.first()
}

fun LibraryDb.Book.comic(): Comic {
    val entries = JSONArray(pages)
    return Comic(id, title, List(entries.length()) { entries.getString(it) }, page, opened, pageFraction)
}

private fun book(source: String, name: String, entries: List<String>) = LibraryDb.Book().apply {
    id = source
    title = name
    pages = JSONArray(entries).toString()
}

private val suffixes = setOf("jpeg", "jpg", "png", "webp", "gif", "bmp")
internal fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in suffixes
internal val pageOrder get() = (android.icu.text.Collator.getInstance() as android.icu.text.RuleBasedCollator).apply {
    numericCollation = true
}

private class LocalZip(val zip: ZipFile, private val stream: InputStream? = null) : Closeable {
    override fun close() {
        try { zip.close() } finally { stream?.close() }
    }
}

private fun legacyZipCopy(context: Context, uri: Uri): File {
    val name = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(File(context.filesDir, "local-zips"), "$name.zip")
}

internal fun discardLegacyZip(context: Context, uri: Uri) {
    Files.deleteIfExists(legacyZipCopy(context, uri).toPath())
}

private fun openLocalZip(context: Context, uri: Uri, allowLegacy: Boolean = true): LocalZip {
    val copy = legacyZipCopy(context, uri)
    try {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(fd)
        val zip = try {
            ZipFile.builder().setSeekableByteChannel(stream.channel).get()
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
        copy.delete()
        return LocalZip(zip, stream)
    } catch (e: Exception) {
        if (!allowLegacy || !copy.isFile) throw e
        return LocalZip(ZipFile.builder().setFile(copy).get())
    }
}

internal fun localZipPages(context: Context, uri: Uri, allowLegacy: Boolean = true): List<String> =
    openLocalZip(context, uri, allowLegacy).use { source ->
        zipPages(source.zip).also { if (it.isEmpty()) throw LibraryError(R.string.no_images) }
    }

fun loadLocalBook(context: Context, uri: Uri, folder: Boolean): LibraryDb.Book {
    val resolver = context.contentResolver
    val root = if (folder) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    val title = resolver.query(root, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: context.getString(R.string.local_comic)
    val sort = pageOrder
    val pages = if (folder) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val images = mutableListOf<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(2).startsWith("image/") || isImage(it.getString(1))) {
                    images += it.getString(1) to DocumentsContract.buildDocumentUriUsingTree(uri, it.getString(0)).toString()
                }
            }
        }
        images.sortedWith { a, b -> sort.compare(a.first, b.first) }.map { it.second }
    } else if (usesLibarchive(title)) context.localArchive(uri).use { it.pages() }
    else localZipPages(context, uri, allowLegacy = false)
    if (pages.isEmpty()) throw LibraryError(R.string.no_images)
    val prefix = when { folder -> "folder:"; usesLibarchive(title) -> "archive:"; else -> "zip:" }
    return book(prefix + uri, if (folder) title else title.substringBeforeLast('.', title), pages)
}

internal data class PageBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

internal fun decode(target: Int, open: () -> InputStream): PageBitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, options) }
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outHeight, options.outWidth) / options.inSampleSize > target * 2) options.inSampleSize *= 2
    val width = options.outWidth
    val height = options.outHeight
    val bitmap = requireNotNull(open().use { BitmapFactory.decodeStream(it, null, options) })
    return PageBitmap(bitmap, width, height)
}

internal fun ensureLocalCover(context: Context, comic: Comic) {
    if (comic.id.startsWith("archive:")) {
        CoverCache.ensure(context, CoverCache.key(comic)) {
            context.localArchive(Uri.parse(comic.id.substringAfter(':'))).use { it.image(comic.cover, 320).bitmap }
        }
        return
    }
    if (!comic.id.startsWith("zip:") && !comic.id.startsWith("folder:")) return
    val resolver = context.contentResolver
    CoverCache.ensure(context, CoverCache.key(comic)) {
        if (comic.id.startsWith("zip:")) openLocalZip(context, Uri.parse(comic.id.removePrefix("zip:"))).use { source ->
            decodeCover(source.zip, comic.cover)
        } else decode(320) { requireNotNull(resolver.openInputStream(Uri.parse(comic.cover))) }.bitmap
    }
}

private fun decodeCover(zip: ZipFile, page: String): Bitmap {
    val entry = requireNotNull(zip.getEntry(page))
    return decode(320) { zip.getInputStream(entry) }.bitmap
}

/** One reader owns the ZIP handle and a byte-limited cache. The mutex also coalesces
 * a swipe to a page whose prefetch is already running. */
internal class ReaderPages(private val context: android.content.Context, private val comic: Comic) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var local: LocalZip? = null
    private var archive: RemoteZip? = null
    private var native: NativeComic? = null
    private val coverKey = CoverCache.key(comic)
    private var checkedCover = false
    private var closed = false
    private val cache = object : android.util.LruCache<String, PageBitmap>(
        (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(80L * 1024 * 1024).toInt()
    ) { override fun sizeOf(key: String, value: PageBitmap) = value.bitmap.allocationByteCount }

    fun cached(page: String) = cache.get(page)

    suspend fun load(page: String): PageBitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closed)
            cached(page) ?: run {
                if (comic.id.startsWith("archive:") || comic.id.startsWith("dav-archive:")) {
                    val source = native ?: (if (comic.id.startsWith("archive:"))
                        context.localArchive(Uri.parse(comic.id.substringAfter(':')))
                    else remoteArchive(context, comic.id).nativeReader(context)).also { native = it }
                    val decoded = source.image(page, 2400)
                    if (!checkedCover) {
                        CoverCache.ensure(context, coverKey) {
                            if (page == comic.cover) decoded.bitmap else source.image(comic.cover, 320).bitmap
                        }
                        checkedCover = true
                    }
                    cache.put(page, decoded)
                    return@withLock decoded
                }
                val job = coroutineContext
                val readPage = { zip: ZipFile ->
                    job.ensureActive()
                    val entry = requireNotNull(zip.getEntry(page))
                    val decoded = decode(2400) { zip.getInputStream(entry) }
                    if (!checkedCover) {
                        CoverCache.ensure(context, coverKey) {
                            job.ensureActive()
                            if (page == comic.cover) decoded.bitmap else decodeCover(zip, comic.cover)
                        }
                        checkedCover = true
                    }
                    decoded
                }
                val decoded = if (comic.id.startsWith("zip:")) {
                    val uri = Uri.parse(comic.id.removePrefix("zip:"))
                    fun readLocal(): PageBitmap {
                        val source = local ?: openLocalZip(context, uri).also { local = it }
                        return readPage(source.zip)
                    }
                    try { readLocal() } catch (e: IOException) {
                        local?.close()
                        local = null
                        job.ensureActive()
                        readLocal()
                    }
                } else {
                    val source = archive ?: remoteArchive(context, comic.id, reuse = true).also { archive = it }
                    source.read(false, action = readPage)
                }
                cache.put(page, decoded)
                decoded
            }
        }
    }

    suspend fun close() = lock.withLock {
        closed = true
        local?.close()
        native?.close()
        archive?.close()
        cache.evictAll()
    }
}

@Composable
internal fun BookArt(comic: Comic, key: String, modifier: Modifier, scale: ContentScale, thumbnail: Boolean = false, session: ReaderPages? = null, preloadNext: Boolean = false, continuous: Boolean = false, loadedModifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (thumbnail) {
        val coverKey = remember(comic.id, key) { CoverCache.key(comic) }
        val cover by produceState(CoverCache.cached(coverKey), coverKey) {
            value = withContext(Dispatchers.IO) { CoverCache.read(context, coverKey) }
        }
        val image = cover
        if (image != null) Image(image.asImageBitmap(), comic.title, modifier, contentScale = scale)
        else Box(modifier.background(Paper), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MenuBook, comic.title, tint = Wine)
        }
        return
    }
    val resolver = context.contentResolver
    val result by produceState<Result<PageBitmap>?>(session?.cached(key)?.let { Result.success(it) }, comic.id, key, thumbnail, session) {
        if (session != null) {
            try { value = Result.success(session.load(key)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { value = Result.failure(e) }
            return@produceState
        }
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val size = 2400
                val readPage = { zip: ZipFile ->
                    val entry = requireNotNull(zip.getEntry(key))
                    decode(size) { zip.getInputStream(entry) }
                }
                Result.success(if (comic.id.startsWith("dav:")) remoteArchive(context, comic.id).read(false, action = readPage)
                else if (comic.id.startsWith("zip:")) openLocalZip(context, Uri.parse(comic.id.removePrefix("zip:"))).use { readPage(it.zip) }
                else decode(size) { requireNotNull(resolver.openInputStream(Uri.parse(key))) })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        }
    }
    val bitmap = result?.getOrNull()
    LaunchedEffect(session, key, bitmap, preloadNext) {
        if (session != null && bitmap != null && preloadNext) {
            val next = comic.pages.getOrNull(comic.pages.indexOf(key) + 1)
            if (next != null) try { session.load(next) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A visible page retries through the normal load path. */ }
        }
    }
    if (bitmap != null) DisplayPage(BitmapPainter(bitmap.bitmap.asImageBitmap()), comic.title, modifier.then(loadedModifier), scale, bitmap.width.toFloat(), bitmap.height.toFloat(), continuous, thumbnail)
    else Box(modifier.then(if (continuous) Modifier.aspectRatio(.70f) else Modifier).background(Paper), contentAlignment = Alignment.Center) {
        if (result == null) CircularProgressIndicator(Modifier.size(24.dp)) else Text(context.getString(R.string.image_failed), color = Wine)
    }
}

@Composable
private fun DisplayPage(painter: Painter, title: String, modifier: Modifier, scale: ContentScale, width: Float, height: Float, continuous: Boolean, thumbnail: Boolean) {
    if (thumbnail) {
        Image(painter, title, modifier, contentScale = scale)
        return
    }
    val frame = if (continuous) modifier.fillMaxWidth().aspectRatio(width / height) else modifier
    PageImage(painter, title, frame, if (continuous) ContentScale.Fit else scale, width, height)
}

@Composable
private fun PageImage(painter: Painter, title: String, modifier: Modifier, scale: ContentScale, width: Float, height: Float) {
    if (scale != ContentScale.None && scale != ContentScale.FillWidth) {
        Image(painter, title, modifier, contentScale = scale)
        return
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val pageWidth = if (scale == ContentScale.FillWidth) maxWidth else with(density) { width.toDp() }
        val pageHeight = pageWidth * (height / width)
        val document = Modifier.then(if (pageWidth > maxWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .then(if (pageHeight > maxHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        Image(painter, title, document.size(pageWidth, pageHeight), contentScale = ContentScale.Fit)
    }
}

internal fun remoteArchive(context: android.content.Context, id: String, reuse: Boolean = false): RemoteZip {
    val serverId = id.substringAfter(':').substringBefore('|')
    val url = id.substringAfter('|').toHttpUrl()
    val server = servers(context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)).find { it.id == serverId }
        ?: throw java.io.IOException(context.getString(R.string.server_removed))
    val root = directoryUrl(server.address.toHttpUrl())
    require(url.scheme == root.scheme && url.host == root.host && url.port == root.port &&
        url.encodedPath.startsWith(root.encodedPath) && url.username.isEmpty() && url.password.isEmpty()) { context.getString(R.string.address_changed) }
    return RemoteZip(url, DavCredentials(context).load(server), context.cacheDir, id, reuse)
}

private fun zipPages(archive: ZipFile): List<String> {
    val sort = pageOrder
    return archive.entries.asSequence().filter { !it.isDirectory && isImage(it.name) && !it.name.startsWith("__MACOSX/") }
        .map { it.name }.sortedWith(sort).toList()
}
internal fun remoteBook(context: android.content.Context, id: String, name: String, indexProgress: (Int) -> Unit, progress: (Long, Long) -> Unit): LibraryDb.Book {
    val source = remoteArchive(context, id)
    fun indexedBook(pages: List<String>, loadCover: (String) -> Bitmap): LibraryDb.Book {
        if (pages.isEmpty()) throw LibraryError(R.string.no_images)
        val entry = book(id, name.substringBeforeLast('.', name), pages)
        val comic = entry.comic()
        CoverCache.ensure(context, CoverCache.key(comic)) { loadCover(comic.cover) }
        return entry
    }
    return if (id.startsWith("dav-archive:")) {
        val pages = source.indexNative(context, progress)
        indexedBook(pages) { page -> source.nativeReader(context).use { it.image(page, 320).bitmap } }
    } else source.read(true, progress, indexProgress) { zip ->
        indexedBook(zipPages(zip)) { page -> decodeCover(zip, page) }
    }
}
