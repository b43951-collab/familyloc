package com.myfamily.locshare.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.myfamily.locshare.MainActivity
import com.myfamily.locshare.R
import com.myfamily.locshare.data.Member
import com.myfamily.locshare.data.Place
import com.myfamily.locshare.util.Fmt
import com.myfamily.locshare.util.Geo
import com.myfamily.locshare.util.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WKind { S, M, L, XL }

/** 홈 화면 위젯 4종(2×1 / 4×1 / 4×2 / 4×4). 위젯 실제 크기에 맞춰 개수·글자·표시 항목이 달라져요. */
open class FamilyWidget : AppWidgetProvider() {
    open val kind: WKind = WKind.L

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx, kind, mgr.getAppWidgetOptions(id)))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        mgr.updateAppWidget(id, build(ctx, kind, newOptions))
    }

    companion object {
        private const val PREF = "widget"
        private const val KEY = "rows"
        private const val KEY_AT = "at"
        private var lastPush = 0L
        private val classes = listOf(
            FamilyWidget::class.java, WidgetS::class.java, WidgetM::class.java, WidgetXL::class.java,
        )
        private fun kindOf(c: Class<*>) = when (c) {
            WidgetS::class.java -> WKind.S
            WidgetM::class.java -> WKind.M
            WidgetXL::class.java -> WKind.XL
            else -> WKind.L
        }

        private var lastLines: String? = null
        private var pending: Runnable? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        /** 앱이 최신 가족 상태를 알려줄 때 호출. 내용이 바뀐 경우만, 최소 10초 간격으로 갱신(너무 잦으면 마지막 값을 나중에 반영). */
        fun push(ctx: Context, members: List<Member>, places: List<Place>) {
            val lines = members.take(8).joinToString("\n") { m ->
                val where = when {
                    m.updatedAt <= 0L -> "정보 없음"   // 아직 위치를 못 받은 상태 (공유 꺼짐과 구분)
                    !m.sharing -> "공유 꺼짐"
                    else -> {
                        val inPlace = places.firstOrNull {
                            Geo.distM(m.lat, m.lng, it.lat, it.lng) <= it.radiusM + 30
                        }
                        when {
                            inPlace != null -> inPlace.name
                            m.speedMps > 1.2f -> "이동 중"
                            else -> "밖"
                        }
                    }
                }
                listOf(
                    m.name, Status.emoji(m), m.color.toInt().toString(), where,
                    m.updatedAt.toString(), m.battery.toString(), if (m.charging) "1" else "0",
                ).joinToString("\t")
            }
            if (lines == lastLines) return
            val app = ctx.applicationContext
            pending?.let { handler.removeCallbacks(it) }
            val wait = 10_000L - (System.currentTimeMillis() - lastPush)
            val job = Runnable {
                pending = null
                lastPush = System.currentTimeMillis()
                lastLines = lines
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY, lines).putLong(KEY_AT, lastPush).apply()
                refresh(app)
            }
            if (wait <= 0L) job.run() else { pending = job; handler.postDelayed(job, wait) }
        }

        fun clear(ctx: Context) {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
            lastLines = null
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            refresh(ctx)
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                for (c in classes) {
                    val ids = mgr.getAppWidgetIds(ComponentName(ctx, c))
                    for (id in ids) mgr.updateAppWidget(id, build(ctx, kindOf(c), mgr.getAppWidgetOptions(id)))
                }
            } catch (_: Exception) {
            }
        }

        private fun avatar(name: String, color: Int, emoji: String): Bitmap {
            val s = 120
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = color
            c.drawCircle(s / 2f, s / 2f, s / 2f - 4f, p)
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.isFakeBoldText = true
            val t = name.take(2)
            p.textSize = if (t.length >= 2) 42f else 56f
            val fm = p.fontMetrics
            c.drawText(t, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p)
            if (emoji.isNotEmpty()) {
                p.isFakeBoldText = false
                p.textSize = 44f
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
                c.drawCircle(s - 28f, s - 28f, 26f, bg)
                val f2 = p.fontMetrics
                c.drawText(emoji, s - 28f, s - 28f - (f2.ascent + f2.descent) / 2f, p)
            }
            return bmp
        }

        private class R0(
            val name: String, val emo: String, val color: Int, val where: String,
            val t: Long, val bat: Int, val chg: Boolean,
        )

        private fun parse(raw: String?): List<R0> = (raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()).map { line ->
            val p = line.split("\t")
            R0(
                p.getOrNull(0) ?: "", p.getOrNull(1) ?: "", p.getOrNull(2)?.toIntOrNull() ?: Color.GRAY,
                p.getOrNull(3) ?: "", p.getOrNull(4)?.toLongOrNull() ?: 0L,
                p.getOrNull(5)?.toIntOrNull() ?: -1, p.getOrNull(6) == "1",
            )
        }

        private fun batText(r: R0) = if (r.bat >= 0) (if (r.chg) "⚡" else "🔋") + "${r.bat}%" else ""

        private fun build(ctx: Context, kind: WKind, opts: Bundle?): RemoteViews {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val rows = parse(sp.getString(KEY, null))
            val d = ctx.resources.displayMetrics.density
            val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 }
                ?: when (kind) { WKind.S -> 110; else -> 250 }
            val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 0 }
                ?: when (kind) { WKind.S, WKind.M -> 40; WKind.L -> 110; WKind.XL -> 250 }

            val v: RemoteViews
            if (kind == WKind.S || kind == WKind.M) {
                v = RemoteViews(ctx.packageName, R.layout.widget_strip)
                v.removeAllViews(R.id.w_list)
                if (kind == WKind.S) {
                    // 아이콘 + (장소 배터리) 세로 한 칸씩
                    val n = minOf(rows.size, ((wDp - 12) / 44).coerceIn(1, 5))
                    for (i in 0 until n) {
                        val r = rows[i]
                        val c = RemoteViews(ctx.packageName, R.layout.widget_col)
                        c.setImageViewBitmap(R.id.c_avatar, avatar(r.name, r.color, r.emo))
                        val short = if (r.where == "이동 중" || r.where == "밖" || r.where == "공유 꺼짐") r.where.take(3) else r.where
                        c.setTextViewText(R.id.c_sub, if (r.bat >= 0) "$short ${r.bat}%" else short)
                        if (r.bat in 0..20 && !r.chg) c.setTextColor(R.id.c_sub, Color.parseColor("#D32F2F"))
                        v.addView(R.id.w_list, c)
                    }
                    v.setViewVisibility(R.id.w_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
                } else {
                    // 가족 카드를 가로로 한 줄
                    val n = minOf(rows.size, (wDp / 100).coerceIn(1, 4))
                    for (i in 0 until n) {
                        val r = rows[i]
                        val c = RemoteViews(ctx.packageName, R.layout.widget_card)
                        c.setImageViewBitmap(R.id.c_avatar, avatar(r.name, r.color, r.emo))
                        c.setTextViewText(R.id.c_name, r.name)
                        val bt = batText(r)
                        c.setTextViewText(R.id.c_sub, if (bt.isNotEmpty()) "${r.where} · $bt" else r.where)
                        v.addView(R.id.w_list, c)
                    }
                    v.setViewVisibility(R.id.w_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
                }
            } else {
                v = RemoteViews(ctx.packageName, R.layout.widget_family)
                val avail = hDp - 40
                val maxRows = (avail / 52).coerceIn(1, 8)
                val n = minOf(rows.size, maxRows)
                val rowH = if (n > 0) (avail / n).coerceIn(52, 96) else 52
                val scale = 1f + (rowH - 52) / 90f
                val pad = ((rowH - 52) / 2).coerceAtLeast(0)
                val wide = wDp >= 230
                val sz = android.util.TypedValue.COMPLEX_UNIT_SP
                v.removeAllViews(R.id.w_list)
                for (i in 0 until n) {
                    val r = rows[i]
                    val c = RemoteViews(ctx.packageName, R.layout.widget_row)
                    c.setImageViewBitmap(R.id.r_avatar, avatar(r.name, r.color, r.emo))
                    c.setTextViewText(R.id.r_name, r.name)
                    c.setTextViewTextSize(R.id.r_name, sz, 14f * scale)
                    val ago = if (r.t > 0) Fmt.ago(r.t) else ""
                    if (wide) {
                        // 넓으면 장소·시간을 가운데 칸으로
                        c.setViewVisibility(R.id.r_sub, View.GONE)
                        c.setViewVisibility(R.id.r_mid, View.VISIBLE)
                        c.setTextViewText(R.id.r_place, r.where)
                        c.setTextViewText(R.id.r_ago, ago)
                        c.setTextViewTextSize(R.id.r_place, sz, 14f * scale)
                        c.setTextViewTextSize(R.id.r_ago, sz, 12f * scale)
                    } else {
                        c.setTextViewText(R.id.r_sub, if (ago.isNotEmpty()) "${r.where} · $ago" else r.where)
                        c.setTextViewTextSize(R.id.r_sub, sz, 12f * scale)
                    }
                    c.setTextViewText(R.id.r_bat, batText(r))
                    c.setTextColor(R.id.r_bat, if (r.bat in 0..20 && !r.chg) Color.parseColor("#D32F2F") else Color.parseColor("#374151"))
                    c.setTextViewTextSize(R.id.r_bat, sz, 13f * scale)
                    val pp = (pad * d).toInt()
                    c.setViewPadding(R.id.r_root, (8 * d).toInt(), (6 * d).toInt() + pp, (12 * d).toInt(), (6 * d).toInt() + pp)
                    v.addView(R.id.w_list, c)
                }
                v.setViewVisibility(R.id.w_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
                val at = sp.getLong(KEY_AT, 0L)
                v.setTextViewText(R.id.w_time, if (at > 0) SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at)) + " 기준" else "")
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}

class WidgetS : FamilyWidget() { override val kind = WKind.S }
class WidgetM : FamilyWidget() { override val kind = WKind.M }
class WidgetXL : FamilyWidget() { override val kind = WKind.XL }
