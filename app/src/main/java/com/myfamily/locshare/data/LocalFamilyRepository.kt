package com.myfamily.locshare.data

import android.content.Context
import com.myfamily.locshare.location.LocationService
import com.myfamily.locshare.location.Notifier
import com.myfamily.locshare.util.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 서버 없이 이 폰 안에서만 동작하는 구현.
 *  - 내 위치/장소/알림/이동기록은 폰에 저장
 *  - "데모 가족"이 내 주변을 돌아다니며 가족 기능(지도, 도착 알림 등)을 미리 체험하게 해 줌
 */
class LocalFamilyRepository(context: Context) : FamilyRepository {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("family_local", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val histLock = Any()
    private val historyFile = File(app.filesDir, "history.csv")

    private val _profile = MutableStateFlow(loadProfile())
    private val _settings = MutableStateFlow(
        Settings(
            sharing = prefs.getBoolean("sharing", true),
            demo = prefs.getBoolean("demo", true),
            mode = prefs.getInt("mode", if (prefs.getBoolean("saver", false)) 1 else 2),
            showPlaces = prefs.getBoolean("show_places", true),
            distOn = prefs.getBoolean("dist_on", false),
            distM = prefs.getInt("dist_m", 1000),
            distTarget = prefs.getString("dist_target", "") ?: "",
            muted = prefs.getStringSet("muted_ids", emptySet())?.toSet() ?: emptySet(),
            selfAlert = prefs.getBoolean("self_alert", false),
        )
    )
    private val _places = MutableStateFlow(loadPlaces())
    private val _events = MutableStateFlow(loadEvents())
    private val _members = MutableStateFlow<List<Member>>(emptyList())

    override val members: StateFlow<List<Member>> = _members
    override val places: StateFlow<List<Place>> = _places
    override val events: StateFlow<List<AlertEvent>> = _events
    override val profile: StateFlow<Profile> = _profile
    override val settings: StateFlow<Settings> = _settings

    private var me: Member? = loadMe()
    private val inside = HashMap<String, Set<String>>() // memberId -> 현재 들어가 있는 placeId 들
    private var lastHist: TrackPoint? = null

    // ---------------- 데모 가족 ----------------

    private class Demo(
        val id: String,
        val name: String,
        val color: Long,
        var lat: Double,
        var lng: Double,
        val fast: Boolean,
    ) {
        var heading: Double = Random.nextDouble() * 360.0
        var moving: Boolean = Random.nextBoolean()
        var battery: Int = Random.nextInt(35, 95)
        var charging: Boolean = false
        var speed: Float = 0f
        var updatedAt: Long = System.currentTimeMillis()

        fun toMember() = Member(
            id = id, name = name, color = color, lat = lat, lng = lng,
            accuracyM = 10f, speedMps = if (moving) speed else 0f,
            battery = battery, charging = charging, updatedAt = updatedAt,
            sharing = true, isMe = false,
        )
    }

    private val demos = ArrayList<Demo>()
    private var demoFallback = false
    private var demoJob: Job? = null

    private fun placeDemosAround(lat: Double, lng: Double) {
        val offsets = listOf(0.004 to 0.003, -0.006 to 0.002, 0.002 to -0.005)
        if (demos.isEmpty()) {
            demos.add(Demo("demo1", "엄마", 0xFFE91E63, lat, lng, false))
            demos.add(Demo("demo2", "아빠", 0xFF43A047, lat, lng, true))
            demos.add(Demo("demo3", "동생", 0xFFFF9800, lat, lng, false))
        }
        demos.forEachIndexed { i, d ->
            d.lat = lat + offsets[i].first
            d.lng = lng + offsets[i].second
        }
        inside.keys.removeAll { it.startsWith("demo") }
    }

    private fun stepDemos() {
        if (demos.isEmpty()) {
            val m = me
            if (m != null) {
                placeDemosAround(m.lat, m.lng)
                demoFallback = false
            } else {
                placeDemosAround(37.5665, 126.9780) // 내 위치를 아직 모를 때: 서울시청 근처
                demoFallback = true
            }
        }
        for (d in demos) {
            if (Random.nextDouble() < 0.05) d.moving = !d.moving
            if (d.moving) {
                d.speed = if (d.fast) 8f else 1.4f
                d.heading += Random.nextDouble(-25.0, 25.0)
                val dist = d.speed * 3.0 // 3초마다 갱신
                val rad = Math.toRadians(d.heading)
                d.lat += dist * cos(rad) / 111320.0
                d.lng += dist * sin(rad) / (111320.0 * cos(Math.toRadians(d.lat)))
            } else {
                d.speed = 0f
            }
            if (Random.nextDouble() < 0.03 && d.battery > 5) d.battery -= 1
            d.updatedAt = System.currentTimeMillis()
            evaluate(d.id, d.name, false, d.lat, d.lng)
        }
        publish()
    }

    private fun startDemo() {
        demoJob?.cancel()
        demoJob = scope.launch {
            while (isActive) {
                synchronized(lock) { stepDemos() }
                delay(3000)
            }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
    }

    // ---------------- 공개 API ----------------

    override fun onMyLocation(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        speedMps: Float,
        battery: Int,
        charging: Boolean,
        time: Long,
    ) {
        synchronized(lock) {
            val p = _profile.value
            me = Member("me", p.name, p.color, lat, lng, accuracyM, speedMps, battery, charging, time,
                _settings.value.sharing, true)
            prefs.edit()
                .putString("me_lat", lat.toString())
                .putString("me_lng", lng.toString())
                .putLong("me_t", time)
                .putInt("me_bat", battery)
                .apply()
            if (demoFallback) {
                placeDemosAround(lat, lng)
                demoFallback = false
            }
            evaluate("me", p.name, true, lat, lng)
            publish()
        }
        appendHistory(lat, lng, time)
    }

    override fun setPlaceShared(id: String, shared: Boolean) {}

    override fun updatePlace(id: String, name: String, lat: Double, lng: Double, radiusM: Int, kind: String, shared: Boolean) {}

    override fun addPlace(name: String, lat: Double, lng: Double, radiusM: Int, kind: String, shared: Boolean) {
        synchronized(lock) {
            val list = _places.value + Place(UUID.randomUUID().toString(), name.trim().ifEmpty { kind }, lat, lng, radiusM, kind)
            _places.value = list
            savePlaces(list)
            inside.clear() // 새 장소 때문에 가짜 도착 알림이 뜨지 않도록 상태 초기화
        }
    }

    override fun removePlace(id: String) {
        synchronized(lock) {
            val list = _places.value.filter { it.id != id }
            _places.value = list
            savePlaces(list)
            inside.clear()
        }
    }

    override fun updateProfile(name: String, color: Long) {
        synchronized(lock) {
            val cur = _profile.value
            val p = cur.copy(name = name.trim().ifEmpty { "나" }, color = color)
            _profile.value = p
            prefs.edit().putString("p_name", p.name).putLong("p_color", p.color).apply()
            publish()
        }
    }

    override fun setSharing(on: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean("sharing", on).apply()
            _settings.value = _settings.value.copy(sharing = on)
            publish()
        }
        if (on) LocationService.start(app) else LocationService.stop(app)
    }

    override fun setDemo(on: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean("demo", on).apply()
            _settings.value = _settings.value.copy(demo = on)
            if (on) startDemo() else stopDemo()
            publish()
        }
    }

