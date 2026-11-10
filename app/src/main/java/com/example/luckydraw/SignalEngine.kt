package com.example.luckydraw

import android.content.Context
import android.location.Location
import android.os.SystemClock
import org.json.JSONArray
import kotlin.math.floor

data class TrafficLight(val id: String, val lat: Double, val lng: Double)

/** An in-memory grid avoids scanning the nationwide data set every second. */
class SignalIndex(context: Context) {
    private val cells = HashMap<Long, MutableList<TrafficLight>>()
    val size: Int
    val sample: TrafficLight?

    init {
        val json = context.assets.open("traffic_lights.json").bufferedReader().use { it.readText() }
        val items = JSONArray(json)
        var first: TrafficLight? = null
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val lat = item.optDouble("lat")
            val lng = item.optDouble("lng")
            if (!lat.isFinite() || !lng.isFinite()) continue
            val light = TrafficLight(item.getString("id"), lat, lng)
            if (first == null) first = light
            cells.getOrPut(key(cell(light.lat), cell(light.lng))) { ArrayList() }.add(light)
        }
        size = items.length()
        sample = first
    }

    fun nearest(lat: Double, lng: Double, radiusMeters: Float = 30f): TrafficLight? {
        if (!lat.isFinite() || !lng.isFinite()) return null
        val row = cell(lat)
        val col = cell(lng)
        var result: TrafficLight? = null
        var best = radiusMeters
        val distances = FloatArray(1)
        for (r in (row - 1)..(row + 1)) {
            for (c in (col - 1)..(col + 1)) {
                for (light in cells[key(r, c)].orEmpty()) {
                    Location.distanceBetween(lat, lng, light.lat, light.lng, distances)
                    if (distances[0] < best) {
                        best = distances[0]
                        result = light
                    }
                }
            }
        }
        return result
    }

    private fun cell(value: Double) = floor(value * 1000).toInt()
    private fun key(row: Int, col: Int) = (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)
}

/** All scoring calls run on the activity main thread; elapsedRealtime is monotonic. */
class SignalEngine {
    var score = 100
        private set
    var longStops = 0
        private set
    var hasDriven = false
        private set

    private val scored = HashSet<String>()
    private var activeId: String? = null
    private var brakeSince: Long? = null
    private var penalizedInCurrentHold = false

    fun reset() {
        score = 100
        longStops = 0
        hasDriven = false
        scored.clear()
        activeId = null
        brakeSince = null
        penalizedInCurrentHold = false
    }

    fun startDrive() {
        reset()
        hasDriven = true
    }

    fun park() {
        activeId = null
        brakeSince = null
        penalizedInCurrentHold = false
    }

    fun scoredIds(): ArrayList<String> = ArrayList(scored)

    fun brakeHoldSeconds(now: Long = SystemClock.elapsedRealtime()): Int {
        val start = brakeSince ?: return 0
        return ((now - start).coerceAtLeast(0L) / 1_000L).toInt()
    }

    fun isTrackingBrake(): Boolean = brakeSince != null

    fun restore(savedScore: Int, savedLongStops: Int, savedHasDriven: Boolean, savedScored: Collection<String>) {
        score = savedScore
        longStops = savedLongStops
        hasDriven = savedHasDriven
        scored.clear()
        scored.addAll(savedScored)
        activeId = null
        brakeSince = null
        penalizedInCurrentHold = false
    }

