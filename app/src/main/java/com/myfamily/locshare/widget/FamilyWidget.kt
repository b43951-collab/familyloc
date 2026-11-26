package com.myfamily.locshare.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.myfamily.locshare.MainActivity
import com.myfamily.locshare.R
import com.myfamily.locshare.data.Member
import com.myfamily.locshare.data.Place
import com.myfamily.locshare.util.Fmt
import com.myfamily.locshare.util.Geo
import com.myfamily.locshare.util.Status

/** 홈 화면 위젯: 가족 위치(장소 이름)·마지막 업데이트·배터리를 한눈에. 탭하면 앱이 열려요. */
class FamilyWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(ctx))
    }

    companion object {
        private const val PREF = "widget"
        private const val KEY = "rows"
        private val rowIds = intArrayOf(R.id.w_r0, R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4, R.id.w_r5)
        private var lastPush = 0L

        /** 앱이 최신 가족 상태를 알려줄 때 호출. 30초에 한 번만 갱신해요. */
        fun push(ctx: Context, members: List<Member>, places: List<Place>) {
            val now = System.currentTimeMillis()
            if (now - lastPush < 30_000L) return
            lastPush = now
            val lines = members.take(rowIds.size).joinToString("\n") { m ->
                val emo = Status.emoji(m)
                val head = (if (emo.isNotEmpty()) "$emo " else "") + m.name
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
                val bat = if (m.battery >= 0) " · 🔋${m.battery}%" else ""
                val ago = if (m.updatedAt > 0) Fmt.ago(m.updatedAt) else ""
                // 시간은 표시할 때 다시 계산하도록 t를 같이 저장
                listOf(head, where, bat, m.updatedAt.toString(), ago).joinToString("\t")
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, lines).apply()
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
                if (ids.isEmpty()) return
                val v = build(ctx)
                for (id in ids) mgr.updateAppWidget(id, v)
            } catch (_: Exception) {
            }
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_family)
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            val rows = raw?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            for ((i, rid) in rowIds.withIndex()) {
                val r = rows.getOrNull(i)
                if (r == null) {
                    v.setViewVisibility(rid, android.view.View.GONE)
                    continue
                }
                val p = r.split("\t")
                val head = p.getOrNull(0) ?: ""
                val where = p.getOrNull(1) ?: ""
                val bat = p.getOrNull(2) ?: ""
                val t = p.getOrNull(3)?.toLongOrNull() ?: 0L
                val ago = if (t > 0) " · " + Fmt.ago(t) else ""
                v.setTextViewText(rid, "$head  $where$ago$bat")
                v.setViewVisibility(rid, android.view.View.VISIBLE)
            }
            if (rows.isEmpty()) {
                v.setTextViewText(R.id.w_r0, "앱을 열어 로그인하면 가족 위치가 보여요")
                v.setViewVisibility(R.id.w_r0, android.view.View.VISIBLE)
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
