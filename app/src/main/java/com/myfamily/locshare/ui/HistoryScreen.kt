package com.myfamily.locshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.TrackPoint
import com.myfamily.locshare.util.Fmt
import com.myfamily.locshare.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun bounds(range: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    val todayStart = c.timeInMillis
    val day = 24L * 3600L * 1000L
    return when (range) {
        0 -> todayStart to Long.MAX_VALUE
        1 -> (todayStart - day) to todayStart
        else -> (todayStart - 6 * day) to Long.MAX_VALUE
    }
}

/** 한 번의 이동(여정). 움직임이 5분 이상 멈추면 끝난 것으로 본다. */
private data class Trip(val start: Long, val end: Long, val distM: Double)

private fun computeTrips(track: List<TrackPoint>): List<Trip> {
    val out = ArrayList<Trip>()
    var startT = -1L
    var dist = 0.0
    var lastMoveT = 0L
    fun close() {
        if (startT >= 0 && dist >= 100.0 && lastMoveT - startT >= 60_000L) out.add(Trip(startT, lastMoveT, dist))
        startT = -1L
        dist = 0.0
    }
    for (i in 1 until track.size) {
        val a = track[i - 1]
        val b = track[i]
        val dt = (b.t - a.t) / 1000.0
        if (dt <= 0.0) continue
        val d = Geo.distM(a.lat, a.lng, b.lat, b.lng)
        val moving = dt <= 600.0 && d >= 15.0 && d / dt >= 0.7
        if (moving) {
            if (startT < 0) {
                startT = a.t
                dist = 0.0
            }
            dist += d
            lastMoveT = b.t
        } else if (startT >= 0 && b.t - lastMoveT >= 5 * 60_000L) {
            close()
        }
    }
    close()
    return out.reversed() // 최근 여정이 위로
}

private fun totalDistance(track: List<TrackPoint>): Double {
    var sum = 0.0
    for (i in 1 until track.size) {
        sum += Geo.distM(track[i - 1].lat, track[i - 1].lng, track[i].lat, track[i].lng)
    }
    return sum
}

@Composable
fun HistoryScreen(repo: FamilyRepository) {
    val members by repo.members.collectAsState()
    val places by repo.places.collectAsState()
    val labels = listOf("오늘", "어제", "최근 7일")
    var range by rememberSaveable { mutableStateOf(0) }
    var reload by rememberSaveable { mutableStateOf(0) }

    val track by produceState(initialValue = emptyList<TrackPoint>(), range, reload) {
        val (from, to) = bounds(range)
        value = withContext(Dispatchers.IO) { repo.history(from, to) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, l ->
                FilterChip(selected = range == i, onClick = { range = i }, label = { Text(l) })
            }
            TextButton(onClick = { reload++ }) { Text("새로고침") }
        }

        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("내 이동 기록", fontWeight = FontWeight.Bold)
                if (track.isEmpty()) {
                    Text(
                        "이 기간에 기록이 없어요. 위치 공유를 켜고 조금 움직이면 쌓여요.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                } else {
                    Text(
                        "이동 거리 약 ${Fmt.distance(totalDistance(track))} · 기록 ${track.size}개",
                        fontSize = 13.sp,
                    )
                    Text(
                        "${Fmt.dateTime(track.first().t)} → ${Fmt.dateTime(track.last().t)}",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
                Text(
                    "가족의 이동 기록은 서버 연동 후 볼 수 있어요.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                )
            }
        }

        val trips = remember(track) { computeTrips(track) }
        if (trips.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("최근 여정 ${trips.size}건", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (trips.size > 2) {
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "더 보기") }
                        }
                    }
                    Column(
                        Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        trips.take(if (expanded) 15 else 2).forEach { t ->
                            val mins = ((t.end - t.start) / 60_000L).coerceAtLeast(1L)
                            val speed = t.distM / ((t.end - t.start) / 1000.0)
                            Text(
                                "${if (speed >= 4.0) "🚗" else "🚶"}  ${Fmt.dateTime(t.start)} ~ ${Fmt.hm(t.end)} (${mins}분) · ${Fmt.distance(t.distM)}",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            FamilyMapView(
                modifier = Modifier.fillMaxSize(),
                members = members.filter { it.isMe },
                places = places,
                track = track,
                fitTrack = true,
            )
        }
    }
}
