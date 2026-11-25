package com.myfamily.locshare.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myfamily.locshare.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2962FF),
            secondary = Color(0xFF00BFA5),
            tertiary = Color(0xFFFF6D00),
        ),
        content = content,
    )
}

/** 프로필 색 선택지 */
val profileColors: List<Long> = listOf(
    0xFF1E88E5, 0xFFE91E63, 0xFF43A047, 0xFFFF9800,
    0xFF8E24AA, 0xFF00ACC1, 0xFFF4511E, 0xFF6D4C41,
)

@Composable
fun Avatar(name: String, color: Long, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** 20초마다 갱신되는 현재 시각 ("3분 전" 같은 표시를 자동으로 갱신하기 위해) */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now.value = System.currentTimeMillis()
        }
    }
    return now
}

@Suppress("DEPRECATION")
private fun reverseGeocode(ctx: Context, lat: Double, lng: Double): String? {
    return try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            val list = Geocoder(ctx, Locale.KOREA).getFromLocation(lat, lng, 1)
            list?.firstOrNull()?.getAddressLine(0)?.removePrefix("대한민국 ")
        }
    } catch (e: Exception) {
        null
    }
}

/** 좌표 → 주소 (인터넷이 안 되거나 실패하면 좌표를 그대로 보여줌) */
@Composable
fun rememberAddress(lat: Double, lng: Double): String {
    val ctx = LocalContext.current
    val key = String.format(Locale.US, "%.4f,%.4f", lat, lng)
    var text by remember(key) { mutableStateOf("주소 확인 중…") }
    LaunchedEffect(key) {
        val r = withContext(Dispatchers.IO) { reverseGeocode(ctx, lat, lng) }
        text = r ?: Fmt.coord(lat, lng)
    }
    return text
}
