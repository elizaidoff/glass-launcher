package com.glass.launcher.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.App
import com.glass.launcher.ui.KitText
import com.glass.launcher.ui.hideSystemNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Colours of iPhone's Messages, light and dark. */
private class MsgColors(val dark: Boolean) {
    val bg = if (dark) Color.Black else Color.White
    val text = if (dark) Color.White else Color.Black
    val sub = if (dark) Color(0xFF8E8E93) else Color(0xFF8A8A8E)
    val sep = if (dark) Color(0xFF38383A) else Color(0xFFC6C6C8)
    val field = if (dark) Color(0xFF1C1C1E) else Color(0xFFEFEFF0)
    val inBubble = if (dark) Color(0xFF26252A) else Color(0xFFE9E9EB)
    val bar = if (dark) Color(0xF0161618) else Color(0xF0F7F7F7)
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
}

class MessagesActivity : ComponentActivity() {

    private val openThread = mutableStateOf<Long?>(null)
    private val draftTo = mutableStateOf<List<String>?>(null)
    private val draftText = mutableStateOf("")
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SmsStore.observe(this)
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val c = remember(dark) { MsgColors(dark) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KitText { Root(c) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemNav()
        tick.intValue++
        SmsStore.clearNames()
        SmsStore.bump()
    }

    override fun onPause() {
        super.onPause()
        openThreadAddress = null
    }

    /** "smsto:050..." from other apps, or a tapped notification. */
    private fun handle(i: Intent?) {
        i ?: return
        val t = i.getLongExtra("thread", -1L)
        if (t > 0) { openThread.value = t; return }
        val data = i.data
        if (data != null && data.scheme in setOf("sms", "smsto", "mms", "mmsto")) {
            val to = data.schemeSpecificPart.substringBefore("?").split(",", ";").map { Uri.decode(it).trim() }.filter { it.isNotEmpty() }
            val body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (to.isNotEmpty()) {
                val th = SmsStore.threadFor(this, to)
                draftText.value = body
                if (th > 0 && SmsStore.conversations(this).any { it.threadId == th }) openThread.value = th
                else draftTo.value = to
            } else { draftTo.value = emptyList(); draftText.value = body }
        } else if (i.action == Intent.ACTION_SEND) {
            draftText.value = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            draftTo.value = emptyList()
        }
    }

    fun requestDefault() {
        val rm = getSystemService(RoleManager::class.java)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 5) }
    }

    @Composable
    private fun Root(c: MsgColors) {
        val ctx = LocalContext.current
        var granted by remember { mutableStateOf(hasPerms(ctx)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerms(ctx) }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            if (!granted) {
                PermissionScreen(c) { ask.launch(neededPerms) }
                return@Box
            }
            val thread by openThread
            val newTo by draftTo
            ConversationList(c, onOpen = { openThread.value = it }, onNew = { draftTo.value = emptyList() })
            AnimatedVisibility(thread != null, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                val t = thread ?: return@AnimatedVisibility
                ThreadScreen(c, t, null, draftText.value, onBack = { openThread.value = null; draftText.value = "" })
            }
            AnimatedVisibility(newTo != null, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                NewMessage(c, newTo ?: emptyList(), draftText.value, onClose = { draftTo.value = null; draftText.value = "" }, onSent = { th ->
                    draftTo.value = null; draftText.value = ""; if (th > 0) openThread.value = th
                })
            }
        }
    }

    companion object {
        /** The number on screen right now (no notification for it). */
        @Volatile var openThreadAddress: String? = null

        val neededPerms = arrayOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_PHONE_NUMBERS,
        )

        fun hasPerms(ctx: Context) = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

// ====================================================================== pieces

