package com.glass.launcher.feature

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.glass.launcher.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * Watch data (Garmin Connect writes it to Health Connect) shaped like Apple's Fitness:
 * three rings – Move (active calories), Exercise (workout minutes), Steps – plus sleep, heart and workouts.
 * Read once when you look, cached for the widget; no background work.
 */
object Health {

    data class Day(val date: LocalDate, val moveKcal: Int, val exerciseMin: Int, val steps: Int)
    data class Workout(val type: Int, val title: String?, val start: Long, val minutes: Int)
    data class Summary(
        val today: Day,
        val week: List<Day>,
        val distanceKm: Double,
        val floors: Int,
        val heartAvg: Int?,
        val heartResting: Int?,
        val heartMin: Int?,
        val heartMax: Int?,
        val sleepMin: Int?,
        val weightKg: Double?,
        val totalKcal: Int = 0,
        val workouts: List<Workout>,
        val updated: Long,
    )

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /** Whether the last read found anything at all (if not, Garmin isn't sharing yet). */
    @Volatile var lastHadData = true

    fun openSettings(ctx: Context) {
        val i = android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { com.glass.launcher.lock.LockGuard.startTrusted(ctx, i) }.onFailure {
            runCatching { ctx.startActivity(android.content.Intent("android.health.connect.action.HEALTH_HOME_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            .contains(HealthPermission.getReadPermission(StepsRecord::class))
    }.getOrDefault(false)

    // goals, like Apple's (editable in the app)
    val moveGoal get() = App.instance.prefs.moveGoal
    val exerciseGoal get() = App.instance.prefs.exerciseGoal
    val stepsGoal get() = App.instance.prefs.stepsGoal

    fun cached(): Summary? = _summary.value ?: load()?.also { _summary.value = it }

    suspend fun refresh(ctx: Context): Summary? = runCatching {
        if (!available(ctx) || !granted(ctx)) return null
        val c = HealthConnectClient.getOrCreate(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.atStartOfDay(zone).toInstant()
        val now = Instant.now()
        // One metric per request: if one permission was refused, the others still show.
        suspend fun <T : Any> one(m: androidx.health.connect.client.aggregate.AggregateMetric<T>, from: Instant = start): T? =
            runCatching { c.aggregate(AggregateRequest(setOf(m), TimeRangeFilter.between(from, now)))[m] }.getOrNull()
        val steps = one(StepsRecord.COUNT_TOTAL)
        val dist = one(DistanceRecord.DISTANCE_TOTAL)
        val active = one(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        val total = one(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        val floors = one(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
        val exDur = one(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        val hAvg = one(HeartRateRecord.BPM_AVG)
        val hMin = one(HeartRateRecord.BPM_MIN)
        val hMax = one(HeartRateRecord.BPM_MAX)
        val resting = runCatching {
            c.aggregate(AggregateRequest(setOf(RestingHeartRateRecord.BPM_AVG), TimeRangeFilter.between(start.minus(Duration.ofDays(1)), now)))[RestingHeartRateRecord.BPM_AVG]
        }.getOrNull()
        // sleep: last night (18:00 yesterday → now)
        val sleep = runCatching {
            c.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                TimeRangeFilter.between(today.minusDays(1).atTime(18, 0).atZone(zone).toInstant(), now)))[SleepSessionRecord.SLEEP_DURATION_TOTAL]
        }.getOrNull()
        val weight = runCatching {
            c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(60)), now), ascendingOrder = false, pageSize = 1))
                .records.firstOrNull()?.weight?.inKilograms
        }.getOrNull()
        val workouts = runCatching {
            c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(14)), now), ascendingOrder = false, pageSize = 30))
                .records.map { Workout(it.exerciseType, it.title, it.startTime.toEpochMilli(), Duration.between(it.startTime, it.endTime).toMinutes().toInt()) }
        }.getOrDefault(emptyList())
        val week = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val ds = d.atStartOfDay(zone).toInstant()
            val de = if (back == 0) now else d.plusDays(1).atStartOfDay(zone).toInstant()
            suspend fun <T : Any> w(m: androidx.health.connect.client.aggregate.AggregateMetric<T>): T? =
                runCatching { c.aggregate(AggregateRequest(setOf(m), TimeRangeFilter.between(ds, de)))[m] }.getOrNull()
            Day(d, kcal(w(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories, w(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories, back > 0),
                w(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)?.toMinutes()?.toInt() ?: 0, (w(StepsRecord.COUNT_TOTAL) ?: 0L).toInt())
        }
        val todayDay = Day(today, kcal(active?.inKilocalories, total?.inKilocalories), exDur?.toMinutes()?.toInt() ?: 0, (steps ?: 0L).toInt())
        val s = Summary(
            todayDay, week, dist?.inKilometers ?: 0.0, (floors ?: 0.0).toInt(),
            hAvg?.toInt(), resting?.toInt(), hMin?.toInt(), hMax?.toInt(),
            sleep?.toMinutes()?.toInt(), weight, (total?.inKilocalories ?: 0.0).toInt(), workouts, System.currentTimeMillis(),
        )
        _summary.value = s
        save(s)
        lastHadData = steps != null || total != null || hAvg != null
        s
    }.getOrNull()

