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