@Composable
private fun PermissionScreen(c: MsgColors, onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppGlyph(84)
        Spacer(Modifier.height(24.dp))
        Text("הודעות", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("כדי להציג ולשלוח הודעות צריך לאשר גישה להודעות ולאנשי הקשר. ההודעות נשארות רק בטלפון.", color = c.sub, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(c.blue).clickable(onClick = onAsk), contentAlignment = Alignment.Center) {
            Text("אישור", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AppGlyph(size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.23f).dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF6CF17E), Color(0xFF0CBD2A)))),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((size * 0.56f).dp, (size * 0.44f).dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

/** Round contact picture, or initials on grey like iPhone. */
@Composable
private fun Avatar(addresses: List<String>, size: Int) {
    val ctx = LocalContext.current
    val first = addresses.firstOrNull() ?: ""
    val photo by produceState<ImageBitmap?>(null, first) {
        value = withContext(Dispatchers.IO) {
            SmsStore.photo(ctx, first)?.let { u -> runCatching { ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
        }
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFA5ABB8), Color(0xFF858994)))),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val name = SmsStore.name(ctx, first)
            val initials = if (addresses.size > 1) "👥" else if (name.firstOrNull()?.isDigit() == true || name.startsWith("+")) "" else
                name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }
            if (initials.isNotEmpty()) Text(initials, color = Color.White, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Medium)
            else Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("d.M.yy")

private fun listTime(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (val d = z.toLocalDate()) {
        today -> z.format(timeFmt)
        today.minusDays(1) -> "אתמול"
        else -> if (d.isAfter(today.minusDays(7))) hebDay(d.dayOfWeek.value) else z.format(dateFmt)
    }
}

private fun hebDay(dow: Int) = when (dow) { 7 -> "יום ראשון"; 1 -> "יום שני"; 2 -> "יום שלישי"; 3 -> "יום רביעי"; 4 -> "יום חמישי"; 5 -> "יום שישי"; else -> "שבת" }

private fun headerTime(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val d = z.toLocalDate()
    val day = when {
        d == today -> "היום"
        d == today.minusDays(1) -> "אתמול"
        d.isAfter(today.minusDays(7)) -> hebDay(d.dayOfWeek.value)
        else -> z.format(DateTimeFormatter.ofPattern("d.M.yyyy"))
    }
    return "$day ${z.format(timeFmt)}"
}

// ====================================================================== list

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationList(c: MsgColors, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as MessagesActivity
    val changes by SmsStore.changes.collectAsState()
    val list by produceState(emptyList<Conversation>(), changes) { value = withContext(Dispatchers.IO) { SmsStore.conversations(ctx) } }
    var q by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    val isDefault = remember(changes) { SmsStore.isDefault(ctx) }
    val state = rememberLazyListState()
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 80 } }
    val barA by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    val shown = remember(list, q) {
        if (q.isBlank()) list else list.filter { cv ->
            SmsStore.title(ctx, cv).contains(q, true) || cv.snippet.contains(q, true) || cv.addresses.any { it.contains(q) }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp)) {
            Text("עריכה", color = c.blue, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { alpha = 0f })
            Text("הודעות", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = barA })
            Icon(Icons.Rounded.Edit, "הודעה חדשה", tint = c.blue, modifier = Modifier.align(Alignment.CenterEnd).size(26.dp).clickable(onClick = onNew))
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Text("הודעות", color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp))
                SearchBox(c, q) { q = it }
                if (!isDefault) {
                    Row(
                        Modifier.padding(16.dp, 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.field)
                            .clickable { act.requestDefault() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("לקבל הודעות כאן", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("הגדרה כאפליקציית ההודעות של הטלפון – לסימון כנקרא, מחיקה והתראות בעיצוב הזה.", color = c.sub, fontSize = 13.sp)
                        }
                        Text("הגדרה", color = c.blue, fontSize = 15.sp)
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(if (q.isBlank()) "אין הודעות" else "אין תוצאות", color = c.sub, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 80.dp))
            }
            items(shown, key = { it.threadId }) { cv ->
                SwipeRow(c, onDelete = {
                    if (!SmsStore.isDefault(ctx)) Toast.makeText(ctx, "מחיקה אפשרית רק כש'הודעות' היא אפליקציית ברירת המחדל", Toast.LENGTH_LONG).show()
                    else SmsStore.deleteThread(ctx, cv.threadId)
                }) {
                    Row(
                        Modifier.fillMaxWidth().background(c.bg)
                            .combinedClickable(onClick = { onOpen(cv.threadId) }, onLongClick = { menuFor = cv })
                            .padding(start = 4.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                            if (cv.unread) Box(Modifier.size(10.dp).clip(CircleShape).background(c.blue))
                        }
                        Avatar(cv.addresses, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(SmsStore.title(ctx, cv), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(listTime(cv.date), color = c.sub, fontSize = 15.sp)
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.sub.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                            }
                            Text(cv.snippet.ifBlank { "📷 תמונה" }, color = c.sub, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 78.dp).height(0.5.dp).background(c.sep))
                }
            }
        }
    }
    menuFor?.let { cv ->
        ActionSheet(c, SmsStore.title(ctx, cv), listOf(
            "סימון כנקרא" to { SmsStore.markRead(ctx, cv.threadId); Unit },
            "מחיקת השיחה" to {
                if (SmsStore.isDefault(ctx)) SmsStore.deleteThread(ctx, cv.threadId)
                else Toast.makeText(ctx, "מחיקה אפשרית רק כש'הודעות' היא אפליקציית ברירת המחדל", Toast.LENGTH_LONG).show()
                Unit
            },
        ), onDismiss = { menuFor = null })
    }
}

