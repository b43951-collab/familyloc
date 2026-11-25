package com.myfamily.locshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myfamily.locshare.data.FamilyRepository
import com.myfamily.locshare.data.ServiceLocator
import com.myfamily.locshare.data.Session
import com.myfamily.locshare.location.LocationService
import com.myfamily.locshare.ui.UpdateHost
import com.myfamily.locshare.ui.AlertsScreen
import com.myfamily.locshare.ui.AppTheme
import com.myfamily.locshare.ui.AuthScreen
import com.myfamily.locshare.ui.CircleScreen
import com.myfamily.locshare.ui.HistoryScreen
import com.myfamily.locshare.ui.MapScreen
import com.myfamily.locshare.ui.PlacesScreen
import com.myfamily.locshare.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    /** 화면이 다시 보일 때마다 증가 → 권한 상태 등을 다시 확인하게 하는 신호 */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = ServiceLocator.repo
        setContent {
            AppTheme {
                AppRoot(repo, resumeTick.intValue)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocationService.viewing.value = true // 앱을 보는 동안은 위치를 자주 갱신
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // 설정 화면에서 권한을 허용하고 돌아온 경우 등을 위해 매번 시작을 시도
        val repo = ServiceLocator.repo
        if (repo.settings.value.sharing && repo.session.value == Session.READY) {
            LocationService.start(this)
        }
    }

    override fun onStop() {
        LocationService.viewing.value = false
        super.onStop()
    }
}

/** 로그인 상태에 따라 화면을 고른다 */
@Composable
fun AppRoot(repo: FamilyRepository, tick: Int) {
    val session by repo.session.collectAsState()
    when (session) {
        Session.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        Session.LOGGED_OUT -> AuthScreen(repo)
        Session.NO_CIRCLE -> CircleScreen(repo)
        Session.READY -> MainTabs(repo, tick)
    }
    UpdateHost()
    if (session == Session.READY) com.myfamily.locshare.ui.HideNotiHost()
}

@Composable
fun MainTabs(repo: FamilyRepository, tick: Int) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf(
        "지도" to Icons.Default.LocationOn,
        "장소" to Icons.Default.Home,
        "기록" to Icons.Default.DateRange,
        "알림" to Icons.Default.Notifications,
        "설정" to Icons.Default.Settings,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> MapScreen(repo, tick) { tab = 4 }
                1 -> PlacesScreen(repo)
                2 -> HistoryScreen(repo)
                3 -> AlertsScreen(repo)
                else -> SettingsScreen(repo, tick)
            }
        }
    }
}
