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

/** 홈 화면 위젯: 가족 위치(장소 이름)·마지막 업데이트·배터리. 크기를 키우면 줄 수와 글자가 함께 커져요. */
class FamilyWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx, mgr.getAppWidgetOptions(id)))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        mgr.updateAppWidget(id, build(ctx, newOptions))
    }

    companion object {
        private const val PREF = "widget"
        private const val KEY = "rows"
        private const val KEY_AT = "at"
        private var lastPush = 0L

        /** 앱이 최신 가족 상태를 알려줄 때 호출. 30초에 한 번만 갱신해요. */
        fun push(ctx: Context, members: List<Member>, places: List<Place>) {
            val now = System.currentTimeMillis()
            if (now - lastPush < 30_000L) return
            lastPush = now
            val lines = members.take(8).joinToString("\n") { m ->
                val where = when {
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
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, lines).putLong(KEY_AT, now).apply()
            refresh(ctx)
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            refresh(ctx)
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, FamilyWidget::class.java))
                for (id in ids) mgr.updateAppWidget(id, build(ctx, mgr.getAppWidgetOptions(id)))
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

        private fun build(ctx: Context, opts: Bundle?): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_family)
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY, null)
            val rows = raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

            // 위젯 크기에 맞춰 보여줄 줄 수와 줄 높이를 정한다
            val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)?.takeIf { it > 0 } ?: 110
            val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: 250
            val avail = hDp - 40
            val maxRows = (avail / 52).coerceIn(1, 8)
            val n = minOf(rows.size, maxRows)
            val rowH = if (n > 0) (avail / n).coerceIn(52, 96) else 52
            val scale = 1f + (rowH - 52) / 90f   // 줄이 높아지면 글자도 같이 커짐
            val pad = ((rowH - 52) / 2).coerceAtLeast(0)
            val d = ctx.resources.displayMetrics.density
            val showAgo = wDp >= 150

            v.removeAllViews(R.id.w_list)
            for (i in 0 until n) {
                val p = rows[i].split("\t")
                val name = p.getOrNull(0) ?: ""
                val emo = p.getOrNull(1) ?: ""
                val color = p.getOrNull(2)?.toIntOrNull() ?: Color.GRAY
                val where = p.getOrNull(3) ?: ""
                val t = p.getOrNull(4)?.toLongOrNull() ?: 0L
                val bat = p.getOrNull(5)?.toIntOrNull() ?: -1
                val chg = p.getOrNull(6) == "1"

                val r = RemoteViews(ctx.packageName, R.layout.widget_row)
                r.setImageViewBitmap(R.id.r_avatar, avatar(name, color, emo))
                r.setTextViewText(R.id.r_name, name)
                r.setTextViewTextSize(R.id.r_name, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale)
                val ago = if (t > 0) Fmt.ago(t) else ""
                r.setTextViewText(R.id.r_sub, if (showAgo && ago.isNotEmpty()) "$where · $ago" else where)
                r.setTextViewTextSize(R.id.r_sub, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale)
                if (bat >= 0) {
                    r.setTextViewText(R.id.r_bat, (if (chg) "⚡" else "🔋") + "$bat%")
                    r.setTextColor(R.id.r_bat, if (bat <= 20 && !chg) Color.parseColor("#D32F2F") else Color.parseColor("#374151"))
                    r.setTextViewTextSize(R.id.r_bat, android.util.TypedValue.COMPLEX_UNIT_SP, 13f * scale)
                } else {
                    r.setTextViewText(R.id.r_bat, "")
                }
                val pp = (pad * d).toInt()
                r.setViewPadding(R.id.r_root, (8 * d).toInt(), (6 * d).toInt() + pp, (12 * d).toInt(), (6 * d).toInt() + pp)
                v.addView(R.id.w_list, r)
            }
            v.setViewVisibility(R.id.w_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            val at = sp.getLong(KEY_AT, 0L)
            v.setTextViewText(R.id.w_time, if (at > 0) SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at)) + " 기준" else "")

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }
}