@Composable
private fun SearchBox(c: MsgColors, q: String, onChange: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(c.field).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text("חיפוש", color = c.sub, fontSize = 17.sp)
            BasicTextField(q, onChange, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 17.sp), modifier = Modifier.fillMaxWidth(),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue))
        }
        if (q.isNotEmpty()) Icon(Icons.Rounded.Close, null, tint = c.sub, modifier = Modifier.size(18.dp).clickable { onChange("") })
    }
}

/** Swipe the row sideways to reveal a red Delete button, like iPhone. */
@Composable
private fun SwipeRow(c: MsgColors, onDelete: () -> Unit, content: @Composable () -> Unit) {
    var off by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val max = with(density) { 88.dp.toPx() }
    val shown by animateFloatAsState(off, label = "swipe")
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.align(Alignment.CenterStart).width(88.dp).height(72.dp).background(c.red).clickable { off = 0f; onDelete() },
            contentAlignment = Alignment.Center,
        ) { Text("מחיקה", color = Color.White, fontSize = 15.sp) }
        Box(
            Modifier.offset { IntOffset(shown.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { off = if (off < -max / 2) -max else 0f },
                        onHorizontalDrag = { ch, d -> ch.consume(); off = (off + d).coerceIn(-max * 1.3f, 0f) },
                    )
                }
        ) { content() }
    }
}

@Composable
private fun ActionSheet(c: MsgColors, title: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(290.dp).clip(RoundedCornerShape(16.dp)).background(c.field)) {
            Text(title, color = c.sub, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(14.dp))
            actions.forEach { (t, a) ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.sep))
                Text(t, color = if (t.contains("מחיקה")) c.red else c.blue, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { a(); onDismiss() }.padding(14.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.sep))
            Text("ביטול", color = c.blue, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(14.dp))
        }
    }
}

// ====================================================================== thread

