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
    private val _state = MutableStateFlow<AirPodsState?>(null)
    val state: StateFlow<AirPodsState?> = _state
    private var scanning = false

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
    }

    private fun handle(r: ScanResult) {
        if (r.rssi < -65) return // only the pair that is close to you
        val data = r.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
        parse(data)?.let { _state.value = it }
    }

    fun parse(d: ByteArray): AirPodsState? {
        if (d.size < 8 || d[0] != 0x07.toByte()) return null
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
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE, byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, callback); scanning = true }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        if (!scanning) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    /** Hide the widget values once the AirPods have not been heard for a while. */
    fun fresh(s: AirPodsState?): AirPodsState? =
        s?.takeIf { System.currentTimeMillis() - it.seenAt < 45_000 }
}
