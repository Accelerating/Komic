package com.komic.reader

import android.content.Context
import android.provider.DocumentsContract
import android.net.Uri
import android.content.Intent
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Observer
import androidx.room.Room
import kotlinx.coroutines.*

private val Day = lightColorScheme(primary = Color(0xFF782E36), background = Color(0xFFFBF6ED),
    surface = Color(0xFFFBF6ED), onSurface = Color(0xFF302921), outlineVariant = Color(0xFFDAD1C5))
private val Night = darkColorScheme(primary = Color(0xFFE9A8AF), background = Color(0xFF211E20),
    surface = Color(0xFF211E20), onSurface = Color(0xFFF2E8E2), outlineVariant = Color(0xFF554B4B))
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Wine: Color @Composable get() = MaterialTheme.colorScheme.primary
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val Editorial = FontFamily.Serif


class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val locales = newBase.resources.configuration.locales
        val tag = languageTag(newBase)
        val primary = if (tag.isNotEmpty()) Locale.forLanguageTag(tag) else locales.get(0)
        val override = android.content.res.Configuration().apply { setLocale(supportedLocale(primary)) }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private fun languageTag(context: Context): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.let {
                supportedLocale(it).toLanguageTag()
            }.orEmpty()
        } else context.getSharedPreferences("appearance", MODE_PRIVATE).getString("language", "").orEmpty()
    }

    fun currentLanguage() = languageTag(this)

    fun changeLanguage(tag: String) {
        if (tag == currentLanguage()) return
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("language", tag).apply()
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = database(applicationContext)
        val prefs = getSharedPreferences("appearance", Context.MODE_PRIVATE)
        setContent {
            var theme by remember { mutableStateOf(prefs.getString("theme", "浅色") ?: "浅色") }
            val dark = theme == "深色" || theme == "跟随系统" && isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) Night else Day) {
                CompositionLocalProvider(LocalContentColor provides Ink) {
                    Komic(this, db.shelf(), theme) {
                        theme = it
                        prefs.edit().putString("theme", it).apply()
                    }
                }
            }
        }
    }

    companion object {
        private var db: LibraryDb? = null
        private fun database(context: Context): LibraryDb = db ?: Room.databaseBuilder(context, LibraryDb::class.java, "komic.db")
            .addMigrations(LibraryDb.POSITION_MIGRATION, LibraryDb.REMOVE_SAMPLES).build().also { db = it }
    }
}

