@file:OptIn(ExperimentalFoundationApi::class)

package com.glass.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.glass.launcher.App
import com.glass.launcher.data.AppEntry
import com.glass.launcher.data.AppMode
import com.glass.launcher.data.HomeGrid
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import com.glass.launcher.feature.SystemActions
import com.glass.launcher.lock.Auth
import com.glass.launcher.lock.LockGuard
import com.glass.launcher.settings.LauncherSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class Overlay { None, Search, Control, GalleryHome, GalleryToday }

@Composable
fun LauncherRoot(act: LauncherActivity) {
    val app = App.instance
    val prefs = app.prefs
    val tick by prefs.version.collectAsState()
    val apps by app.repo.apps.collectAsState()
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }

    val pages = remember(tick) { prefs.homePages.ifEmpty { listOf(List(prefs.columns * prefs.rows) { null }) } }
    val dock = remember(tick) { prefs.dock }
    val widgetsOn = prefs.widgetsEnabled
    val libraryOn = prefs.libraryEnabled
    val first = if (widgetsOn) 1 else 0
    val pageCount = first + pages.size + (if (libraryOn) 1 else 0)
    val libraryIndex = if (libraryOn) pageCount - 1 else -1

    val pager = rememberPagerState(initialPage = first) { pageCount }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var editMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var glow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun exitEdit() {
        editMode = false
        // Drop trailing empty pages, keep at least one.
        val p = prefs.homePages.toMutableList()
        while (p.size > 1 && p.last().all { it == null }) p.removeAt(p.lastIndex)
        prefs.homePages = p
    }

    LaunchedEffect(Unit) {
        act.homePressed.collect {
            overlay = Overlay.None
            showMenu = false
            if (editMode) exitEdit()
            LockGuard.hiddenRevealed.value = false
            pager.animateScrollToPage(first)
        }
    }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != libraryIndex) LockGuard.hiddenRevealed.value = false
    }

    val isHome by act.isDefaultHome
    val onHomePage = pager.currentPage in first until first + pages.size
    val blurred = overlay != Overlay.None || !onHomePage
    LaunchedEffect(blurred) { act.setBackdropBlur(if (blurred) 90 else 0) }
    val baseDim = if (prefs.dimWallpaper) 0.25f else 0f
    val dim by animateFloatAsState(if (blurred) 0.32f + baseDim else baseDim, tween(260), label = "dim")

    fun openApp(e: AppEntry) {
        if (editMode) return
        LockGuard.open(ctx, e)
    }

    fun assistant() {
        scope.launch {
            glow = true
            delay(450)
            SystemActions.openAssistant(ctx)
            delay(400)
            glow = false
        }
    }

    BackHandler(enabled = overlay != Overlay.None || editMode) {
        if (overlay != Overlay.None) overlay = Overlay.None else exitEdit()
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (!isHome) HomeBanner { act.requestHome() }
            CompositionLocalProvider(LocalLayoutDirection provides if (prefs.pagesRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1,
                userScrollEnabled = overlay == Overlay.None,
            ) { page ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                when {
                    widgetsOn && page == 0 -> TodayView(
                        onSearch = { if (prefs.searchEnabled) overlay = Overlay.Search },
                        onAdd = { overlay = Overlay.GalleryToday },
                    )
                    page == libraryIndex -> AppLibrary(apps, act, ::openApp)
                    else -> {
                        val index = page - first
                        HomePage(
                            pageIndex = index,
                            slots = pages[index],
                            byPkg = byPkg,
                            editMode = editMode,
                            onOpen = ::openApp,
                            onEnterEdit = { editMode = true },
                            onSwipeDown = { rightSide ->
                                overlay = when {
                                    rightSide && prefs.controlCenterEnabled -> Overlay.Control
                                    prefs.searchEnabled -> Overlay.Search
                                    else -> Overlay.None
                                }
                            },
                            onEmptyLongPress = { showMenu = true },
                            onEmptyDoubleTap = { if (prefs.assistantGesture == "double_tap") assistant() },
                        )
                    }
                }
                }
            }
            }
            AnimatedVisibility(onHomePage, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (prefs.searchPill && prefs.searchEnabled) SearchPill { overlay = Overlay.Search }
                    else PageDots(count = pages.size, current = pager.currentPage - first)
                    Spacer(Modifier.height(10.dp))
                    Dock(dock, byPkg, editMode, ::openApp, onEnterEdit = { editMode = true })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        if (editMode) {
            Row(
                Modifier.statusBarsPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("＋ ווידג'ט") { overlay = Overlay.GalleryHome }
                    Pill("דף חדש") {
                        prefs.homePages = prefs.homePages + listOf(List(prefs.columns * prefs.rows) { null })
                    }
                }
                Pill("סיום") { exitEdit() }
            }
        }

        if (showMenu) {
            GlassMenu(
                listOf(
                    MenuItem("עריכת מסך הבית") { editMode = true },
                    MenuItem("הוספת ווידג'ט") { overlay = Overlay.GalleryHome },
                    MenuItem("טפט") {
                        LockGuard.startTrusted(
                            ctx,
                            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "בחירת טפט"),
                        ) { it.contains("Wallpaper", true) || it.contains("Chooser", true) }
                    },
                    MenuItem("הגדרות הלאנצ'ר") { LauncherSettingsActivity.open(ctx) },
                ),
                onDismiss = { showMenu = false },
            )
        }

        when (overlay) {
            Overlay.Search -> SearchOverlay(apps, onOpen = { overlay = Overlay.None; openApp(it) }, onClose = { overlay = Overlay.None })
            Overlay.Control -> ControlCenter(onClose = { overlay = Overlay.None })
            Overlay.GalleryToday -> WidgetGallery(act, onPick = { entry ->
                prefs.widgets = prefs.widgets + entry
                overlay = Overlay.None
            }, onClose = { overlay = Overlay.None })
            Overlay.GalleryHome -> WidgetGallery(act, onPick = { entry ->
                val pageNow = (pager.currentPage - first).coerceIn(0, pages.size - 1)
                prefs.homePages = HomeGrid.place(prefs.homePages, prefs.columns, prefs.rows, "w:$entry", pageNow)
                overlay = Overlay.None
            }, onClose = { overlay = Overlay.None })
            Overlay.None -> {}
        }

        if (glow) AssistantGlow()
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Box(Modifier.glass(RoundedCornerShape(50)).tap(onClick).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun HomeBanner(onSet: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), 1.3f).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("זכוכית עוד לא מוגדרת כמסך הבית", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Pill("הגדרה", onSet)
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier.glass(RoundedCornerShape(50), 0.8f).tap(onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("חיפוש", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun PageDots(count: Int, current: Int) {
    if (count <= 1) return
    Row(
        Modifier.glass(RoundedCornerShape(50), 0.6f).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        repeat(count) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == current) 1f else 0.4f)))
        }
    }
}

