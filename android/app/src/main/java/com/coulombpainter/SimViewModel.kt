package com.coulombpainter

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
    private var handle: Long = 0L
    private var tickJob: Job? = null
    private var statsJob: Job? = null

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
        startLoops()
    }

    private fun startLoops() {
        tickJob = viewModelScope.launch(Dispatchers.Default) {
            // 500 iters per tick keeps the responsiveness reasonable at M3a
            // stub cadence; M3b re-tunes this against the SurfaceControl
            // frame callback so tick + render stay in sync.
            while (true) {
                if (handle != 0L && _running.value) {
                    CoulombNative.nativeSimTick(handle, 500L)
                }
                delay(16L)
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
        if (handle != 0L) {
            CoulombNative.nativeSimDestroy(handle)
            handle = 0L
        }
    }
}

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
