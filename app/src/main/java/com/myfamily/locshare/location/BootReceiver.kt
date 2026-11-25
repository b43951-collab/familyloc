package com.myfamily.locshare.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 폰을 재부팅해도 위치 공유가 자동으로 다시 시작되게 함 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("family_local", Context.MODE_PRIVATE)
            if (prefs.getBoolean("sharing", true)) {
                LocationService.start(context)
            }
        }
    }
}