    /** Garmin usually writes total calories; the "Move" ring uses active ones, so estimate them when missing. */
    private fun kcal(active: Double?, total: Double?, wholeDay: Boolean = false): Int {
        if (active != null && active > 0) return active.toInt()
        if (total == null) return 0
        // Total minus the resting share of the day so far.
        val frac = if (wholeDay) 1.0 else (java.time.LocalTime.now().toSecondOfDay() / 86400.0).coerceIn(0.05, 1.0)
        return (total - App.instance.prefs.restingKcalPerDay * frac).coerceAtLeast(0.0).toInt()
    }

    fun workoutName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "ריצה"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "הליכה"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "טיול רגלי"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "רכיבה"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "שחייה"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "אימון כוח"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "אליפטיקל"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "חתירה"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "מתיחות"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "מדרגות"
        else -> "אימון"
    }

    // ---------------------------------------------------------------- cache

    private fun save(s: Summary) {
        val o = JSONObject()
        o.put("d", s.today.date.toString()); o.put("m", s.today.moveKcal); o.put("e", s.today.exerciseMin); o.put("s", s.today.steps)
        o.put("km", s.distanceKm); o.put("tk", s.totalKcal); o.put("fl", s.floors); o.put("u", s.updated)
        s.heartAvg?.let { o.put("ha", it) }; s.heartResting?.let { o.put("hr", it) }; s.sleepMin?.let { o.put("sl", it) }
        o.put("w", JSONArray(s.week.map { JSONObject().put("d", it.date.toString()).put("m", it.moveKcal).put("e", it.exerciseMin).put("s", it.steps) }))
        App.instance.prefs.healthCache = o.toString()
    }

    private fun load(): Summary? = runCatching {
        val o = JSONObject(App.instance.prefs.healthCache ?: return null)
        val d = LocalDate.parse(o.getString("d"))
        val today = if (d == LocalDate.now()) Day(d, o.getInt("m"), o.getInt("e"), o.getInt("s")) else Day(LocalDate.now(), 0, 0, 0)
        val wa = o.optJSONArray("w") ?: JSONArray()
        val week = (0 until wa.length()).map { i -> wa.getJSONObject(i).let { Day(LocalDate.parse(it.getString("d")), it.getInt("m"), it.getInt("e"), it.getInt("s")) } }
        Summary(today, week, o.optDouble("km", 0.0), o.optInt("fl"), o.optInt("ha").takeIf { it > 0 }, o.optInt("hr").takeIf { it > 0 },
            null, null, o.optInt("sl").takeIf { it > 0 }, null, o.optInt("tk"), emptyList(), o.optLong("u"))
    }.getOrNull()
}