/** Bubble with iPhone's little tail on the last bubble of a run. In RTL, sent bubbles sit on the left. */
private fun bubbleShape(outgoing: Boolean, tail: Boolean, rtl: Boolean) = GenericShape { size, _ ->
    val rad = minOf(size.height / 2, 54f)
    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(rad, rad)))
    if (tail) {
        // the tail points to the side the bubble sits on
        val left = outgoing == rtl
        val w = size.width
        val h = size.height
        if (left) {
            moveTo(10f, h - 18f); quadraticTo(6f, h - 2f, -6f, h); quadraticTo(12f, h + 1f, 22f, h - 6f); close()
        } else {
            moveTo(w - 10f, h - 18f); quadraticTo(w - 6f, h - 2f, w + 6f, h); quadraticTo(w - 12f, h + 1f, w - 22f, h - 6f); close()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadScreen(c: MsgColors, threadId: Long, toOverride: List<String>?, initialText: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val changes by SmsStore.changes.collectAsState()
    val msgs by produceState(emptyList<Message>(), changes, threadId) { value = withContext(Dispatchers.IO) { SmsStore.messages(ctx, threadId) } }
    val addresses by produceState(toOverride ?: emptyList(), threadId, changes) {
        value = toOverride ?: withContext(Dispatchers.IO) { SmsStore.addressesOf(ctx, threadId) }
    }
    LaunchedEffect(threadId, msgs.size) {
        withContext(Dispatchers.IO) { SmsStore.markRead(ctx, threadId) }
        addresses.firstOrNull()?.let { MessagesActivity.openThreadAddress = it; MessageNotifier.cancel(ctx, it) }
    }
    val green = App.instance.prefs.messagesGreen
    val sentColor = if (green) c.green else c.blue
    var menuFor by remember { mutableStateOf<Message?>(null) }
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.scrollToItem(0) }
    val rtl = true
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        // Top bar: back, small round picture and the name under it.
        Column(Modifier.fillMaxWidth().background(c.bar).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(70.dp)) {
                Row(Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "חזרה", tint = c.blue, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(addresses, 40)
                    Spacer(Modifier.height(3.dp))
                    Text(addresses.joinToString(", ") { SmsStore.name(ctx, it) }, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp).clickable {
                            addresses.firstOrNull()?.let { a ->
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(a))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure {
                                    ctx.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, a))
                                }
                            }
                        })
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.sep))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, reverseLayout = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            val rev = msgs.asReversed()
            val lastOut = msgs.lastOrNull { it.outgoing }
            items(rev.size, key = { i -> (if (rev[i].mms) "m" else "s") + rev[i].id }) { i ->
                val m = rev[i]
                val older = rev.getOrNull(i + 1)
                val newer = rev.getOrNull(i - 1)
                val showTime = older == null || m.date - older.date > 60 * 60 * 1000
                val tail = newer == null || newer.outgoing != m.outgoing || newer.date - m.date > 60 * 1000
                Column(Modifier.fillMaxWidth()) {
                    if (showTime) Text(headerTime(m.date), color = c.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
                    if (!m.outgoing && addresses.size > 1 && (older == null || older.address != m.address || older.outgoing)) {
                        Text(SmsStore.name(ctx, m.address), color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp))
                    }
                    Bubble(c, m, tail, if (m.outgoing) sentColor else c.inBubble, rtl, onLong = { menuFor = m })
                    if (m == lastOut) {
                        val label = when (m.status) {
                            Message.Status.Delivered -> "נמסר"
                            Message.Status.Sent -> "נשלח"
                            Message.Status.Sending -> "שולח…"
                            Message.Status.Failed -> "לא נשלח · הקש לניסיון חוזר"
                            else -> null
                        }
                        if (label != null) Text(label, color = if (m.status == Message.Status.Failed) c.red else c.sub, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 6.dp).clickable(enabled = m.status == Message.Status.Failed) {
                                if (SmsSender.send(ctx, addresses, m.body, m.images)) SmsStore.deleteMessage(ctx, m)
                            })
                    }
                    Spacer(Modifier.height(if (tail) 8.dp else 2.dp))
                }
            }
        }
        Composer(c, initialText, sentColor, onSend = { text, imgs ->
            if (addresses.isEmpty()) false
            else SmsSender.send(ctx, addresses, text, imgs).also { if (!it) Toast.makeText(ctx, "השליחה נכשלה", Toast.LENGTH_SHORT).show() }
        })
    }
    menuFor?.let { m ->
        ActionSheet(c, "", listOfNotNull(
            if (m.body.isNotBlank()) "העתקה" to {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("msg", m.body)); Unit
            } else null,
            "העברה" to {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.body), "העברה")); Unit
            },
            "מחיקה" to {
                if (SmsStore.isDefault(ctx)) SmsStore.deleteMessage(ctx, m)
                else Toast.makeText(ctx, "מחיקה אפשרית רק כש'הודעות' היא אפליקציית ברירת המחדל", Toast.LENGTH_LONG).show()
                Unit
            },
        ), onDismiss = { menuFor = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(c: MsgColors, m: Message, tail: Boolean, color: Color, rtl: Boolean, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val textColor = if (m.outgoing) Color.White else c.text
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        m.images.forEach { u ->
            val img by produceState<ImageBitmap?>(null, u) { value = withContext(Dispatchers.IO) { loadThumb(ctx, u) } }
            Box(
                Modifier.padding(vertical = 2.dp).widthIn(max = 240.dp).heightIn(min = 120.dp, max = 320.dp).clip(RoundedCornerShape(18.dp))
                    .background(c.field)
                    .combinedClickable(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }, onLongClick = onLong),
            ) { img?.let { Image(it, null, contentScale = ContentScale.Fit) } }
        }
        if (m.body.isNotBlank()) {
            val emojiOnly = m.body.length <= 6 && m.body.none { it.isLetterOrDigit() }
            if (emojiOnly) Text(m.body, fontSize = 44.sp, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLong).padding(4.dp))
            else Box(
                Modifier.widthIn(max = 290.dp)
                    .background(color, bubbleShape(m.outgoing, tail, rtl))
                    .combinedClickable(onClick = {}, onLongClick = onLong)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Text(m.body, color = textColor, fontSize = 17.sp, lineHeight = 22.sp)
            }
        }
    }
}