/** The app icon, rendered off the main thread and cached. */
@Composable
fun IconImage(e: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val pack = App.instance.prefs.iconPack
    val bmp by produceState<ImageBitmap?>(null, e.pkg, px, pack) {
        value = withContext(Dispatchers.Default) { App.instance.icons.icon(e, px, pack).asImageBitmap() }
    }
    val filter = remember(App.instance.prefs.iconStyle, App.instance.prefs.tintColor) { iconFilter() }
    Box(modifier.size(size)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), colorFilter = filter) }
    }
}

/** Dark and tinted icon looks, like on iPhone. */
fun iconFilter(): ColorFilter? {
    val p = App.instance.prefs
    return when (p.iconStyle) {
        "dark" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.62f, 0f, 0f, 0f, -8f,
            0f, 0.62f, 0f, 0f, -8f,
            0f, 0f, 0.66f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f,
        )))
        "tinted" -> {
            val c = Color(p.tintColor)
            fun row(t: Float) = floatArrayOf(0.299f * t * 1.15f, 0.587f * t * 1.15f, 0.114f * t * 1.15f, 0f, 0f)
            ColorFilter.colorMatrix(ColorMatrix(row(c.red) + row(c.green) + row(c.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
        }
        else -> null
    }
}

@Composable
fun AppIcon(
    e: AppEntry,
    size: Dp,
    showLabel: Boolean,
    labelColor: Color = Color.White,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val src = remember { MutableInteractionSource() }
    val view = LocalView.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconImage(
            e, size,
            Modifier
                .pressScale(src)
                .combinedClickable(
                    interactionSource = src,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { view.thud(); onLongClick() },
                )
        )
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                e.label,
                style = LabelStyle.copy(color = labelColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size + 18.dp),
            )
        }
    }
}

