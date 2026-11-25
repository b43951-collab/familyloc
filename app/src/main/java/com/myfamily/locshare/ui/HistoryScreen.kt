package com.myfamily.locshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
