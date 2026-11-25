package com.myfamily.locshare

import android.app.Application
import com.myfamily.locshare.data.FirebaseFamilyRepository
import com.myfamily.locshare.data.ServiceLocator
import com.myfamily.locshare.location.Notifier
import com.kakao.vectormap.KakaoMapSdk
import com.myfamily.locshare.data.KakaoConfig

class FamilyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        KakaoMapSdk.init(this, KakaoConfig.NATIVE_APP_KEY)

        Notifier.ensureChannels(this)

        // 서버 연동 구현. (서버 없이 체험만 하려면 LocalFamilyRepository(this) 로 바꾸면 됩니다)
        ServiceLocator.repo = FirebaseFamilyRepository(this)
    }
}
