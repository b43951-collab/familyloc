package com.myfamily.locshare.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** 검색 결과 한 건. isPlace=true면 상호/장소 명칭, false면 주소 */
class SearchHit(val name: String, val addr: String, val lat: Double, val lng: Double, val isPlace: Boolean)

object PlaceSearch {
    /** 지번·도로명 주소와 장소 이름을 함께 검색. 카카오 키가 있으면 카카오, 없으면 폰의 주소 검색. */
    suspend fun search(ctx: Context, query: String, nearLat: Double, nearLng: Double): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext Result.success(emptyList())
            try {
                if (KakaoConfig.REST_API_KEY.isNotBlank()) Result.success(kakao(q, nearLat, nearLng))
                else Result.success(device(ctx, q))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("Authorization", "KakaoAK ${KakaoConfig.REST_API_KEY}")
        try {
            if (c.responseCode != 200) {
                val err = c.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("검색 서버 오류 ${c.responseCode} $err".trim())
            }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun kakao(q: String, lat: Double, lng: Double): List<SearchHit> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()
        fun add(h: SearchHit) { if (seen.add("${h.lat},${h.lng}")) out.add(h) }

        // 숫자가 섞이면 지번·도로명 주소일 가능성이 커서 주소 검색을 먼저
        val addrFirst = q.any { it.isDigit() }
        fun addr() {
            val docs = get("https://dapi.kakao.com/v2/local/search/address.json?size=5&query=$enc").optJSONArray("documents")
            if (docs != null) for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val road = d.optJSONObject("road_address")?.optString("address_name").orEmpty()
                val jibun = d.optJSONObject("address")?.optString("address_name").orEmpty()
                val name = d.optString("address_name")
                val sub = listOf(road, jibun).filter { it.isNotEmpty() && it != name }.joinToString(" / ")
                add(SearchHit(name, sub, d.getString("y").toDouble(), d.getString("x").toDouble(), false))
            }
        }
        fun keyword() {
            val docs = get(
                "https://dapi.kakao.com/v2/local/search/keyword.json?size=8&x=$lng&y=$lat&query=$enc"
            ).optJSONArray("documents")
            if (docs != null) for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val road = d.optString("road_address_name")
                val a = if (road.isNotEmpty()) road else d.optString("address_name")
                add(SearchHit(d.optString("place_name"), a, d.getString("y").toDouble(), d.getString("x").toDouble(), true))
            }
        }
        if (addrFirst) { addr(); keyword() } else { keyword(); addr() }
        return out.take(8)
    }

    @Suppress("DEPRECATION")
    private fun device(ctx: Context, q: String): List<SearchHit> {
        if (!Geocoder.isPresent()) throw RuntimeException("이 폰은 주소 검색을 지원하지 않아요")
        val list = Geocoder(ctx, Locale.KOREA).getFromLocationName(q, 6) ?: emptyList()
        return list.map {
            val line = it.getAddressLine(0) ?: ""
            SearchHit(line, "", it.latitude, it.longitude, false)
        }
    }
}