private fun loadThumb(ctx: Context, u: Uri): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > 900) s *= 2
    ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap() }
}.getOrNull()

/** The bottom bar: + for a picture, the text field, and the round blue arrow. */
@Composable
private fun Composer(c: MsgColors, initial: String, sendColor: Color, onSend: (String, List<Uri>) -> Boolean) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    val imgs = remember { mutableStateListOf<Uri>() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) imgs += u }
    val view = androidx.compose.ui.platform.LocalView.current
    Column(Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding()) {
        if (imgs.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(start = 52.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(imgs.toList()) { u ->
                val img by produceState<ImageBitmap?>(null, u) { value = withContext(Dispatchers.IO) { loadThumb(ctx, u) } }
                Box(Modifier.size(70.dp).clip(RoundedCornerShape(12.dp)).background(c.field)) {
                    img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { imgs -= u }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(c.field).clickable {
                    pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "תמונה", tint = c.sub, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
                    .background(c.bg).then(Modifier.background(Color.Transparent))
                    .padding(start = 12.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 5.dp)) {
                    if (text.isEmpty()) Text(if (imgs.isEmpty()) "הודעת טקסט" else "הוספת כיתוב", color = c.sub, fontSize = 17.sp)
                    BasicTextField(text, { text = it }, textStyle = TextStyle(color = c.text, fontSize = 17.sp), maxLines = 6, modifier = Modifier.fillMaxWidth(),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue))
                }
                val can = text.isNotBlank() || imgs.isNotEmpty()
                AnimatedVisibility(can, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(sendColor).clickable {
                            if (onSend(text.trim(), imgs.toList())) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                text = ""; imgs.clear()
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ArrowUpward, "שליחה", tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

// ====================================================================== new message

@Composable
private fun NewMessage(c: MsgColors, initialTo: List<String>, initialText: String, onClose: () -> Unit, onSent: (Long) -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val to = remember { mutableStateListOf<String>().apply { addAll(initialTo) } }
    var q by remember { mutableStateOf("") }
    val found by produceState(emptyList<com.glass.launcher.feature.Spotlight.Contact>(), q) {
        value = withContext(Dispatchers.IO) { com.glass.launcher.feature.Spotlight.contacts(ctx, q) }
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Column(Modifier.fillMaxWidth().background(c.bar).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp)) {
                Text("הודעה חדשה", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Text("ביטול", color = c.blue, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onClose))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("אל:", color = c.sub, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                LazyRow(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(to.toList()) { a ->
                        Text(SmsStore.name(ctx, a), color = c.blue, fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.blue.copy(alpha = 0.12f)).clickable { to -= a }.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 16.sp), modifier = Modifier.weight(1f),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.blue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        val n = q.filter { it.isDigit() || it == '+' }
                        if (n.length >= 3) { to += n; q = "" }
                    }))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.sep))
        }
        LazyColumn(Modifier.weight(1f)) {
            val typed = q.filter { it.isDigit() || it == '+' }
            if (typed.length >= 3 && found.none { it.number?.filter { ch -> ch.isDigit() } == typed.filter { ch -> ch.isDigit() } }) item {
                Text("שליחה אל $typed", color = c.blue, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { to += typed; q = "" }.padding(16.dp))
            }
            items(found, key = { it.lookup.toString() + it.number }) { ct ->
                Row(Modifier.fillMaxWidth().clickable { ct.number?.let { to += it }; q = "" }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(listOfNotNull(ct.number), 36)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(ct.name, color = c.text, fontSize = 17.sp)
                        ct.number?.let { Text(it, color = c.sub, fontSize = 14.sp) }
                    }
                }
            }
        }
        Composer(c, initialText, if (App.instance.prefs.messagesGreen) c.green else c.blue, onSend = { text, imgs ->
            if (to.isEmpty()) { Toast.makeText(ctx, "בחר למי לשלוח", Toast.LENGTH_SHORT).show(); false }
            else {
                val ok = SmsSender.send(ctx, to.toList(), text, imgs)
                if (ok) onSent(SmsStore.threadFor(ctx, to.toList())) else Toast.makeText(ctx, "השליחה נכשלה", Toast.LENGTH_SHORT).show()
                ok
            }
        })
    }
}
