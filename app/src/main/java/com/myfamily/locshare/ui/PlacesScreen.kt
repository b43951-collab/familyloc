package com.myfamily.locshare.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.Place

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
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(me != null) {
        val m = me
        if (m != null && focus == null) {
            focus = MapFocus(m.lat, m.lng, 16.0, System.nanoTime())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            OsmMapView(
                modifier = Modifier.fillMaxSize(),
                members = members.filter { it.isMe },
                places = places,
                focus = focus,
                onCenterChange = { la, ln -> center = la to ln },
            )
            // 지도 가운데 십자 마커 (이 위치에 장소가 추가됨)
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color(0xFFD32F2F),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-18).dp)
                    .size(36.dp),
            )
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("지도 가운데에 장소 추가") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

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
                    onClick = { focus = MapFocus(p.lat, p.lng, 17.0, System.nanoTime()) },
                    onDelete = { repo.removePlace(p.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (showAdd) {
        AddPlaceDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, kind, radius ->
                repo.addPlace(name, center.first, center.second, radius, kind)
                showAdd = false
            },
        )
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
            Text("${p.kind} · 반경 ${p.radiusM}m", fontSize = 12.sp, color = Color.Gray)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "삭제")
        }
    }
}

@Composable
private fun AddPlaceDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("집") }
    var radius by remember { mutableStateOf(150f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("장소 추가") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름 (비우면 종류 이름)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("집", "학교", "직장", "기타").forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("반경 ${radius.toInt()}m")
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 50f..500f,
                    steps = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, kind, radius.toInt()) }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
