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
