package com.myfamily.locshare.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 내 업데이트: GitHub Releases(공개 저장소)의 최신 빌드를 확인하고 APK를 받아 설치 화면을 띄운다.
 * 릴리스 태그는 "build-숫자", 앱의 versionCode는 같은 숫자(GitHub Actions 실행 번호)다.
 */
object UpdateManager {
    private const val API = "https://api.github.com/repos/b43951-collab/familyloc/releases/latest"

    data class Info(val build: Int, val url: String, val size: Long)

    sealed interface UiState {
        object Idle : UiState
        object UpToDate : UiState
        data class Available(val info: Info, val current: Int) : UiState
        data class Downloading(val percent: Int) : UiState
        data class Failed(val message: String) : UiState
    }

    val state = MutableStateFlow<UiState>(UiState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false

    fun installedBuild(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    fun dismiss() {
        state.value = UiState.Idle
    }

    /** manual=true면 "최신 버전이에요"/실패도 화면에 알려준다. 앱 시작 때는 조용히 확인만. */
    fun check(ctx: Context, manual: Boolean) {
        if (busy) return
        busy = true
        val app = ctx.applicationContext
        scope.launch {
            try {
                val cur = installedBuild(app)
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "eodijji-app")
                }
                if (conn.responseCode != 200) throw IllegalStateException("응답 ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val build = tag.removePrefix("build-").toIntOrNull() ?: throw IllegalStateException("태그 형식 오류")
                val assets = json.getJSONArray("assets")
                var url: String? = null
                var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url")
                        size = a.optLong("size", 0L)
                        break
                    }
                }
                if (url != null && build > cur) {
                    state.value = UiState.Available(Info(build, url, size), cur)
                } else if (manual) {
                    state.value = UiState.UpToDate
                }
            } catch (e: Exception) {
                if (manual) state.value = UiState.Failed("업데이트 확인 실패: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    fun downloadAndInstall(ctx: Context, info: Info) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            // 이 앱이 APK 설치를 요청할 수 있게 허용하는 설정 화면으로 보낸다
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(i)
            state.value = UiState.Failed("'이 출처 허용'을 켠 뒤 다시 업데이트를 눌러주세요")
            return
        }
        state.value = UiState.Downloading(0)
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "eodijji.apk")
                val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "eodijji-app")
                }
                if (conn.responseCode != 200) throw IllegalStateException("다운로드 응답 ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
                var done = 0L
                var last = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    state.value = UiState.Downloading(p)
                                }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
                state.value = UiState.Idle
            } catch (e: Exception) {
                state.value = UiState.Failed("다운로드 실패: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
