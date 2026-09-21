package com.coulombpainter

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Owns the native sim handle for the lifetime of the Activity's ViewModel
 * scope and pumps two coroutines:
 *
 *  - a physics tick loop, which calls [CoulombNative.nativeSimTick] on the
 *    default dispatcher so the mutex contention stays off the main thread;
 *  - a stats loop, which refreshes [stats] a few times per second so the
 *    live one-liner in the UI is legible without a per-frame native crossing.
 *
 * Real texture-driven display is M3b's job; this VM's stub-canvas peers only
 * at [stats], so the physics is a proof-of-plumbing input.
 */
class SimViewModel : ViewModel() {
    // Handle is intentionally public so `PhysicsSurface`'s onSurface callback
    // can pass it straight into `nativeSimBindSurface` without an extra
    // getter. Kotlin visibility here is a code-organisation choice; the
    // lifetime contract is still that the VM owns the handle for its own
    // scope, and clears it in onCleared before anyone else could see 0.
    var handle: Long = 0L
        private set
    private var tickJob: Job? = null
    private var statsJob: Job? = null
    private var thermalJob: Job? = null

    private val _lattice = MutableStateFlow(512)
    val lattice: StateFlow<Int> = _lattice.asStateFlow()

    private val _adapter = MutableStateFlow<AdapterInfo?>(null)
    val adapter: StateFlow<AdapterInfo?> = _adapter.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private val _thermal = MutableStateFlow(ThermalReading(headroom = Float.NaN, timestampNs = 0L))
    val thermal: StateFlow<ThermalReading> = _thermal.asStateFlow()

    private val _showDiagnostics = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = _showDiagnostics.asStateFlow()

    private val _gestureView = MutableStateFlow(ViewportGesture())
    val gestureView: StateFlow<ViewportGesture> = _gestureView.asStateFlow()

    // Simple sliding-window FPS. onFrameRendered is called from the render
    // loop each time a swapchain image is queued; the sliding window resets
    // every 500 ms so a paused render loop does not linger at a stale FPS.
    private var frameCountWindow = 0
    private var frameWindowStartNs = 0L

    private var thermalHeadroomLastLoggedBand = -1

    private val _stats = MutableStateFlow<CoulombNative.Stats?>(null)
    val stats: StateFlow<CoulombNative.Stats?> = _stats.asStateFlow()

    private val _mode = MutableStateFlow(Mode.Paint)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _brush = MutableStateFlow(BrushSettings())
    val brush: StateFlow<BrushSettings> = _brush.asStateFlow()

    private val _params = MutableStateFlow(ParamsSnapshot())
    val params: StateFlow<ParamsSnapshot> = _params.asStateFlow()

    private val _running = MutableStateFlow(true)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun ensureCreated(h: Int, w: Int, seed: Long, nParticles: Int) {
        if (handle != 0L) return
        // The native call is cheap on a small lattice but returns 0 on
        // failure; a nonzero handle is the invariant every other call
        // depends on.
        handle = CoulombNative.nativeSimCreate(h.toLong(), w.toLong(), seed, nParticles.toLong())
        if (handle == 0L) return
        _lattice.value = h
        startLoops()
    }

