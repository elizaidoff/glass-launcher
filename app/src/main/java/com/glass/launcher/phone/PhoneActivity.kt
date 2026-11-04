package com.glass.launcher.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AccessTimeFilled
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.feature.Spotlight
import com.glass.launcher.messages.SmsStore
import com.glass.launcher.ui.KitText
import com.glass.launcher.ui.hideSystemNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class PColors(dark: Boolean) {
    val bg = if (dark) Color.Black else Color.White
    val text = if (dark) Color.White else Color.Black
    val sub = if (dark) Color(0xFF8E8E93) else Color(0xFF8A8A8E)
    val sep = if (dark) Color(0xFF38383A) else Color(0xFFC6C6C8)
    val bar = if (dark) Color(0xF0161618) else Color(0xF0F7F7F7)
    val field = if (dark) Color(0xFF1C1C1E) else Color(0xFFEFEFF0)
    val blue = Color(0xFF0A84FF)
    val red = Color(0xFFFF3B30)
    val green = Color(0xFF34C759)
}

/** iPhone's Phone app: Favourites, Recents, Contacts, Keypad (tabs at the bottom). */
class PhoneActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(3)
    private val prefill = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handle(intent)
        setContent {
            val c = PColors(isSystemInDarkTheme())
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { KitText { Root(c) } }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); hideSystemNav() }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "tel") { prefill.value = d.schemeSpecificPart ?: ""; tab.intValue = 3 }
    }

    fun requestDialer() {
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER), 7) }
    }

    @Composable
    private fun Root(c: PColors) {
        val ctx = LocalContext.current
        var granted by remember { mutableStateOf(has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.READ_CONTACTS)) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = has(Manifest.permission.READ_CALL_LOG) && has(Manifest.permission.READ_CONTACTS)
        }
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!granted && tab.intValue != 3) {
                    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("כדי להציג שיחות ואנשי קשר צריך אישור.", color = c.sub, fontSize = 17.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Text("אישור", color = c.blue, fontSize = 17.sp, modifier = Modifier.clickable {
                            ask.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
                        })
                    }
                } else when (tab.intValue) {
                    0 -> Favorites(c)
                    1 -> Recents(c)
                    2 -> Contacts(c)
                    else -> Dialer(c, prefill.value)
                }
            }
            // Bottom tab bar
            Column(Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.sep))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TabItem(c, Icons.Rounded.Star, "מועדפים", tab.intValue == 0) { tab.intValue = 0 }
                    TabItem(c, Icons.Rounded.AccessTimeFilled, "אחרונים", tab.intValue == 1) { tab.intValue = 1 }
                    TabItem(c, Icons.Rounded.AccountCircle, "אנשי קשר", tab.intValue == 2) { tab.intValue = 2 }
                    TabItem(c, Icons.Rounded.Dialpad, "מקלדת", tab.intValue == 3) { tab.intValue = 3 }
                    TabItem(c, Icons.Rounded.Voicemail, "תא קולי", false) {
                        CallManager.dial(ctx, voicemail(ctx))
                    }
                }
            }
        }
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun voicemail(ctx: android.content.Context): String = runCatching {
        ctx.getSystemService(android.telephony.TelephonyManager::class.java).voiceMailNumber
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "*111"
}

