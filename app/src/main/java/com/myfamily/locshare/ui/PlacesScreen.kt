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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.Surface
import kotlinx.coroutines.launch
import com.myfamily.locshare.data.PlaceSearch
import com.myfamily.locshare.data.SearchHit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SmallFloatingActionButton
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

    // 주소·장소명 검색
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusMgr = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMsg by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    fun runSearch() {
        if (query.isBlank() || searching) return
        searching = true
        searchMsg = null
        scope.launch {
            val r = PlaceSearch.search(ctx, query, center.first, center.second)
            searching = false
            searched = true
            r.onSuccess { hits = it; if (it.isEmpty()) searchMsg = "검색 결과가 없어요" }
                .onFailure { hits = emptyList(); searchMsg = "검색에 실패했어요 (${it.message ?: "네트워크 확인"})" }
        }
    }

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
            // 검색창 (지도 위쪽)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    color = Color.White,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.Black),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusMgr.clearFocus(); runSearch() }),
                            modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text("주소·장소명 검색 (예: 부발읍 893, 이천역)", color = Color.Gray, fontSize = 13.sp)
                                inner()
                            },
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; hits = emptyList(); searchMsg = null; searched = false }) {
                                Icon(Icons.Default.Clear, contentDescription = "지우기", tint = Color.Gray)
                            }
                        }
                        IconButton(onClick = { focusMgr.clearFocus(); runSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "검색", tint = Color(0xFF2962FF))
                        }
                    }
                }
                if (searching || hits.isNotEmpty() || searchMsg != null) {
                    Surface(
                        modifier = Modifier.padding(top = 6.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = Color.White,
                        shadowElevation = 4.dp,
                    ) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            if (searching) Text("검색 중…", modifier = Modifier.padding(14.dp), fontSize = 13.sp, color = Color.Gray)
                            searchMsg?.let { Text(it, modifier = Modifier.padding(14.dp), fontSize = 13.sp, color = Color.Gray) }
                            hits.forEach { h ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            focus = MapFocus(h.lat, h.lng, 17.0, System.nanoTime())
                                            val cur = draft
                                            if (cur == null) {
                                                draft = Draft(null, if (h.isPlace) h.name else "", "기타", 150f, true)
                                            } else if (cur.name.isBlank() && h.isPlace) {
                                                cur.name = h.name
                                            }
                                            hits = emptyList(); searchMsg = null
                                            focusMgr.clearFocus()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Text(h.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    if (h.addr.isNotEmpty()) Text(h.addr, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            if (d == null) {
                SmallFloatingActionButton(
                    onClick = { draft = Draft(null, "", "기타", 150f, true) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) { Icon(Icons.Default.Add, contentDescription = "장소 추가") }
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
                "내 장소 ${places.size}곳 · 누르면 수정",
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 2.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )

            if (places.isEmpty()) {
                Text(
                    "아직 장소가 없어요. 지도를 움직여 집·학교·직장 위치를 찍어 보세요.",
                    modifier = Modifier.padding(16.dp),
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
            }

            LazyColumn(Modifier.heightIn(max = 150.dp)) {
                items(places, key = { it.id }) { p ->
                    PlaceRow(
                        p,
                        onClick = {
                            // 누르면 그 장소로 이동하면서 수정 모드
                            focus = MapFocus(p.lat, p.lng, 17.0, System.nanoTime())
                            draft = Draft(p.id, p.name, p.kind, p.radiusM.toFloat().coerceIn(50f, 500f), p.shared)
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
                    valueRange = 50f..500f,
                    steps = 8,
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
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (p.kind == "집") Icons.Default.Home else Icons.Default.Place,
            contentDescription = null,
            tint = Color(0xFF2962FF),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Text(
                "${p.radiusM}m · " + (if (p.shared) "가족 공유" else "나만 보기"),
                fontSize = 11.sp,
                color = Color.Gray,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "삭제")
        }
    }
}
