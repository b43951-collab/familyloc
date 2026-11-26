package com.myfamily.locshare.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.shape.DotPoints
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.PolygonOptions
import com.kakao.vectormap.shape.PolygonStyle
import com.kakao.vectormap.shape.PolylineOptions
import com.myfamily.locshare.data.Member
import com.myfamily.locshare.data.Place
import com.myfamily.locshare.data.TrackPoint

/** 지도를 특정 위치로 옮기라는 요청. 같은 좌표라도 다시 이동시키려면 token을 바꾼다. */
data class MapFocus(val lat: Double, val lng: Double, val zoom: Double?, val token: Long)

private val bmpCache = HashMap<String, Bitmap>()

/** 지도 위에 올릴 가족 마커(색 동그라미 + 이름 첫 글자 + 꼬리) */
fun avatarBitmap(ctx: Context, name: String, color: Long, selected: Boolean, emoji: String = ""): Bitmap {
    val key = "$name/$color/$selected/$emoji"
    bmpCache[key]?.let { return it }

    val d = ctx.resources.displayMetrics.density
    val size = ((if (selected) 54 else 44) * d).toInt()
    val tail = (10 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size + tail, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f

    // 꼬리(아래 뾰족한 부분)
    paint.color = android.graphics.Color.WHITE
    val path = Path()
    path.moveTo(r - 8 * d, size - 5 * d)
    path.lineTo(r + 8 * d, size - 5 * d)
    path.lineTo(r, (size + tail).toFloat())
    path.close()
    c.drawPath(path, paint)

    // 흰 테두리 + 색 원
    paint.color = android.graphics.Color.WHITE
    c.drawCircle(r, r, r, paint)
    paint.color = color.toInt()
    c.drawCircle(r, r, r - 3 * d, paint)

    // 이름 첫 글자
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = size * 0.45f
    val y = r - (paint.ascent() + paint.descent()) / 2f
    c.drawText(name.take(1), r, y, paint)

    // 상태 이모지 배지 (오른쪽 위)
    if (emoji.isNotEmpty()) {
        val br = 10 * d
        val bx = size - br
        val by = br
        paint.color = android.graphics.Color.WHITE
        c.drawCircle(bx, by, br, paint)
        paint.color = android.graphics.Color.BLACK
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 13 * d
        val ey = by - (paint.ascent() + paint.descent()) / 2f
        c.drawText(emoji, bx, ey, paint)
    }

    bmpCache[key] = bmp
    return bmp
}

/**
 * 카카오맵 기반 지도.
 * 가족 마커, 장소 원, 이동 경로를 같이 그린다.
 */
@Composable
fun FamilyMapView(
    modifier: Modifier = Modifier,
    members: List<Member> = emptyList(),
    places: List<Place> = emptyList(),
    track: List<TrackPoint> = emptyList(),
    selectedId: String? = null,
    focus: MapFocus? = null,
    fitTrack: Boolean = false,
    onMemberClick: (String) -> Unit = {},
    onCenterChange: ((Double, Double) -> Unit)? = null,
    /** 장소 편집용: 지도 가운데 기준 이 반경(m)이 화면에서 몇 px인지 알려준다 */
    previewRadiusM: Int? = null,
    onPreviewPx: ((Float) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val mapView = remember { MapView(ctx) }

    val memberClick by rememberUpdatedState(onMemberClick)
    val centerCallback by rememberUpdatedState(onCenterChange)
    val pxCallback by rememberUpdatedState(onPreviewPx)
    var camTick by remember { mutableStateOf(0) }

    // 지도 시작 (한 번만)
    DisposableEffect(mapView) {
        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {}
                override fun onMapError(e: Exception?) {
                    Log.e("FamilyMap", "map error", e)
                    error = "지도 오류: ${e?.message ?: e}"
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    map.setOnLabelClickListener { _, _, label ->
                        (label?.tag as? String)?.let { memberClick(it) }
                        true
                    }
                    map.setOnCameraMoveEndListener { _, pos, _ ->
                        centerCallback?.invoke(pos.position.latitude, pos.position.longitude)
                        camTick++
                    }
                    kakaoMap = map
                }

                override fun getPosition(): LatLng = LatLng.from(37.5665, 126.9780)
                override fun getZoomLevel(): Int = 16
            },
        )
        onDispose {
            kakaoMap = null
            mapView.finish()
        }
    }

    // 화면 생명주기 연결 (resume/pause를 빼먹으면 크래시)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 카메라 이동 요청
    LaunchedEffect(focus, kakaoMap) {
        val map = kakaoMap ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        val target = LatLng.from(f.lat, f.lng)
        if (f.zoom != null) {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(target, f.zoom.toInt()))
        } else {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(target), CameraAnimation.from(400, true, true))
        }
    }

    // 반경 미리보기: 지도 가운데에서 반경(m)이 화면 몇 px인지 계산 (확대/축소·이동 뒤, 반경 변경 시)
    LaunchedEffect(kakaoMap, previewRadiusM, camTick) {
        val map = kakaoMap ?: return@LaunchedEffect
        val r = previewRadiusM ?: return@LaunchedEffect
        val c = map.cameraPosition?.position ?: return@LaunchedEffect
        val dLng = r / (111320.0 * Math.cos(Math.toRadians(c.latitude)))
        val p1 = map.toScreenPoint(c) ?: return@LaunchedEffect
        val p2 = map.toScreenPoint(LatLng.from(c.latitude, c.longitude + dLng)) ?: return@LaunchedEffect
        pxCallback?.invoke(Math.abs(p2.x - p1.x).toFloat())
    }

    // 이동 경로 전체 보기
    LaunchedEffect(track, fitTrack, kakaoMap) {
        val map = kakaoMap ?: return@LaunchedEffect
        if (!fitTrack || track.isEmpty()) return@LaunchedEffect
        if (track.size >= 2) {
            val pts = track.map { LatLng.from(it.lat, it.lng) }.toTypedArray()
            map.moveCamera(CameraUpdateFactory.fitMapPoints(pts, 120))
        } else {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(track[0].lat, track[0].lng), 17))
        }
    }

    // 장소 원 / 경로 / 가족 마커 다시 그리기
    LaunchedEffect(kakaoMap, members, places, track, selectedId) {
        val map = kakaoMap ?: return@LaunchedEffect
        val shapes = map.shapeManager!!.layer!!
        val labels = map.labelManager!!.layer!!
        shapes.removeAll()
        labels.removeAll()

        for (p in places) {
            val dots = DotPoints.fromCircle(LatLng.from(p.lat, p.lng), p.radiusM.toFloat())
            shapes.addPolygon(
                PolygonOptions.from(dots, PolygonStyle.from(0x332962FF, 3f, 0xFF2962FF.toInt())),
            )
        }

        if (track.size >= 2) {
            val pts = MapPoints.fromLatLng(track.map { LatLng.from(it.lat, it.lng) })
            shapes.addPolyline(PolylineOptions.from(pts, 9f, 0xFF2962FF.toInt()))
        }

        for (m in members) {
            if (!m.sharing) continue
            val bmp = avatarBitmap(ctx, m.name, m.color, m.id == selectedId, com.myfamily.locshare.util.Status.emoji(m))
            val style = LabelStyle.from(bmp)
                .setAnchorPoint(0.5f, 1.0f)
                .setApplyDpScale(false)
            labels.addLabel(
                LabelOptions.from(LatLng.from(m.lat, m.lng))
                    .setStyles(LabelStyles.from(style))
                    .setClickable(true)
                    .setTag(m.id),
            )
        }
    }

    Box(modifier) {
        AndroidView(modifier = Modifier.matchParentSize(), factory = { mapView })
        error?.let {
            Text(
                it,
                color = Color.Red,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
