package com.komic.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException

internal fun usesLibarchive(name: String) =
    name.substringAfterLast('.', "").lowercase() in setOf("rar", "cbr", "7z", "cb7")

/** Keep the sequential decoder between adjacent pages, including solid compression blocks. */
internal class NativeComic(private val context: Context, private val open: () -> ParcelFileDescriptor) : Closeable {
    private var handle = 0L
    private var fd: ParcelFileDescriptor? = null
    private var copy: File? = null

    private fun restart() {
        closeReader()
        try {
            val descriptor = copy?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) } ?: open()
            fd = descriptor
            try {
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESPIPE) throw e
                val temp = File.createTempFile("comic-source-", ".part", context.cacheDir)
                copy = temp
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                }
                fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            handle = Archive.readNew()
            Archive.setCharset(handle, "UTF-8".toByteArray())
            Archive.readSupportFormatRar(handle)
            Archive.readSupportFormatRar5(handle)
            Archive.readSupportFormat7zip(handle)
            Archive.readOpenFd(handle, requireNotNull(fd).fd, 65536)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun nextImage(): String? {
        while (true) {
            val entry = Archive.readNextHeader(handle)
            if (entry == 0L) return null
            if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG) continue
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: continue
            if (!isImage(name) || name.startsWith("__MACOSX/")) continue
            if (ArchiveEntry.isEncrypted(entry)) throw LibraryError(R.string.invalid_archive)
            return name
        }
    }

    fun pages(): List<String> {
        restart()
        val sort = pageOrder
        return try { generateSequence(::nextImage).toList().sortedWith { a, b -> sort.compare(a, b) } }
        finally { closeReader() }
    }

    fun image(page: String, target: Int): PageBitmap {
        val resume = handle != 0L
        if (!resume) restart()
        fun find(): Boolean {
            while (true) {
                val name = nextImage() ?: return false
                if (name == page) return true
            }
        }
        if (!find()) {
            if (!resume) throw FileNotFoundException(page)
            restart()
            if (!find()) throw FileNotFoundException(page)
        }
        // Never use archive entry paths as filesystem paths.
        val temp = File.createTempFile("comic-page-", ".part", context.cacheDir)
        try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_WRITE_ONLY).use {
                Archive.readDataIntoFd(handle, it.fd)
            }
            return decode(target) { temp.inputStream() }
        } finally { temp.delete() }
    }

    private fun closeReader() {
        try { if (handle != 0L) Archive.free(handle) }
        finally { handle = 0; fd?.close(); fd = null }
    }

    override fun close() {
        try { closeReader() } finally { copy?.delete(); copy = null }
    }
}

internal fun Context.localArchive(uri: Uri) = NativeComic(this) {
    requireNotNull(contentResolver.openFileDescriptor(uri, "r"))
}
