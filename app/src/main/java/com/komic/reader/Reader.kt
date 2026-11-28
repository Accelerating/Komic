package com.komic.reader

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.view.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Reader(activity: MainActivity, comic: Comic, theme: String, prefs: SharedPreferences, onPage: (Int, Float) -> Unit, onExit: () -> Unit) {
    val session = remember(comic.id) {
        if (comic.id.substringBefore(':') in setOf("zip", "dav", "archive", "dav-archive")) ReaderPages(activity.applicationContext, comic) else null
    }
    LaunchedEffect(session) {
        try { kotlinx.coroutines.awaitCancellation() }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { session?.close() } }
    }
    val pager = rememberPagerState(initialPage = comic.page.coerceIn(comic.pages.indices)) { comic.pages.size }
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = comic.page.coerceIn(comic.pages.indices))
    var menu by rememberSaveable { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(prefs.getFloat("brightness", -1f)) }
    var size by remember { mutableStateOf(prefs.getString("scale", "整页") ?: "整页") }
    var paging by remember { mutableStateOf(prefs.getString("paging", null)?.takeIf { it in listOf("左右", "纵向分页") } ?: "连续滚动") }
    var direction by remember { mutableStateOf(prefs.getString("direction", "竖屏") ?: "竖屏") }
    val dark = theme == "深色" || theme == "跟随系统" && isSystemInDarkTheme()
    val systemBrightness = remember { android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }
    val window = activity.window
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val oldBrightness = window.attributes.screenBrightness
        val oldOrientation = activity.requestedOrientation
        val oldBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = oldBehavior
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            activity.requestedOrientation = oldOrientation
        }
    }
    LaunchedEffect(brightness) { window.attributes = window.attributes.apply { screenBrightness = brightness } }
    LaunchedEffect(direction) {
        activity.requestedOrientation = when (direction) {
            "横屏" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "竖屏" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val savePage by rememberUpdatedState(onPage)
    val continuous = paging == "连续滚动"
    val currentPage by remember(continuous, pager, strip) { derivedStateOf {
        if (!continuous) pager.settledPage
        else if (!strip.canScrollForward && strip.canScrollBackward) comic.pages.lastIndex
        else strip.firstVisibleItemIndex
    } }
    val initialPage = remember { comic.page.coerceIn(comic.pages.indices) }
    val initialFraction = remember { comic.pageFraction.coerceIn(0f, 1f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    var restoring by remember { mutableStateOf(continuous && initialFraction > 0f) }
    LaunchedEffect(restoring, continuous) {
        if (restoring && continuous) {
            // Wait for the actual image layout; a loading placeholder has a different height.
            val height = snapshotFlow { heights[initialPage] }.first { it != null }!!
            strip.scrollToItem(initialPage, (height * initialFraction).roundToInt().coerceIn(0, height - 1))
            restoring = false
        }
    }
    LaunchedEffect(strip, restoring) {
        if (restoring) {
            snapshotFlow { strip.isScrollInProgress }.first { it }
            restoring = false
        }
    }
    fun position(): Pair<Int, Float>? {
        if (!continuous) return pager.settledPage to 0f
        if (restoring) return null
        val index = strip.firstVisibleItemIndex
        val height = heights[index] ?: return null
        return index to (strip.firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f)
    }
    LaunchedEffect(continuous) {
        snapshotFlow { if (continuous && strip.isScrollInProgress) null else position() }
            .collect { it?.let { (page, fraction) -> savePage(page, fraction) } }
    }
    val latestPosition by rememberUpdatedState { position() }
    DisposableEffect(Unit) {
        onDispose { latestPosition()?.let { (page, fraction) -> savePage(page, fraction) } }
    }
    BackHandler { if (menu) menu = false else onExit() }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val viewport = with(LocalDensity.current) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        var zoom by remember(comic.id) { mutableStateOf(PageTransform()) }
        val change = remember(viewport, continuous, strip) {
            { factor: Float, focus: Offset, pan: Offset ->
                val previous = zoom
                val next = previous.change(factor, focus, pan, viewport)
                if (continuous && (next.scale != previous.scale || pan.y != 0f)) {
                    val ratio = next.scale / previous.scale
                    val offset = (strip.firstVisibleItemScrollOffset + focus.y) * ratio - focus.y - pan.y
                    strip.requestScrollToItem(strip.firstVisibleItemIndex, offset.roundToInt())
                }
                zoom = next
            }
        }
        val gestures = Modifier.readerGestures(paging != "左右", { zoom.scale }, change) { menu = true }
        val pagePan = remember(paging, viewport, pager) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || zoom.scale <= 1f || pager.currentPageOffsetFraction != 0f)
                        return Offset.Zero
                    val previous = zoom
                    zoom = previous.panAlongPage(available, paging == "纵向分页", viewport)
                    return zoom.offset - previous.offset
                }

                override suspend fun onPreFling(available: Velocity): Velocity =
                    if (zoom.scale > 1f && pager.currentPageOffsetFraction == 0f) available else Velocity.Zero
            }
        }
        val x = zoom.offset.x.coerceIn(viewport.width * (1f - zoom.scale), 0f)
        val y = zoom.offset.y.coerceIn(viewport.height * (1f - zoom.scale), 0f)
        val pageContent: @Composable PagerScope.(Int) -> Unit = { index ->
            val scale = when (size) { "适宽" -> ContentScale.FillWidth; "原大" -> ContentScale.None; else -> ContentScale.Fit }
            Box(Modifier.fillMaxSize().clipToBounds()) {
                BookArt(comic, comic.pages[index], Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = x
                    translationY = y
                }, scale, session = session, preloadNext = pager.settledPage == index)
            }
        }
        if (continuous) {
            val pageWidth = maxWidth * zoom.scale
            Box(Modifier.fillMaxSize().clipToBounds().then(gestures)) {
                LazyColumn(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true)
                    .absoluteOffset { IntOffset(x.roundToInt(), 0) }.width(pageWidth),
                    state = strip, userScrollEnabled = !menu) {
                    items(comic.pages.size) { index ->
                        DisposableEffect(index) { onDispose { heights.remove(index) } }
                        BookArt(comic, comic.pages[index], Modifier.fillMaxWidth(), ContentScale.FillWidth,
                            session = session, preloadNext = currentPage == index, continuous = true, loadedModifier = Modifier.onSizeChanged {
                                if (it.height > 0) heights[index] = it.height
                            })
                    }
                }
            }
        } else if (paging == "纵向分页") {
            VerticalPager(pager, userScrollEnabled = !menu, modifier = Modifier.fillMaxSize().nestedScroll(pagePan).then(gestures), pageContent = pageContent)
        } else {
            HorizontalPager(pager, userScrollEnabled = !menu, modifier = Modifier.fillMaxSize().nestedScroll(pagePan).then(gestures), pageContent = pageContent)
        }
        AnimatedVisibility(
            visible = menu,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160))
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .23f)).clickable(
                interactionSource = null, indication = null) { menu = false })
        }
        AnimatedVisibility(
            visible = menu,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(220)) { it },
            exit = slideOutVertically(tween(160)) { it }
        ) {
            val surface = if (dark) Color(0xFF272628) else Paper
            val fg = if (dark) Color(0xFFF9F2E7) else Ink
            val accent = if (dark) Color(0xFF995F62) else Wine
            val keyboard = LocalSoftwareKeyboardController.current
            var draft by rememberSaveable(currentPage) { mutableStateOf((currentPage + 1).toString()) }
            var invalid by remember { mutableStateOf(false) }
            fun jump() {
                val page = draft.toIntOrNull()
                if (page == null || page !in 1..comic.pages.size) { invalid = true; return }
                keyboard?.hide()
                scope.launch {
                    restoring = false
                    if (continuous) strip.scrollToItem(page - 1) else pager.scrollToPage(page - 1)
                    menu = false
                }
            }
            Surface(Modifier.fillMaxWidth().imePadding(), color = surface, contentColor = fg,
                shape = RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp)) {
                Column(Modifier.displayCutoutPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).background(fg.copy(alpha = .6f), RoundedCornerShape(4.dp)))
                    MenuRow(activity.getString(R.string.brightness), fg) {
                        Icon(Icons.Outlined.BrightnessLow, null, Modifier.size(19.dp))
                        Slider(value = if (brightness < 0) systemBrightness else brightness, onValueChange = { brightness = it },
                            onValueChangeFinished = { prefs.edit().putFloat("brightness", brightness).apply() },
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp), valueRange = .03f..1f,
                            thumb = { Box(Modifier.size(18.dp).background(if (dark) Color(0xFFD59A96) else Wine, CircleShape)) },
                            track = { state ->
                                val fraction = ((state.value - .03f) / .97f).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth().height(4.dp).background(fg.copy(alpha = .28f), CircleShape)) {
                                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (dark) Color(0xFFD59A96) else Wine, CircleShape))
                                }
                            })
                        Icon(Icons.Outlined.BrightnessHigh, null, Modifier.size(19.dp))
                    }
                    if (!continuous) MenuRow(activity.getString(R.string.image_scale), fg) { Choices(listOf("整页", "适宽", "原大"), size, accent, fg) {
                        size = it; prefs.edit().putString("scale", it).apply()
                    } }
                    MenuRow(activity.getString(R.string.reading_mode), fg) { Choices(listOf("连续滚动", "纵向分页", "左右"), paging, accent, fg) {
                        if (it != paging) {
                            restoring = false
                            if (it == "连续滚动") strip.requestScrollToItem(currentPage)
                            else pager.requestScrollToPage(currentPage)
                        }
                        paging = it; prefs.edit().putString("paging", it).apply()
                    } }
                    MenuRow(activity.getString(R.string.orientation), fg) { Choices(listOf("竖屏", "横屏", "自动"), direction, accent, fg) {
                        direction = it; prefs.edit().putString("direction", it).apply()
                    } }
                    MenuRow(activity.getString(R.string.jump_to), fg) {
                        BasicTextField(draft, { if (it.length <= 9 && it.all(Char::isDigit)) { draft = it; invalid = false } },
                            modifier = Modifier.weight(1f).border(1.dp, fg.copy(alpha = .5f), RoundedCornerShape(5.dp)).padding(8.dp),
                            singleLine = true, textStyle = TextStyle(color = fg, fontSize = 16.sp, textAlign = TextAlign.Center),
                            cursorBrush = SolidColor(fg), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { jump() }))
                        Text(" / ${comic.pages.size}", Modifier.weight(1f), fontSize = 15.sp)
                        Button(onClick = { jump() }, colors = ButtonDefaults.buttonColors(containerColor = accent), shape = RoundedCornerShape(5.dp)) { Text(activity.getString(R.string.go), color = Color.White) }
                    }
                    if (invalid) Text(activity.getString(R.string.page_range, comic.pages.size), color = fg, fontSize = 13.sp)
                    TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                        Icon(Icons.Outlined.ExitToApp, null, tint = fg); Spacer(Modifier.width(9.dp))
                        Text(activity.getString(R.string.exit_reader), color = fg, fontFamily = Editorial, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, fg: Color, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 52.dp else 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(82.dp), fontFamily = Editorial, fontSize = 16.sp)
        content()
    }
    HorizontalDivider(color = fg.copy(alpha = .22f))
}