/** Long-press menu for an app, shared by home screen, dock and library. */
@Composable
fun AppMenu(e: AppEntry, onHome: Boolean, inDock: Boolean, onEdit: (() -> Unit)?, onDismiss: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val mode = prefs.modeOf(e.pkg)
    var pickFolder by remember { mutableStateOf(false) }
    if (pickFolder) {
        GlassMenu(
            prefs.folders.ifEmpty { listOf(com.glass.launcher.data.FolderDef("נסתר", true)) }.map { f ->
                MenuItem((if (f.hidden) "🔒 " else "") + f.name) {
                    if (f.hidden) { prefs.setMode(e.pkg, AppMode.HIDDEN, f.name); app.repo.removeFromHome(e.pkg) }
                    else prefs.setFolder(e.pkg, f.name)
                }
            },
            onDismiss = onDismiss,
        )
        return
    }
    val items = buildList {
        if (onEdit != null) add(MenuItem("עריכת מסך הבית", onClick = onEdit))
        if (!onHome && !inDock) add(MenuItem("הוספה למסך הבית") { app.repo.addToHome(e.pkg) })
        if (onHome) add(MenuItem("הוצאה ממסך הבית") { app.repo.removeFromHome(e.pkg) })
        if (onHome && !inDock) add(MenuItem("העברה ל-Dock") {
            val d = (prefs.dock + List(6) { null }).take(prefs.dockCount).toMutableList()
            val i = d.indexOfFirst { it == null }
            if (i >= 0) { app.repo.removeFromHome(e.pkg); d[i] = e.pkg; prefs.dock = d }
        })
        if (mode == AppMode.LOCKED) {
            add(MenuItem("ביטול נעילה") {
                act?.let { Auth.prompt(it, "ביטול נעילה", onSuccess = { prefs.setMode(e.pkg, AppMode.NORMAL) }) }
            })
        } else {
            add(MenuItem("נעילה עם זיהוי") { prefs.setMode(e.pkg, AppMode.LOCKED) })
        }
        add(MenuItem("העברה לתיקייה") { pickFolder = true })
        add(MenuItem("הסרה מהתצוגה") { prefs.setMode(e.pkg, AppMode.REMOVED); app.repo.removeFromHome(e.pkg) })
        if (!e.isSystem) add(MenuItem("מחיקת האפליקציה", danger = true) {
            SystemActions.start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
        })
    }
    GlassMenu(items, onDismiss = { if (!pickFolder) onDismiss() })
}

@Composable
private fun Wiggle(enabled: Boolean, seed: Int, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val t = rememberInfiniteTransition(label = "wiggle")
    val r by t.animateFloat(
        -1.8f, 1.8f,
        infiniteRepeatable(tween(130 + (seed % 3) * 15), RepeatMode.Reverse),
        label = "r",
    )
    Box(Modifier.rotate(r)) { content() }
}

