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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceAtLeast
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

enum class Overlay { None, Search, Control, Notifications, GalleryHome, GalleryToday, GalleryReplace }

@Composable
fun LauncherRoot(act: LauncherActivity) {
    val app = App.instance
    val prefs = app.prefs
    val tick by prefs.version.collectAsState()
    val apps by app.repo.apps.collectAsState()
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }

    var editModeFlag by remember { mutableStateOf(false) }
    val pages = remember(tick, editModeFlag) {
        val all = prefs.homePages.ifEmpty { listOf(List(prefs.columns * prefs.rows) { null }) }
        // Empty pages at the end are not shown (except while editing, so new pages can be filled).
        if (editModeFlag) all else {
            // A page counts as empty when nothing on it is shown (hidden, removed or uninstalled apps don't count).
            var n = all.size
            while (n > 1 && homePageCount(all[n - 1]) == 0) n--
            all.take(n)
        }
    }
    val dock = remember(tick) { prefs.dock }
    val widgetsOn = prefs.widgetsEnabled
    val libraryOn = prefs.libraryEnabled
    val first = if (widgetsOn) 1 else 0
    val pageCount = first + pages.size + (if (libraryOn) 1 else 0)
    val libraryIndex = if (libraryOn) pageCount - 1 else -1

    val pager = rememberPagerState(initialPage = first) { pageCount }
    val drag = remember { IconDrag() }
    drag.currentPage = (pager.currentPage - first).coerceIn(0, pages.size - 1)
    drag.pagesRtl = prefs.pagesRtl
    var overlay by remember { mutableStateOf(Overlay.None) }
    // Widget whose content is being replaced: (page, index) on the home screen, or page -1 + entry for the widgets page.
    var replaceTarget by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var editMode by remember { mutableStateOf(false) }
    editModeFlag = editMode
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
        LockGuard.closedPkg.collect { closed ->
            if (closed == null) return@collect
            Landing.pkg = closed
            Landing.progress.snapTo(0f)
            Landing.progress.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 320f))
            Landing.pkg = null
            LockGuard.closedPkg.value = null
        }
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
    val overlayOpen = overlay != Overlay.None
    val dim by animateFloatAsState(
        when { overlayOpen -> 0.5f; blurred -> 0.32f + baseDim; else -> baseDim }, tween(260), label = "dim",
    )
    // Like iOS: the home screen fades away behind Search and Control Center.
    val contentAlpha by animateFloatAsState(if (overlayOpen) 0f else 1f, tween(220), label = "content")

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

    val density = LocalDensity.current
    drag.edgePx = with(density) { 34.dp.toPx() }
    CompositionLocalProvider(LocalIconDrag provides drag) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))
            .onGloballyPositioned { drag.screenWidth = it.size.width.toFloat() }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().graphicsLayer {
            alpha = contentAlpha
            // The home screen settles in as an app goes back into its icon.
            val l = if (Landing.pkg != null) 1f - Landing.progress.value else 0f
            scaleX = 1f + 0.06f * l; scaleY = 1f + 0.06f * l
        }) {
            if (!isHome) HomeBanner { act.requestHome() }
            CompositionLocalProvider(LocalLayoutDirection provides if (prefs.pagesRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1,
                userScrollEnabled = overlay == Overlay.None,
            ) { page ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().pageEffect(pager, page, prefs.pageEffect)) {
                when {
                    widgetsOn && page == 0 -> TodayView(
                        onSearch = { if (prefs.searchEnabled) overlay = Overlay.Search },
                        onAdd = { overlay = Overlay.GalleryToday },
                        onReplace = { e -> replaceTarget = Triple(-1, -1, e); overlay = Overlay.GalleryReplace },
                    )
                    page == libraryIndex -> AppLibrary(apps, act, ::openApp)
                    else -> {
                        val index = page - first
                        HomePage(
                            version = tick,
                            pageIndex = index,
                            slots = pages[index],
                            byPkg = byPkg,
                            editMode = editMode,
                            onOpen = ::openApp,
                            onEnterEdit = { editMode = true },
                            onSwipeDown = { upperHalf, leftSide ->
                                val action = when {
                                    // With "pull down from the top" on, the home screen works like iPhone: swipe down = Search.
                                    prefs.topPull -> "search"
                                    !upperHalf -> prefs.swipeBottom
                                    leftSide -> prefs.swipeTopLeft
                                    else -> prefs.swipeTopRight
                                }
                                overlay = when (action) {
                                    "control" -> if (prefs.controlCenterEnabled) Overlay.Control else Overlay.None
                                    "search" -> if (prefs.searchEnabled) Overlay.Search else Overlay.None
                                    "notifications" -> Overlay.Notifications
                                    else -> Overlay.None
                                }
                            },
                            onEmptyLongPress = { showMenu = true },
                            onEmptyDoubleTap = { if (prefs.assistantGesture == "double_tap") assistant() },
                            onReplaceWidget = { idx, v -> replaceTarget = Triple(index, idx, v); overlay = Overlay.GalleryReplace },
                        )
                    }
                }
                }
                }
            }
            }
            AnimatedVisibility(onHomePage, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (prefs.aboveDock) {
                        "search" -> if (prefs.searchEnabled) SearchPill { overlay = Overlay.Search }
                        "dots" -> PageDots(count = pages.size, current = pager.currentPage - first)
                        else -> {}
                    }
                    Spacer(Modifier.height(10.dp))
                    Dock(tick, dock, byPkg, editMode, ::openApp, onEnterEdit = { editMode = true })
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
            Overlay.Notifications -> NotificationCenter(onClose = { overlay = Overlay.None })
            Overlay.GalleryReplace -> WidgetGallery(act, replacing = true, onPick = { entry ->
                val t = replaceTarget
                val newKey = HomeGrid.parse(entry).first
                if (t != null) {
                    if (t.first < 0) {
                        prefs.widgets = prefs.widgets.map { if (it == t.third) replaceWidgetKey(it, newKey) else it }
                        removeWidgetEverywhere(t.third, fromToday = false)
                    } else {
                        val cols = prefs.columns
                        val rows = prefs.rows
                        val pg = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
                        if (t.first in pg.indices && pg[t.first][t.second] == t.third) {
                            pg[t.first][t.second] = replaceWidgetKey(t.third, newKey)
                            prefs.homePages = pg
                            removeWidgetEverywhere(t.third, fromToday = false)
                        }
                    }
                }
                replaceTarget = null
                overlay = Overlay.None
            }, onClose = { replaceTarget = null; overlay = Overlay.None })
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

        // The icon under the finger while dragging, plus a hint when it will go to another page.
        drag.pkg?.let { dp ->
            if (dp.startsWith("f:")) {
                val sizeDp = prefs.iconDp().dp
                val half = with(density) { (sizeDp / 2).toPx() }
                Box(
                    Modifier
                        .absoluteOffset { androidx.compose.ui.unit.IntOffset((drag.pos.x - half).roundToInt(), (drag.pos.y - half * 1.4f).roundToInt()) }
                        .size(sizeDp).glass(SquircleShape, 1.2f)
                )
            }
            byPkg[dp]?.let { e ->
                val sizeDp = prefs.iconDp().dp
                val half = with(density) { (sizeDp / 2).toPx() }
                IconImage(
                    e, sizeDp,
                    Modifier
                        .absoluteOffset { androidx.compose.ui.unit.IntOffset((drag.pos.x - half).roundToInt(), (drag.pos.y - half * 1.4f).roundToInt()) }
                        .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f; shadowElevation = 16f }
                )
            }
            val e = drag.edge()
            if (e != 0) Box(Modifier.fillMaxSize(), contentAlignment = if ((e == 1) != drag.pagesRtl) Alignment.CenterEnd else Alignment.CenterStart) {
                Pill(if (e == 1) "לעמוד הבא" else "לעמוד הקודם") {}
            }
        }
    }
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

