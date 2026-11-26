package com.komic.reader

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class DavItem(val name: String, val url: HttpUrl, val folder: Boolean) {
    val nativeArchive get() = usesLibarchive(name) || usesLibarchive(url.pathSegments.last())
    val isComic get() = nativeArchive || listOf(name, url.pathSegments.last()).any {
        it.endsWith(".zip", true) || it.endsWith(".cbz", true)
    }
}
internal val davClient = OkHttpClient.Builder().followRedirects(false).build()

internal fun directoryUrl(url: HttpUrl): HttpUrl =
    if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()

internal fun directoryCall(url: HttpUrl, auth: String?): Call {
    val body = """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>"""
    val request = Request.Builder().url(url).header("Depth", "1")
        .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
    if (auth != null) request.header("Authorization", auth)
    return davClient.newCall(request.build())
}

internal fun readListing(base: HttpUrl, input: InputStream): List<DavItem> {
    val result = linkedMapOf<String, DavItem>()
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    var href = ""
    var name = ""
    var folder = false
    var propName = ""
    var propFolder = false
    var status = ""
    var multistatus = false
    val text = StringBuilder()
    val handler = object : DefaultHandler() {
        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        override fun resolveEntity(publicId: String?, systemId: String?) = throw LibraryError(R.string.external_entity)
        override fun startElement(uri: String, local: String, q: String, attrs: Attributes) {
            text.setLength(0)
            if (uri != "DAV:") return
            when (local) {
                "multistatus" -> multistatus = true
                "response" -> { href = ""; name = ""; folder = false }
                "propstat" -> { propName = ""; propFolder = false; status = "" }
                "collection" -> propFolder = true
            }
        }
        override fun endElement(uri: String, local: String, q: String) {
            if (uri != "DAV:") return
            when (local) {
                "href" -> href = text.toString().trim()
                "displayname" -> propName = text.toString().trim()
                "status" -> status = text.toString().trim()
                "propstat" -> if (status.split(' ').getOrNull(1)?.startsWith("2") == true) {
                    if (propName.isNotEmpty()) name = propName
                    folder = folder || propFolder
                }
                "response" -> {
                    val url = base.resolve(href) ?: return
                    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                        url.username.isNotEmpty() || url.password.isNotEmpty()) return
                    val parent = base.encodedPath.trimEnd('/') + "/"
                    if (!url.encodedPath.startsWith(parent)) return
                    val child = url.encodedPath.removePrefix(parent).trimEnd('/')
                    if (child.isEmpty() || '/' in child) return
                    val label = name.ifEmpty { url.pathSegments.last { it.isNotEmpty() } }
                    result[child] = DavItem(label, if (folder) directoryUrl(url) else url, folder)
                }
            }
        }
    }
    factory.newSAXParser().parse(input, handler)
    if (!multistatus) throw LibraryError(R.string.invalid_listing)
    return result.values.sortedWith(compareByDescending<DavItem> { it.folder }.thenBy { it.name })
}
