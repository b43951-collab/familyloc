package com.myfamily.locshare.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** 두 좌표 사이 거리(m) - 하버사인 */
    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}

object Status {
    /** 직접 고른 이모지, 또는 자동 판정 결과. 자동 값은 위치가 3분 넘게 안 올라오면 숨긴다. */
    fun emoji(m: com.myfamily.locshare.data.Member): String {
        if (m.emoji.isEmpty()) return ""
        if (m.auto && (m.updatedAt <= 0L || System.currentTimeMillis() - m.updatedAt > AutoStatus.STALE_MS)) return ""
        return m.emoji
    }
}

/** "자동" 상태 이모지: 최근 이동 기록으로 🚗/🚶, 멈춰 있으면 등록 장소(🏠/🏫/🏢). */
object AutoStatus {
    const val STALE_MS = 180_000L
    private const val HOLD_MS = 90_000L   // 신호 대기 등으로 잠깐 멈춰도 이만큼은 이동 중으로 본다
    private const val SPAN_MS = 120_000L

    class Sample(val t: Long, val lat: Double, val lng: Double, val spd: Float)

    fun pick(
        samples: List<Sample>,
        now: Long,
        updatedAt: Long,
        lat: Double,
        lng: Double,
        places: List<com.myfamily.locshare.data.Place>,
    ): String {
        if (updatedAt <= 0L || now - updatedAt > STALE_MS) return ""
        val win = samples.filter { now - it.t <= SPAN_MS }
        val hold = win.filter { now - it.t <= HOLD_MS }
        // 🚗: 최근 90초 안에 시속 18km(5m/s) 넘는 순간이 있었음 (순간 속도 또는 두 점 사이 평균 속도)
        var drive = hold.any { it.spd > 5f }
        if (!drive) {
            for (i in 1 until hold.size) {
                val dt = (hold[i].t - hold[i - 1].t) / 1000.0
                if (dt in 3.0..120.0 &&
                    Geo.distM(hold[i - 1].lat, hold[i - 1].lng, hold[i].lat, hold[i].lng) / dt > 5.0
                ) { drive = true; break }
            }
        }
        if (drive) return "🚗"
        // 🚶: 2분 안에 순이동 50m 이상, 또는 걷는 속도(1m/s 넘음)가 두 번 이상 관측
        if (win.size >= 2) {
            val d = Geo.distM(win.first().lat, win.first().lng, win.last().lat, win.last().lng)
            if (d >= 50.0) return "🚶"
        }
        if (hold.count { it.spd > 1.0f } >= 2) return "🚶"
        // 멈춰 있으면 등록 장소 안인지로 판정
        val p = places.firstOrNull { Geo.distM(lat, lng, it.lat, it.lng) <= it.radiusM + 30 } ?: return ""
        val key = p.name + p.kind
        return when {
            "집" in key -> "🏠"
            "학교" in key || "학원" in key -> "🏫"
            "회사" in key || "직장" in key -> "🏢"
            else -> ""
        }
    }
}

object Fmt {
    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= 0L) return "정보 없음"
        val s = (now - t) / 1000
        return when {
            s < 60 -> "방금 전"
            s < 3600 -> "${s / 60}분 전"
            s < 86400 -> "${s / 3600}시간 전"
            else -> "${s / 86400}일 전"
        }
    }

    fun distance(m: Double): String =
        if (m < 1000) "${m.toInt()}m" else String.format(Locale.US, "%.1fkm", m / 1000.0)

    fun dateTime(t: Long): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(t))

    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(t))

    fun coord(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lng)
}

object Perms {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun fine(ctx: Context): Boolean = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** "항상 허용" (Android 10 미만은 앱 사용 중 허용이 곧 백그라운드 허용) */
    fun background(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < 29) fine(ctx)
        else granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun notifications(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < 33) true
        else granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryExempt(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