/** How a page moves while swiping between home pages (chosen in the look settings). */
fun Modifier.pageEffect(pager: androidx.compose.foundation.pager.PagerState, page: Int, effect: String): Modifier =
    if (effect == "slide") this else graphicsLayer {
        val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
        val a = kotlin.math.abs(off)
        when (effect) {
            "fade" -> { alpha = 1f - a; translationX = off * size.width * 0.85f }
            "scale" -> { val sc = 1f - 0.18f * a; scaleX = sc; scaleY = sc; alpha = 1f - 0.5f * a }
            "cube" -> {
                cameraDistance = 14f * density
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (off < 0) 0f else 1f, 0.5f)
                rotationY = -off * 70f
            }
        }
    }

/** A home-screen folder: glass tile with up to nine tiny icons, like iPhone. */
@Composable
fun FolderTile(id: String, byPkg: Map<String, AppEntry>, size: Dp, showLabel: Boolean, modifier: Modifier, gesture: Modifier) {
    val prefs = App.instance.prefs
    val f = prefs.homeFolders[id] ?: return
    val apps = f.second.mapNotNull { byPkg[it] }.filter { prefs.isVisible(it.pkg) }.take(9)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(gesture.size(size).glass(SquircleShape, 1.1f).padding(size * 0.12f)) {
            Column(verticalArrangement = Arrangement.spacedBy(size * 0.04f)) {
                apps.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(size * 0.04f)) {
                        row.forEach { IconImage(it, size * 0.22f) }
                    }
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(f.first, style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(size + 18.dp))
        }
    }
}

