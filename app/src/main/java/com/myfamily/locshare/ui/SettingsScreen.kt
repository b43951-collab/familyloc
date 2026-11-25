package com.myfamily.locshare.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.location.LocationService
import com.myfamily.locshare.util.Perms

@Composable
fun SettingsScreen(repo: FamilyRepository, tick: Int) {
    val ctx = LocalContext.current
    val settings by repo.settings.collectAsState()
    val profile by repo.profile.collectAsState()

    var bump by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(0) } // 1 = 서클 나가기, 2 = 로그아웃
    val key = tick + bump
    val fine = remember(key) { Perms.fine(ctx) }
    val bg = remember(key) { Perms.background(ctx) }
    val noti = remember(key) { Perms.notifications(ctx) }
    val batt = remember(key) { Perms.batteryExempt(ctx) }

    fun afterPerm() {
        bump++
        if (Perms.fine(ctx) && repo.settings.value.sharing) LocationService.start(ctx)
    }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { afterPerm() }
    val notiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { afterPerm() }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "설정 화면을 열 수 없어요. 직접 설정 앱에서 변경해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    fun appDetails() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${ctx.packageName}")
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 권한 · 절전 ----------
        item {
            SectionCard("권한 · 절전 설정 (삼성 갤럭시 필수)") {
                PermRow(
                    "위치 권한", "앱을 사용하는 동안 내 위치 사용", fine, "허용",
                ) {
                    fineLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
                PermRow(
                    "항상 허용", "앱을 꺼도 위치 공유 (권한 → 위치 → '항상 허용' 선택)", bg, "설정 열기",
                ) {
                    if (!fine) {
                        Toast.makeText(ctx, "먼저 위치 권한을 허용해 주세요", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "권한 → 위치 → '항상 허용'을 선택해 주세요", Toast.LENGTH_LONG).show()
                        open(appDetails())
                    }
                }
                PermRow(
                    "알림 권한", "도착/출발·SOS 알림 받기", noti, "허용",
                ) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notiLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                PermRow(
                    "배터리 '제한 없음'", "절전 때문에 앱이 꺼지는 것을 방지", batt, "설정",
                ) {
                    open(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "갤럭시는 절전이 강해서 아래도 꼭 확인하세요 (One UI 버전에 따라 이름이 조금 달라요)\n" +
                        "• 설정 → 배터리 → 백그라운드 사용 제한 → '사용하지 않는 앱 절전 전환' 끄기\n" +
                        "• 같은 메뉴의 '절전 상태로 전환되지 않는 앱'에 이 앱 추가\n" +
                        "• 설정 → 앱 → 어디찌? → 배터리 → '제한 없음'\n" +
                        "• 최근 앱 화면에서 이 앱 아이콘을 눌러 '잠금 유지' (위로 밀어 닫지 않기)",
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(appDetails()) }) { Text("앱 정보 열기") }
                    OutlinedButton(onClick = {
                        open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("배터리 최적화 목록") }
                }
            }
        }

        // ---------- 공유 옵션 ----------
        item {
            SectionCard("위치 공유") {
                SwitchRow("내 위치 공유", "끄면 가족에게 내 위치가 보이지 않아요", settings.sharing) {
                    if (it && !Perms.fine(ctx)) {
                        Toast.makeText(ctx, "먼저 위치 권한을 허용해 주세요", Toast.LENGTH_SHORT).show()
                    }
                    repo.setSharing(it)
                }
                SwitchRow("배터리 절약 모드", "수집 주기를 늘려 배터리를 아껴요 (실시간성은 조금 떨어짐)", settings.saver) {
                    repo.setSaver(it)
                }
            }
        }

        // ---------- 프로필 ----------
        item {
            SectionCard("내 프로필") {
                var nameText by remember(profile.name) { mutableStateOf(profile.name) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(nameText.ifEmpty { "나" }, profile.color, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it.take(8) },
                        label = { Text("이름") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profileColors.forEach { c ->
                        val selected = c == profile.color
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(
                                    if (selected) Modifier.border(3.dp, Color.Black, CircleShape)
                                    else Modifier
                                )
                                .clickable { repo.updateProfile(nameText, c) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    repo.updateProfile(nameText, profile.color)
                    Toast.makeText(ctx, "저장했어요", Toast.LENGTH_SHORT).show()
                }) { Text("이름 저장") }
            }
        }

        // ---------- 가족 서클 ----------
        item {
            SectionCard("가족 서클") {
                Text("내 서클 코드", fontSize = 12.sp, color = Color.Gray)
                Text(profile.circleCode, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("circle", profile.circleCode))
                        Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
                    }) { Text("코드 복사") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (repo.supportsDemo) "서버를 연결하면 이 코드로 가족이 같은 서클에 들어올 수 있어요."
                    else "가족이 앱에서 '코드로 참여'를 고르고 이 코드를 입력하면 같은 서클에 들어와요.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                if (!repo.supportsDemo) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = 1 }) { Text("서클 나가기") }
                        OutlinedButton(onClick = { confirm = 2 }) { Text("로그아웃") }
                    }
                }
            }
        }

        // ---------- 데모 / 정보 ----------
        item {
            SectionCard("체험 · 정보") {
                if (repo.supportsDemo) SwitchRow(
                    "데모 가족 표시",
                    "서버 없이도 가족 기능을 체험할 수 있게 가짜 가족(엄마·아빠·동생)이 내 주변을 돌아다녀요",
                    settings.demo,
                ) { repo.setDemo(it) }
                Spacer(Modifier.height(6.dp))
                val updCtx = LocalContext.current
                OutlinedButton(
                    onClick = { com.myfamily.locshare.util.UpdateManager.check(updCtx, manual = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("업데이트 확인 (현재 빌드 ${com.myfamily.locshare.util.UpdateManager.installedBuild(updCtx)})") }
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (repo.supportsDemo) "어디찌? v0.1 · 로컬 전용(서버 미연동)" else "어디찌? v0.1 · Firebase 서버 연동") +
                        "\n지도: 카카오맵",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
        }
    }

    if (confirm != 0) {
        AlertDialog(
            onDismissRequest = { confirm = 0 },
            title = { Text(if (confirm == 1) "서클에서 나갈까요?" else "로그아웃할까요?") },
            text = {
                Text(
                    if (confirm == 1) "더 이상 가족의 위치를 볼 수 없고 내 위치도 공유되지 않아요. 코드로 다시 들어올 수 있어요."
                    else "로그아웃하면 위치 공유가 멈춰요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = confirm
                    confirm = 0
                    if (c == 1) repo.leaveCircle() else repo.signOut()
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { confirm = 0 }) { Text("취소") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PermRow(
    title: String,
    desc: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = Color.Gray)
        }
        if (!ok) {
            Button(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(actionLabel, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