    fun tick(light: TrafficLight?, brakePressed: Boolean, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!brakePressed) {
            penalizedInCurrentHold = false
        }
        if (light?.id != activeId) {
            activeId = light?.id
            brakeSince = null
        }
        if ((light == null) || !brakePressed || penalizedInCurrentHold || (light.id in scored)) {
            brakeSince = null
            return false
        }
        val start = brakeSince
        if (start == null) {
            brakeSince = now
            return false
        }
        if ((now - start) >= 5_000L) {
            score -= 10
            longStops++
            scored.add(light.id)
            brakeSince = null
            penalizedInCurrentHold = true
            return true
        }
        return false
    }

    private var lastMessage: String? = null

    fun grade(): String = when {
        score >= 90 -> "대길 · 大吉"
        score >= 60 -> "길 · 吉"
        score >= 40 -> "소길 · 小吉"
        else -> "흉 · 凶"
    }

    fun message(): String {
        val pool = when {
            score >= 90 -> DAEGIL_MESSAGES
            score >= 60 -> GIL_MESSAGES
            score >= 40 -> SOGIL_MESSAGES
            else -> HYUNG_MESSAGES
        }
        val candidates = if (pool.size > 1) pool.filterNot { it == lastMessage } else pool
        val chosen = candidates.random()
        lastMessage = chosen
        return lockLineBreaksToPunctuation(chosen)
    }

    companion object {
        private val BREAK_PUNCTUATION = charArrayOf(',', '!', '.')

        private val DAEGIL_MESSAGES = listOf(
            "오늘의 도로는 당신의 무대!",
            "초럭키 요정 강림!",
            "무지개빛 행운이 당신을 따라다닐 거예요!",
            "오늘은 당신을 위한 꽃길이 펼쳐졌네요!",
            "신호등도, 도로도 모두 당신의 편!",
            "오늘 하루는 뭐든 잘 풀릴 것 같은 예감!",
            "이 정도면 운전의 신이 다녀간 게 아닐까요?\n오늘의 행운 지수 100%!",
            "도로 위의 행운 수집가 등장!\n오늘은 모든 순간이 반짝반짝!",
            "이보다 더 좋을 순 없다!\n오늘의 드라이브는 럭키 그 자체!",
        )

        private val GIL_MESSAGES = listOf(
            "순조로운 출발, 기분 좋은 하루!",
            "신호등도 당신의 편인 것 같네요!",
            "신호등 요정이 살짝 윙크했어요!\n오늘은 왠지 좋은 예감이 드네요!",
            "콧노래가 절로 나오는 드라이브!\n오늘 하루도 산뜻하게 시작해요!",
            "오늘의 도로 컨디션은 꽤 괜찮은걸요?\n기분 좋은 하루 보내세요!",
            "바람도 기분도 산뜻하게!\n오늘은 작은 행복을 발견하기 좋은 날이에요!",
            "출발부터 도착까지 기분 좋은 흐름!\n오늘의 운세는 맑음이에요!",
            "좋은 에너지가 가득하네요!",
        )

        private val SOGIL_MESSAGES = listOf(
            "오늘도 무사히 도착한 당신이 최고예요!",
            "조금 천천히 가더라도 괜찮아요!\n안전하게 도착한 당신이 오늘의 주인공!",
            "도로가 조금 붐볐나 봐요!\n그래도 여유롭게 달리는 게 최고랍니다!",
            "거북이처럼 천천히, 하지만 확실하게!\n오늘도 안전운전 성공!",
            "잠깐의 정체도 여행의 일부!\n조급해하지 말고 여유를 가져봐요!",
            "오늘은 여유가 행운을 만드는 날!\n천천히 가도 충분히 잘하고 있어요!",
            "도로가 살짝 심술을 부렸지만 괜찮아요!\n당신의 안전운전 점수는 만점!",
            "빨리 가는 것보다 안전하게 가는 게 더 멋진 법!\n오늘도 수고했어요!",
        )

        private val HYUNG_MESSAGES = listOf(
            "다음 드라이브에는 더 좋은 행운이 찾아올 거예요!",
            "오늘은 길이 좀 까칠했어요.\n당신 탓 아니에요.",
            "빨간불 맛집이었던 하루.\n내일은 초록불 맛집으로.",
            "이런 날도 있어야 좋은 날이 더 빛나요.\n고생했어요.",
            "내일은 분명 더 부드러울 거예요!",
            "어라? 예상치 못한 일이 있었네요!\n오늘은 따뜻한 차 한 잔으로 기분 전환 어때요?",
            "오늘의 도로는 조금 까칠했나 봐요!\n그래도 무사히 도착했다면 그걸로 충분해요!",
            "잠깐의 불운은 훌훌 털어버려요!\n다음에는 분명 꽃길이 펼쳐질 거예요!",
            "너무 속상해하지 마세요!\n행운은 언제든 다시 찾아오니까요!",
            "오늘은 작은 액땜을 했다고 생각해요!\n다음 드라이브는 행복으로 가득하길!",
        )

        /**
         * Ensures line breaks can only occur after ',', '!', or '.' (and explicit '\n'),
         * preventing mid-word or arbitrary space wrapping in TextView.
         */
        fun lockLineBreaksToPunctuation(raw: String): String {
            val cleaned = raw
                .replace("\u00A0", " ")
                .replace("\u2060", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")

            val sb = StringBuilder(cleaned.length * 2)
            for (i in cleaned.indices) {
                val c = cleaned[i]
                if (c == '\n') {
                    sb.append('\n')
                    continue
                }
                if (c == ' ') {
                    val prev = if (i > 0) cleaned[i - 1] else '\u0000'
                    if (prev in BREAK_PUNCTUATION) {
                        // Allow line break right after ',', '!', or '.'
                        sb.append(' ')
                    } else {
                        // Prevent line break at regular word spaces
                        sb.append('\u00A0')
                        sb.append('\u2060')
                    }
                    continue
                }
                sb.append(c)
                val next = if (i + 1 < cleaned.length) cleaned[i + 1] else '\u0000'
                if (next != '\u0000' && next != '\n' && !(c in BREAK_PUNCTUATION && next == ' ')) {
                    sb.append('\u2060')
                }
            }
            return sb.toString()
        }
    }
}
