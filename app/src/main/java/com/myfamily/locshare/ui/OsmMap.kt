package com.myfamily.locshare.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.myfamily.locshare.data.Member
import com.myfamily.locshare.data.Place
import com.myfamily.locshare.data.TrackPoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** 지도를 특정 위치로 옮기라는 요청. 같은 좌표라도 다시 이동시키려면 token을 바꾼다. */
data class MapFocus(val lat: Double, val lng: Double, val zoom: Double?, val token: Long)

private val bmpCache = HashMap<String, Bitmap>()

/** 지도 위에 올릴 가족 마커(색 동그라미 + 이름 첫 글자 + 꼬리) */
fun avatarBitmap(ctx: Context, name: String, color: Long, selected: Boolean): Bitmap {
    val key = "$name/$color/$selected"
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

    bmpCache[key] = bmp
    return bmp
}

/**
 * OpenStreetMap 기반 지도 (API 키 불필요).
 * 가족 마커, 장소 원, 이동 경로를 같이 그린다.
 */
@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    members: List<Member> = emptyList(),
    places: List<Place> = emptyList(),
    track: List<TrackPoint> = emptyList(),
    selectedId: String? = null,
    focus: MapFocus? = null,
    fitTrack: Boolean = false,
    onMemberClick: (String) -> Unit = {},
    onCenterChange: ((Double, Double) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(37.5665, 126.9780))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    val centerCallback by rememberUpdatedState(onCenterChange)
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val c = mapView.mapCenter
                centerCallback?.invoke(c.latitude, c.longitude)
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean = false
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        val target = GeoPoint(f.lat, f.lng)
        if (f.zoom != null) {
            mapView.controller.setZoom(f.zoom)
            mapView.controller.setCenter(target)
        } else {
            mapView.controller.animateTo(target)
        }
    }

    LaunchedEffect(track, fitTrack) {
        if (fitTrack && track.size >= 2) {
            val pts = track.map { GeoPoint(it.lat, it.lng) }
            mapView.post {
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120)
            }
        } else if (fitTrack && track.size == 1) {
            mapView.controller.setCenter(GeoPoint(track[0].lat, track[0].lng))
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { mv ->
            mv.overlays.clear()

            for (p in places) {
                val poly = Polygon(mv)
                poly.points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lng), p.radiusM.toDouble())
                poly.fillPaint.color = 0x332962FF
                poly.outlinePaint.color = 0xFF2962FF.toInt()
                poly.outlinePaint.strokeWidth = 3f
                poly.setOnClickListener { _, _, _ -> true }
                mv.overlays.add(poly)
            }

            if (track.size >= 2) {
                val line = Polyline(mv)
                line.setPoints(track.map { GeoPoint(it.lat, it.lng) })
                line.outlinePaint.color = 0xFF2962FF.toInt()
                line.outlinePaint.strokeWidth = 9f
                line.setOnClickListener { _, _, _ -> true }
                mv.overlays.add(line)
            }

            for (m in members) {
                if (!m.sharing) continue
                val marker = Marker(mv)
                marker.position = GeoPoint(m.lat, m.lng)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.icon = BitmapDrawable(
                    ctx.resources,
                    avatarBitmap(ctx, m.name, m.color, m.id == selectedId),
                )
                marker.setOnMarkerClickListener { _, _ ->
                    onMemberClick(m.id)
                    true
                }
                mv.overlays.add(marker)
            }
            mv.invalidate()
        },
    )
}
