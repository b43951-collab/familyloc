package com.myfamily.locshare.data

import android.content.Context
import com.myfamily.locshare.util.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** 내 이동 기록을 폰 안의 파일(history.csv)에 저장. 최근 7일만 보관. */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "history.csv")
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var last: TrackPoint? = null

    fun append(lat: Double, lng: Double, t: Long) {
        val l = last
        if (l != null && Geo.distM(l.lat, l.lng, lat, lng) < 15.0 && t - l.t < 10 * 60_000L) return
        last = TrackPoint(t, lat, lng)
        scope.launch {
            synchronized(lock) {
                try {
                    file.appendText("$t,$lat,$lng\n")
                } catch (e: Exception) {
                    // 저장 실패는 무시
                }
            }
        }
    }

    fun read(fromMs: Long, toMs: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        synchronized(lock) {
            if (!file.exists()) return out
            try {
                file.forEachLine { line ->
                    val s = line.split(",")
                    if (s.size == 3) {
                        val t = s[0].toLongOrNull()
                        val la = s[1].toDoubleOrNull()
                        val ln = s[2].toDoubleOrNull()
                        if (t != null && la != null && ln != null && t >= fromMs && t < toMs) {
                            out.add(TrackPoint(t, la, ln))
                        }
                    }
                }
            } catch (e: Exception) {
                // 파일이 깨졌으면 빈 목록
            }
        }
        out.sortBy { it.t }
        return out
    }

    fun trim() {
        scope.launch {
            synchronized(lock) {
                try {
                    if (!file.exists()) return@synchronized
                    val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                    val kept = file.readLines().filter {
                        val t = it.substringBefore(",").toLongOrNull()
                        t != null && t >= limit
                    }
                    file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                } catch (e: Exception) {
                    // 무시
                }
            }
        }
    }
}