    override fun setMode(mode: Int) {
        prefs.edit().putInt("mode", mode).apply()
        _settings.value = _settings.value.copy(mode = mode)
    }

    override fun setSelfAlert(on: Boolean) {
        prefs.edit().putBoolean("self_alert", on).apply()
        _settings.value = _settings.value.copy(selfAlert = on)
    }

    override fun setMemberAlert(memberId: String, on: Boolean) {
        val next = if (on) _settings.value.muted - memberId else _settings.value.muted + memberId
        prefs.edit().putStringSet("muted_ids", HashSet(next)).apply()
        _settings.value = _settings.value.copy(muted = next)
    }

    override fun setShowPlaces(on: Boolean) {
        prefs.edit().putBoolean("show_places", on).apply()
        _settings.value = _settings.value.copy(showPlaces = on)
    }

    override fun setDistanceAlert(on: Boolean, meters: Int, targetId: String) {
        prefs.edit().putBoolean("dist_on", on).putInt("dist_m", meters).putString("dist_target", targetId).apply()
        _settings.value = _settings.value.copy(distOn = on, distM = meters, distTarget = targetId)
    }

    override fun setEmoji(emoji: String) {
        prefs.edit().putString("p_emoji", emoji).apply()
        synchronized(lock) {
            _profile.value = _profile.value.copy(emoji = emoji)
            publish()
        }
    }

