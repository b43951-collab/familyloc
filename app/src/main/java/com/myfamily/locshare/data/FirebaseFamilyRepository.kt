package com.myfamily.locshare.data

import android.content.Context
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.myfamily.locshare.location.LocationService
import com.myfamily.locshare.location.Notifier
import com.myfamily.locshare.util.Geo
import com.myfamily.locshare.location.Ringer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * Firebase(Auth + Realtime Database) 구현.
 *
 * DB 구조
 *   users/{uid}/cid                    내가 속한 서클 id
 *   codes/{CODE}                       서클 코드 → 서클 id (참여할 때 조회)
 *   circles/{cid}/code                 서클 코드 (멤버만 읽기)
 *   circles/{cid}/members/{uid}        {k: 코드, name, color}   ← k가 맞아야 참여 가능
 *   circles/{cid}/loc/{uid}            {lat,lng,acc,spd,bat,chg,t,sharing}
 *   circles/{cid}/places/{pid}         {name,lat,lng,r,kind}
 *   circles/{cid}/events/{eid}         {text,t,kind,uid}
 *   circles/{cid}/cmd/{uid}            {type,t,by}  가족이 보내는 분실 대응 명령 (RING/STOP/SAVER/NORMAL/TRACK)
 *   circles/{cid}/refresh              마지막 "새로고침 요청" 시각 (바뀌면 모든 폰이 위치 갱신)
 *
 * 푸시 서버(Cloud Functions) 없이, 위치 서비스가 켜져 있는 동안 각 폰이 DB 변경을 직접 감지한다.
 */
