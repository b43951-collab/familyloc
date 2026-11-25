package com.myfamily.locshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.AlertEvent
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.util.Fmt

@Composable
fun AlertsScreen(repo: FamilyRepository) {
    val events by repo.events.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("알림 기록", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TextButton(onClick = { repo.clearEvents() }, enabled = events.isNotEmpty()) {
                Text("모두 지우기")
            }
        }

        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 알림이 없어요.\n장소를 등록하면 가족이 도착/출발할 때 여기에 쌓여요.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { e ->
                    EventRow(e)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: AlertEvent) {
    val icon = when (e.kind) {
        "ARRIVE", "LEAVE" -> Icons.Default.Home
        "SOS" -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
    val tint = when (e.kind) {
        "ARRIVE" -> Color(0xFF2E7D32)
        "LEAVE" -> Color(0xFFEF6C00)
        "SOS" -> Color(0xFFD32F2F)
        else -> Color.Gray
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.text, fontSize = 15.sp)
            Text(Fmt.dateTime(e.time), fontSize = 12.sp, color = Color.Gray)
        }
    }
}
