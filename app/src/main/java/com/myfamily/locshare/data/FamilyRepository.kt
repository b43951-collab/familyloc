package com.myfamily.locshare.data

import kotlinx.coroutines.flow.StateFlow

/**
 * 앱 전체가 의존하는 데이터 창구.
 *
 * 지금은 [LocalFamilyRepository] (이 폰 안에서만 동작 + 데모 가족)를 쓰고,
 * 서버가 준비되면 같은 인터페이스를 구현한 FirebaseFamilyRepository로 교체하면 됩니다.
 * (FamilyApp.kt 에서 ServiceLocator.repo 만 바꿔 끼우면 화면 코드는 그대로)
 *
 * 서버 연동 시 대응 관계:
 *  - onMyLocation  → 내 위치를 서버에 업로드
 *  - members       → 가족 위치 실시간 구독
 *  - places/events → 서클 단위로 서버에 저장·구독, 도착/출발 알림은 서버(또는 FCM)에서
 *  - sendSos       → 가족 전원에게 푸시
 *  - requestRefresh→ 가족 폰에 FCM 푸시로 "지금 위치 올려" 요청
 */
/** 앱의 로그인/서클 상태. READY일 때만 지도 등 메인 화면을 보여준다. */
enum class Session { LOADING, LOGGED_OUT, NO_CIRCLE, READY }

interface FamilyRepository {
    val session: StateFlow<Session>
    /** 데모 가족 같은 로컬 전용 기능을 지원하는지 (서버 연동 구현은 false) */
    val supportsDemo: Boolean

    fun signIn(email: String, password: String, onResult: (String?) -> Unit)
    fun signUp(email: String, password: String, name: String, onResult: (String?) -> Unit)
    fun createCircle(onResult: (String?) -> Unit)
    fun joinCircle(code: String, onResult: (String?) -> Unit)
    fun leaveCircle()
    fun signOut()

    val members: StateFlow<List<Member>>
    val places: StateFlow<List<Place>>
    val events: StateFlow<List<AlertEvent>>
    val profile: StateFlow<Profile>
    val settings: StateFlow<Settings>

    fun onMyLocation(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        speedMps: Float,
        battery: Int,
        charging: Boolean,
        time: Long,
    )

    fun addPlace(name: String, lat: Double, lng: Double, radiusM: Int, kind: String)
    fun removePlace(id: String)
    fun updateProfile(name: String, color: Long)
    fun setSharing(on: Boolean)
    fun setDemo(on: Boolean)
    fun setSaver(on: Boolean)
    fun sendSos()
    fun requestRefresh()
    fun history(fromMs: Long, toMs: Long): List<TrackPoint>
    fun clearEvents()
}

object ServiceLocator {
    lateinit var repo: FamilyRepository
}