@Composable
private fun TabItem(c: PColors, icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (on) c.blue else c.sub, modifier = Modifier.size(26.dp))
        Text(label, color = if (on) c.blue else c.sub, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Title(c: PColors, t: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private fun call(ctx: android.content.Context, n: String) { if (n.isNotBlank()) CallManager.dial(ctx, n) }

// ---------------------------------------------------------------- keypad

@Composable
private fun Dialer(c: PColors, prefill: String) {
    val ctx = LocalContext.current
    var number by remember(prefill) { mutableStateOf(prefill) }
    val match by produceState<Spotlight.Contact?>(null, number) {
        value = if (number.length >= 3) withContext(Dispatchers.IO) { Spotlight.contacts(ctx, number).firstOrNull() } else null
    }
    val light = !isSystemInDarkTheme()
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text(number, color = c.text, fontSize = if (number.length > 12) 30.sp else 38.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
        Text(match?.name ?: if (number.isNotEmpty() && match == null && number.length >= 9) "הוספת מספר" else "", color = c.blue, fontSize = 16.sp,
            modifier = Modifier.height(24.dp).clickable(enabled = match == null && number.length >= 9) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, number)) }
            })
        Spacer(Modifier.height(18.dp))
        Keypad(light = light, onLong = { d -> if (d == "0") number += "+" }) { d -> number += d }
        Spacer(Modifier.height(18.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(106.dp))
                Box(Modifier.size(80.dp).clip(CircleShape).background(c.green).clickable {
                    if (number.isBlank()) number = CallManager.log(ctx, false).firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE }?.number ?: ""
                    else call(ctx, number)
                }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Call, "חיוג", tint = Color.White, modifier = Modifier.size(36.dp)) }
                Spacer(Modifier.width(26.dp))
                if (number.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.Backspace, "מחיקה", tint = c.sub,
                    modifier = Modifier.size(34.dp).clickable { number = number.dropLast(1) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- recents

private val tf = DateTimeFormatter.ofPattern("HH:mm")
private fun whenText(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val t = LocalDate.now()
    return when (z.toLocalDate()) {
        t -> z.format(tf)
        t.minusDays(1) -> "אתמול"
        else -> z.format(DateTimeFormatter.ofPattern("d.M.yy"))
    }
}

@Composable
private fun Recents(c: PColors) {
    val ctx = LocalContext.current
    var missed by remember { mutableStateOf(false) }
    val tickState = remember { mutableIntStateOf(0) }
    val list by produceState(emptyList<CallManager.LogEntry>(), missed, tickState.intValue) { value = withContext(Dispatchers.IO) { CallManager.log(ctx, missed) } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(9.dp)).background(c.field).padding(2.dp)) {
                listOf(false to "הכול", true to "שיחות שלא נענו").forEach { (m, t) ->
                    Text(t, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (missed == m) c.bg else Color.Transparent)
                            .clickable { missed = m }.padding(horizontal = 18.dp, vertical = 6.dp))
                }
            }
        }
        Title(c, "אחרונים")
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            items(list) { e ->
                val isMissed = e.type == CallLog.Calls.MISSED_TYPE || e.type == CallLog.Calls.REJECTED_TYPE
                Row(Modifier.fillMaxWidth().clickable { call(ctx, e.number) }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(22.dp)) { if (e.type == CallLog.Calls.OUTGOING_TYPE) Icon(Icons.Rounded.CallMade, null, tint = c.sub, modifier = Modifier.size(16.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(SmsStore.name(ctx, e.number) + if (e.count > 1) " (${e.count})" else "", color = if (isMissed) c.red else c.text,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("נייד", color = c.sub, fontSize = 14.sp)
                    }
                    Text(whenText(e.date), color = c.sub, fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.Info, "פרטים", tint = c.blue, modifier = Modifier.size(24.dp).clickable { openContact(ctx, e.number) })
                }
                Box(Modifier.fillMaxWidth().padding(start = 38.dp).height(0.5.dp).background(c.sep))
            }
        }
    }
}

private fun openContact(ctx: android.content.Context, number: String) {
    val lookup = runCatching {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null,
        )?.use { c -> if (c.moveToFirst()) ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)) else null }
    }.getOrNull()
    runCatching {
        if (lookup != null) ctx.startActivity(Intent(Intent.ACTION_VIEW, lookup))
        else ctx.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, number))
    }
}

// ---------------------------------------------------------------- contacts & favourites

private data class Person(val name: String, val number: String?, val starred: Boolean)

private fun people(ctx: android.content.Context, starredOnly: Boolean): List<Person> {
    val out = ArrayList<Person>()
    runCatching {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED),
            if (starredOnly) "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1" else null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                if (seen.add(n)) out += Person(n, c.getString(1), c.getInt(2) == 1)
            }
        }
    }
    return out
}

@Composable
private fun Contacts(c: PColors) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val all by produceState(emptyList<Person>()) { value = withContext(Dispatchers.IO) { people(ctx, false) } }
    val shown = remember(all, q) { if (q.isBlank()) all else all.filter { it.name.contains(q, true) || (it.number ?: "").contains(q) } }
    Column(Modifier.fillMaxSize()) {
        Title(c, "אנשי קשר") {
            Text("+", color = c.blue, fontSize = 30.sp, modifier = Modifier.clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)) }
            })
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(c.field).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("חיפוש", color = c.sub, fontSize = 17.sp)
                androidx.compose.foundation.text.BasicTextField(q, { q = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 17.sp), modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp, top = 6.dp)) {
            val groups = shown.groupBy { it.name.take(1).uppercase() }
            groups.forEach { (letter, list) ->
                item(key = "h$letter") {
                    Text(letter, color = c.sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(c.field.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 3.dp))
                }
                items(list, key = { "p" + it.name }) { p ->
                    Text(p.name, color = c.text, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable { p.number?.let { openContact(ctx, it) } }.padding(horizontal = 16.dp, vertical = 11.dp))
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(c.sep))
                }
            }
        }
    }
}

@Composable
private fun Favorites(c: PColors) {
    val ctx = LocalContext.current
    val list by produceState(emptyList<Person>()) { value = withContext(Dispatchers.IO) { people(ctx, true) } }
    Column(Modifier.fillMaxSize()) {
        Title(c, "מועדפים")
        if (list.isEmpty()) Text("אין מועדפים. סמן כוכב ליד איש קשר כדי שיופיע כאן.", color = c.sub, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
        LazyColumn {
            items(list) { p ->
                Row(Modifier.fillMaxWidth().clickable { p.number?.let { call(ctx, it) } }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF9EA3AE)), contentAlignment = Alignment.Center) {
                        Text(p.name.take(1), color = Color.White, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("נייד", color = c.sub, fontSize = 14.sp)
                    }
                    Icon(Icons.Rounded.Info, null, tint = c.blue, modifier = Modifier.size(24.dp).clickable { p.number?.let { openContact(ctx, it) } })
                }
                Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.sep))
            }
        }
    }
}