/** Removes a folder; its apps go back onto the home screen. */
fun dissolveFolder(id: String) {
    val prefs = App.instance.prefs
    val f = prefs.homeFolders[id] ?: return
    val cols = prefs.columns
    val rows = prefs.rows
    var pages = prefs.homePages.map { page -> page.map { if (it == "f:$id") null else it } }
    f.second.forEach { pages = HomeGrid.place(pages, cols, rows, it) }
    prefs.homePages = pages
    prefs.homeFolders = prefs.homeFolders - id
}

/** How many things are actually shown on a home page (visible apps + widgets). */
fun homePageCount(page: List<String?>): Int {
    val prefs = App.instance.prefs
    val repo = App.instance.repo
    return page.count { v ->
        v != null && (HomeGrid.isWidget(v) || v.startsWith("f:") || v.startsWith("s:") || (repo.byPackage(v) != null && prefs.isVisible(v)))
    }
}

/** The icon of the app just closed with the home gesture "lands" back in its place. */
object Landing {
    var pkg by mutableStateOf<String?>(null)
    val progress = androidx.compose.animation.core.Animatable(1f)
}

/** What an app looks like on screen: a disguised app shows the icon and name of its cover app. */
fun shown(e: AppEntry): AppEntry =
    App.instance.prefs.disguise[e.pkg]?.let { App.instance.repo.byPackage(it) } ?: e

