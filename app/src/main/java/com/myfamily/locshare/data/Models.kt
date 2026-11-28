package com.myfamily.locshare.data

/** 가족 구성원 (색상은 ARGB Long, 예: 0xFF1E88E5) */
data class Member(
    val id: String,
    val name: String,
    val color: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float = 0f,
    val speedMps: Float = 0f,
    val battery: Int = -1,
    val charging: Boolean = false,
    val updatedAt: Long = 0L,
    val sharing: Boolean = true,
    val isMe: Boolean = false,
    val emoji: String = "",
    /** true면 emoji가 직접 고른 게 아니라 자동 판정 결과 */
    val auto: Boolean = false,
)

/** 장소 (집/학교/직장 등) - 도착/출발 알림의 기준 */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val kind: String,
    /** true면 가족 모두의 장소에 자동 추가, false면 만든 사람만 봄 */
    val shared: Boolean = true,
    val owner: String = "",
)

/** 알림 기록. kind = ARRIVE / LEAVE / SOS / INFO */
data class AlertEvent(
    val id: String,
    val text: String,
    val time: Long,
    val kind: String,
)

data class Profile(
    val name: String,
    val color: Long,
    val circleCode: String,
    val emoji: String = "",
)

data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lng: Double,
)

data class Settings(
    val sharing: Boolean,
    val demo: Boolean,
    /** 백그라운드 동작: 0 슈퍼 절전, 1 절전, 2 보통, 3 높은 정확도 */
    val mode: Int = 2,
    val showPlaces: Boolean = true,
    /** 거리 알림(이 폰에서만 판정): 켬/끔, 기준 거리(m), 대상 멤버 id(빈 값이면 첫 가족) */
    val distOn: Boolean = false,
    val distM: Int = 1000,
    val distTarget: String = "",
    /** 도착/출발 알림을 받지 않을 가족 id (이 폰에서만 적용) */
    val muted: Set<String> = emptySet(),
    /** 내 도착/출발 알림을 이 폰에서 받을지 (기본 꺼짐) */
    val selfAlert: Boolean = false,
    /** 배터리 부족 알림: 켜기/끄기, 저장된 장소 안에서도 알릴지, 기준 % */
    val lowBatOn: Boolean = true,
    val lowBatPlaces: Boolean = true,
    val lowBatLevel: Int = 10,
)
