package com.komic.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import java.io.IOException

@Composable
internal fun RemoteDirectory(server: Server, openComic: (Server, DavItem) -> Unit, importComics: (Server, List<DavItem>) -> Unit, importing: Boolean, exit: () -> Unit) {
    val root = remember(server.address) { directoryUrl(server.address.toHttpUrl()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val locations = remember(context) { context.getSharedPreferences("davImportLocations", android.content.Context.MODE_PRIVATE) }
    val locationKey = "${server.id}|${root}"
    var path by rememberSaveable(locationKey) {
        val saved = locations.getString(locationKey, null)?.let { org.json.JSONArray(it) }
        mutableStateOf(if (saved == null) arrayListOf(root.toString())
            else ArrayList((0 until saved.length()).map { saved.getString(it) }))
    }
    fun rememberImportDirectory() {
        locations.edit().putString(locationKey, org.json.JSONArray(path).toString()).apply()
    }
    val url = remember(path) { path.last().toHttpUrl() }
    var selecting by remember(url) { mutableStateOf(false) }
    var selected by remember(url) { mutableStateOf(setOf<String>()) }
    var attempt by remember { mutableIntStateOf(0) }
    val credentials = remember(context) { DavCredentials(context) }
    var auth by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf(false) }
    var error by remember(url, attempt) { mutableStateOf<String?>(null) }
    var items by remember(url, attempt) { mutableStateOf<List<DavItem>?>(null) }
    var call by remember { mutableStateOf<okhttp3.Call?>(null) }
    DisposableEffect(url, attempt) { onDispose { call?.cancel() } }
    LaunchedEffect(url, attempt) {
        try {
            val submitted = auth
            val authorization = submitted ?: withContext(Dispatchers.IO) { credentials.load(server) }
            val request = directoryCall(url, authorization)
            call = request
            items = withContext(Dispatchers.IO) {
                request.execute().use { response ->
                    when (response.code) {
                        401 -> throw LoginRequired()
                        403 -> throw IOException(context.getString(R.string.forbidden))
                        404, 410 -> throw MissingDirectory()
                        in 300..399 -> throw IOException(context.getString(R.string.redirect))
                    }
                    if (!response.isSuccessful) throw IOException(context.getString(R.string.http_code, response.code))
                    readListing(url, requireNotNull(response.body).byteStream())
                }
            }
            if (submitted != null) {
                withContext(Dispatchers.IO) { credentials.save(server, submitted) }
                auth = null
            }
        } catch (e: CancellationException) { throw e }
        catch (e: MissingDirectory) {
            if (path.size > 1 && locations.getString(locationKey, null) == org.json.JSONArray(path).toString()) {
                locations.edit().remove(locationKey).apply()
                path = arrayListOf(root.toString())
            } else error = context.getString(R.string.missing_directory)
        }
        catch (e: LoginRequired) { credentials.forget(server); auth = null; error = context.getString(R.string.login_needed); login = true }
        catch (e: Exception) { error = context.getString(R.string.list_failed, if (e is LibraryError) e.describe(context) else e.localizedMessage.orEmpty()) }
    }
    fun up() { if (path.size == 1) exit() else path = ArrayList(path.dropLast(1)) }
    BackHandler { if (selecting) { selecting = false; selected = emptySet() } else up() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { up() }) { Icon(Icons.Outlined.ArrowBack, context.getString(R.string.parent_directory)) }
            Column(Modifier.weight(1f)) {
                Text(server.name, fontFamily = Editorial)
                Text(url.encodedPath, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { login = true }) { Icon(Icons.Outlined.AccountCircle, context.getString(R.string.login)) }
            IconButton(onClick = { attempt++ }) { Icon(Icons.Outlined.Refresh, context.getString(R.string.refresh_folder)) }
        }
        HorizontalDivider()
        if (items != null && items.orEmpty().any { it.isComic }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !importing, onClick = {
                    selecting = !selecting
                    selected = emptySet()
                }) { Text(context.getString(if (selecting) R.string.cancel else R.string.select_comics)) }
                if (selecting) {
                    TextButton(enabled = !importing, onClick = {
                        val comics = items.orEmpty().filter { it.isComic }.map { it.url.toString() }.toSet()
                        selected = if (selected.containsAll(comics)) emptySet() else comics
                    }) { Text(context.getString(R.string.select_all)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = selected.isNotEmpty() && !importing, onClick = {
                        rememberImportDirectory()
                        importComics(server, items.orEmpty().filter { it.url.toString() in selected })
                        selecting = false
                        selected = emptySet()
                    }) { Text(context.getString(R.string.import_selected, selected.size)) }
                }
            }
        }
        if (error != null) {
            Text(error.orEmpty(), Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { attempt++ }) { Text(context.getString(R.string.retry)) }
        } else if (items == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (items!!.isEmpty()) Text(context.getString(R.string.empty_folder), Modifier.padding(16.dp))
        else key(url) { LazyColumn { items(items.orEmpty(), key = { it.url.toString() }) { item ->
            Row(Modifier.fillMaxWidth().clickable(enabled = item.folder || item.isComic) {
                if (item.folder) path = ArrayList(path + item.url.toString())
                else if (selecting) {
                    val id = item.url.toString()
                    selected = if (id in selected) selected - id else selected + id
                } else if (!importing) { rememberImportDirectory(); openComic(server, item) }
            }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (item.folder) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = Wine)
                Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp))
                if (item.folder) Icon(Icons.Outlined.ChevronRight, context.getString(R.string.enter_folder))
                else if (selecting && item.isComic) Checkbox(item.url.toString() in selected, onCheckedChange = null)
            }
        } } }
    }
    if (login) {
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { login = false }, title = { Text(context.getString(R.string.login_title)) },
            text = { Column {
                Text(server.address)
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(context.getString(R.string.username)) })
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(context.getString(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation())
            } }, confirmButton = { TextButton(enabled = user.isNotBlank(), onClick = {
                auth = Credentials.basic(user, password, Charsets.UTF_8); login = false; attempt++
            }) { Text(context.getString(R.string.login)) } }, dismissButton = { TextButton(onClick = { login = false }) { Text(context.getString(R.string.cancel)) } })
    }
}

private class LoginRequired : IOException()

private class MissingDirectory : IOException()