    override fun sendCommand(targetId: String, type: String) {}

    override fun sendSos() {
        synchronized(lock) {
            pushEvent("SOS", "SOS를 보냈어요 (서버 연동 전이라 이 폰에만 기록돼요)")
        }
    }

    override fun requestRefresh() {
        LocationService.boost(app)
        if (_settings.value.demo) {
            scope.launch { synchronized(lock) { stepDemos() } }
        }
    }

    override fun loadFamilyHistory(memberId: String, fromMs: Long, toMs: Long, onResult: (List<TrackPoint>) -> Unit) {
        onResult(emptyList())
    }

    override fun history(fromMs: Long, toMs: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        synchronized(histLock) {
            if (!historyFile.exists()) return out
            try {
                historyFile.forEachLine { line ->
                    val s = line.split(",")
                    if (s.size == 3) {
                        val t = s[0].toLongOrNull()
                        val la = s[1].toDoubleOrNull()
                        val ln = s[2].toDoubleOrNull()
                        if (t != null && la != null && ln != null && t >= fromMs && t < toMs) {
                            out.add(TrackPoint(t, la, ln))
                        }
                    }
                }
            } catch (e: Exception) {
                // 파일이 깨졌으면 빈 목록
            }
        }
        out.sortBy { it.t }
        return out
    }

    override fun clearEvents() {
        synchronized(lock) {
            _events.value = emptyList()
            saveEvents(emptyList())
        }
    }

    // ---------------- 로그인/서클 (로컬 구현은 항상 준비 완료 상태) ----------------

    override val session: StateFlow<Session> = MutableStateFlow(Session.READY)
    override val supportsDemo: Boolean = true
    override fun signIn(email: String, password: String, onResult: (String?) -> Unit) = onResult(null)
    override fun signUp(email: String, password: String, name: String, onResult: (String?) -> Unit) = onResult(null)
    override fun createCircle(onResult: (String?) -> Unit) = onResult(null)
    override fun joinCircle(code: String, onResult: (String?) -> Unit) = onResult("로컬 모드에서는 지원하지 않아요")
    override fun leaveCircle() {}
    override fun signOut() {}

    // ---------------- 내부 로직 ----------------

    private fun publish() {
        val list = ArrayList<Member>()
        val m = me
        if (m != null) {
            val p = _profile.value
            list.add(m.copy(name = p.name, color = p.color, sharing = _settings.value.sharing))
        }
        if (_settings.value.demo) {
            for (d in demos) list.add(d.toMember())
        }
        _members.value = list
    }

