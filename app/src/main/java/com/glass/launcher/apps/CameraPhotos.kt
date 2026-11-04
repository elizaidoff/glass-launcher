package com.glass.launcher.apps

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val yellow = Color(0xFFFFD60A)

// =============================================================== Camera

class CameraActivity : GlassApp() {
    @Composable override fun Content() = CameraScreen()
}

@Composable
private fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    var mode by remember { mutableIntStateOf(1) } // 0 video, 1 photo, 2 square
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var flashBlink by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember { VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    LaunchedEffect(Unit) { lastThumb = withContext(Dispatchers.IO) { latestThumb(ctx) } }
    LaunchedEffect(recording) { while (recording != null) { now = System.currentTimeMillis(); delay(500) } }
    DisposableEffect(granted, front, mode) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({
                provider = f.get()
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                provider?.unbindAll()
                camera = runCatching {
                    provider?.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        p, if (mode == 0) videoCapture else imageCapture)
                }.getOrNull()
                zoom = 1f
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose { provider?.unbindAll() }
    }
    fun shoot() {
        if (mode == 0) {
            val r = recording
            if (r != null) { r.stop(); recording = null; return }
            val name = "VID_" + System.currentTimeMillis()
            val out = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera") }).build()
            val pending = videoCapture.output.prepareRecording(ctx, out)
            val withAudio = if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending.withAudioEnabled() else pending
            recStart = System.currentTimeMillis()
            recording = withAudio.start(ContextCompat.getMainExecutor(ctx)) { e ->
                if (e is VideoRecordEvent.Finalize) { recording = null }
            }
            return
        }
        imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val name = "IMG_" + System.currentTimeMillis()
        val out = ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera") }).build()
        flashBlink = true
        imageCapture.takePicture(out, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                r.savedUri?.let { u -> Thread { val b = thumb(ctx, u); preview.post { lastThumb = b } }.start() }
            }
            override fun onError(e: ImageCaptureException) { Toast.makeText(ctx, "הצילום נכשל", Toast.LENGTH_SHORT).show() }
        })
    }
    LaunchedEffect(flashBlink) { if (flashBlink) { delay(90); flashBlink = false } }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (flash) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "פלאש", tint = if (flash) yellow else Color.White,
                modifier = Modifier.size(26.dp).clickable { flash = !flash; camera?.cameraControl?.enableTorch(false) })
            Spacer(Modifier.weight(1f))
            if (recording != null) {
                val s = (now - recStart) / 1000
                Text("%02d:%02d".format(s / 60, s % 60), color = Color.White, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF3B30)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (mode == 2) 1f else 3f / 4f)
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, z, _ ->
                            val info = camera?.cameraInfo?.zoomState?.value
                            zoom = (zoom * z).coerceIn(info?.minZoomRatio ?: 1f, info?.maxZoomRatio ?: 8f)
                            camera?.cameraControl?.setZoomRatio(zoom)
                        }
                    }
                    .pointerInput(camera) {
                        detectTapGestures { o ->
                            val p = preview.meteringPointFactory.createPoint(o.x, o.y)
                            camera?.cameraControl?.startFocusAndMetering(androidx.camera.core.FocusMeteringAction.Builder(p).build())
                        }
                    }
            ) {
                AndroidView({ preview }, Modifier.fillMaxSize())
                if (flashBlink) Box(Modifier.fillMaxSize().background(Color.Black))
            }
            // zoom chips like iPhone
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val minZ = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                listOfNotNull(if (minZ < 1f) minZ else null, 1f, 2f).forEach { z ->
                    val on = kotlin.math.abs(zoom - z) < 0.05f
                    Box(Modifier.size(if (on) 38.dp else 32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { zoom = z; camera?.cameraControl?.setZoomRatio(z) },
                        contentAlignment = Alignment.Center) {
                        Text(if (z < 1f) ".5" else if (on) "${z.toInt()}×" else "${z.toInt()}", color = if (on) yellow else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            listOf("וידאו", "תמונה", "ריבוע").forEachIndexed { i, t ->
                Text(t, color = if (mode == i) yellow else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = recording == null) { mode = i }.padding(horizontal = 14.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF222222)).clickable {
                ctx.startActivity(Intent(ctx, PhotosActivity::class.java))
            }) { lastThumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(if (recording != null) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (mode == 0) Color(0xFFFF3B30) else Color.White).clickable { shoot() })
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0xFF333333)).clickable(enabled = recording == null) { front = !front }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cameraswitch, "החלפת מצלמה", tint = Color.White)
            }
        }
    }
}

private fun latestThumb(ctx: android.content.Context): ImageBitmap? = media(ctx).lastOrNull()?.let { thumb(ctx, it.uri) }

