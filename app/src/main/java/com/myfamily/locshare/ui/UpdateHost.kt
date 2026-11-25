package com.myfamily.locshare.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myfamily.locshare.util.UpdateManager
import com.myfamily.locshare.util.UpdateManager.UiState

/** 앱 시작 시 새 버전을 조용히 확인하고, 있으면 업데이트 안내창을 띄운다. */
@Composable
fun UpdateHost() {
    val ctx = LocalContext.current
    val st by UpdateManager.state.collectAsState()

    LaunchedEffect(Unit) { UpdateManager.check(ctx, manual = false) }

    when (val s = st) {
        UiState.Idle -> {}
        UiState.UpToDate -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("최신 버전이에요") },
            text = { Text("현재 빌드 ${UpdateManager.installedBuild(ctx)}") },
            confirmButton = { TextButton(onClick = { UpdateManager.dismiss() }) { Text("확인") } },
        )
        is UiState.Available -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("새 버전이 있어요") },
            text = { Text("빌드 ${s.current} → ${s.info.build}\n업데이트해도 로그인과 가족 정보는 그대로예요.") },
            confirmButton = {
                TextButton(onClick = { UpdateManager.downloadAndInstall(ctx, s.info) }) { Text("업데이트") }
            },
            dismissButton = { TextButton(onClick = { UpdateManager.dismiss() }) { Text("나중에") } },
        )
        is UiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("다운로드 중… ${s.percent}%") },
            text = {
                Column {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is UiState.Failed -> AlertDialog(
            onDismissRequest = { UpdateManager.dismiss() },
            title = { Text("업데이트") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { UpdateManager.dismiss() }) { Text("확인") } },
        )
    }
}

/** 위치 공유 상태 알림이 아직 켜져 있으면 한 번 물어보고, 원하면 그 채널 설정 화면으로 바로 보낸다. */
@Composable
fun HideNotiHost() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var show by remember {
        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val ch = nm.getNotificationChannel(com.myfamily.locshare.location.Notifier.CH_SERVICE)
        mutableStateOf(
            ch != null && ch.importance != android.app.NotificationManager.IMPORTANCE_NONE &&
                !prefs.getBoolean("hide_noti_asked", false)
        )
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("상단 알림을 숨길까요?") },
        text = {
            Text(
                "\"가족과 위치 공유 중\" 알림은 꺼도 위치 공유는 계속돼요.\n" +
                    "열린 화면에서 스위치를 끄세요. (삼성: 설정 → 알림 → 고급 설정에서 \"앱별 알림 카테고리 관리\"를 켜야 보일 수 있어요)"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean("hide_noti_asked", true).apply()
                show = false
                try {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.myfamily.locshare.location.Notifier.CH_SERVICE)
                    )
                } catch (e: Exception) {
                }
            }) { Text("설정 열기") }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit().putBoolean("hide_noti_asked", true).apply()
                show = false
            }) { Text("그대로 둘게요") }
        },
    )
}