    /** 장소 도착/출발 판정. 처음 판정할 때는 알림 없이 현재 상태만 기록한다. */
    private fun evaluate(memberId: String, name: String, isMe: Boolean, lat: Double, lng: Double) {
        val ps = _places.value
        val prev = inside[memberId]
        val cur = HashSet<String>()
        for (p in ps) {
            val was = prev?.contains(p.id) == true
            // 경계에서 GPS가 흔들려 알림이 연타되는 것을 막기 위해, 이미 안에 있던 경우 30m 여유를 줌
            val limit = if (was) p.radiusM + 30 else p.radiusM
            if (Geo.distM(lat, lng, p.lat, p.lng) <= limit) cur.add(p.id)
        }
        if (prev != null) {
            val who = if (isMe) "내가" else "$name 님이"
            for (p in ps) {
                val was = prev.contains(p.id)
                val now = cur.contains(p.id)
                if (!was && now) pushEvent("ARRIVE", "$who ${p.name}에 도착했어요")
                if (was && !now) pushEvent("LEAVE", "$who ${p.name}에서 출발했어요")
            }
        }
        inside[memberId] = cur
    }

    private fun pushEvent(kind: String, text: String) {
        val e = AlertEvent(UUID.randomUUID().toString(), text, System.currentTimeMillis(), kind)
        val list = (listOf(e) + _events.value).take(100)
        _events.value = list
        saveEvents(list)
        Notifier.alert(app, text)
    }

    private fun appendHistory(lat: Double, lng: Double, t: Long) {
        val last = lastHist
        if (last != null && Geo.distM(last.lat, last.lng, lat, lng) < 15.0 && t - last.t < 10 * 60_000L) return
        lastHist = TrackPoint(t, lat, lng)
        scope.launch(Dispatchers.IO) {
            synchronized(histLock) {
                try {
                    historyFile.appendText("$t,$lat,$lng\n")
                } catch (e: Exception) {
                    // 저장 실패는 무시
                }
            }
        }
    }

    private fun trimHistory() {
        scope.launch(Dispatchers.IO) {
            synchronized(histLock) {
                try {
                    if (!historyFile.exists()) return@synchronized
                    val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                    val kept = historyFile.readLines().filter {
                        val t = it.substringBefore(",").toLongOrNull()
                        t != null && t >= limit
                    }
                    historyFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                } catch (e: Exception) {
                    // 무시
                }
            }
        }
    }

    // ---------------- 저장/불러오기 ----------------

    private fun loadProfile(): Profile {
        var code = prefs.getString("circle_code", null)
        if (code == null) {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            code = (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("circle_code", code).apply()
        }
        return Profile(
            name = prefs.getString("p_name", "나") ?: "나",
            color = prefs.getLong("p_color", 0xFF1E88E5),
            circleCode = code,
        )
    }

    private fun loadMe(): Member? {
        val la = prefs.getString("me_lat", null)?.toDoubleOrNull() ?: return null
        val ln = prefs.getString("me_lng", null)?.toDoubleOrNull() ?: return null
        val p = _profile.value
        return Member(
            id = "me", name = p.name, color = p.color, lat = la, lng = ln,
            battery = prefs.getInt("me_bat", -1), updatedAt = prefs.getLong("me_t", 0L),
            sharing = prefs.getBoolean("sharing", true), isMe = true,
        )
    }

    private fun loadPlaces(): List<Place> = try {
        val arr = JSONArray(prefs.getString("places", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"),
                o.getInt("r"), o.optString("kind", "기타"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun savePlaces(list: List<Place>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat)
                .put("lng", p.lng).put("r", p.radiusM).put("kind", p.kind))
        }
        prefs.edit().putString("places", arr.toString()).apply()
    }

    private fun loadEvents(): List<AlertEvent> = try {
        val arr = JSONArray(prefs.getString("events", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AlertEvent(o.getString("id"), o.getString("text"), o.getLong("t"), o.optString("kind", "INFO"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveEvents(list: List<AlertEvent>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("id", e.id).put("text", e.text).put("t", e.time).put("kind", e.kind))
        }
        prefs.edit().putString("events", arr.toString()).apply()
    }

    // 모든 프로퍼티가 초기화된 뒤에 실행되도록 클래스 맨 아래에 둔다
    init {
        synchronized(lock) { publish() }
        if (_settings.value.demo) startDemo()
        trimHistory()
    }
}