class FirebaseFamilyRepository(context: Context) : FamilyRepository {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("family_fb", Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance(FirebaseConfig.DB_URL).also {
        try {
            it.setPersistenceEnabled(true)
        } catch (e: Exception) {
            // 이미 사용 중이면 무시
        }
    }
    private val history = HistoryStore(app)

    private class Loc(
        val lat: Double,
        val lng: Double,
        val acc: Float,
        val spd: Float,
        val bat: Int,
        val chg: Boolean,
        val t: Long,
        val sharing: Boolean,
    )

    private val _session = MutableStateFlow(Session.LOADING)
    private val _members = MutableStateFlow<List<Member>>(emptyList())
    private val _places = MutableStateFlow<List<Place>>(emptyList())
    private val _events = MutableStateFlow<List<AlertEvent>>(emptyList())
    private val _profile = MutableStateFlow(
        Profile(
            name = prefs.getString("p_name", "나") ?: "나",
            color = prefs.getLong("p_color", 0xFF1E88E5),
            circleCode = "------",
            emoji = prefs.getString("p_emoji", "") ?: "",
        )
    )
    private val _settings = MutableStateFlow(
        Settings(
            sharing = prefs.getBoolean("sharing", true),
            demo = false,
            mode = prefs.getInt("mode", if (prefs.getBoolean("saver", false)) 1 else 2),
            showPlaces = prefs.getBoolean("show_places", true),
            distOn = prefs.getBoolean("dist_on", false),
            distM = prefs.getInt("dist_m", 1000),
            distTarget = prefs.getString("dist_target", "") ?: "",
            muted = prefs.getStringSet("muted_ids", emptySet())?.toSet() ?: emptySet(),
            selfAlert = prefs.getBoolean("self_alert", false),
        )
    )

    override val session: StateFlow<Session> = _session
    override val supportsDemo: Boolean = false
    override val members: StateFlow<List<Member>> = _members
    override val places: StateFlow<List<Place>> = _places
    override val events: StateFlow<List<AlertEvent>> = _events
    override val profile: StateFlow<Profile> = _profile
    override val settings: StateFlow<Settings> = _settings

    private var uid: String? = null
    private var cid: String? = null
    private val profiles = LinkedHashMap<String, Pair<String, Long>>() // uid -> (이름, 색)
    private val emojis = HashMap<String, String>()
    private val locs = HashMap<String, Loc>()
    private var myLast: Loc? = null
    private var insideMe: Set<String>? = null
    private var allEvents: List<AlertEvent> = emptyList()
    private val seen = HashSet<String>()
    private var eventsInit = false
    private var refreshInit = false
    private var lastRefreshSeen: Long? = null
    private var lastRefreshWrite = 0L
    private val listeners = ArrayList<Pair<Query, ValueEventListener>>()

    // ---------------- 로그인 ----------------

    override fun signIn(email: String, password: String, onResult: (String?) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { t ->
            onResult(if (t.isSuccessful) null else msg(t.exception))
        }
    }

    override fun signUp(email: String, password: String, name: String, onResult: (String?) -> Unit) {
        val n = name.trim().ifEmpty { "나" }
        prefs.edit().putString("p_name", n).apply()
        _profile.value = _profile.value.copy(name = n)
        auth.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { t ->
            onResult(if (t.isSuccessful) null else msg(t.exception))
        }
    }

    override fun signOut() {
        LocationService.stop(app)
        auth.signOut() // → onAuthChanged(null)
    }

    private fun onAuthChanged(user: FirebaseUser?) {
        if (user == null) {
            detach()
            uid = null
            prefs.edit().remove("cid").apply()
            _session.value = Session.LOGGED_OUT
            LocationService.stop(app)
            return
        }
        if (uid == user.uid && cid != null) return // 이미 연결됨
        uid = user.uid
        _session.value = Session.LOADING
        db.getReference("users/${user.uid}/cid").get().addOnCompleteListener { t ->
            val found: String? = if (t.isSuccessful) {
                t.result?.value as? String
            } else {
                prefs.getString("cid", null) // 오프라인이면 저장해 둔 값 사용
            }
            if (found != null) attach(found) else _session.value = Session.NO_CIRCLE
        }
    }

    // ---------------- 서클 ----------------

    private fun memberMap(code: String): HashMap<String, Any> {
        val p = _profile.value
        val m = HashMap<String, Any>()
        m["k"] = code
        m["name"] = p.name
        m["color"] = p.color
        return m
    }

    private fun randomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..10).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override fun createCircle(onResult: (String?) -> Unit) {
        val u = uid ?: return onResult("로그인이 필요해요")
        if (cid != null) return onResult("이미 서클에 들어 있어요")
        val newCid = db.getReference("circles").push().key ?: return onResult("서클을 만들 수 없어요")
        val code = randomCode()
        val up = HashMap<String, Any>()
        up["circles/$newCid/code"] = code
        up["circles/$newCid/members/$u"] = memberMap(code)
        up["codes/$code"] = newCid
        up["users/$u/cid"] = newCid
        db.reference.updateChildren(up).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                attach(newCid)
                onResult(null)
            } else {
                onResult(msg(t.exception))
            }
        }
    }

    override fun joinCircle(code: String, onResult: (String?) -> Unit) {
        val u = uid ?: return onResult("로그인이 필요해요")
        if (cid != null) return onResult("이미 서클에 들어 있어요")
        val cc = code.trim().uppercase()
        if (cc.length != 6 && cc.length != 10) return onResult("코드는 10자리예요")
        db.getReference("codes/$cc").get().addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                onResult(msg(t.exception))
                return@addOnCompleteListener
            }
            val target = t.result?.value as? String
            if (target == null) {
                onResult("코드를 찾을 수 없어요")
                return@addOnCompleteListener
            }
            val up = HashMap<String, Any>()
            up["circles/$target/members/$u"] = memberMap(cc)
            up["users/$u/cid"] = target
            db.reference.updateChildren(up).addOnCompleteListener { t2 ->
                if (t2.isSuccessful) {
                    attach(target)
                    onResult(null)
                } else {
                    onResult(msg(t2.exception))
                }
            }
        }
    }

    override fun leaveCircle() {
        val u = uid ?: return
        val c = cid ?: return
        val up = HashMap<String, Any?>()
        up["circles/$c/loc/$u"] = null
        up["circles/$c/members/$u"] = null
        up["users/$u/cid"] = null
        db.reference.updateChildren(up)
        detach()
        prefs.edit().remove("cid").apply()
        LocationService.stop(app)
        _session.value = Session.NO_CIRCLE
    }

    // ---------------- 실시간 구독 ----------------

    private fun listen(q: Query, onChange: (DataSnapshot) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                // 권한 없음 등은 무시
            }
        }
        q.addValueEventListener(l)
        listeners.add(q to l)
    }

    private fun attach(c: String) {
        detach()
        cid = c
        prefs.edit().putString("cid", c).apply()
        seen.clear()
        eventsInit = false
        refreshInit = false
        lastRefreshSeen = null
        insideMe = null

        // 서클 코드
        listen(db.getReference("circles/$c/code")) { s ->
            val code = s.value as? String
            if (code != null) _profile.value = _profile.value.copy(circleCode = code)
        }

        // 멤버 프로필
        listen(db.getReference("circles/$c/members")) { s ->
            profiles.clear()
            emojis.clear()
            for (ch in s.children) {
                val k = ch.key ?: continue
                emojis[k] = ch.child("emoji").value as? String ?: ""
                val name = ch.child("name").value as? String ?: "?"
                val color = (ch.child("color").value as? Number)?.toLong() ?: 0xFF1E88E5
                profiles[k] = name to color
            }
            publish()
        }

        // 위치
        listen(db.getReference("circles/$c/loc")) { s ->
            locs.clear()
            for (ch in s.children) {
                val k = ch.key ?: continue
                val lat = ch.num("lat")
                val lng = ch.num("lng")
                val sharing = (ch.child("sharing").value as? Boolean ?: true) && lat != null && lng != null
                locs[k] = Loc(
                    lat = lat ?: 0.0,
                    lng = lng ?: 0.0,
                    acc = (ch.num("acc") ?: 0.0).toFloat(),
                    spd = (ch.num("spd") ?: 0.0).toFloat(),
                    bat = (ch.num("bat") ?: -1.0).toInt(),
                    chg = ch.child("chg").value as? Boolean ?: false,
                    t = (ch.num("t") ?: 0.0).toLong(),
                    sharing = sharing,
                )
            }
            publish()
        }

        // 장소
        listen(db.getReference("circles/$c/places")) { s ->
            val list = ArrayList<Place>()
            for (ch in s.children) {
                val id = ch.key ?: continue
                val name = ch.child("name").value as? String ?: continue
                val lat = ch.num("lat") ?: continue
                val lng = ch.num("lng") ?: continue
                val r = (ch.num("r") ?: 150.0).toInt()
                val kind = ch.child("kind").value as? String ?: "기타"
                val shared = ch.child("shared").value as? Boolean ?: true // 예전 장소는 모두 공유
                val owner = ch.child("owner").value as? String ?: ""
                if (shared || owner == uid) list.add(Place(id, name, lat, lng, r, kind, shared, owner))
            }
            _places.value = list
            insideMe = null // 장소가 바뀌면 현재 상태부터 다시 기준을 잡는다
        }

        // 알림 기록 (다른 가족의 새 알림은 이 폰에서도 알림으로 띄움)
        listen(db.getReference("circles/$c/events").orderByKey().limitToLast(50)) { s ->
            val firstLoad = !eventsInit
            val list = ArrayList<AlertEvent>()
            for (ch in s.children) {
                val id = ch.key ?: continue
                val text = ch.child("text").value as? String ?: continue
                val t = (ch.num("t") ?: 0.0).toLong()
                val kind = ch.child("kind").value as? String ?: "INFO"
                val by = ch.child("uid").value as? String
                list.add(AlertEvent(id, text, t, kind))
                val mutedHere = (kind == "ARRIVE" || kind == "LEAVE") && by != null && by in _settings.value.muted
                if (seen.add(id) && !firstLoad && by != uid && !mutedHere) Notifier.alert(app, text)
            }
            eventsInit = true
            list.reverse() // 최신이 위로
            allEvents = list
            applyEventFilter()
        }

        // 새로고침 요청: 값이 바뀌면 이 폰도 위치를 바로 갱신
        listen(db.getReference("circles/$c/refresh")) { s ->
            val t = (s.value as? Number)?.toLong()
            if (!refreshInit) {
                refreshInit = true
                lastRefreshSeen = t
                return@listen
            }
            if (t != null && t != lastRefreshSeen) {
                lastRefreshSeen = t
                LocationService.boost(app)
            }
        }

        // 분실 대응 명령 (내 폰으로 온 것만)
        uid?.let { me ->
            listen(db.getReference("circles/$c/cmd/$me")) { s -> onCommand(s) }
        }

        _session.value = Session.READY
        if (_settings.value.sharing) LocationService.start(app)
    }

    private var lastCmdT = 0L

    private fun onCommand(s: DataSnapshot) {
        val type = s.child("type").value as? String ?: return
        val t = s.num("t")?.toLong() ?: return
        if (t == lastCmdT) return
        lastCmdT = t
        if (kotlin.math.abs(System.currentTimeMillis() - t) > 90_000L) return // 오래된 명령은 무시
        when (type) {
            "RING" -> Ringer.start(app)
            "STOP" -> Ringer.stop()
            "SAVER" -> setMode(0)
            "NORMAL" -> setMode(2)
            "TRACK" -> LocationService.boost(app, 10 * 60_000L)
        }
    }

    override fun sendCommand(targetId: String, type: String) {
        val c = cid ?: return
        val m = HashMap<String, Any>()
        m["type"] = type
        m["t"] = ServerValue.TIMESTAMP
        m["by"] = uid ?: ""
        db.getReference("circles/$c/cmd/$targetId").setValue(m)
    }

    private fun detach() {
        for ((q, l) in listeners) q.removeEventListener(l)
        listeners.clear()
        profiles.clear()
        emojis.clear()
        locs.clear()
        allEvents = emptyList()
        _members.value = emptyList()
        _places.value = emptyList()
        _events.value = emptyList()
        cid = null
        myLast = null
        insideMe = null
    }

    private fun applyEventFilter() {
        val cleared = prefs.getLong("cleared", 0L)
        _events.value = allEvents.filter { it.time > cleared }
    }

    private fun publish() {
        val me = uid
        val ml = myLast
        val keys = profiles.keys.sortedWith(
            compareBy<String>({ it != me }, { profiles[it]?.first ?: "" })
        )
        val out = ArrayList<Member>()
        for (u in keys) {
            val prof = profiles[u] ?: continue
            val isMe = u == me
            val name = if (isMe) _profile.value.name else prof.first
            val color = if (isMe) _profile.value.color else prof.second
            val l: Loc? = if (isMe && _settings.value.sharing && ml != null) ml else locs[u]
            val emo = if (isMe) _profile.value.emoji else (emojis[u] ?: "")
            if (l != null && l.sharing) {
                out.add(Member(u, name, color, l.lat, l.lng, l.acc, l.spd, l.bat, l.chg, l.t, true, isMe, emo))
            } else {
                out.add(Member(u, name, color, 0.0, 0.0, 0f, 0f, -1, false, l?.t ?: 0L, false, isMe, emo))
            }
        }
        _members.value = out
    }

    // ---------------- 내 위치 / 장소 / 알림 ----------------

    override fun onMyLocation(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        speedMps: Float,
        battery: Int,
        charging: Boolean,
        time: Long,
    ) {
        val u = uid ?: return
        val c = cid ?: return
        myLast = Loc(lat, lng, accuracyM, speedMps, battery, charging, time, true)
        val recorded = history.append(lat, lng, time)
        if (speedMps >= 1.0f) lastMoveAt = time
        evaluateMe(lat, lng, accuracyM, time)
        checkLowBattery(battery, charging)
        publish()
        if (!_settings.value.sharing) return

        val m = HashMap<String, Any>()
        m["lat"] = lat
        m["lng"] = lng
        m["acc"] = accuracyM.toDouble()
        m["spd"] = speedMps.toDouble()
        m["bat"] = battery
        m["chg"] = charging
        m["sharing"] = true
        m["t"] = ServerValue.TIMESTAMP
        db.getReference("circles/$c/loc/$u").setValue(m)

        if (recorded) {
            histBuf.add(TrackPoint(time, lat, lng))
            flushHistory(c, u, false)
        }
    }

    // ---------------- 이동 기록 서버 업로드 (최근 7일, 가족이 볼 수 있음) ----------------

    private val histBuf = ArrayList<TrackPoint>()
    private var lastFlush = 0L

    private fun dayKey(t: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(t))

    private fun putPoints(map: HashMap<String, Any>, pts: List<TrackPoint>) {
        for (p in pts) {
            map["${dayKey(p.t)}/${p.t}"] = String.format(java.util.Locale.US, "%.5f,%.5f", p.lat, p.lng)
        }
    }

    private fun flushHistory(c: String, u: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && histBuf.size < 10 && now - lastFlush < 5 * 60_000L) return
        val ref = db.getReference("circles/$c/hist/$u")
        val m = HashMap<String, Any>()

        // 처음 한 번: 이 폰에 쌓인 최근 7일 기록도 올린다
        val bfKey = "hist_bf_$c"
        if (!prefs.getBoolean(bfKey, false)) {
            prefs.edit().putBoolean(bfKey, true).apply()
            val old = history.read(now - 7L * 24 * 3600 * 1000, Long.MAX_VALUE)
            putPoints(m, if (old.size > 3000) old.takeLast(3000) else old)
        }
        if (histBuf.isNotEmpty()) {
            putPoints(m, histBuf)
            histBuf.clear()
        }
        lastFlush = now
        if (m.isNotEmpty()) ref.updateChildren(m)

        // 8일 지난 기록 정리 (하루 한 번)
        if (now - prefs.getLong("hist_pruned", 0L) > 12 * 3600_000L) {
            prefs.edit().putLong("hist_pruned", now).apply()
            for (i in 8..14) ref.child(dayKey(now - i * 24L * 3600 * 1000)).removeValue()
        }
    }

    override fun loadFamilyHistory(memberId: String, fromMs: Long, toMs: Long, onResult: (List<TrackPoint>) -> Unit) {
        val c = cid
        if (c == null) {
            onResult(emptyList())
            return
        }
        val end = minOf(toMs, System.currentTimeMillis() + 1)
        val days = ArrayList<String>()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = fromMs
        while (cal.timeInMillis < end && days.size < 8) {
            days.add(dayKey(cal.timeInMillis))
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        if (days.isEmpty()) {
            onResult(emptyList())
            return
        }
        val out = ArrayList<TrackPoint>()
        var left = days.size
        fun done() {
            left--
            if (left == 0) {
                out.sortBy { it.t }
                onResult(out)
            }
        }
        for (day in days) {
            db.getReference("circles/$c/hist/$memberId/$day")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (ch in snapshot.children) {
                            val t = ch.key?.toLongOrNull() ?: continue
                            if (t < fromMs || t >= toMs) continue
                            val v = (ch.value as? String)?.split(",") ?: continue
                            if (v.size != 2) continue
                            val la = v[0].toDoubleOrNull() ?: continue
                            val ln = v[1].toDoubleOrNull() ?: continue
                            out.add(TrackPoint(t, la, ln))
                        }
                        done()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        done()
                    }
                })
        }
    }

    /**
     * 내가 장소에 들어가거나 나가면 알림 기록을 남긴다. (가족 각자의 폰이 자기 것을 판정)
     * 경계 GPS 흔들림 방지: 정확도 나쁜 위치 무시, 연속 2회 확인, 같은 장소 알림 쿨다운 1분.
     */
    private var lastMoveAt = 0L

    private fun evaluateMe(lat: Double, lng: Double, acc: Float, time: Long) {
        if (acc > 100f) return // 오차가 큰 위치는 도착/출발 판정에서 제외
        val ps = _places.value
        val prev = insideMe
        if (prev == null) {
            // 처음에는 현재 상태만 기준으로 잡는다 (가짜 도착 알림 방지)
            val init = HashSet<String>()
            for (p in ps) if (Geo.distM(lat, lng, p.lat, p.lng) <= p.radiusM) init.add(p.id)
            insideMe = init
            pending.clear()
            return
        }
        val cur = HashSet(prev)
        val who = "${_profile.value.name} 님이"
        val now = System.currentTimeMillis()
        for (p in ps) {
            val was = prev.contains(p.id)
            val d = Geo.distM(lat, lng, p.lat, p.lng)
            val limit = if (was) p.radiusM + maxOf(30f, acc) else p.radiusM.toFloat()
            val raw = d <= limit
            // 최근 5분 안에 움직인 적이 없으면(가만히 있으면) GPS가 튀어도 도착/출발로 보지 않는다
            if (raw == was || time - lastMoveAt > 5 * 60_000L) {
                pending.remove(p.id)
                continue
            }
            val n = (pending[p.id] ?: 0) + 1
            if (n < 2) {
                pending[p.id] = n
                continue
            }
            pending.remove(p.id)
            if (raw) cur.add(p.id) else cur.remove(p.id)
            val last = lastEventAt[p.id] ?: 0L
            if (now - last < 120_000L) continue // 쿨다운 2분: 상태만 바꾸고 알림은 생략
            lastEventAt[p.id] = now
            val arrive = "$who ${p.name}에 도착했어요"
            val leave = "$who ${p.name}에서 출발했어요"
            if (raw) {
                if (p.shared) pushEvent("ARRIVE", arrive) else if (_settings.value.selfAlert) Notifier.alert(app, arrive)
            } else {
                if (p.shared) pushEvent("LEAVE", leave) else if (_settings.value.selfAlert) Notifier.alert(app, leave)
            }
        }
        insideMe = cur
    }

    private val pending = HashMap<String, Int>()
    private val lastEventAt = HashMap<String, Long>()

    /** 배터리가 20% 이하로 떨어지면 가족에게 한 번 알린다. 충전하거나 30%를 넘으면 다시 준비. */
    private fun checkLowBattery(b: Int, charging: Boolean) {
        if (b < 0) return
        if (charging || b >= 30) {
            if (prefs.getBoolean("low_sent", false)) prefs.edit().putBoolean("low_sent", false).apply()
            return
        }
        if (b <= 5 && !prefs.getBoolean("low5_sent", false)) {
            prefs.edit().putBoolean("low5_sent", true).apply()
            pushEvent("INFO", "${_profile.value.name} 님의 배터리가 ${b}%예요. 곧 꺼질 수 있어요 (마지막 위치가 기록됐어요)")
        } else if (b > 8 && prefs.getBoolean("low5_sent", false)) {
            prefs.edit().putBoolean("low5_sent", false).apply()
        }
        if (b <= 20 && !prefs.getBoolean("low_sent", false)) {
            prefs.edit().putBoolean("low_sent", true).apply()
            pushEvent("INFO", "${_profile.value.name} 님의 배터리가 ${b}% 남았어요")
        }
    }

    private fun pushEvent(kind: String, text: String) {
        val c = cid ?: return
        val m = HashMap<String, Any>()
        m["text"] = text
        m["t"] = ServerValue.TIMESTAMP
        m["kind"] = kind
        m["uid"] = uid ?: ""
        db.getReference("circles/$c/events").push().setValue(m)
        // 내 폰에도 알림 (다른 가족은 DB 변경을 감지해서 알림). 내 도착/출발은 설정에서 켠 경우만
        if ((kind != "ARRIVE" && kind != "LEAVE") || _settings.value.selfAlert) Notifier.alert(app, text)
    }

    override fun updatePlace(id: String, name: String, lat: Double, lng: Double, radiusM: Int, kind: String, shared: Boolean) {
        val c = cid ?: return
        val old = _places.value.firstOrNull { it.id == id }
        val m = HashMap<String, Any>()
        m["name"] = name.trim().ifEmpty { kind }
        m["lat"] = lat
        m["lng"] = lng
        m["r"] = radiusM
        m["kind"] = kind
        m["shared"] = shared
        m["owner"] = old?.owner?.takeIf { it.isNotEmpty() } ?: (uid ?: "")
        db.getReference("circles/$c/places/$id").updateChildren(m)
    }

    override fun setPlaceShared(id: String, shared: Boolean) {
        val c = cid ?: return
        db.getReference("circles/$c/places/$id/shared").setValue(shared)
    }

    override fun addPlace(name: String, lat: Double, lng: Double, radiusM: Int, kind: String, shared: Boolean) {
        val c = cid ?: return
        val m = HashMap<String, Any>()
        m["shared"] = shared
        m["owner"] = uid ?: ""
        m["name"] = name.trim().ifEmpty { kind }
        m["lat"] = lat
        m["lng"] = lng
        m["r"] = radiusM
        m["kind"] = kind
        db.getReference("circles/$c/places").push().setValue(m)
    }

    override fun removePlace(id: String) {
        val c = cid ?: return
        db.getReference("circles/$c/places/$id").removeValue()
    }

    override fun updateProfile(name: String, color: Long) {
        val n = name.trim().ifEmpty { "나" }
        _profile.value = _profile.value.copy(name = n, color = color)
        prefs.edit().putString("p_name", n).putLong("p_color", color).apply()
        val u = uid
        val c = cid
        if (u != null && c != null) {
            val m = HashMap<String, Any>()
            m["name"] = n
            m["color"] = color
            db.getReference("circles/$c/members/$u").updateChildren(m)
        }
        publish()
    }

    override fun setSharing(on: Boolean) {
        prefs.edit().putBoolean("sharing", on).apply()
        _settings.value = _settings.value.copy(sharing = on)
        val u = uid
        val c = cid
        if (on) {
            LocationService.start(app)
        } else {
            LocationService.stop(app)
            myLast = null
            if (u != null && c != null) {
                // 좌표를 지우고 "공유 꺼짐"만 남긴다
                val m = HashMap<String, Any>()
                m["sharing"] = false
                m["t"] = ServerValue.TIMESTAMP
                db.getReference("circles/$c/loc/$u").setValue(m)
            }
        }
        publish()
    }

    override fun setDemo(on: Boolean) {
        // 서버 모드에는 데모 가족이 없다
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
        _profile.value = _profile.value.copy(emoji = emoji)
        val u = uid
        val c = cid
        if (u != null && c != null) {
            db.getReference("circles/$c/members/$u/emoji").setValue(emoji)
        }
        publish()
    }

    override fun sendSos() {
        pushEvent("SOS", "${_profile.value.name} 님이 SOS를 보냈어요! 위치를 확인하세요")
        forceRefresh()
    }

    override fun requestRefresh() {
        LocationService.boost(app)
        val now = System.currentTimeMillis()
        if (now - lastRefreshWrite < 20_000L) return // 연타 방지
        lastRefreshWrite = now
        forceRefresh()
    }

    private fun forceRefresh() {
        val c = cid ?: return
        db.getReference("circles/$c/refresh").setValue(ServerValue.TIMESTAMP)
    }

    override fun history(fromMs: Long, toMs: Long): List<TrackPoint> = history.read(fromMs, toMs)

    override fun clearEvents() {
        prefs.edit().putLong("cleared", System.currentTimeMillis()).apply()
        applyEventFilter()
    }

    // ---------------- 도우미 ----------------

    private fun msg(e: Exception?): String = when (e) {
        is FirebaseAuthInvalidCredentialsException -> "이메일 또는 비밀번호가 맞지 않아요"
        is FirebaseAuthInvalidUserException -> "가입되지 않은 이메일이에요"
        is FirebaseAuthUserCollisionException -> "이미 가입된 이메일이에요"
        is FirebaseAuthWeakPasswordException -> "비밀번호는 6자 이상이어야 해요"
        is FirebaseNetworkException -> "인터넷 연결을 확인해 주세요"
        else -> {
            val m = e?.message ?: ""
            when {
                m.contains("Permission denied", true) -> "권한이 없어요 (Firebase 보안 규칙을 확인해 주세요)"
                m.isEmpty() -> "오류가 발생했어요"
                else -> m
            }
        }
    }

    // 모든 프로퍼티가 초기화된 뒤에 실행되도록 클래스 맨 아래에 둔다
    init {
        history.trim()
        auth.addAuthStateListener { fa -> onAuthChanged(fa.currentUser) }
    }
}

private fun DataSnapshot.num(key: String): Double? = (child(key).value as? Number)?.toDouble()
