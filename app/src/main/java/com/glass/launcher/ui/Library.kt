package com.glass.launcher.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.data.AppEntry
import com.glass.launcher.lock.Auth
import com.glass.launcher.lock.LockGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Group(val name: String, val apps: List<AppEntry>, val secret: Boolean = false)

@Composable
fun AppLibrary(all: List<AppEntry>, act: Activity, onOpen: (AppEntry) -> Unit) {
    val prefs = App.instance.prefs
    val repo = App.instance.repo
    val tick by prefs.version.collectAsState()
    val revealed by LockGuard.hiddenRevealed.collectAsState()
    var query by remember { mutableStateOf("") }
    var openGroup by remember { mutableStateOf<Group?>(null) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val visible = remember(all, tick) { all.filter { prefs.isVisible(it.pkg) } }
    // Apps already on the home screen or in the Dock are not repeated in the library cards.
    val onHome = remember(tick) {
        (prefs.homePages.flatten() + prefs.dock).filterNotNull().filter { !com.glass.launcher.data.HomeGrid.isWidget(it) }.toSet()
    }
    val inLibrary = remember(visible, onHome) { visible.filter { it.pkg !in onHome } }
    val groups = remember(inLibrary, visible, tick) {
        val allVisible = visible
        val visible = inLibrary
        buildList {
            val byPkg = visible.associateBy { it.pkg }
            val rec = prefs.recents.mapNotNull { byPkg[it] }.take(8)
            if (prefs.libSuggestions && rec.isNotEmpty()) add(Group("הצעות", rec))
            val inFolder = prefs.folderOf
            // Your own folders: they hold whatever you put in them, also apps that are on the home screen.
            prefs.folders.filter { !it.hidden }.forEach { f ->
                add(Group(f.name, allVisible.filter { inFolder[it.pkg] == f.name }))
            }
            if (prefs.libRecentlyAdded) add(Group("נוספו לאחרונה", visible.filter { !it.isSystem }.sortedByDescending { it.installTime }.take(8)))
            val loose = visible.filter { prefs.folderOf[it.pkg] == null }
            if (prefs.libAutoCategories) {
                loose.groupBy { repo.categoryName(shown(it)) }
                    .map { (n, a) -> Group(n, a) }
                    .sortedByDescending { it.apps.size }
                    .forEach { add(it) }
            } else {
                add(Group("שאר האפליקציות", loose))
            }
        }.filter { it.apps.isNotEmpty() }
    }
    val secretGroups = remember(all, tick, revealed) {
        if (!revealed) emptyList() else {
            val byPkg = all.associateBy { it.pkg }
            val hidden = prefs.hidden
            prefs.hiddenFolders.map { f -> Group(f, hidden.filterValues { it == f }.keys.mapNotNull { byPkg[it] }, secret = true) }
                .filter { it.apps.isNotEmpty() }
        }
    }

    fun openFromGroup(g: Group, e: AppEntry) {
        if (g.secret) LockGuard.open(ctx, e, alreadyAuthorized = true) else onOpen(e)
    }

    val gridState = rememberLazyGridState()
    val silentOk = remember { Auth.silentFingerprintAvailable(ctx) }
    val reveal = { view.thud(); LockGuard.hiddenRevealed.value = true }
    Box(
        Modifier.fillMaxSize().pointerInput(revealed, silentOk) {
            if (revealed) return@pointerInput
            // Secret gesture: at the bottom of the library, drag up and hold while touching the fingerprint sensor.
            // Nothing is shown on screen.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startY = down.position.y
                var armedAt = 0L
                var fingerOk = false
                var signal: android.os.CancellationSignal? = null
                var done = false
                while (!done) {
                    val ev = withTimeoutOrNull(100) { awaitPointerEvent(PointerEventPass.Initial) }
                    if (ev != null) {
                        val ch = ev.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed) break
                        if (armedAt == 0L && !gridState.canScrollForward && startY - ch.position.y > 60.dp.toPx()) {
                            armedAt = System.currentTimeMillis()
                            if (silentOk) {
                                val sig = android.os.CancellationSignal()
                                signal = sig
                                Auth.silentFingerprint(ctx, sig) { ok -> if (ok) fingerOk = true }
                            }
                        }
                    }
                    // Silent reading: open the moment the owner's finger is recognized.
                    if (armedAt > 0 && silentOk && fingerOk) { reveal(); done = true }
                }
                if (!done) signal?.cancel()
            }
        }
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(10.dp))
        SearchField(query, "ספריית אפליקציות") { query = it }
        Spacer(Modifier.height(16.dp))
        if (query.isNotBlank()) {
            val q = query.trim()
            AlphaList(visible.filter { shown(it).label.contains(q, ignoreCase = true) }, onOpen) { menuFor = it }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 30.dp),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
            ) {
                items(groups + secretGroups, key = { (if (it.secret) "s:" else "g:") + it.name }) { g ->
                    LibraryCard(g, prefs.showLabels, onOpen = { openFromGroup(g, it) }, onMore = { openGroup = g }, onLong = { if (!g.secret) menuFor = it })
                }
            }
        }
    }

    openGroup?.let { g ->
        GroupSheet(
            g.name, g.apps,
            onOpen = { openGroup = null; openFromGroup(g, it) },
            onClose = { openGroup = null },
            onLong = { openGroup = null; menuFor = it },
        )
    }
    menuFor?.let { e ->
        AppMenu(e, onHome = false, inDock = false, onEdit = null, onDismiss = { menuFor = null })
    }
    }
}

