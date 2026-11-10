package com.example.luckydraw

import android.content.Context
import android.location.Location
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class DriveRoute(
    val name: String,
    val lat: Double,
    val lng: Double,
    val summary: String,
    val signalStars: Int,
)

data class RecommendedRoute(
    val route: DriveRoute,
    val distanceKm: Double,
    val estimatedMinutes: Int,
) {
    fun metaText(): String = String.format(
        Locale.KOREA,
        "🚗 약 %d분 소요 · %.1fkm",
        estimatedMinutes,
        distanceKm,
    )

    fun signalBadgeText(): String {
        val filled = "★".repeat(route.signalStars.coerceIn(1, 5))
        val empty = "☆".repeat((5 - route.signalStars).coerceIn(0, 4))
        return "신호 여유 $filled$empty"
    }
}

data class RecommendationResult(
    val items: List<RecommendedRoute>,
    val usingDefaultLocation: Boolean,
    val radiusLabel: String,
)

class RouteRecommender(context: Context) {
    private val routes: List<DriveRoute>

    val size: Int
        get() = routes.size

    init {
        val json = context.assets.open("drive_routes.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val parsed = ArrayList<DriveRoute>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val lat = item.optDouble("lat")
            val lng = item.optDouble("lng")
            if (!lat.isFinite() || !lng.isFinite()) continue
            parsed.add(
                DriveRoute(
                    name = item.getString("name"),
                    lat = lat,
                    lng = lng,
                    summary = item.optString("summary", ""),
                    signalStars = item.optInt("signalStars", 4).coerceIn(1, 5),
                ),
            )
        }
        routes = parsed
    }

    fun recommend(
        lat: Double?,
        lng: Double?,
        page: Int = 0,
        limit: Int = 3,
    ): RecommendationResult {
        if (routes.isEmpty()) {
            return RecommendationResult(emptyList(), usingDefaultLocation = true, radiusLabel = "코스 준비 중")
        }
        val validKorea = (lat != null) && (lng != null) &&
            (lat in 33.0..39.5) && (lng in 124.0..132.0)
        val anchorLat = if (validKorea) lat else DEFAULT_LAT
        val anchorLng = if (validKorea) lng else DEFAULT_LNG

        data class ScoredCandidate(
            val item: RecommendedRoute,
            val straightKm: Double,
        )

        val out = FloatArray(1)
        val allCandidates = routes.map { route ->
            Location.distanceBetween(anchorLat, anchorLng, route.lat, route.lng, out)
            val straightKm = out[0] / 1_000.0
            // 실제 도로 굴곡 계수(1.25)와 교외 평균 주행 속도(45km/h) 반영
            val roadKm = (straightKm * 1.25).coerceAtLeast(1.0)
            val minutes = (roadKm / 45.0 * 60.0).roundToInt().coerceAtLeast(12)
            ScoredCandidate(
                item = RecommendedRoute(route, roadKm, minutes),
                straightKm = straightKm,
            )
        }

        val sweetSpot = allCandidates
            .filter { it.straightKm in 15.0..40.0 }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.item.route.signalStars }
                    .thenBy { abs(it.straightKm - 26.0) },
            )

        val mediumSpot = allCandidates
            .filter { it.straightKm in 5.0..80.0 }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.item.route.signalStars }
                    .thenBy { abs(it.straightKm - 26.0) },
            )

        val nearestSpot = allCandidates.sortedBy { it.straightKm }

        val (sourcePool, radiusLabel) = when {
            sweetSpot.size >= limit -> sweetSpot to "반경 15~40km 힐링 코스"
            mediumSpot.size >= limit -> mediumSpot to "주변 근교 힐링 코스"
            else -> nearestSpot to "가까운 힐링 코스"
        }

        // 인접한 동일 관광단지(3km 이내)가 한 화면에 중복 노출되지 않도록 우선 분산 배치
        val distinctPool = ArrayList<RecommendedRoute>(sourcePool.size)
        val deferredPool = ArrayList<RecommendedRoute>()
        for (candidate in sourcePool) {
            val tooClose = distinctPool.any { chosen ->
                Location.distanceBetween(
                    chosen.route.lat,
                    chosen.route.lng,
                    candidate.item.route.lat,
                    candidate.item.route.lng,
                    out,
                )
                out[0] < 3_000f
            }
            if (!tooClose) {
                distinctPool.add(candidate.item)
            } else {
                deferredPool.add(candidate.item)
            }
        }
        val orderedPool = distinctPool + deferredPool
        val safePage = page.coerceAtLeast(0)
        val start = (safePage * limit) % orderedPool.size
        val selected = ArrayList<RecommendedRoute>(limit)
        for (offset in 0 until minOf(limit, orderedPool.size)) {
            selected.add(orderedPool[(start + offset) % orderedPool.size])
        }

        return RecommendationResult(
            items = selected,
            usingDefaultLocation = !validKorea,
            radiusLabel = radiusLabel,
        )
    }

    companion object {
        // 에뮬레이터 등 해외/미수신 GPS 환경일 때 수도권 기본 출발점(서울 시청) 기준 추천
        private const val DEFAULT_LAT = 37.5665
        private const val DEFAULT_LNG = 126.9780
    }
}
