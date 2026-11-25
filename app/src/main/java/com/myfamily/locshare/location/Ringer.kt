package com.myfamily.locshare.location

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** 분실폰 벨. 무음 모드여도 알람 볼륨으로 울리고, 60초 뒤 자동으로 멈춘다. */
object Ringer {
    val ringing = MutableStateFlow(false)

    private const val MAX_MS = 60_000L
    private var player: MediaPlayer? = null
    private var savedVol = -1
    private var audio: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    fun start(ctx: Context) {
        if (ringing.value) {
            handler.removeCallbacks(autoStop)
            handler.postDelayed(autoStop, MAX_MS)
            return
        }
        try {
            val app = ctx.applicationContext
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio = am
            savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            ringing.value = true
            Notifier.alert(app, "가족이 이 폰을 찾고 있어요. 앱에서 벨을 끌 수 있어요")
            handler.removeCallbacks(autoStop)
            handler.postDelayed(autoStop, MAX_MS)
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        if (savedVol >= 0) {
            try {
                audio?.setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0)
            } catch (e: Exception) {
            }
            savedVol = -1
        }
        ringing.value = false
    }
}
