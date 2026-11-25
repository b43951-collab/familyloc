package com.myfamily.locshare.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.Member
import com.myfamily.locshare.data.Place
import com.myfamily.locshare.util.Fmt
import com.myfamily.locshare.util.Geo
import com.myfamily.locshare.util.Perms
import com.myfamily.locshare.util.Status

@Composable
fun MapScreen(repo: FamilyRepository, tick: Int, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val members by repo.members.collectAsState()
    val places by repo.places.collectAsState()
    val settings by repo.settings.collectAsState()
    val now by rememberNow()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var showSos by remember { mutableStateOf(false) }
    var focus by remember {
        mutableStateOf(
            repo.members.value.firstOrNull { it.isMe }?.let { MapFocus(it.lat, it.lng, 16.0, 0L) }
        )
    }

    val me = members.firstOrNull { it.isMe }
    val selected = members.firstOrNull { it.id == selectedId }
    val needSetup = remember(tick) { !Perms.fine(ctx) || !Perms.background(ctx) }

    // 지도를 열면 가족 폰에 "지금 위치 올려" 요청 (아이쉐어링처럼 실시간 느낌)
    LaunchedEffect(Unit) { repo.requestRefresh() }

    // 처음 내 위치가 잡히면 한 번만 그 위치로 이동
    LaunchedEffect(me != null) {
        val m = me
        if (m != null && focus == null) {
            focus = MapFocus(m.lat, m.lng, 16.0, System.nanoTime())
        }
    }

    Box(Modifier.fillMaxSize()) {
        FamilyMapView(
            modifier = Modifier.fillMaxSize(),
            members = members,
            places = if (settings.showPlaces) places else emptyList(),
            selectedId = selectedId,
            focus = focus,
            onMemberClick = { selectedId = it },
        )

        // ---- 상단: 가족 칩 + 안내 배너 ----
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(members, key = { it.id }) { m ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        shadowElevation = 4.dp,
                        color = if (m.id == selectedId) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable {
                            selectedId = m.id
                            if (m.sharing) focus = MapFocus(m.lat, m.lng, null, System.nanoTime())
                        },
                    ) {
                        Row(
                            Modifier.padding(start = 6.dp, top = 6.dp, end = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(m.name, m.color, 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                val emo = Status.emoji(m)
                                Text(
                                    (if (emo.isNotEmpty()) "$emo " else "") + m.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                val low = m.battery in 0..20 && !m.charging
                                Text(
                                    Fmt.ago(m.updatedAt, now) +
                                        (if (m.sharing && m.battery >= 0) " · 🔋${m.battery}%" else ""),
                                    fontSize = 11.sp,
                                    color = if (low) Color(0xFFD32F2F) else Color.Gray,
                                )
                            }
                        }
                    }
                }
            }

            if (members.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("내 위치를 찾는 중이에요… (설정 탭에서 권한을 확인해 주세요)", Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
            if (needSetup) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .clickable { onOpenSettings() },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                ) {
                    Text(
                        "⚠️ 위치 권한 설정이 필요해요. 탭해서 설정하기",
                        Modifier.padding(12.dp),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // ---- 하단: 버튼들 + 선택한 가족 카드 ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(
                onClick = { showSos = true },
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White,
            ) {
                Text("SOS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            SmallFloatingActionButton(onClick = {
                repo.requestRefresh()
                Toast.makeText(ctx, "가족 위치를 새로 불러오는 중…", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
            }
            SmallFloatingActionButton(onClick = {
                me?.let { focus = MapFocus(it.lat, it.lng, 17.0, System.nanoTime()) }
            }) {
                Icon(Icons.Default.LocationOn, contentDescription = "내 위치")
            }
            if (selected != null) {
                MemberCard(selected, places, now) { selectedId = null }
            }
        }
    }

    if (showSos) {
        AlertDialog(
            onDismissRequest = { showSos = false },
            title = { Text("SOS 보내기") },
            text = { Text("가족에게 긴급 알림과 내 위치를 보낼까요?\n(서버 연동 전에는 이 폰에만 기록돼요)") },
            confirmButton = {
                TextButton(onClick = {
                    repo.sendSos()
                    showSos = false
                }) { Text("보내기", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { showSos = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun MemberCard(m: Member, places: List<Place>, now: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val addr = rememberAddress(m.lat, m.lng)
    val inPlace = places.firstOrNull { Geo.distM(m.lat, m.lng, it.lat, it.lng) <= it.radiusM }
    val status = when {
        !m.sharing -> "위치 공유 꺼짐"
        m.speedMps > 1.0f -> "이동 중 · ${(m.speedMps * 3.6f).toInt()}km/h"
        else -> "정지"
    }
    val battery = if (m.battery >= 0) {
        "🔋 ${m.battery}%" + (if (m.charging) " ⚡충전 중" else "")
    } else {
        "🔋 -"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, m.color, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Status.emoji(m).let { if (it.isNotEmpty()) "$it " else "" } +
                            m.name + (if (m.isMe) " (나)" else ""),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Text("${Fmt.ago(m.updatedAt, now)} 업데이트", fontSize = 12.sp, color = Color.Gray)
                }
                TextButton(onClick = onClose) { Text("닫기") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !m.sharing -> "📍 위치를 공유하고 있지 않아요"
                    inPlace != null -> "📍 ${inPlace.name}에 있음"
                    else -> "📍 $addr"
                },
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text("🚶 $status    $battery", fontSize = 13.sp, color = Color.DarkGray)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                try {
                    val uri = Uri.parse("geo:${m.lat},${m.lng}?q=${m.lat},${m.lng}(${Uri.encode(m.name)})")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(ctx, "지도 앱을 열 수 없어요", Toast.LENGTH_SHORT).show()
                }
            }) { Text("지도앱으로 열기 / 길찾기") }
        }
    }
}
