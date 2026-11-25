package com.myfamily.locshare.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.myfamily.locshare.MainActivity

object Notifier {
    // 채널 중요도는 만든 뒤에 못 바꿔서, 최소 중요도용 새 채널을 쓰고 예전 "sharing" 채널은 지운다.
    const val CH_SERVICE = "sharing_min"
    const val CH_ALERT = "alerts"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("sharing")
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "위치 공유 상태", NotificationManager.IMPORTANCE_MIN).apply {
                description = "위치를 공유하는 동안 표시되는 알림 (끄면 앱이 종료될 수 있어요)"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "가족 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "도착/출발, SOS 알림"
            }
        )
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun serviceNotification(ctx: Context): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("가족과 위치 공유 중")
            .setContentText("가족이 내 위치를 볼 수 있어요")
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(ctx))
            .build()

    fun alert(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("어디찌?")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
            nm.notify((System.nanoTime() and 0x7FFFFFFF).toInt(), n)
        } catch (e: Exception) {
            // 알림 권한이 없는 경우 등은 무시
        }
    }
}