/** The app icon, rendered off the main thread and cached. */
@Composable
fun IconImage(e0: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val e = shown(e0)
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
    gesture: Modifier? = null,
) {
    val src = remember { MutableInteractionSource() }
    val view = LocalView.current
    val badges by com.glass.launcher.feature.NotifListener.badges.collectAsState()
    val disguised = e.pkg in App.instance.prefs.disguise
    val count = if (App.instance.prefs.badges && !disguised) badges[e.pkg] ?: 0 else 0
    val label = shown(e).label
    val landing = Landing.pkg == e.pkg
    Column(modifier.graphicsLayer {
        if (landing) {
            val f = Landing.progress.value
            val sc = 1.5f - 0.5f * f
            scaleX = sc; scaleY = sc
            alpha = f.coerceIn(0f, 1f)
        }
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.onGloballyPositioned { c ->
            val b = c.boundsInWindow()
            val w = c.findRootCoordinates().size.width
            if (b.left >= 0f && b.right <= w) // only icons that are on screen now
                App.instance.repo.iconBounds[e.pkg] = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }) {
            IconImage(
                e, size,
                gesture ?: Modifier
                    .pressScale(src)
                    .combinedClickable(
                        interactionSource = src,
                        indication = null,
                        onClick = onClick,
                        onLongClick = { view.thud(); onLongClick() },
                    )
            )
            if (count > 0) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)
                    .size(22.dp).clip(CircleShape).background(Color(0xFFFF3B30)),
                contentAlignment = Alignment.Center,
            ) { Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 12.sp) }
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                label,
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
    var pickHidden by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var pickPage by remember { mutableStateOf(false) }
    if (pickPage) {
        GlassMenu(
            prefs.homePages.indices.map { idx ->
                MenuItem("עמוד ${idx + 1}") {
                    app.repo.removeFromHome(e.pkg)
                    val cols = prefs.columns
                    val rows = prefs.rows
                    prefs.homePages = HomeGrid.place(prefs.homePages, cols, rows, e.pkg, idx)
                }
            },
            onDismiss = onDismiss,
        )
        return
    }
    if (newFolder) {
        TextPrompt(if (pickHidden) "תיקייה נסתרת חדשה" else "תיקייה חדשה", "שם התיקייה", hiddenOption = true, startHidden = pickHidden, onDone = { name, hidden ->
            val n = name.trim()
            if (n.isNotEmpty()) {
                if (prefs.folders.none { it.name == n }) prefs.folders = prefs.folders + com.glass.launcher.data.FolderDef(n, hidden)
                if (hidden) { prefs.setMode(e.pkg, AppMode.HIDDEN, n); app.repo.removeFromHome(e.pkg) } else prefs.setFolder(e.pkg, n)
            }
            onDismiss()
        }, onCancel = onDismiss)
        return
    }
    if (pickFolder) {
        GlassMenu(
            prefs.folders.filter { it.hidden == pickHidden }.map { f ->
                MenuItem((if (f.hidden) "🔒 " else "") + f.name + (if (prefs.folderOf[e.pkg] == f.name) "  ✓" else "")) {
                    if (f.hidden) { prefs.setMode(e.pkg, AppMode.HIDDEN, f.name); app.repo.removeFromHome(e.pkg) }
                    else { if (prefs.modeOf(e.pkg) == AppMode.HIDDEN) prefs.setMode(e.pkg, AppMode.NORMAL); prefs.setFolder(e.pkg, f.name) }
                }
            } + listOfNotNull(
                MenuItem(if (pickHidden) "＋ תיקייה נסתרת חדשה…" else "＋ תיקייה חדשה…") { newFolder = true },
                if (prefs.folderOf[e.pkg] != null) MenuItem("הוצאה מהתיקייה") {
                    if (prefs.modeOf(e.pkg) == AppMode.HIDDEN) prefs.setMode(e.pkg, AppMode.NORMAL) else prefs.setFolder(e.pkg, null)
                } else null,
            ),
            onDismiss = { if (!newFolder) onDismiss() },
        )
        return
    }
    val items = buildList {
        if (onEdit != null) add(MenuItem("שינוי סדר (גרירה לכל מקום)", onClick = onEdit))
        if (!onHome && !inDock) add(MenuItem("הוספה למסך הבית") { app.repo.addToHome(e.pkg) })
        if (onHome) add(MenuItem("הסרה ממסך הבית (נשארת בספרייה)") { app.repo.removeFromHome(e.pkg) })
        if (onHome && !inDock && prefs.homePages.size > 1) add(MenuItem("העברה לעמוד אחר") { pickPage = true })
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
        add(MenuItem("🔒 העברה לתיקייה נסתרת") { pickHidden = true; pickFolder = true })
        add(MenuItem("העברה לתיקייה") { pickHidden = false; pickFolder = true })
        add(MenuItem("הסרה מהתצוגה") { prefs.setMode(e.pkg, AppMode.REMOVED); app.repo.removeFromHome(e.pkg) })
        if (!e.isSystem) add(MenuItem("מחיקת האפליקציה", danger = true) {
            SystemActions.start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
        })
    }
    GlassMenu(items, onDismiss = { if (!pickFolder && !newFolder && !pickPage) onDismiss() })
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
    version: Int,
    pageIndex: Int,
    slots: List<String?>,
    byPkg: Map<String, AppEntry>,
    editMode: Boolean,
    onOpen: (AppEntry) -> Unit,
    onEnterEdit: () -> Unit,
    onSwipeDown: (upperHalf: Boolean, leftSide: Boolean) -> Unit,
    onEmptyLongPress: () -> Unit,
    onEmptyDoubleTap: () -> Unit,
    onReplaceWidget: (Int, String) -> Unit = { _, _ -> },
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
    var openFolder by remember { mutableStateOf<String?>(null) }
    var shortcutMenu by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var resizeIndex by remember { mutableIntStateOf(-1) }
    var resizeOffset by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val density = LocalDensity.current

    /** Drag the corner handle: new width/height in whole cells. Right-to-left: the handle is on the left. */
    fun applyResize(from: Int, cwPx: Float, chPx: Float) {
        val pages = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
        if (pageIndex !in pages.indices) return
        val p = pages[pageIndex]
        val v = p[from] ?: return
        val (w, h) = HomeGrid.span(v, cols, rows)
        val nw = (w + (-resizeOffset.x / cwPx).roundToInt()).coerceIn(1, cols - from % cols)
        val nh = (h + (resizeOffset.y / chPx).roundToInt()).coerceIn(1, rows - from / cols)
        if (nw == w && nh == h) return
        if (!HomeGrid.fits(p, cols, rows, from, nw, nh, ignore = from)) return
        p[from] = "w:" + HomeGrid.entry(HomeGrid.parse(v).first, nw, nh)
        prefs.homePages = pages
        view.tick()
    }

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

    val drag = LocalIconDrag.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .onGloballyPositioned { drag.gridRects[pageIndex] = it.boundsInRoot() }
            .pointerInput(editMode) {
                if (editMode) return@pointerInput
                var startY = 0f
                var startX = 0f
                var total = 0f
                // Swipe down: what opens depends on where it starts (set in the launcher settings).
                detectVerticalDragGestures(
                    onDragStart = { startY = it.y; startX = it.x; total = 0f },
                    onDragEnd = { if (total > 90.dp.toPx()) onSwipeDown(startY < size.height / 2f, startX < size.width / 2f) },
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
            val isFolder = v != null && v.startsWith("f:")
            val isShortcut = com.glass.launcher.data.Shortcuts.isShortcut(v)
            val e = if (!isW && !isFolder && !isShortcut) v?.let { byPkg[it] }?.takeIf { prefs.isVisible(it.pkg) } else null
            val hovered = drag.pkg != null && drag.hover == DragSource(pageIndex, i)
            val dragMod = Modifier.pointerInput(editMode, i) {
                if (!editMode) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragIndex = i; dragOffset = Offset.Zero },
                    onDragEnd = { drop(i, cwPx, chPx); dragIndex = -1; dragOffset = Offset.Zero },
                    onDragCancel = { dragIndex = -1; dragOffset = Offset.Zero },
                    onDrag = { chg, d -> chg.consume(); dragOffset += d },
                )
            }
            val growW = if (i == resizeIndex) with(density) { (-resizeOffset.x).toDp() } else 0.dp
            val growH = if (i == resizeIndex) with(density) { resizeOffset.y.toDp() } else 0.dp
            Box(
                Modifier
                    .offset(x = cw * c, y = ch * r)
                    .size((cw * w + growW).coerceAtLeast(cw * 0.6f), (ch * h + growH).coerceAtLeast(ch * 0.6f))
                    .zIndex(if (isDragged) 10f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.06f; scaleY = 1.06f
                        }
                    }
                    .then(if (hovered) Modifier.padding(4.dp).glass(RoundedCornerShape(18.dp), 0.7f) else Modifier)
                    .then(
                        if (!isW && !isFolder && !isShortcut && e == null) Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { view.thud(); onEmptyLongPress() },
                                onDoubleTap = { onEmptyDoubleTap() },
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isShortcut -> Wiggle(editMode, i) {
                        val sv = v!!
                        val src = DragSource(pageIndex, i)
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val sizePx = with(density) { prefs.iconDp().dp.roundToPx() }
                        val bmp = remember(sv) { com.glass.launcher.data.Shortcuts.icon(ctx, sv, sizePx)?.asImageBitmap() }
                        Column(
                            Modifier.graphicsLayer { alpha = if (drag.pkg == sv && drag.source == src) 0f else 1f },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(prefs.iconDp().dp).dragIcon(sv, src, editMode,
                                onTap = { com.glass.launcher.data.Shortcuts.open(ctx, sv) },
                                onMenu = { shortcutMenu = sv }, onEnterEdit = onEnterEdit)) {
                                if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize())
                                else Box(Modifier.fillMaxSize().glass(SquircleShape))
                            }
                            if (prefs.showLabels) {
                                Spacer(Modifier.height(5.dp))
                                Text(remember(sv) { com.glass.launcher.data.Shortcuts.label(ctx, sv) }, style = LabelStyle, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(prefs.iconDp().dp + 18.dp))
                            }
                        }
                    }
                    isFolder -> Wiggle(editMode, i) {
                        val id = v!!.removePrefix("f:")
                        val src = DragSource(pageIndex, i)
                        FolderTile(
                            id, byPkg, prefs.iconDp().dp, prefs.showLabels,
                            Modifier.graphicsLayer { alpha = if (drag.pkg == v && drag.source == src) 0f else 1f },
                            gesture = Modifier.dragIcon(v, src, editMode, onTap = { openFolder = id }, onMenu = { folderMenu = id }, onEnterEdit = onEnterEdit),
                        )
                    }
                    isW -> Wiggle(editMode && !isDragged, i) {
                        val (key, size) = HomeGrid.parse(v!!)
                        Box(Modifier.fillMaxSize()) {
                            WidgetCard(
                                key, size,
                                Modifier.fillMaxSize().padding(6.dp).then(dragMod),
                                onLongPress = { view.thud(); widgetMenu = i },
                            )
                            if (editMode) Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .pointerInput(i) {
                                        detectDragGestures(
                                            onDragStart = { resizeIndex = i; resizeOffset = Offset.Zero },
                                            onDragEnd = { applyResize(i, cwPx, chPx); resizeIndex = -1; resizeOffset = Offset.Zero },
                                            onDragCancel = { resizeIndex = -1; resizeOffset = Offset.Zero },
                                            onDrag = { chg, dd -> chg.consume(); resizeOffset += dd },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text("⤡", color = Color.Black, fontSize = 16.sp) }
                        }
                    }
                    e != null -> Wiggle(editMode && !isDragged, i) {
                        val src = DragSource(pageIndex, i)
                        val beingDragged = drag.pkg == e.pkg && drag.source == src
                        val iconGesture = Modifier.dragIcon(
                            e.pkg, src, editMode,
                            onTap = { onOpen(e) },
                            onMenu = { menuFor = e },
                            onEnterEdit = onEnterEdit,
                        )
                        Box {
                            AppIcon(
                                e, prefs.iconDp().dp, prefs.showLabels,
                                onClick = { onOpen(e) },
                                onLongClick = { if (!editMode) menuFor = e },
                                gesture = iconGesture,
                                modifier = Modifier.graphicsLayer { alpha = if (beingDragged) 0f else 1f },
                            )
                            // iPhone edit mode: a small minus takes the app off the home screen (it stays in the library).
                            if (editMode && !beingDragged) Box(
                                Modifier.align(Alignment.TopStart).offset(x = (-4).dp, y = (-4).dp).size(22.dp)
                                    .clip(CircleShape).background(Color(0xE6636366))
                                    .tap { App.instance.repo.removeFromHome(e.pkg) },
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(10.dp, 2.dp).background(Color.White)) }
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { e ->
        AppMenu(e, onHome = true, inDock = false, onEdit = onEnterEdit, onDismiss = { menuFor = null })
    }
    openFolder?.let { id ->
        val f = prefs.homeFolders[id]
        if (f == null) openFolder = null else {
            var inMenu by remember { mutableStateOf<AppEntry?>(null) }
            GroupSheet(
                f.first, f.second.mapNotNull { byPkg[it] }.filter { prefs.isVisible(it.pkg) },
                onOpen = { openFolder = null; onOpen(it) },
                onClose = { openFolder = null },
                onLong = { app -> inMenu = app },
            )
            inMenu?.let { app ->
                GlassMenu(listOf(
                    MenuItem("הוצאה מהתיקייה") {
                        val left = f.second - app.pkg
                        prefs.homeFolders = prefs.homeFolders + (id to (f.first to left))
                        prefs.homePages = HomeGrid.place(prefs.homePages, cols, rows, app.pkg, pageIndex)
                        if (left.size <= 1) dissolveFolder(id)
                    },
                ), onDismiss = { inMenu = null })
            }
        }
    }
    shortcutMenu?.let { sv ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        GlassMenu(listOf(
            MenuItem("שינוי סדר ועריכה", onClick = onEnterEdit),
            MenuItem("הסרה ממסך הבית", danger = true) {
                prefs.homePages = prefs.homePages.map { page -> page.map { if (it == sv) null else it } }
                com.glass.launcher.data.Shortcuts.unpin(ctx, sv)
            },
        ), onDismiss = { shortcutMenu = null })
    }
    folderMenu?.let { id ->
        GlassMenu(listOf(
            MenuItem("שינוי שם") { renameFolder = id },
            MenuItem("שינוי סדר ועריכה", onClick = onEnterEdit),
            MenuItem("פירוק התיקייה", danger = true) { dissolveFolder(id) },
        ), onDismiss = { folderMenu = null })
    }
    renameFolder?.let { id ->
        TextPrompt("שם התיקייה", prefs.homeFolders[id]?.first ?: "", onDone = { name, _ ->
            val f = prefs.homeFolders[id]
            if (f != null && name.isNotBlank()) prefs.homeFolders = prefs.homeFolders + (id to (name.trim() to f.second))
            renameFolder = null
        }, onCancel = { renameFolder = null })
    }
    if (widgetMenu >= 0) {
        val idx = widgetMenu
        val v = cells.getOrNull(idx)
        if (v == null || !HomeGrid.isWidget(v)) { widgetMenu = -1 } else {
            val (key, size) = HomeGrid.parse(v)
            GlassMenu(
                listOf(MenuItem("שינוי סדר ועריכה", onClick = onEnterEdit)) +
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
                    } + MenuItem("החלפת התוכן (אותו גודל ומקום)") { onReplaceWidget(idx, v) } + MenuItem("הסרת הווידג'ט", danger = true) {
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
    version: Int,
    dock: List<String?>,
    byPkg: Map<String, AppEntry>,
    editMode: Boolean,
    onOpen: (AppEntry) -> Unit,
    onEnterEdit: () -> Unit,
) {
    val prefs = App.instance.prefs
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    val drag = LocalIconDrag.current
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .onGloballyPositioned { drag.dockRect = it.boundsInRoot() }
            .then(if (prefs.dockBackground) Modifier.glass(RoundedCornerShape(34.dp)) else Modifier)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (dock + List(6) { null }).take(prefs.dockCount).forEachIndexed { i, pkg ->
            val e = pkg?.let { byPkg[it] }?.takeIf { prefs.isVisible(it.pkg) }
            Box(Modifier.size((prefs.iconDp() + 2).dp), contentAlignment = Alignment.Center) {
                // A pinned shortcut (e.g. direct dial) can sit in the Dock too.
                if (e == null && com.glass.launcher.data.Shortcuts.isShortcut(pkg)) Wiggle(editMode, i + 50) {
                    val sv = pkg!!
                    val src = DragSource(-1, i)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { prefs.iconDp().dp.roundToPx() }
                    val bmp = remember(sv) { com.glass.launcher.data.Shortcuts.icon(ctx, sv, sizePx)?.asImageBitmap() }
                    var menu by remember { mutableStateOf(false) }
                    Box(Modifier.size(prefs.iconDp().dp)
                        .graphicsLayer { alpha = if (drag.pkg == sv && drag.source == src) 0f else 1f }
                        .dragIcon(sv, src, editMode, onTap = { com.glass.launcher.data.Shortcuts.open(ctx, sv) }, onMenu = { menu = true }, onEnterEdit = onEnterEdit)) {
                        if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize())
                        else Box(Modifier.fillMaxSize().glass(SquircleShape))
                    }
                    if (menu) GlassMenu(listOf(
                        MenuItem("שינוי סדר ועריכה", onClick = onEnterEdit),
                        MenuItem("הוצאה מה-Dock", danger = true) { prefs.dock = prefs.dock.map { if (it == sv) null else it } },
                    ), onDismiss = { menu = false })
                }
                if (e != null) Wiggle(editMode, i + 50) {
                    val src = DragSource(-1, i)
                    val beingDragged = drag.pkg == e.pkg && drag.source == src
                    AppIcon(
                        e, prefs.iconDp().dp, false,
                        onClick = { onOpen(e) },
                        onLongClick = { if (!editMode) menuFor = e },
                        gesture = Modifier.dragIcon(e.pkg, src, editMode, onTap = { onOpen(e) }, onMenu = { menuFor = e }, onEnterEdit = onEnterEdit),
                        modifier = Modifier.graphicsLayer { alpha = if (beingDragged) 0f else 1f },
                    )
                }
            }
        }
    }
    menuFor?.let { e ->
        AppMenu(e, onHome = true, inDock = true, onEdit = onEnterEdit, onDismiss = { menuFor = null })
    }
}
