package com.myfamily.locshare

import android.app.Application
import com.myfamily.locshare.data.FirebaseFamilyRepository
import com.myfamily.locshare.data.ServiceLocator
import com.myfamily.locshare.location.Notifier
import org.osmdroid.config.Configuration
import java.io.File

class FamilyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 지도 타일 캐시를 앱 전용 폴더에 둠 (저장소 권한 불필요)
        val base = File(cacheDir, "osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }

        Notifier.ensureChannels(this)

        // 서버 연동 구현. (서버 없이 체험만 하려면 LocalFamilyRepository(this) 로 바꾸면 됩니다)
        ServiceLocator.repo = FirebaseFamilyRepository(this)
    }
}
