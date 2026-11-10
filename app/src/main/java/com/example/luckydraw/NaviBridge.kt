package com.example.luckydraw

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Pleos requests are asynchronous: getCurrentLocationInfo delivers onCurrentLocationInfo. */
class NaviBridge private constructor(
    private val sdk: Any,
    private val listenerType: Class<*>,
) {
    data class Position(
        val latitude: Double,
        val longitude: Double,
        val address: String,
        val receivedAt: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val requestLocationMethod: Method = sdk.javaClass.getMethod("getCurrentLocationInfo")
    private val addListenerMethod: Method = sdk.javaClass.getMethod("addListener", listenerType)
    private val removeListenerMethod: Method = sdk.javaClass.getMethod("removeListener", listenerType)
    private val releaseMethod: Method = sdk.javaClass.getMethod("release")

    private var listener: Any? = null
    private var connected = false
    private var closed = false
    private var requestAt = 0L
    private var pending = false
    private var latest: Position? = null

    fun requestCurrentLocation() {
        if (!connected || closed) return
        val now = SystemClock.elapsedRealtime()
        if (pending && ((now - requestAt) < 2_000L)) return
        pending = true
        requestAt = now
        runCatching { requestLocationMethod.invoke(sdk) }
            .onFailure { pending = false }
    }

    fun currentLocation(): Position? {
        val result = latest ?: return null
        val age = SystemClock.elapsedRealtime() - result.receivedAt
        return if (age in 0..5_000L) result else null
    }

    fun release() {
        closed = true
        connected = false
        pending = false
        latest = null
        main.removeCallbacksAndMessages(null)
        listener?.let { registered ->
            runCatching { removeListenerMethod.invoke(sdk, registered) }
        }
        listener = null
        runCatching { releaseMethod.invoke(sdk) }
    }

    private fun initialize() {
        val onConnected: () -> Unit = {
            main.post {
                if (!closed) {
                    val registered = listener ?: createListener().also { listener = it }
                    runCatching { addListenerMethod.invoke(sdk, registered) }
                        .onSuccess { connected = true }
                }
            }
        }
        val onFailed: (Throwable) -> Unit = {
            main.post {
                connected = false
                pending = false
                latest = null
            }
        }
        sdk.javaClass.getMethod("initialize", Function0::class.java, Function1::class.java)
            .invoke(sdk, onConnected, onFailed)
    }

    private fun createListener(): Any = Proxy.newProxyInstance(
        listenerType.classLoader,
        arrayOf(listenerType),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "LuckyDrawNaviListener"
            "onCurrentLocationInfo" -> {
                val value = args?.firstOrNull()
                val position = runCatching {
                    if (value == null) return@runCatching null
                    val type = value.javaClass
                    Position(
                        latitude = (type.getMethod("getLatitude").invoke(value) as Number).toDouble(),
                        longitude = (type.getMethod("getLongitude").invoke(value) as Number).toDouble(),
                        address = (type.getMethod("getAddress").invoke(value) as? String) ?: "",
                        receivedAt = SystemClock.elapsedRealtime(),
                    )
                }.getOrNull()
                main.post {
                    if (!closed) {
                        latest = position
                        pending = false
                    }
                }
                if (method.returnType == Void.TYPE) null else Unit
            }
            "onError" -> {
                main.post { pending = false }
                if (method.returnType == Void.TYPE) null else Unit
            }
            else -> if (method.returnType == Void.TYPE) null else Unit
        }
    }

    companion object {
        fun connect(context: Context): NaviBridge? = runCatching {
            val sdkType = Class.forName("ai.pleos.playground.navi.helper.NaviHelper")
            val listenerType = Class.forName("ai.pleos.playground.navi.helper.listener.NaviHelperEventListener")
            val sdk = sdkType.getConstructor(Context::class.java).newInstance(context)
            val bridge = NaviBridge(sdk, listenerType)
            runCatching { bridge.initialize() }
                .onFailure {
                    bridge.release()
                    throw it
                }
            bridge
        }.getOrNull()
    }
}
