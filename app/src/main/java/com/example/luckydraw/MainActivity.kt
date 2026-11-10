package com.example.luckydraw

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val engine = SignalEngine()
    private lateinit var voiceGuide: FortuneVoiceGuide
    private var index: SignalIndex? = null
    private var recommender: RouteRecommender? = null
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviBridge? = null
    private var brake: Boolean? = null
    private var lastGps: Location? = null
    private var lastKnownDomesticPos: Pair<Double, Double>? = null
    private var locationManager: LocationManager? = null
    private var mode = Mode.UNKNOWN
    private var currentGear: Int? = null
    private var lastObservedVhalGear: Int? = null
    private var demo = false
    private var demoLight: TrafficLight? = null
    private var demoBrake = false
    private var demoStopSeq = 0
    private var lastDeductedNoticeAt = 0L
    private var active = false
    private var brakeListenerRegistered = false
    private var brakeRequestPending = false
    private var brakeRequestAt = 0L
    private var lastSensorPollAt = 0L
    private var carFloat: ObjectAnimator? = null
    private var cloverGlow: ObjectAnimator? = null
    private var showingRoutes = false
    private var routePage = 0
    private var displayedRoutes: List<RecommendedRoute> = emptyList()
    private var lastLayoutSize: Pair<Int, Int>? = null

    private enum class Mode { UNKNOWN, DRIVE, PARK }

    private val backFromRoutesCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeRoutePanel()
        }
    }

    private val gpsListener = LocationListener { location ->
        if (!demo) {
            lastGps = location
            if ((location.latitude in 33.0..39.5) && (location.longitude in 124.0..132.0)) {
                lastKnownDomesticPos = Pair(location.latitude, location.longitude)
            }
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            main.post {
                if (active && (mode == Mode.DRIVE)) {
                    brake = status
                }
            }
        }

        override fun onFailed(e: Exception) {
            // Polling fallback in readBrake() handles transient listener errors.
        }
    }

    private val finishDemoBrake = Runnable {
        if (!active || !demo || (mode != Mode.DRIVE) || !demoBrake) return@Runnable
        val deducted = engine.tick(light = demoLight, brakePressed = true)
        demoBrake = false
        if (deducted) {
            lastDeductedNoticeAt = SystemClock.elapsedRealtime()
            text(R.id.tvSignal, "5초 이상 정차 감지 · 10점 감점 완료 (현재 ${engine.score}점)")
        }
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            readGear()
            if (mode == Mode.DRIVE) {
                if ((now - lastSensorPollAt) >= 1_000L) {
                    lastSensorPollAt = now
                    readBrake()
                    if (!demo) navi?.requestCurrentLocation()
                }
                val naviPosition = if (demo) null else navi?.currentLocation()
                val realBrake = (brake == true) && (currentGear != VehicleGear.GEAR_REVERSE)
                if (demo && realBrake && !demoBrake && ((demoLight == null) || (demoLight!!.id in engine.scoredIds()))) {
                    demoLight = nextDemoLight()
                }
                val position = if (demo) null else readPosition(naviPosition)
                val light = if (demo) {
                    demoLight
                } else {
                    position?.let { (lat, lng) -> index?.nearest(lat, lng) }
                }
                val outsideKorea = !demo && (position != null) &&
                    ((position.first !in 33.0..39.5) || (position.second !in 124.0..132.0))
                // Unknown sensor state, reverse maneuver, or missing location must never create a penalty.
                val effectiveBrake = if (demo) (demoBrake || realBrake) else realBrake
                val deducted = engine.tick(light = light, brakePressed = effectiveBrake, now = now)
                if (deducted) {
                    lastDeductedNoticeAt = now
                    if (demoBrake) {
                        demoBrake = false
                        main.removeCallbacks(finishDemoBrake)
                    }
                }
                text(R.id.tvRoad, if (demo) "테스트 좌표 (시뮬레이션 모드)" else roadName(naviPosition))
                val holdSec = engine.brakeHoldSeconds(now)
                text(
                    R.id.tvSignal,
                    when {
                        (now - lastDeductedNoticeAt) in 0..2_500L ->
                            "5초 이상 정차 감지 · 10점 감점 완료 (현재 ${engine.score}점)"
                        engine.isTrackingBrake() ->
                            "🔴 신호 대기 브레이크 감지 중 · ${holdSec}초 / 5초"
                        outsideKorea ->
                            "현재 GPS가 국내 신호등 범위 밖입니다 · 하단 시뮬레이션 버튼 또는 국내 좌표를 사용하세요"
                        light == null ->
                            "근처 30m 이내 신호등 없음 · 감점 대상 아님"
                        else ->
                            "근처 신호등 감지 · 5초 이상 정차 시 10점 감점"
                    },
                )
            }
            render()
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        voiceGuide = FortuneVoiceGuide(applicationContext) {
            (supportFragmentManager.findFragmentByTag(FortuneDialogFragment.TAG) as? FortuneDialogFragment)
                ?.refreshVoiceButton()
        }
        findViewById<View>(R.id.contentHost).addOnLayoutChangeListener { host, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            host.post { adaptToSize(width, height) }
        }
        onBackPressedDispatcher.addCallback(this, backFromRoutesCallback)
        restoreSessionState(savedInstanceState)
        findViewById<View>(R.id.simulationBar).visibility =
            if (isEmulator()) View.VISIBLE else View.GONE
        setupRouteButtons()
        setupDemoButtons()
        render()
        loader.execute {
            val loadedSignals = runCatching { SignalIndex(applicationContext) }
            val loadedRoutes = runCatching { RouteRecommender(applicationContext) }
            main.post {
                if (isFinishing || isDestroyed) return@post
                index = loadedSignals.getOrNull()
                recommender = loadedRoutes.getOrNull()
                if (demo && (mode == Mode.DRIVE) && (demoLight == null)) {
                    demoLight = nextDemoLight()
                }
                val routeInfo = recommender?.let { " · 추천 코스 ${it.size}곳" } ?: ""
                text(
                    R.id.tvSystemStatus,
                    if (index == null) {
                        "신호등 데이터를 읽지 못했습니다"
                    } else {
                        "신호등 ${index!!.size}곳$routeInfo 준비 · 교통 신호와 안전을 우선하세요"
                    },
                )
                if (showingRoutes) refreshRecommendedRoutes()
            }
        }
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOCATION_PERMISSION_REQUEST_CODE,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
        outState.putBoolean(KEY_DEMO, demo)
        outState.putInt(KEY_DEMO_STOP_SEQ, demoStopSeq)
        outState.putInt(KEY_SCORE, engine.score)
        outState.putInt(KEY_LONG_STOPS, engine.longStops)
        outState.putBoolean(KEY_HAS_DRIVEN, engine.hasDriven)
        outState.putStringArrayList(KEY_SCORED_IDS, engine.scoredIds())
        outState.putBoolean(KEY_SHOWING_ROUTES, showingRoutes)
        outState.putInt(KEY_ROUTE_PAGE, routePage)
    }

    override fun onStart() {
        super.onStart()
        voiceGuide.onStart()
        active = true
        connectCar()
        connectVehicle()
        connectNavi()
        if ((mode == Mode.DRIVE) && !demo) startGps()
        main.post(ticker)
    }

    override fun onStop() {
        voiceGuide.onStop()
        active = false
        main.removeCallbacks(ticker)
        main.removeCallbacks(finishDemoBrake)
        demoBrake = false
        carFloat?.cancel()
        carFloat = null
        cloverGlow?.cancel()
        cloverGlow = null
        stopGps()
        runCatching { navi?.release() }
        navi = null
        if (brakeListenerRegistered) {
            runCatching { vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener) }
            brakeListenerRegistered = false
        }
        runCatching { vehicle?.release() }
        vehicle = null
        brake = null
        brakeRequestPending = false
        lastObservedVhalGear = null
        runCatching { car?.disconnect() }
        car = null
        properties = null
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun restoreSessionState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        mode = state.getString(KEY_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.UNKNOWN
        demo = state.getBoolean(KEY_DEMO, false)
        demoStopSeq = state.getInt(KEY_DEMO_STOP_SEQ, 0)
        showingRoutes = (mode == Mode.PARK) && state.getBoolean(KEY_SHOWING_ROUTES, false)
        routePage = state.getInt(KEY_ROUTE_PAGE, 0)
        backFromRoutesCallback.isEnabled = showingRoutes
        engine.restore(
            savedScore = state.getInt(KEY_SCORE, 100),
            savedLongStops = state.getInt(KEY_LONG_STOPS, 0),
            savedHasDriven = state.getBoolean(KEY_HAS_DRIVEN, false),
            savedScored = state.getStringArrayList(KEY_SCORED_IDS).orEmpty(),
        )
    }

    private fun connectCar() {
        runCatching {
            car = Car.createCar(applicationContext)
            properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        }
    }

    private fun connectVehicle() {
        runCatching {
            val sdk = Vehicle(applicationContext).also { it.initialize() }
            vehicle = sdk
            runCatching {
                sdk.getBrake().registerBrakePedalStatus(brakeListener)
                brakeListenerRegistered = true
            }
        }.onFailure {
            vehicle = null
            brakeListenerRegistered = false
        }
    }

    private fun connectNavi() {
        navi = NaviBridge.connect(applicationContext)
    }

    private fun readGear() {
        val value = runCatching {
            properties?.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)
        }.getOrNull() ?: return
        val previousVhalGear = lastObservedVhalGear
        lastObservedVhalGear = value
        currentGear = value
        // If user is in demo mode, keep demo state unless VHAL gear actually changes interactively.
        if (demo && ((previousVhalGear == null) || (previousVhalGear == value))) {
            return
        }
        if (demo) {
            main.removeCallbacks(finishDemoBrake)
            demo = false
            demoBrake = false
            demoLight = null
        }
        when (value) {
            VehicleGear.GEAR_DRIVE -> setMode(Mode.DRIVE)
            VehicleGear.GEAR_PARK -> setMode(Mode.PARK)
        }
    }

    private fun readBrake() {
        val now = SystemClock.elapsedRealtime()
        if (brakeRequestPending && ((now - brakeRequestAt) < 2_000L)) return
        if (brakeRequestPending) brake = null
        val sdk = vehicle ?: return
        brakeRequestPending = true
        brakeRequestAt = now
        runCatching {
            sdk.getBrake().getCurrentBrakePedalStatus(
                onComplete = { value ->
                    main.post {
                        if (active && (mode == Mode.DRIVE)) brake = value
                        brakeRequestPending = false
                    }
                },
            ) {
                main.post {
                    brake = null
                    brakeRequestPending = false
                }
            }
        }.onFailure {
            brake = null
            brakeRequestPending = false
        }
    }

    private fun readPosition(info: NaviBridge.Position?): Pair<Double, Double>? {
        if ((info != null) && valid(info.latitude, info.longitude)) {
            return Pair(info.latitude, info.longitude)
        }
        val gps = lastGps ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - gps.elapsedRealtimeNanos) / 1_000_000L
        if ((ageMs !in 0..5_000) || (gps.hasAccuracy() && (gps.accuracy > 25f))) return null
        return if (valid(gps.latitude, gps.longitude)) Pair(gps.latitude, gps.longitude) else null
    }

    private fun roadName(info: NaviBridge.Position?): String {
        val address = info?.address
        return address?.takeIf { it.isNotBlank() }?.let { "현재 도로 · $it" }
            ?: if (navi == null) "현재 위치 · Android 위치 정보 사용 중" else "현재 도로 · 순정 내비 정보 확인 중"
    }

    private fun valid(lat: Double, lng: Double): Boolean =
        (lat in -90.0..90.0) && (lng in -180.0..180.0) && (lat != 0.0) && (lng != 0.0)

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        val previousMode = mode
        mode = newMode
        lastDeductedNoticeAt = 0L
        if (newMode == Mode.DRIVE) {
            voiceGuide.cancel()
            (supportFragmentManager.findFragmentByTag(FortuneDialogFragment.TAG) as? FortuneDialogFragment)
                ?.dismissAllowingStateLoss()
            showingRoutes = false
            backFromRoutesCallback.isEnabled = false
            engine.startDrive()
            if (!demo) startGps() else stopGps()
        } else {
            engine.park()
            stopGps()
            brake = null
        }
        render()
        if (previousMode == Mode.DRIVE && newMode == Mode.PARK && engine.hasDriven) {
            main.post { showFortune() }
        }
    }

    private fun hasLocationPermission(): Boolean =
        (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
            (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun startGps() {
        if ((checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
            (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        ) {
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, gpsListener, Looper.getMainLooper()) }
        }
    }

    private fun stopGps() {
        runCatching { locationManager?.removeUpdates(gpsListener) }
        locationManager = null
        lastGps = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if ((requestCode == LOCATION_PERMISSION_REQUEST_CODE) && (mode == Mode.DRIVE) && !demo) {
            startGps()
        }
    }

    private fun setupRouteButtons() {
        findViewById<View>(R.id.btnCloverRouteBox).setOnClickListener {
            if (mode != Mode.PARK) return@setOnClickListener
            showingRoutes = true
            routePage = 0
            backFromRoutesCallback.isEnabled = true
            navi?.requestCurrentLocation()
            refreshRecommendedRoutes()
            findViewById<ScrollView>(R.id.routePanel).scrollTo(0, 0)
            render()
        }
        findViewById<View>(R.id.btnRouteBack).setOnClickListener {
            closeRoutePanel()
        }
        findViewById<View>(R.id.btnRouteRefresh).setOnClickListener {
            if (!showingRoutes) return@setOnClickListener
            routePage++
            refreshRecommendedRoutes()
            findViewById<ScrollView>(R.id.routePanel).scrollTo(0, 0)
        }
        val goButtonIds = intArrayOf(R.id.btnRouteGo1, R.id.btnRouteGo2, R.id.btnRouteGo3)
        for (idx in goButtonIds.indices) {
            findViewById<View>(goButtonIds[idx]).setOnClickListener {
                val selected = displayedRoutes.getOrNull(idx)?.route ?: return@setOnClickListener
                launchRouteNavigation(selected)
            }
        }
    }

    private fun closeRoutePanel() {
        if (!showingRoutes) return
        showingRoutes = false
        backFromRoutesCallback.isEnabled = false
        findViewById<ScrollView>(R.id.routePanel).scrollTo(0, 0)
        render()
    }

    private fun showFortune() {
        if (mode != Mode.PARK || !engine.hasDriven || showingRoutes ||
            isFinishing || isDestroyed || supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(FortuneDialogFragment.TAG) != null
        ) return
        FortuneDialogFragment.newInstance(
            score = engine.score,
            grade = engine.grade(),
            message = engine.message(),
            longStops = engine.longStops,
        ).show(supportFragmentManager, FortuneDialogFragment.TAG)
    }

    fun isFortuneVoiceEnabled(): Boolean = voiceGuide.enabled

    fun hasFortuneVoiceError(): Boolean = voiceGuide.hasError

    fun announceFortune(text: String) {
        if (mode == Mode.PARK && engine.hasDriven && !showingRoutes) voiceGuide.speak(text)
    }

    fun toggleFortuneVoice(text: String) {
        if (voiceGuide.enabled && voiceGuide.hasError) {
            voiceGuide.retry(text)
            return
        }
        val enable = !voiceGuide.enabled
        voiceGuide.setEnabled(enable, if (enable && mode == Mode.PARK) text else null)
    }

    fun cancelFortuneVoice() {
        voiceGuide.cancel()
    }

    private fun adaptToSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0 || lastLayoutSize == Pair(widthPx, heightPx)) return
        lastLayoutSize = Pair(widthPx, heightPx)
        val density = resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density
        val compact = widthDp < 600f || resources.configuration.fontScale >= 1.3f
        val driveHeroDp = if (compact) 238f else heightDp.coerceAtMost(1150f)
        val parkHeroDp = if (compact) 238f else heightDp.coerceAtMost(1150f)
        fun size(id: Int, width: Float? = null, height: Float? = null) {
            findViewById<View>(id).apply {
                layoutParams = layoutParams.apply {
                    if (width != null) this.width = (width * density).toInt()
                    if (height != null) this.height = (height * density).toInt()
                }
            }
        }
        size(R.id.driveHero, height = driveHeroDp)
        size(R.id.parkHero, height = parkHeroDp)
        for ((id, overlapDp) in arrayOf(
            R.id.driveHeadlineCard to if (compact) 44f else (driveHeroDp * 0.30f),
            R.id.parkHeadlineCard to if (compact) 44f else (parkHeroDp * 0.28f),
        )) {
            findViewById<View>(id).apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = (-overlapDp * density).toInt()
                }
            }
        }
        val carDp = if (compact) 185f else (driveHeroDp * 0.60f).coerceAtMost(620f)
        size(R.id.tvCarArt, width = carDp, height = carDp)
        val fairyDp = if (compact) 185f else (parkHeroDp * 0.58f).coerceAtMost(620f)
        size(R.id.tvFairyArt, width = fairyDp, height = fairyDp)
        val cardWidth = (widthDp - 24f).coerceAtMost(760f)
        for (id in intArrayOf(R.id.driveHeadlineCard, R.id.driveInfoCard, R.id.parkHeadlineCard)) {
            size(id, width = cardWidth)
        }
        val routeWidth = (widthDp - 24f).coerceAtMost(900f)
        size(R.id.routeContent, width = (widthDp - 24f).coerceAtMost(1100f))
        size(R.id.routeHero, height = if (compact) 165f else 280f)
        size(R.id.routeActions, width = routeWidth)
        size(R.id.tvRouteBasis, width = routeWidth)
        for (id in intArrayOf(R.id.routeCard1, R.id.routeCard2, R.id.routeCard3)) {
            size(id, width = routeWidth)
        }

        val wideButtons = widthDp >= 700f && resources.configuration.fontScale < 1.3f
        val bar = findViewById<LinearLayout>(R.id.simulationBar)
        val minor = findViewById<LinearLayout>(R.id.simMinorActions)
        bar.orientation = if (wideButtons) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        minor.orientation = if (wideButtons) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        minor.layoutParams = (minor.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (wideButtons) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            weight = if (wideButtons) 2f else 0f
        }
        for (id in intArrayOf(R.id.btnSimDrive, R.id.btnSimBrake)) {
            findViewById<View>(id).apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (wideButtons) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                    weight = if (wideButtons) 1f else 0f
                    topMargin = if (wideButtons) 0 else if (id == R.id.btnSimBrake) (4 * density).toInt() else 0
                    marginStart = if (wideButtons && id == R.id.btnSimBrake) (6 * density).toInt() else 0
                }
            }
        }
        findViewById<View>(R.id.btnSimPark).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = if (wideButtons) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                weight = if (wideButtons) 1f else 0f
                topMargin = if (wideButtons) 0 else (4 * density).toInt()
                marginStart = if (wideButtons) (6 * density).toInt() else 0
            }
        }
    }

    private fun currentAnchorForRoutes(): Pair<Double, Double>? {
        val naviLoc = navi?.currentLocation()
        if ((naviLoc != null) && valid(naviLoc.latitude, naviLoc.longitude)) {
            if ((naviLoc.latitude in 33.0..39.5) && (naviLoc.longitude in 124.0..132.0)) {
                lastKnownDomesticPos = Pair(naviLoc.latitude, naviLoc.longitude)
            }
            return Pair(naviLoc.latitude, naviLoc.longitude)
        }
        lastKnownDomesticPos?.let { return it }
        if ((checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
            (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        ) {
            val manager = getSystemService(LOCATION_SERVICE) as? LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val loc = runCatching { manager?.getLastKnownLocation(provider) }.getOrNull() ?: continue
                if (valid(loc.latitude, loc.longitude)) {
                    if ((loc.latitude in 33.0..39.5) && (loc.longitude in 124.0..132.0)) {
                        lastKnownDomesticPos = Pair(loc.latitude, loc.longitude)
                    }
                    return Pair(loc.latitude, loc.longitude)
                }
            }
        }
        return null
    }

    private fun refreshRecommendedRoutes() {
        val engineRecommender = recommender ?: return
        val anchor = currentAnchorForRoutes()
        val result = engineRecommender.recommend(
            lat = anchor?.first,
            lng = anchor?.second,
            page = routePage,
            limit = 3,
        )
        displayedRoutes = result.items
        text(
            R.id.tvRouteBasis,
            if (result.usingDefaultLocation) {
                "기본 출발지(서울 도심) 기준 · ${result.radiusLabel}"
            } else {
                "현재 차량 위치 기준 · ${result.radiusLabel}"
            },
        )
        val cardIds = intArrayOf(R.id.routeCard1, R.id.routeCard2, R.id.routeCard3)
        val nameIds = intArrayOf(R.id.tvRouteName1, R.id.tvRouteName2, R.id.tvRouteName3)
        val signalIds = intArrayOf(R.id.tvRouteSignal1, R.id.tvRouteSignal2, R.id.tvRouteSignal3)
        val metaIds = intArrayOf(R.id.tvRouteMeta1, R.id.tvRouteMeta2, R.id.tvRouteMeta3)
        val descIds = intArrayOf(R.id.tvRouteDesc1, R.id.tvRouteDesc2, R.id.tvRouteDesc3)

        for (i in cardIds.indices) {
            val card = findViewById<View>(cardIds[i])
            val item = result.items.getOrNull(i)
            if (item == null) {
                card.visibility = View.GONE
            } else {
                card.visibility = View.VISIBLE
                text(nameIds[i], item.route.name)
                text(signalIds[i], item.signalBadgeText())
                text(metaIds[i], item.metaText())
                text(descIds[i], item.route.summary)
            }
        }
    }

    private fun launchRouteNavigation(route: DriveRoute) {
        val encodedLabel = Uri.encode(route.name)
        val geoUri = String.format(
            Locale.US,
            "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
            route.lat,
            route.lng,
            route.lat,
            route.lng,
            encodedLabel,
        ).toUri()
        val navIntent = Intent(Intent.ACTION_VIEW, geoUri)
        runCatching {
            startActivity(navIntent)
        }.onSuccess {
            text(R.id.tvSystemStatus, "📍 '${route.name}' 목적지를 내비게이션으로 전송했습니다 · D 기어로 출발하세요")
            Toast.makeText(this, "📍 '${route.name}' 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            val coord = String.format(Locale.US, "%.4f, %.4f", route.lat, route.lng)
            text(R.id.tvSystemStatus, "📍 '${route.name}' ($coord) 선택됨 · 내비게이션 앱 미설치 환경")
            Toast.makeText(
                this,
                "📍 '${route.name}' 목적지 좌표($coord)가 준비되었습니다.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setupDemoButtons() {
        findViewById<View>(R.id.btnShowFortune).setOnClickListener { showFortune() }
        findViewById<View>(R.id.btnRestart).setOnClickListener {
            if (mode != Mode.PARK) return@setOnClickListener
            main.removeCallbacks(finishDemoBrake)
            engine.reset()
            demoBrake = false
            demoLight = null
            demoStopSeq = 0
            lastDeductedNoticeAt = 0L
            render()
        }
        findViewById<View>(R.id.btnSimDrive).setOnClickListener {
            main.removeCallbacks(finishDemoBrake)
            showingRoutes = false
            backFromRoutesCallback.isEnabled = false
            demo = true
            demoBrake = false
            demoStopSeq = 0
            lastDeductedNoticeAt = 0L
            demoLight = nextDemoLight()
            stopGps()
            if (mode == Mode.DRIVE) {
                engine.startDrive()
            } else {
                setMode(Mode.DRIVE)
            }
            text(R.id.tvRoad, "테스트 좌표 (시뮬레이션 모드)")
            text(R.id.tvSignal, "근처 신호등 감지 · 5초 이상 정차 시 10점 감점")
            render()
        }
        findViewById<View>(R.id.btnSimBrake).setOnClickListener {
            if (mode != Mode.DRIVE) {
                // Allow immediate testing even if user tapped 5s brake directly before tapping D.
                demo = true
                demoStopSeq = 0
                stopGps()
                setMode(Mode.DRIVE)
            } else if (!demo) {
                demo = true
                stopGps()
            }
            main.removeCallbacks(finishDemoBrake)
            val targetLight = nextDemoLight()
            demoLight = targetLight
            demoBrake = true
            lastDeductedNoticeAt = 0L
            // Start the 5-second timer immediately on button tap (t = 0.000s).
            engine.tick(light = targetLight, brakePressed = true)
            text(R.id.tvRoad, "테스트 좌표 (시뮬레이션 모드)")
            text(R.id.tvSignal, "🔴 신호 대기 브레이크 감지 중 · 0초 / 5초")
            main.postDelayed(finishDemoBrake, 5_050L)
            render()
        }
        findViewById<View>(R.id.btnSimPark).setOnClickListener {
            if (mode == Mode.DRIVE) {
                main.removeCallbacks(finishDemoBrake)
                demo = true
                demoBrake = false
                lastDeductedNoticeAt = 0L
                setMode(Mode.PARK)
            } else if (mode == Mode.PARK && engine.hasDriven) {
                showFortune()
            } else {
                Toast.makeText(this, "주행 결과가 아직 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun nextDemoLight(): TrafficLight {
        val sample = index?.sample
        val seq = ++demoStopSeq
        return TrafficLight(
            id = "demo-stop-$seq",
            lat = sample?.lat ?: 37.5665,
            lng = sample?.lng ?: 126.9780,
        )
    }

    private fun render() {
        val driving = mode == Mode.DRIVE
        val showRouteView = mode == Mode.PARK && showingRoutes
        val showCloverBox = mode == Mode.PARK && !showingRoutes

        val animateCar = driving && active && ValueAnimator.areAnimatorsEnabled()
        if (animateCar && (carFloat == null)) {
            val distance = resources.displayMetrics.density * 1.5f
            carFloat = ObjectAnimator.ofFloat(findViewById(R.id.tvCarArt), View.TRANSLATION_Y, -distance, distance).apply {
                duration = 1_200L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else if (!animateCar) {
            carFloat?.cancel()
            carFloat = null
            findViewById<View>(R.id.tvCarArt).translationY = 0f
        }

        val cloverBox = findViewById<View>(R.id.btnCloverRouteBox)
        cloverBox.visibility = if (showCloverBox) View.VISIBLE else View.GONE
        val animateClover = showCloverBox && active && ValueAnimator.areAnimatorsEnabled()
        if (animateClover && (cloverGlow == null)) {
            cloverGlow = ObjectAnimator.ofPropertyValuesHolder(
                cloverBox,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.04f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.04f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.90f, 1.0f),
            ).apply {
                duration = 1_100L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else if (!animateClover) {
            cloverGlow?.cancel()
            cloverGlow = null
            cloverBox.scaleX = 1.0f
            cloverBox.scaleY = 1.0f
            cloverBox.alpha = 1.0f
        }

        findViewById<View>(R.id.drivePanel).visibility = if (driving) View.VISIBLE else View.GONE
        findViewById<View>(R.id.parkPanel).visibility =
            if (!driving && !showRouteView) View.VISIBLE else View.GONE
        findViewById<View>(R.id.routePanel).visibility = if (showRouteView) View.VISIBLE else View.GONE
        findViewById<View>(R.id.simulationBar).visibility =
            if (isEmulator() && !showRouteView) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvDriveScore).visibility = if (driving) View.VISIBLE else View.GONE
        if (driving) text(R.id.tvDriveScore, "현재 점수 ${engine.score}점")
        text(
            R.id.tvMode,
            when {
                driving -> "DRIVE · 안내 중"
                showRouteView -> "PARK · 클로버 힐링 루트"
                mode == Mode.PARK -> "PARK · 주행 결과"
                else -> "차량 상태 확인 중"
            },
        )
        if (!driving && !showRouteView) {
            text(R.id.tvFortuneTitle, if (engine.hasDriven) "오늘의 주행을 마쳤어요!" else "오늘의 운세를 준비해요")
            text(R.id.tvParkSubtitle, getString(if (engine.hasDriven) R.string.park_subtitle else R.string.park_subtitle_waiting))
            findViewById<View>(R.id.btnShowFortune).visibility =
                if (engine.hasDriven && mode == Mode.PARK && !isEmulator()) View.VISIBLE else View.GONE
            findViewById<View>(R.id.tvSessionSummary).visibility = View.GONE
            findViewById<View>(R.id.btnRestart).visibility =
                if (engine.hasDriven && (mode == Mode.PARK)) View.VISIBLE else View.GONE
        }
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 10
        private const val KEY_MODE = "mode"
        private const val KEY_DEMO = "demo"
        private const val KEY_DEMO_STOP_SEQ = "demo_stop_seq"
        private const val KEY_SCORE = "score"
        private const val KEY_LONG_STOPS = "long_stops"
        private const val KEY_HAS_DRIVEN = "has_driven"
        private const val KEY_SCORED_IDS = "scored_ids"
        private const val KEY_SHOWING_ROUTES = "showing_routes"
        private const val KEY_ROUTE_PAGE = "route_page"
    }
}
