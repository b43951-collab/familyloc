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
)

/** 장소 (집/학교/직장 등) - 도착/출발 알림의 기준 */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val kind: String,
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
)

data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lng: Double,
)

data class Settings(
    val sharing: Boolean,
    val demo: Boolean,
    val saver: Boolean,
)