    private fun startLoops() {
        tickJob = viewModelScope.launch(Dispatchers.Default) {
            // Small tick batch (was 500 at M3a stub time) so the shared sim
            // mutex is only held for a short slice each pass. The render
            // loop, touch stroke, and stats read all wait on the same
            // mutex; a long hold ANRs the app on Adreno 750 within one
            // frame period at 512x512.
            while (true) {
                if (handle != 0L && _running.value) {
                    CoulombNative.nativeSimTick(handle, 50L)
                }
                delay(4L)
            }
        }
        statsJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                if (handle != 0L) {
                    // Native fetch on the default dispatcher so the main
                    // thread stays free for gestures; the DoubleArray copy is
                    // a few hundred bytes so the crossing itself is cheap.
                    val next = withContext(Dispatchers.Default) {
                        CoulombNative.Stats.from(CoulombNative.nativeSimStats(handle))
                    }
                    if (next != null) _stats.value = next
                }
                delay(200L)
            }
        }
    }

    fun startThermalObserver(headroomProvider: (Int) -> Float?) {
        if (thermalJob != null) return
        thermalJob = viewModelScope.launch(Dispatchers.Default) {
            // Poll at 1 Hz. `getThermalHeadroom(10)` forecasts the next 10 s,
            // per docs/android-plan.md §5. Threshold-crossing logs make the
            // stress-run correlation legible in logcat without a per-tick
            // spam. M3b observes; M5 acts on the reading.
            val bands = floatArrayOf(0.5f, 0.7f, 0.85f, 1.0f)
            while (true) {
                val h = headroomProvider(10)
                val now = System.nanoTime()
                if (h != null) {
                    _thermal.value = ThermalReading(headroom = h, timestampNs = now)
                    val band = bands.indexOfLast { it <= h }
                    if (band != thermalHeadroomLastLoggedBand) {
                        Log.i(
                            "CoulombThermal",
                            "headroom crossed band ${bandLabel(band)}: value=$h",
                        )
                        thermalHeadroomLastLoggedBand = band
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun bandLabel(band: Int): String = when (band) {
        -1 -> "<0.5"
        0 -> ">=0.5"
        1 -> ">=0.7"
        2 -> ">=0.85"
        3 -> ">=1.0 (throttling imminent)"
        else -> "unknown"
    }

    fun onAdapterInfoRefresh() {
        val json = if (handle != 0L) CoulombNative.nativeSimAdapterInfoJson(handle) else null
        if (json.isNullOrBlank() || json == "{}") {
            _adapter.value = null
            return
        }
        _adapter.value = runCatching {
            val o = JSONObject(json)
            AdapterInfo(
                name = o.optString("name", "unknown"),
                backend = o.optString("backend", "unknown"),
                driver = o.optString("driver", ""),
                deviceType = o.optString("device_type", ""),
            )
        }.getOrNull()
    }

    fun onFrameRendered() {
        val now = System.nanoTime()
        if (frameWindowStartNs == 0L) {
            frameWindowStartNs = now
            frameCountWindow = 0
            return
        }
        frameCountWindow++
        val elapsedNs = now - frameWindowStartNs
        if (elapsedNs >= 500_000_000L) {
            val secs = elapsedNs / 1_000_000_000.0
            _fps.value = frameCountWindow.toDouble() / secs
            frameWindowStartNs = now
            frameCountWindow = 0
        }
    }

    fun onGestureTransform(panX: Float, panY: Float, zoom: Float) {
        // Only View mode consumes pan/pinch; other modes still record the
        // gesture on the state so the diagnostic overlay can show it.
        val g = _gestureView.value
        _gestureView.value = g.copy(
            panX = g.panX + panX,
            panY = g.panY + panY,
            zoom = (g.zoom * zoom).coerceIn(0.25f, 8f),
        )
    }

    fun setShowDiagnostics(show: Boolean) { _showDiagnostics.value = show }

    fun modeSnapshot(): Mode = _mode.value
    fun brushSignSnapshot(): Double = _brush.value.sign

    /**
     * Touch-driven paint: run on the default dispatcher so a paint stroke
     * that waits for the sim mutex does not stall the main thread. The
     * caller (PhysicsSurface's OnTouchListener) fires and forgets; the
     * ordering here matches ACTION_DOWN -> ACTION_MOVE* -> ACTION_UP.
     */
    fun paintBegin(sign: Double) {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimPaintBegin(handle, sign)
        }
    }

    fun paintPoint(x: Double, y: Double, timestamp: Double) {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimPaintStrokePoint(handle, x, y, timestamp)
        }
    }

    fun paintEnd() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimPaintEnd(handle)
        }
    }

    fun setRunning(running: Boolean) {
        _running.value = running
        if (handle != 0L) {
            if (running) CoulombNative.nativeSimResume(handle)
            else CoulombNative.nativeSimPause(handle)
        }
    }

    fun setMode(m: Mode) { _mode.value = m }

    fun setBrush(b: BrushSettings) { _brush.value = b }

    fun setParam(key: String, value: Double) {
        // M3a exposes only temperature as live-mutable; the drawer surfaces
        // are still visible so the layout can be judged, but nativeSetParam
        // silently no-ops on unrecognised keys (see coulomb-jni::apply_param).
        if (handle != 0L) CoulombNative.nativeSimSetParam(handle, key, value)
        _params.value = _params.value.setByName(key, value)
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        statsJob?.cancel()
        thermalJob?.cancel()
        if (handle != 0L) {
            // Unbind the surface first so wgpu can drop its swapchain before
            // the physics core goes away. Destroy is idempotent on an empty
            // renderer slot, but calling in this order keeps the shutdown
            // trace legible in wgpu's own logs.
            CoulombNative.nativeSimUnbindSurface(handle)
            CoulombNative.nativeSimDestroy(handle)
            handle = 0L
        }
    }
}

data class AdapterInfo(
    val name: String,
    val backend: String,
    val driver: String,
    val deviceType: String,
)

data class ThermalReading(
    val headroom: Float,
    val timestampNs: Long,
)

data class ViewportGesture(
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
)

enum class Mode { Paint, Heat, View }

data class BrushSettings(
    val sign: Double = 1.0,
    val magnitude: Double = 4.0,
    val density: Double = 1.0,
    val thickness: Double = 10.0,
    val flow: Double = 1.0,
    val hardness: Double = 0.5,
    val penetrability: Double = 1.0,
    val coupling: Double = 12.0,
    val budget: Double = 60.0,
)

/**
 * Mirror of the params surface for the accordion drawer. Live-mutated fields
 * are pushed through nativeSimSetParam; the rest are decorative in M3a and
 * become authoritative in M4 when the drawer wires up.
 */
data class ParamsSnapshot(
    val resolution: Int = 512,
    val lineDensity: Double = 1.0,
    val periodic: Boolean = false,
    val temperature: Double = 5000.0,
    val strength: Double = 1.0,
    val screening: Double = 0.0,
    val cutoff: Double = 12.0,
    val charge: Double = 1.0,
    val attractDepth: Double = 0.0,
    val attractRange: Double = 1.5,
    val coolingActive: Boolean = true,
    val schedule: String = "geometric",
    val coolingRate: Double = 0.97,
    val batch: Int = 64,
    val batchMin: Int = 1,
    val batchDecrement: Int = 1,
    val failLimit: Int = 12,
    val stepSize: Int = 2,
) {
    fun setByName(key: String, value: Double): ParamsSnapshot = when (key) {
        "temperature" -> copy(temperature = value)
        "strength" -> copy(strength = value)
        "screening" -> copy(screening = value)
        "cutoff" -> copy(cutoff = value)
        "charge" -> copy(charge = value)
        "attract_depth" -> copy(attractDepth = value)
        "attract_range" -> copy(attractRange = value)
        else -> this
    }
}
