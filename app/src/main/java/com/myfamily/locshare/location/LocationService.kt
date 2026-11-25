package com.myfamily.locshare.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.ServiceLocator
import com.myfamily.locshare.util.Fmt
import com.myfamily.locshare.util.Geo
import com.myfamily.locshare.util.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 위치를 계속 수집하는 포그라운드 서비스.
 *
 * 수집 주기 (아이쉐어링처럼 "보고 있을 땐 실시간, 가만히 있을 땐 절전"):
 *  - 앱을 보고 있거나 '새로고침' 직후 1분간 : 5초 (고정밀)
 *  - 이동 중                              : 10초 (절약 모드 30초)
 *  - 정지 중                              : 2분 (절약 모드 5분, 균형 정확도)
 */
class LocationService : Service() {

    companion object {
        const val ACTION_BOOST = "com.myfamily.locshare.BOOST"
        const val NOTI_ID = 1001

        /** 앱 화면이 보이는 동안 true (MainActivity가 갱신) */
        val viewing = MutableStateFlow(false)

        fun start(ctx: Context) {
            if (!Perms.fine(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
            } catch (e: Exception) {
                // 백그라운드에서 시작이 막힌 경우 등 - 다음 기회에 다시 시도됨
            }
        }

        fun boost(ctx: Context, durationMs: Long = 60_000L) {
            if (!Perms.fine(ctx)) return
            try {
                val i = Intent(ctx, LocationService::class.java).setAction(ACTION_BOOST)
                    .putExtra("ms", durationMs)
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // 무시
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var activeKey = ""
    private var moving = false
    private var slowCount = 0
    private var boostUntil = 0L
    private var lastFix: Location? = null

    private val repo: FamilyRepository
        get() = ServiceLocator.repo

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        Notifier.ensureChannels(this)
        scope.launch {
            combine(viewing, repo.settings) { v, s -> v to s.mode }.collect {
                applyRequest()
            }
        }
        scope.launch {
            repo.members.collect { checkDistance() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_BOOST) {
            val ms = intent.getLongExtra("ms", 60_000L)
            boostUntil = maxOf(boostUntil, SystemClock.elapsedRealtime() + ms)
            applyRequest()
            scope.launch {
                delay(ms + 1_000L)
                applyRequest()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notifier.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun applyRequest() {
        val mode = repo.settings.value.mode
        val boosting = SystemClock.elapsedRealtime() < boostUntil || viewing.value
        val interval: Long
        val priority: Int
        if (boosting) {
            interval = 5_000L
            priority = Priority.PRIORITY_HIGH_ACCURACY
        } else if (moving) {
            // 0 슈퍼 절전 / 1 절전 / 2 보통 / 3 높은 정확도
            interval = when (mode) { 0 -> 60_000L; 1 -> 30_000L; 3 -> 5_000L; else -> 10_000L }
            priority = Priority.PRIORITY_HIGH_ACCURACY
        } else {
            interval = when (mode) { 0 -> 600_000L; 1 -> 300_000L; 3 -> 30_000L; else -> 120_000L }
            priority = if (mode == 3) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val key = "$interval/$priority"
        if (key == activeKey) return

        callback?.let { client.removeLocationUpdates(it) }
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handle(it) }
            }
        }
        val req = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        try {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            callback = cb
            activeKey = key
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun handle(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 300f) return // 너무 부정확한 값은 버림

        val prev = lastFix
        var speed = if (loc.hasSpeed()) loc.speed else 0f
        if (!loc.hasSpeed() && prev != null) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt > 0f) speed = loc.distanceTo(prev) / dt
        }
        lastFix = loc

        if (speed > 1.0f) {
            moving = true
            slowCount = 0
        } else {
            slowCount++
            if (slowCount >= 3) moving = false
        }

        val (pct, charging) = batteryInfo()
        repo.onMyLocation(loc.latitude, loc.longitude, loc.accuracy, speed, pct, charging, System.currentTimeMillis())
        checkDistance()
        applyRequest()
    }

    private var farState = false

    /** 거리 알림: 이 폰의 위치와 선택한 가족의 위치를 비교해서, 기준 거리를 넘으면 이 폰에 알림을 띄운다. */
    private fun checkDistance() {
        val s = repo.settings.value
        if (!s.distOn) {
            farState = false
            return
        }
        val me = lastFix ?: return
        val others = repo.members.value.filter { !it.isMe && it.sharing }
        val target = others.firstOrNull { it.id == s.distTarget } ?: others.firstOrNull() ?: return
        if (System.currentTimeMillis() - target.updatedAt > 30 * 60_000L) return // 오래된 위치는 무시
        val d = Geo.distM(me.latitude, me.longitude, target.lat, target.lng)
        val limit = s.distM.toDouble()
        if (!farState && d > limit) {
            farState = true
            Notifier.alert(this, "${target.name} 님과 ${Fmt.distance(d)} 떨어졌어요")
        } else if (farState && d < limit * 0.9) {
            farState = false
        }
    }

    private fun batteryInfo(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        activeKey = ""
        scope.cancel()
        super.onDestroy()
    }
}
