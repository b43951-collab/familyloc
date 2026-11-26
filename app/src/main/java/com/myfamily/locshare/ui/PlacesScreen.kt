package com.myfamily.locshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.Place

/** 편집 중인 장소. id가 null이면 새 장소 추가. */
private class Draft(
    val id: String?,
    name: String,
    kind: String,
    radius: Float,
    shared: Boolean,
) {
    var name by mutableStateOf(name)
    var kind by mutableStateOf(kind)
    var radius by mutableStateOf(radius)
    var shared by mutableStateOf(shared)
}

/** 종류 버튼 대신 이름으로 추정: "집"이 들어가면 집 아이콘, 아니면 기존 종류 유지 */
private fun kindOf(d: Draft): String = if (d.name.contains("집")) "집" else d.kind

@Composable
fun PlacesScreen(repo: FamilyRepository) {
    val members by repo.members.collectAsState()
    val places by repo.places.collectAsState()
    val me = members.firstOrNull { it.isMe }

    var focus by remember {
        mutableStateOf(
            repo.members.value.firstOrNull { it.isMe }?.let { MapFocus(it.lat, it.lng, 16.0, 0L) }
        )
    }
    var center by remember {
        mutableStateOf(
            (repo.members.value.firstOrNull { it.isMe }?.lat ?: 37.5665) to
                (repo.members.value.firstOrNull { it.isMe }?.lng ?: 126.9780)
        )
    }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var deleteTarget by remember { mutableStateOf<Place?>(null) }
    var radiusPx by remember { mutableStateOf(0f) }

    LaunchedEffect(me != null) {
        val m = me
        if (m != null && focus == null) {
            focus = MapFocus(m.lat, m.lng, 16.0, System.nanoTime())
        }
    }

    val d = draft

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            FamilyMapView(
                modifier = Modifier.fillMaxSize(),
                members = members.filter { it.isMe },
                // 편집 중인 장소는 미리보기 원으로 대신 그린다
                places = places.filter { it.id != d?.id },
                focus = focus,
                onCenterChange = { la, ln -> center = la to ln },
                previewRadiusM = d?.radius?.toInt(),
                onPreviewPx = { radiusPx = it },
            )
            if (d != null) {
                // 지도 가운데 기준 반경 원 (지도를 움직이면 같이 움직이는 것처럼 보임)
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    if (radiusPx > 0f) {
                        drawCircle(Color(0x332962FF), radius = radiusPx, center = c)
                        drawCircle(Color(0xFF2962FF), radius = radiusPx, center = c, style = Stroke(width = 3f))
                    }
                }
            }
            // 지도 가운데 핀 (끝이 가운데)
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color(0xFFD32F2F),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-18).dp)
                    .size(36.dp),
            )
            if (d == null) {
                ExtendedFloatingActionButton(
                    onClick = { draft = Draft(null, "", "기타", 150f, true) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("지도 가운데에 장소 추가") },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }

        if (d != null) {
            EditPanel(
                d = d,
                onCancel = { draft = null },
                onSave = {
                    if (d.id == null) {
                        repo.addPlace(d.name, center.first, center.second, d.radius.toInt(), kindOf(d), d.shared)
                    } else {
                        repo.updatePlace(d.id, d.name, center.first, center.second, d.radius.toInt(), kindOf(d), d.shared)
                    }
                    draft = null
                },
            )
        } else {
            Text(
                "내 장소 (가족이 들어오고 나가면 알림)",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )

            if (places.isEmpty()) {
                Text(
                    "아직 장소가 없어요. 지도를 움직여 집·학교·직장 위치를 찍어 보세요.",
                    modifier = Modifier.padding(16.dp),
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
            }

            LazyColumn(Modifier.weight(0.8f)) {
                items(places, key = { it.id }) { p ->
                    PlaceRow(
                        p,
                        onClick = {
                            // 누르면 그 장소로 이동하면서 수정 모드
                            focus = MapFocus(p.lat, p.lng, 17.0, System.nanoTime())
                            draft = Draft(p.id, p.name, p.kind, p.radiusM.toFloat().coerceIn(100f, 500f), p.shared)
                        },
                        onDelete = { deleteTarget = p },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("장소 삭제") },
            text = { Text("\"${t.name}\" 장소를 삭제할까요?" + if (t.shared) "\n가족 모두에게서도 사라져요." else "") },
            confirmButton = {
                TextButton(onClick = {
                    repo.removePlace(t.id)
                    deleteTarget = null
                }) { Text("삭제", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun EditPanel(d: Draft, onCancel: () -> Unit, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = d.name,
                onValueChange = { d.name = it },
                placeholder = { Text(if (d.id == null) "장소 이름 (지도를 움직여 위치 맞추기)" else "장소 이름 (지도를 움직이면 위치가 바뀌어요)", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("반경 ${d.radius.toInt()}m", fontSize = 13.sp, modifier = Modifier.width(78.dp))
                Slider(
                    value = d.radius,
                    onValueChange = { d.radius = it },
                    valueRange = 100f..500f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { d.shared = !d.shared },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = d.shared, onCheckedChange = { d.shared = it })
                    Text("가족과 공유", fontSize = 14.sp)
                }
                TextButton(onClick = onCancel) { Text("취소") }
                Button(onClick = onSave) { Text(if (d.id == null) "추가" else "저장") }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Place, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (p.kind == "집") Icons.Default.Home else Icons.Default.Place,
            contentDescription = null,
            tint = Color(0xFF2962FF),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            Text(
                "${p.kind} · 반경 ${p.radiusM}m · " + (if (p.shared) "가족 공유" else "나만 보기"),
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "삭제")
        }
    }
}