@Composable
fun HomePage(
    pageIndex: Int,
    slots: List<String?>,
    byPkg: Map<String, AppEntry>,
    editMode: Boolean,
    onOpen: (AppEntry) -> Unit,
    onEnterEdit: () -> Unit,
    onSwipeDown: (rightSide: Boolean) -> Unit,
    onEmptyLongPress: () -> Unit,
    onEmptyDoubleTap: () -> Unit,
) {
    val prefs = App.instance.prefs
    val cols = prefs.columns
    val rows = prefs.rows
    val cells = HomeGrid.normalize(slots, cols, rows)
    val owners = remember(cells, cols, rows) { HomeGrid.owners(cells, cols, rows) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var widgetMenu by remember { mutableIntStateOf(-1) }
    val view = LocalView.current
    val density = LocalDensity.current

    fun drop(from: Int, cwPx: Float, chPx: Float) {
        val col = from % cols
        val row = from / cols
        // Layout is right-to-left: moving the finger right lowers the column index.
        val tCol = (col - (dragOffset.x / cwPx).roundToInt()).coerceIn(0, cols - 1)
        val tRow = (row + (dragOffset.y / chPx).roundToInt()).coerceIn(0, rows - 1)
        val to = tRow * cols + tCol
        if (to == from) return
        val pages = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
        if (pageIndex !in pages.indices) return
        val p = pages[pageIndex]
        val v = p[from] ?: return
        if (HomeGrid.isWidget(v)) {
            val (w, h) = HomeGrid.span(v, cols, rows)
            if (!HomeGrid.fits(p, cols, rows, to, w, h, ignore = from)) return
            p[from] = null; p[to] = v
        } else {
            if (owners[to] != to || HomeGrid.isWidget(p[to])) return  // cannot drop an app onto a widget
            val tmp = p[to]; p[to] = v; p[from] = tmp
        }
        prefs.homePages = pages
        view.tick()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .pointerInput(editMode) {
                if (editMode) return@pointerInput
                var startX = 0f
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { startX = it.x; total = 0f },
                    onDragEnd = { if (total > 90.dp.toPx()) onSwipeDown(startX > size.width * 0.62f) },
                    onVerticalDrag = { _, d -> total += d },
                )
            }
    ) {
        val cw = maxWidth / cols
        val ch = maxHeight / rows
        val cwPx = with(density) { cw.toPx() }
        val chPx = with(density) { ch.toPx() }

        for (i in 0 until cols * rows) {
            if (owners[i] != i) continue // covered by a widget
            val v = cells[i]
            val r = i / cols
            val c = i % cols
            val isDragged = i == dragIndex
            val isW = HomeGrid.isWidget(v)
            val (w, h) = if (isW) HomeGrid.span(v!!, cols, rows) else (1 to 1)
            val e = if (!isW) v?.let { byPkg[it] }?.takeIf { prefs.isVisible(it.pkg) } else null
            val dragMod = Modifier.pointerInput(editMode, i) {
                if (!editMode) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragIndex = i; dragOffset = Offset.Zero },
                    onDragEnd = { drop(i, cwPx, chPx); dragIndex = -1; dragOffset = Offset.Zero },
                    onDragCancel = { dragIndex = -1; dragOffset = Offset.Zero },
                    onDrag = { chg, d -> chg.consume(); dragOffset += d },
                )
            }
            Box(
                Modifier
                    .offset(x = cw * c, y = ch * r)
                    .size(cw * w, ch * h)
                    .zIndex(if (isDragged) 10f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.06f; scaleY = 1.06f
                        }
                    }
                    .then(
                        if (!isW && e == null) Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { view.thud(); onEmptyLongPress() },
                                onDoubleTap = { onEmptyDoubleTap() },
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isW -> Wiggle(editMode && !isDragged, i) {
                        val (key, size) = HomeGrid.parse(v!!)
                        WidgetCard(
                            key, size,
                            Modifier.fillMaxSize().padding(6.dp).then(dragMod),
                            onLongPress = { view.thud(); widgetMenu = i },
                        )
                    }
                    e != null -> Wiggle(editMode && !isDragged, i) {
                        AppIcon(
                            e, prefs.iconDp().dp, prefs.showLabels,
                            onClick = { onOpen(e) },
                            onLongClick = { if (!editMode) menuFor = e },
                            modifier = dragMod,
                        )
                    }
                }
            }
        }
    }
    menuFor?.let { e ->
        AppMenu(e, onHome = true, inDock = false, onEdit = onEnterEdit, onDismiss = { menuFor = null })
    }
    if (widgetMenu >= 0) {
        val idx = widgetMenu
        val v = cells.getOrNull(idx)
        if (v == null || !HomeGrid.isWidget(v)) { widgetMenu = -1 } else {
            val (key, size) = HomeGrid.parse(v)
            GlassMenu(
                listOf(MenuItem("עריכת מסך הבית", onClick = onEnterEdit)) +
                    HomeGrid.Size.entries.filter { it != size }.map { s ->
                        MenuItem("גודל: ${s.label}") {
                            val pages = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
                            val p = pages[pageIndex]
                            val nv = "w:" + HomeGrid.entry(key, s)
                            val (nw, nh) = HomeGrid.span(nv, cols, rows)
                            p[idx] = null
                            if (HomeGrid.fits(p, cols, rows, idx, nw, nh)) { p[idx] = nv; prefs.homePages = pages }
                            else { p[idx] = v; prefs.homePages = HomeGrid.place(pages.also { it[pageIndex][idx] = null }, cols, rows, nv, pageIndex) }
                        }
                    } + MenuItem("הסרת הווידג'ט", danger = true) {
                        val pages = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
                        pages[pageIndex][idx] = null
                        prefs.homePages = pages
                        removeWidgetEverywhere(v, fromToday = false)
                    },
                onDismiss = { widgetMenu = -1 },
            )
        }
    }
}

@Composable
fun Dock(
    dock: List<String?>,
    byPkg: Map<String, AppEntry>,
    editMode: Boolean,
    onOpen: (AppEntry) -> Unit,
    onEnterEdit: () -> Unit,
) {
    val prefs = App.instance.prefs
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .then(if (prefs.dockBackground) Modifier.glass(RoundedCornerShape(34.dp)) else Modifier)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (dock + List(6) { null }).take(prefs.dockCount).forEachIndexed { i, pkg ->
            val e = pkg?.let { byPkg[it] }?.takeIf { prefs.isVisible(it.pkg) }
            Box(Modifier.size((prefs.iconDp() + 2).dp), contentAlignment = Alignment.Center) {
                if (e != null) Wiggle(editMode, i + 50) {
                    AppIcon(e, prefs.iconDp().dp, false, onClick = { onOpen(e) }, onLongClick = { if (!editMode) menuFor = e })
                }
            }
        }
    }
    menuFor?.let { e ->
        AppMenu(e, onHome = true, inDock = true, onEdit = onEnterEdit, onDismiss = { menuFor = null })
    }
}