private fun thumb(ctx: android.content.Context, u: Uri): ImageBitmap? = runCatching {
    ctx.contentResolver.loadThumbnail(u, Size(300, 300), null).asImageBitmap()
}.getOrNull()

// =============================================================== Photos

class PhotosActivity : GlassApp() {
    @Composable override fun Content() = PhotosScreen()
}

private data class Media(val id: Long, val uri: Uri, val video: Boolean, val date: Long, val fav: Boolean)

private fun media(ctx: android.content.Context): List<Media> {
    val out = ArrayList<Media>()
    val uri = MediaStore.Files.getContentUri("external")
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.MediaColumns.IS_FAVORITE),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})", null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Media(id, ContentUris.withAppendedId(base, id), video, c.getLong(2), c.getInt(3) == 1)
            }
        }
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotosScreen() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    var granted by remember { mutableStateOf(perm.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.any { v -> v } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(perm) }
    var reload by remember { mutableIntStateOf(0) }
    val items by produceState(emptyList<Media>(), granted, reload) { value = if (granted) withContext(Dispatchers.IO) { media(ctx) } else emptyList() }
    var favOnly by remember { mutableStateOf(false) }
    val shown = remember(items, favOnly) { if (favOnly) items.filter { it.fav } else items }
    var open by remember { mutableStateOf<Int?>(null) }
    val grid = rememberLazyGridState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) grid.scrollToItem(shown.size - 1) }
    val editReq = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (favOnly) "מועדפים" else "ספרייה", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("${shown.size} פריטים", color = Color(0xFF8E8E93), fontSize = 13.sp)
                }
                Text(if (favOnly) "הכול" else "מועדפים", color = Color(0xFF0A84FF), fontSize = 17.sp, modifier = Modifier.clickable { favOnly = !favOnly })
            }
            if (!granted) Text("אישור גישה לתמונות", color = Color(0xFF0A84FF), fontSize = 17.sp, modifier = Modifier.clickable { ask.launch(perm) }.padding(16.dp))
            LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { it.id }) { m ->
                    val t by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { thumb(ctx, m.uri) } }
                    Box(Modifier.aspectRatio(1f).background(Color(0xFF1C1C1E)).clickable { open = shown.indexOf(m) }) {
                        t?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (m.video) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp))
                        if (m.fav) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp))
                    }
                }
            }
        }
        open?.let { start ->
            BackHandler { open = null }
            val pager = rememberPagerState(start) { shown.size }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { shown.getOrNull(it)?.id ?: it }) { i ->
                    val m = shown.getOrNull(i) ?: return@HorizontalPager
                    val img by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { full(ctx, m.uri) } }
                    var scale by remember { mutableStateOf(1f) }
                    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    Box(Modifier.fillMaxSize().pointerInput(m.id) {
                        detectTransformGestures { _, pan, z, _ -> scale = (scale * z).coerceIn(1f, 5f); off = if (scale > 1f) off + pan else androidx.compose.ui.geometry.Offset.Zero }
                    }, contentAlignment = Alignment.Center) {
                        img?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y }, contentScale = ContentScale.Fit) }
                        if (m.video) Box(Modifier.size(70.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "הפעלה", tint = Color.White, modifier = Modifier.size(44.dp)) }
                    }
                }
                val cur = shown.getOrNull(pager.currentPage)
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                    Text("סגירה", color = Color(0xFF0A84FF), fontSize = 17.sp, modifier = Modifier.clickable { open = null }.padding(6.dp))
                    Spacer(Modifier.weight(1f))
                    cur?.let { Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale("he")).format(java.util.Date(it.date * 1000)), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(6.dp)) }
                }
                if (cur != null) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Rounded.Share, "שיתוף", tint = Color(0xFF0A84FF), modifier = Modifier.size(28.dp).clickable {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (cur.video) "video/*" else "image/*").putExtra(Intent.EXTRA_STREAM, cur.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "שיתוף"))
                    })
                    Icon(if (cur.fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "מועדף", tint = Color(0xFF0A84FF), modifier = Modifier.size(28.dp).clickable {
                        runCatching { editReq.launch(IntentSenderRequest.Builder(MediaStore.createFavoriteRequest(ctx.contentResolver, listOf(cur.uri), !cur.fav).intentSender).build()) }
                    })
                    Icon(Icons.Rounded.Delete, "מחיקה", tint = Color(0xFF0A84FF), modifier = Modifier.size(28.dp).clickable {
                        runCatching { editReq.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(ctx.contentResolver, listOf(cur.uri), true).intentSender).build()); open = null }
                    })
                }
            }
        }
    }
}

private fun full(ctx: android.content.Context, u: Uri): ImageBitmap? = runCatching {
    ctx.contentResolver.loadThumbnail(u, Size(1600, 1600), null).asImageBitmap()
}.getOrNull()

@Suppress("unused") private fun unusedBitmap(b: Bitmap) = b