@Composable
private fun Komic(activity: MainActivity, shelf: LibraryDb.Shelf, menuTheme: String, onTheme: (String) -> Unit) {
    var updateRequest by remember { mutableIntStateOf(0) }
    UpdatePrompt(activity, updateRequest)
    val prefs = remember { activity.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val tabs = listOf(R.string.shelf, R.string.recent, R.string.settings).map(activity::getString)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    var books by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var importTotal by remember { mutableIntStateOf(0) }
    var importDone by remember { mutableIntStateOf(0) }
    var importTitle by remember { mutableStateOf("") }
    val importing = importTotal > 0
    var importMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notices = remember { SnackbarHostState() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, shelf) {
        val data = shelf.observe()
        val observer = Observer<List<LibraryDb.Book>> { books = it.orEmpty().map { row -> row.comic() } }
        data.observe(owner, observer)
        onDispose { data.removeObserver(observer) }
    }
    var selected by rememberSaveable(tab) { mutableStateOf(arrayListOf<String>()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun toggle(id: String) {
        selected = ArrayList(if (id in selected) selected - id else selected + id)
    }
    if (confirming && selected.isNotEmpty()) AlertDialog(
        onDismissRequest = { if (!deleting) confirming = false },
        title = { Text(activity.getString(R.string.delete_confirm, selected.size)) },
        text = { Text(activity.getString(R.string.delete_detail)) },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                val batch = books.filter { it.id in selected }
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        batch.filter { comic ->
                            try {
                                removeFromLibrary(activity, shelf, comic)
                                false
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { true }
                        }
                    }
                    selected = ArrayList(failed.map { it.id })
                    deleting = false
                    confirming = false
                    notices.showSnackbar(if (failed.isEmpty()) activity.getString(R.string.deleted_count, batch.size) else activity.getString(R.string.delete_failed, failed.size))
                }
            }) { Text(if (deleting) activity.getString(R.string.deleting) else activity.getString(R.string.delete)) }
        }, dismissButton = { TextButton(enabled = !deleting, onClick = { confirming = false }) { Text(activity.getString(R.string.cancel)) } }
    )
    fun import(uris: List<android.net.Uri>, folder: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            val files = uris.distinct()
            importDone = 0
            importTitle = ""
            importTotal = files.size
            val errors = withContext(Dispatchers.IO) {
                files.mapNotNull { uri ->
                    val name = runCatching {
                        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull()
                    importTitle = name.orEmpty()
                    try {
                        activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val book = loadLocalBook(activity, uri, folder)
                        ensureLocalCover(activity, book.comic())
                        shelf.add(book)
                        val directory = when {
                            folder -> DocumentsContract.buildDocumentUri(uri.authority,
                                DocumentsContract.getTreeDocumentId(uri))
                            uri.authority == "com.android.externalstorage.documents" -> {
                                val id = DocumentsContract.getDocumentId(uri)
                                val parent = id.substringBeforeLast('/', id.substringBefore(':') + ":")
                                DocumentsContract.buildDocumentUri(uri.authority, parent)
                            }
                            else -> uri // The picker can resolve a document to its containing directory.
                        }
                        prefs.edit().putString("localImportLocation", directory.toString()).apply()
                        null
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (e is LibraryError) e.describe(activity) else activity.getString(R.string.invalid_archive)
                    } finally { importDone++ }
                }
            }
            importTotal = 0
            if (errors.size < uris.distinct().size) tab = 0
            notices.showSnackbar(if (uris.size == 1) errors.firstOrNull() ?: activity.getString(R.string.added)
                else activity.getString(R.string.import_result, uris.distinct().size - errors.size, errors.size))
        }
    }
    fun localImportLocation(): Uri = prefs.getString("localImportLocation", null)?.let(Uri::parse)
        ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> import(listOfNotNull(uri), true) }
    val archivePicker = remember {
        object : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, localImportLocation())
                }
        }
    }
    val filePicker = rememberLauncherForActivityResult(archivePicker) { uris -> import(uris, false) }
    var settingsVisit by rememberSaveable { mutableIntStateOf(0) }
    // Keep the directory when closing a comic, but only within this visit to Settings.
    val settingsState = if (tab == 2) key(settingsVisit) {
        androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    } else null
    var opening by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteTitle by rememberSaveable { mutableStateOf("") }
    opening?.let { id ->
        var status by remember(id) { mutableStateOf(activity.getString(R.string.reading_index)) }
        var problem by remember(id) { mutableStateOf<String?>(null) }
        var attempt by remember(id) { mutableIntStateOf(0) }
        LaunchedEffect(id, attempt) {
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    val book = remoteBook(activity, id, remoteTitle, indexProgress = { count ->
                        job.ensureActive()
                        status = activity.getString(R.string.index_part, count)
                    }) { bytes, total ->
                        job.ensureActive()
                        status = if (total > 0) activity.getString(R.string.cache_percent, bytes * 100 / total) else activity.getString(R.string.cache_kb, bytes / 1024)
                    }
                    job.ensureActive()
                    shelf.indexRemote(book, org.json.JSONArray(book.pages).length() - 1)
                }
                active = id
                opening = null
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                problem = if (e is LibraryError) e.describe(activity) else e.localizedMessage ?: activity.getString(R.string.open_failed)
            }
        }
        AlertDialog(onDismissRequest = { opening = null }, title = { Text(remoteTitle) },
            text = { Text(problem ?: status) }, confirmButton = {
                if (problem != null) TextButton(onClick = { problem = null; attempt++ }) { Text(activity.getString(R.string.retry)) }
            }, dismissButton = { TextButton(onClick = { opening = null }) { Text(activity.getString(R.string.cancel)) } })
    }
    val current = books.find { it.id == active }
    if (current != null) {
        key(current.id) {
            LaunchedEffect(current.id) { withContext(Dispatchers.IO) { ensureLocalCover(activity, current) } }
            Reader(activity, current, menuTheme, prefs, onPage = { index, fraction ->
                // Queue progress writes in order, including a final page change just before leaving.
                LibraryWrites.save(shelf, current.id, index, fraction)
            }, onExit = { active = null })
        }
        return
    }
    BackHandler(tab != 0 || selected.isNotEmpty()) { if (selected.isNotEmpty()) selected = arrayListOf() else tab = 0 }
    val gridStates = listOf(rememberLazyGridState(), rememberLazyGridState())
    Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
        Column {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) IconButton(onClick = { selected = arrayListOf() }) {
                    Icon(Icons.Outlined.Close, activity.getString(R.string.end_selection), tint = Wine)
                }
                Text(if (selected.isEmpty()) tabs[tab] else activity.getString(R.string.selected_count, selected.size), Modifier.weight(1f), color = Wine, fontFamily = Editorial, fontWeight = FontWeight.Bold, fontSize = 29.sp)
                if (selected.isNotEmpty()) IconButton(onClick = { confirming = true }) {
                    Icon(Icons.Outlined.Delete, activity.getString(R.string.delete_selected), tint = Wine)
                } else if (tab != 2) IconButton(onClick = { importMenu = true }, enabled = !importing) {
                    Icon(Icons.Outlined.Add, activity.getString(R.string.add_comic), tint = Wine, modifier = Modifier.size(27.dp))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Wine.copy(alpha = .65f))
            if (importing) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(activity.getString(R.string.import_progress, importDone, importTotal) + "  " + importTitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { if (importTotal > 0) importDone.toFloat() / importTotal else 1f },
                    modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f)) {
                if (settingsState != null) settingsState.SaveableStateProvider("settings") {
                    Settings(prefs, menuTheme, onTheme, activity.currentLanguage(), activity::changeLanguage,
                        checkUpdates = { updateRequest++ }, openComic = { server, item ->
                            remoteTitle = item.name
                            val source = if (item.nativeArchive) "dav-archive" else "dav"
                            opening = "$source:${server.id}|${item.url}"
                        }, importComics = { server, items ->
                            scope.launch {
                                importDone = 0
                                importTitle = ""
                                importTotal = items.size
                                val failed = withContext(Dispatchers.IO) {
                                    items.count { item ->
                                        importTitle = item.name
                                        try {
                                            val source = if (item.nativeArchive) "dav-archive" else "dav"
                                            val id = "$source:${server.id}|${item.url}"
                                            val book = remoteBook(activity, id, item.name, indexProgress = {}, progress = { _, _ -> })
                                            shelf.indexRemote(book, org.json.JSONArray(book.pages).length() - 1)
                                            false
                                        } catch (e: CancellationException) { throw e } catch (_: Exception) { true } finally { importDone++ }
                                    }
                                }
                                importTotal = 0
                                notices.showSnackbar(activity.getString(R.string.import_result, items.size - failed, failed))
                            }
                        }, importing = importing)
                }
                else {
                    val visible = if (tab == 0) books else books.filter { it.opened > 0 }.sortedByDescending { it.opened }
                    if (visible.isEmpty() && tab == 1) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(activity.getString(R.string.no_history), fontFamily = Editorial, fontSize = 20.sp)
                        TextButton(onClick = { tab = 0 }) { Text(activity.getString(R.string.choose_book)) }
                    }
                    LazyVerticalGrid(GridCells.Fixed(2), state = gridStates[tab], contentPadding = PaddingValues(15.dp, 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        items(visible, key = { it.id }) { comic ->
                            val checked = comic.id in selected
                            Column(Modifier.combinedClickable(
                                enabled = !importing && !deleting,
                                onLongClickLabel = activity.getString(R.string.select_comics),
                                onLongClick = { toggle(comic.id) },
                                onClick = { if (selected.isEmpty()) active = comic.id else toggle(comic.id) }
                            )) {
                                Box {
                                    BookArt(comic, comic.cover, Modifier.fillMaxWidth().aspectRatio(.75f), ContentScale.Crop, true)
                                    if (selected.isNotEmpty()) {
                                        if (checked) Box(Modifier.matchParentSize().border(3.dp, Wine))
                                        Checkbox(checked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd)
                                            .padding(6.dp).background(Paper).padding(2.dp))
                                    }
                                }
                                Text(comic.title, Modifier.padding(top = 5.dp, start = 3.dp), fontFamily = Editorial,
                                    fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (tab == 1) {
                                    Text("${comic.page + 1} / ${comic.pages.size}", Modifier.padding(start = 3.dp), fontSize = 12.sp)
                                    LinearProgressIndicator(progress = { (comic.page + 1f) / comic.pages.size },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp).height(3.dp), color = Wine, trackColor = Line)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().height(57.dp)) {
                val icons = listOf(Icons.Outlined.MenuBook, Icons.Outlined.Schedule, Icons.Outlined.Settings)
                tabs.forEachIndexed { index, label ->
                    val color = if (tab == index) Wine else Ink.copy(alpha = .68f)
                    Column(Modifier.weight(1f).fillMaxHeight().clickable {
                        if (index == 2) settingsVisit++
                        tab = index
                    },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icons[index], null, Modifier.size(23.dp), tint = color)
                        Text(label, fontFamily = Editorial, fontSize = 13.sp, color = color)
                    }
                }
            }
        }
        SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(bottom = 62.dp))
    }
    if (importMenu) AlertDialog(onDismissRequest = { importMenu = false }, title = { Text(activity.getString(R.string.add_to_shelf), fontFamily = Editorial) },
        text = { Column {
            TextButton(onClick = { importMenu = false; folderPicker.launch(localImportLocation()) }) { Text(activity.getString(R.string.choose_folder)) }
            TextButton(onClick = { importMenu = false; filePicker.launch(arrayOf("*/*")) }) { Text(activity.getString(R.string.open_zip)) }
        } }, confirmButton = { TextButton(onClick = { importMenu = false }) { Text(activity.getString(R.string.cancel)) } })
}

private fun removeFromLibrary(context: Context, shelf: LibraryDb.Shelf, comic: Comic) {
    CoverCache.discard(context, comic)
    if (comic.id.startsWith("dav:") || comic.id.startsWith("dav-archive:")) RemoteZip.discard(context.cacheDir, comic.id)
    if (comic.id.startsWith("folder:") || comic.id.startsWith("zip:") || comic.id.startsWith("archive:")) {
        val uri = android.net.Uri.parse(comic.id.substringAfter(':'))
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { /* The grant may already have been revoked. */ }
    }
    shelf.remove(comic.id)
}

private object LibraryWrites {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun save(shelf: LibraryDb.Shelf, id: String, page: Int, fraction: Float) {
        executor.execute { shelf.remember(id, page, fraction, System.currentTimeMillis()) }
    }
}