@Composable
fun SearchField(value: String, hint: String, autoFocus: Modifier = Modifier, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), 0.9f).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().then(autoFocus),
            )
        }
    }
}

@Composable
private fun LibraryCard(g: Group, showLabel: Boolean, onOpen: (AppEntry) -> Unit, onMore: () -> Unit, onLong: (AppEntry) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(1f).glass(RoundedCornerShape(26.dp))
                .then(if (g.apps.size > 4) Modifier.tap(onMore) else Modifier)
                .padding(14.dp)
        ) {
            val cell: Dp = (maxWidth - 12.dp) / 2
            val big = cell * 0.92f
            val many = g.apps.size > 4
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in 0 until 2) {
                            val i = r * 2 + c
                            Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                                if (many && i == 3) {
                                    MiniCluster(g.apps.drop(3).take(4), cell, onMore)
                                } else {
                                    g.apps.getOrNull(i)?.let { e ->
                                        AppIcon(e, big, false, onClick = { onOpen(e) }, onLongClick = { onLong(e) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(g.name, style = LabelStyle.copy(fontSize = 13.sp))
        }
    }
}

@Composable
private fun SecretFolderCard(act: Activity, showLabel: Boolean, onTap: () -> Unit, onReveal: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).glass(RoundedCornerShape(26.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val start = System.currentTimeMillis()
                        val signal = android.os.CancellationSignal()
                        var revealed = false
                        val job = scope.launch {
                            delay(450)
                            val listening = Auth.silentFingerprint(ctx, signal) { ok ->
                                if (ok) act.runOnUiThread { revealed = true; onReveal() }
                            }
                            // No pop-up ever: if the phone cannot read silently, nothing happens here.
                        }
                        tryAwaitRelease()
                        job.cancel()
                        if (!revealed) signal.cancel()
                        if (System.currentTimeMillis() - start < 400) onTap()
                    })
                }
        )
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text("אחר", style = LabelStyle.copy(fontSize = 13.sp))
        }
    }
}

@Composable
private fun MiniCluster(apps: List<AppEntry>, cell: Dp, onClick: () -> Unit) {
    val small = (cell - 6.dp) / 2
    Column(Modifier.tap(onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (c in 0 until 2) {
                Box(Modifier.size(small)) { apps.getOrNull(r * 2 + c)?.let { IconImage(it, small) } }
            }
        }
    }
}

@Composable
private fun AlphaList(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onLong: (AppEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        items(apps, key = { it.pkg }) { e ->
            Row(
                Modifier.fillMaxWidth().tap { onOpen(e) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconImage(e, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(shown(e).label, color = Color.White, fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun GroupSheet(
    title: String,
    apps: List<AppEntry>,
    onOpen: (AppEntry) -> Unit,
    onClose: () -> Unit,
    onLong: (AppEntry) -> Unit = {},
) {
    // Its own window, so nothing behind it (pages, library gestures) can take the touches.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
        ) {
            Box(
                Modifier.fillMaxSize().tap(onClose),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(20.dp)
                        .fillMaxWidth()
                        .background(Color(0xE61C1C1E), RoundedCornerShape(36.dp))
                        .glass(RoundedCornerShape(36.dp), 1.2f)
                        .tap { }
                        .padding(20.dp)
                ) {
                    if (title.isNotEmpty()) {
                        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.height(if (apps.isEmpty()) 200.dp else 420.dp),
                    ) {
                        items(apps, key = { it.pkg }) { e ->
                            AppIcon(e, 58.dp, App.instance.prefs.showLabels, onClick = { onOpen(e) }, onLongClick = { onLong(e) })
                        }
                    }
                }
            }
        }
    }
}
