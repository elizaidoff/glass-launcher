package com.glass.launcher.feature

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Battery levels in percent; null = that part is not in range / not reported. */
data class AirPodsState(
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val seenAt: Long,
)

/**
 * AirPods broadcast their battery status in Bluetooth LE advertisements (Apple manufacturer id 0x004C,
 * "proximity pairing" message type 0x07). This reads those broadcasts; no pairing changes are made.
 * Values come in 10% steps, the same as other Android apps that show AirPods battery.
 */
object AirPods {
    private const val APPLE = 0x004C
    private val _state = MutableStateFlow<AirPodsState?>(load())
    /** Latest reading; it stays after the AirPods go out of range (check [isLive] / seenAt). */
    val state: StateFlow<AirPodsState?> = _state

    private fun load(): AirPodsState? = runCatching {
        val o = org.json.JSONObject(com.glass.launcher.App.instance.prefs.podsLast ?: return null)
        fun n(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
        AirPodsState(n("l"), n("r"), n("c"), false, false, false, o.getLong("t"))
    }.getOrNull()

    /** Accept a reading from our own scan or from another app (MaterialPods' notification). */
    fun update(s: AirPodsState) {
        val prev = _state.value
        // Keep earlier values for parts that this reading does not include (e.g. the case is closed).
        val merged = s.copy(
            left = s.left ?: prev?.left,
            right = s.right ?: prev?.right,
            case = s.case ?: prev?.case,
        )
        _state.value = merged
        com.glass.launcher.App.instance.prefs.podsLast = org.json.JSONObject()
            .put("l", merged.left ?: org.json.JSONObject.NULL).put("r", merged.right ?: org.json.JSONObject.NULL)
            .put("c", merged.case ?: org.json.JSONObject.NULL).put("t", merged.seenAt).toString()
    }

    fun isLive(s: AirPodsState?) = s != null && System.currentTimeMillis() - s.seenAt < 45_000

    /** "עכשיו" / "לפני 5 דק׳" / "לפני 3 שע׳" */
    fun ageText(s: AirPodsState): String {
        val m = (System.currentTimeMillis() - s.seenAt) / 60_000
        return when { m < 1 -> "עכשיו"; m < 60 -> "לפני $m דק׳"; m < 48 * 60 -> "לפני ${m / 60} שע׳"; else -> "לפני ${m / 1440} ימים" }
    }

    /** Reads percentages from MaterialPods' notification text, e.g. "L 80% · R 75% · Case 60%". */
    fun fromNotificationText(text: String) {
        lastNotifText = text.take(300)
        val t = text.replace('\n', ' ')
        fun find(vararg labels: String): Int? {
            for (l in labels) {
                Regex("(?i)" + Regex.escape(l) + "[^0-9%]{0,6}(\\d{1,3})\\s*%").find(t)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
                Regex("(?i)(\\d{1,3})\\s*%[^0-9]{0,4}" + Regex.escape(l)).find(t)?.let { return it.groupValues[1].toInt().coerceIn(0, 100) }
            }
            return null
        }
        var l = find("Left", "L", "שמאל", "ש׳")
        var r = find("Right", "R", "ימין", "י׳")
        var c = find("Case", "מארז", "קופסה")
        if (l == null && r == null && c == null) {
            val all = Regex("(\\d{1,3})\\s*%").findAll(t).map { it.groupValues[1].toInt() }.toList()
            if (all.isEmpty()) return
            l = all.getOrNull(0); r = all.getOrNull(1); c = all.getOrNull(2)
        }
        update(AirPodsState(l, r, c, false, false, false, System.currentTimeMillis()))
    }
    private var scanning = false

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- Diagnostics (shown in the AirPods settings screen) ----
    @Volatile var appleSeen = 0
    @Volatile var podsSeen = 0
    @Volatile var lastRaw: String = ""
    @Volatile var lastRssi = 0
    @Volatile var lastNotifText: String = ""
    @Volatile var scanError: String = ""
    fun isScanning() = scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) { scanError = "שגיאת סריקה $errorCode"; scanning = false }
    }

    private fun handle(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
        appleSeen++
        if (data.isEmpty() || data[0] != 0x07.toByte()) return
        podsSeen++
        lastRaw = data.joinToString("") { "%02X".format(it) }
        lastRssi = r.rssi
        if (r.rssi < -85) return // only a pair that is near you
        parse(data)?.let { update(it) }
    }

    fun parse(d: ByteArray): AirPodsState? {
        if (d.size < 9 || d[0] != 0x07.toByte()) return null
        fun nib(i: Int): Int {
            val b = d[i / 2].toInt() and 0xFF
            return if (i % 2 == 0) b shr 4 else b and 0x0F
        }
        fun pct(v: Int): Int? = when (v) { 10 -> 100; in 0..9 -> v * 10 + 5; else -> null }
        val flipped = (nib(10) and 0x02) == 0
        val l = if (flipped) nib(12) else nib(13)
        val r = if (flipped) nib(13) else nib(12)
        val cs = nib(14)
        return AirPodsState(
            left = pct(l),
            right = pct(r),
            case = pct(nib(15)),
            leftCharging = if (flipped) (cs and 0x02) != 0 else (cs and 0x01) != 0,
            rightCharging = if (flipped) (cs and 0x01) != 0 else (cs and 0x02) != 0,
            caseCharging = (cs and 0x04) != 0,
            seenAt = System.currentTimeMillis(),
        )
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (scanning || !hasPermission(ctx)) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        // Filter by Apple's id only; some phones' hardware filters drop the data-prefix filter.
        val filter = ScanFilter.Builder().setManufacturerData(APPLE, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, callback); scanning = true; scanError = "" }
            .onFailure { scanError = it.message ?: "שגיאה" }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        if (!scanning) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    /** The last reading (kept even when the case is closed). */
    fun fresh(s: AirPodsState?): AirPodsState? = s
}
