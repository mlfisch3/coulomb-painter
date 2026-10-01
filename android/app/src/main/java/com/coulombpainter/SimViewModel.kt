package com.coulombpainter

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    // Viewport is the sub-rect of the lattice (origin + size in lattice
    // cells) that the renderer samples into its on-screen letterbox rect.
    // A zero-size rect means "render the whole lattice". The physics surface
    // mutates this on view-mode pinch + two-finger pan; the diagnostic
    // overlay reads it so a developer can confirm what the shader is
    // actually sampling.
    private val _viewport = MutableStateFlow(Viewport())
    val viewport: StateFlow<Viewport> = _viewport.asStateFlow()

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

    private var persistenceJob: Job? = null

    /**
     * Hydrate the Kotlin-side state from a previously saved snapshot. Must be
     * called before [ensureCreated] so the first `pushBrushToNative` and
     * `pushParamsToNative` pick up the restored values; otherwise the native
     * side starts on the hard-coded defaults and the artist loses their
     * tuning across a reboot or app update. The `running` flag is applied as
     * well so a deliberately paused session comes back paused rather than
     * silently resuming.
     */
    fun restoreSettings(brush: BrushSettings, params: ParamsSnapshot, running: Boolean) {
        _brush.value = brush
        _params.value = params
        _running.value = running
    }

    /**
     * Observe the state flows and persist each change to [SettingsStore].
     * Started by MainActivity after [restoreSettings] + [ensureCreated] so the
     * first on-screen frame already reflects the saved values and any
     * subsequent slider move writes back. The `drop(1)` suppresses the
     * initial emission; the `debounce` coalesces mid-drag storms since the
     * drawer's LiveSlider pattern commits on `onValueChangeFinished` while
     * BrushRow fires per-tick during a drag.
     */
    @OptIn(FlowPreview::class)
    fun startPersistence(appContext: Context) {
        if (persistenceJob != null) return
        val ctx = appContext.applicationContext
        persistenceJob = viewModelScope.launch(Dispatchers.IO) {
            _brush
                .drop(1)
                .distinctUntilChanged()
                .debounce(300L)
                .onEach { SettingsStore.saveBrush(ctx, it) }
                .launchIn(this)
            _params
                .drop(1)
                .distinctUntilChanged()
                .debounce(300L)
                .onEach { SettingsStore.saveParams(ctx, it) }
                .launchIn(this)
            _running
                .drop(1)
                .distinctUntilChanged()
                .onEach { SettingsStore.saveRunning(ctx, it) }
                .launchIn(this)
        }
    }

    fun ensureCreated(h: Int, w: Int, seed: Long, nParticles: Int) {
        if (handle != 0L) return
        // The native call is cheap on a small lattice but returns 0 on
        // failure; a nonzero handle is the invariant every other call
        // depends on.
        handle = CoulombNative.nativeSimCreate(h.toLong(), w.toLong(), seed, nParticles.toLong())
        if (handle == 0L) return
        _lattice.value = h
        // A fresh handle resets the view-mode viewport back to the whole
        // lattice so a loadPreset or activity recreate does not strand the
        // user zoomed into coordinates the new lattice may no longer cover.
        _viewport.value = Viewport()
        // Push the artist's current brush and params into the fresh handle so
        // an app-update or activity recreate does not silently reset the
        // brush to the fat-line default or the interaction cutoff to 12.
        pushBrushToNative()
        pushParamsToNative()
        // If the artist left the previous session paused, the restored
        // _running is false; mirror it on the native handle so the paused
        // state is explicit on both sides. The tick loop already guards on
        // _running.value, but keeping native in sync matches the pause/resume
        // button contract.
        if (!_running.value) CoulombNative.nativeSimPause(handle)
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

    /**
     * Apply a view-mode gesture to the viewport. `panX`/`panY` are in lattice
     * cells (positive = scroll content to the right / down, matching
     * finger-drag-content follows); `zoomFactor` is multiplicative around the
     * viewport centre (`> 1` = zoom in). Values are clamped so the viewport
     * stays inside the lattice.
     *
     * `PhysicsSurface` is the only caller; it translates raw pointer deltas
     * into lattice cells using the current letterbox dst rect. Keeping the
     * conversion in the touch handler leaves this VM method free of a
     * surface-size dependency.
     */
    fun applyViewGesture(panX: Double, panY: Double, zoomFactor: Double) {
        val lat = _lattice.value.toDouble()
        if (lat <= 0.0) return
        val cur = _viewport.value.ensureSized(lat)
        val newW = (cur.w / zoomFactor).coerceIn(8.0, lat)
        val newH = (cur.h / zoomFactor).coerceIn(8.0, lat)
        // Zoom around the viewport centre so the pinch feels anchored to the
        // middle of the two fingers rather than the corner.
        val cx = cur.x + cur.w * 0.5 + panX
        val cy = cur.y + cur.h * 0.5 + panY
        val newX = (cx - newW * 0.5).coerceIn(0.0, lat - newW)
        val newY = (cy - newH * 0.5).coerceIn(0.0, lat - newH)
        setViewport(newX, newY, newW, newH)
    }

    /**
     * Reset the view-mode viewport back to the whole lattice. Called from
     * the mode-selector when the user leaves View mode so the next entry
     * does not resume at a stale zoom, and from the diagnostic overlay for
     * manual recovery.
     */
    fun resetViewport() {
        _viewport.value = Viewport()
        pushViewportToNative()
    }

    /**
     * Set the viewport directly (lattice cells). Values are clamped to the
     * current lattice. Zero-size clears the viewport.
     */
    fun setViewport(x: Double, y: Double, w: Double, h: Double) {
        val lat = _lattice.value.toDouble()
        _viewport.value = if (w <= 0.0 || h <= 0.0) {
            Viewport()
        } else {
            val cw = w.coerceIn(1.0, lat)
            val ch = h.coerceIn(1.0, lat)
            val cx = x.coerceIn(0.0, lat - cw)
            val cy = y.coerceIn(0.0, lat - ch)
            Viewport(cx, cy, cw, ch)
        }
        pushViewportToNative()
    }

    fun setShowDiagnostics(show: Boolean) { _showDiagnostics.value = show }

    fun modeSnapshot(): Mode = _mode.value
    fun brushSignSnapshot(): Double = _brush.value.sign

    /**
     * Touch-driven paint. A per-point coroutine launch would race the
     * ACTION_UP callback and drop tail points on a fast swipe.
     * `paintBegin` / `paintPoint` are cheap (Vec::push through a small
     * mutex on the Rust side), so we run them synchronously in the
     * caller's thread; ordering is preserved by call order. `paintEnd`
     * takes the physics-side sim mutex (paint_stroke recomputes the
     * u_edge patch), so it goes to Default to avoid stalling the touch
     * callback.
     */
    fun paintBegin(sign: Double) {
        if (handle == 0L) return
        CoulombNative.nativeSimPaintBegin(handle, sign)
    }

    fun paintPoint(x: Double, y: Double, timestamp: Double) {
        if (handle == 0L) return
        CoulombNative.nativeSimPaintStrokePoint(handle, x, y, timestamp)
    }

    fun paintEnd() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimPaintEnd(handle)
        }
    }

    /**
     * Pop the most recent stroke. The physics state the gas settled into
     * between paint and undo stays - only the stroke's charge and painted-
     * block flags are reverted, matching the desktop `undo_stroke` contract.
     */
    fun undo() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimUndo(handle)
        }
    }

    /**
     * Captain-level destructive op: discard every painted stroke. Loops
     * `undo_stroke` on the native side; the caller is responsible for
     * confirming via a dialog per captain destructive-action rule.
     */
    fun clearPaint() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimClearPaint(handle)
        }
    }

    /**
     * Advance one iteration while the sim is otherwise paused. Pairs with
     * the Freeze/Resume toggle in the top bar: the artist freezes to paint
     * without physics advancing, then steps the annealer one iteration at a
     * time to watch the response.
     */
    fun step() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimStep(handle)
        }
    }

    /**
     * Encode the current sim as a PNG byte array. Null on failure (no handle,
     * encoder error). The caller writes it through SAF; nothing on the Rust
     * side touches the filesystem.
     */
    suspend fun snapshotPng(): ByteArray? {
        if (handle == 0L) return null
        return withContext(Dispatchers.Default) {
            CoulombNative.nativeSimSnapshotPng(handle)
        }
    }

    /**
     * Rebuild the canvas from captain-chosen geometry and charge sign. Used by
     * the New Canvas dialog's Create button. Params (brush, temperature, …)
     * carry across because the native call keeps the current `Params` fields
     * other than the ones named here; the Kotlin mirror is kept in sync
     * through `pushParamsToNative`.
     */
    fun recreate(h: Int, w: Int, seed: Long, nParticles: Int, chargeSign: Int) {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            val ok = CoulombNative.nativeSimRecreate(
                handle,
                h.toLong(),
                w.toLong(),
                seed,
                nParticles.toLong(),
                chargeSign,
            )
            if (ok) {
                _lattice.value = maxOf(h, w)
                // Mirror the native-side charge sign flip into the Kotlin
                // ParamsSnapshot BEFORE pushParamsToNative, otherwise the
                // immediate push would overwrite the sign we just set.
                applyChargeSignToSnapshot(chargeSign)
                pushBrushToNative()
                pushParamsToNative()
                // The lattice shape just changed, so a view-mode viewport
                // from before the recreate would address cells that no
                // longer exist. Reset so the next render shows the whole
                // new lattice; the artist can zoom back in from there.
                resetViewport()
                onAdapterInfoRefresh()
            }
        }
    }

    /**
     * Rebuild the canvas from a user-uploaded grayscale coverage image. `cov`
     * is `h * w` bytes, 0..=255. The caller is responsible for decoding the
     * picked bitmap and down/up-sampling to the target lattice.
     */
    fun rebuildWithCoverage(
        h: Int,
        w: Int,
        seed: Long,
        nParticles: Int,
        chargeSign: Int,
        cov: ByteArray,
    ) {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            val ok = CoulombNative.nativeSimRebuildWithCoverage(
                handle,
                h.toLong(),
                w.toLong(),
                seed,
                nParticles.toLong(),
                chargeSign,
                cov,
            )
            if (ok) {
                _lattice.value = maxOf(h, w)
                applyChargeSignToSnapshot(chargeSign)
                pushBrushToNative()
                pushParamsToNative()
                resetViewport()
                onAdapterInfoRefresh()
            }
        }
    }

    /**
     * Mirror the native-side `apply_charge_sign` into the Kotlin
     * `ParamsSnapshot` so the drawer's charge-per-particle row and the next
     * `pushParamsToNative` agree with the sim's actual sign. `0` keeps the
     * current sign to match the Rust-side default.
     */
    private fun applyChargeSignToSnapshot(sign: Int) {
        val cur = _params.value.charge
        val mag = kotlin.math.abs(cur)
        val next = when {
            sign > 0 -> mag
            sign < 0 -> -mag
            else -> cur
        }
        if (next != cur) {
            _params.value = _params.value.setByName("charge", next)
        }
    }

    /**
     * Reset the sim to a fresh blank at the current params. The
     * `nativeSimLoadPreset("blank")` path in `coulomb-jni` rebuilds a
     * Sim with the current Params via `Sim::new_blank`, which gives the
     * user a fresh charge arrangement without discarding their tuning.
     */
    fun resetCanvas() {
        loadPreset("blank")
    }

    /**
     * Swap the sim to a named preset the JNI `nativeSimLoadPreset` recognises
     * (`blank`, `wire_mesh`, …). The current brush and params are pushed back
     * onto the fresh sim so a preset switch does not silently reset the
     * artist's tuning.
     */
    fun loadPreset(name: String) {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimLoadPreset(handle, name)
            pushBrushToNative()
            pushParamsToNative()
            // Reset the viewport so a preset reload (which can swap the
            // lattice dims under the artist, e.g. wire_mesh -> blank) does
            // not leave the renderer looking at coordinates that no longer
            // exist. `pushViewportToNative` fires inside `resetViewport`.
            resetViewport()
            onAdapterInfoRefresh()
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

    /**
     * Update the artist's brush and push every field into the native handle.
     *
     * Previously this only updated the Kotlin state; the native side kept
     * using `Brush::default()` for every stroke, which the captain hit on
     * the S24 as "the brush creates fat lines no matter what I try."
     * Pushing on every change means a slider drag is reflected on the very
     * next `ACTION_DOWN`.
     */
    fun setBrush(b: BrushSettings) {
        _brush.value = b
        if (handle != 0L) {
            CoulombNative.nativeSimSetBrush(
                handle,
                b.magnitude,
                b.density,
                b.thickness,
                b.flow,
                b.hardness,
                b.penetrability,
                b.coupling,
                b.target.code,
            )
        }
    }

    /**
     * Push the current brush shape after the native handle is (re)created.
     * Called from `ensureCreated` and after a reset so the very first stroke
     * uses the artist's values, not `Brush::default()`.
     */
    private fun pushBrushToNative() {
        if (handle == 0L) return
        val b = _brush.value
        CoulombNative.nativeSimSetBrush(
            handle,
            b.magnitude,
            b.density,
            b.thickness,
            b.flow,
            b.hardness,
            b.penetrability,
            b.coupling,
            b.target.code,
        )
    }

    /**
     * Hot-probe the sim with ~60 uphill-move samples at effectively infinite
     * T, then set the simulation's temperature so about 60% of uphill moves
     * are accepted. Native entry: `nativeSimAutoTemperature`. Returns the new
     * temperature in Kelvin, or `null` on failure.
     */
    fun autoTemperature(target: Double = 0.6, samples: Long = 60L): Double? {
        if (handle == 0L) return null
        val t = CoulombNative.nativeSimAutoTemperature(handle, target, samples)
        if (!t.isFinite() || t <= 0.0) return null
        // Mirror what the native side already wrote into Params onto our
        // snapshot so the drawer slider follows the Auto-T decision.
        _params.value = _params.value.copy(temperature = t)
        return t
    }

    /**
     * Reseed the mobile gas uniformly across the free cells; the painted
     * layer stays in place. Native entry: `nativeSimAddUniformCharges`.
     */
    fun reseedMobileGas() {
        if (handle == 0L) return
        viewModelScope.launch(Dispatchers.Default) {
            CoulombNative.nativeSimAddUniformCharges(handle)
        }
    }

    /**
     * Push every live-mutable parameter into the native handle. Called after
     * `nativeSimCreate` so a persisted `ParamsSnapshot` from a previous
     * session survives the app restart, and after a `Reset canvas` so the
     * fresh `Sim` keeps the artist's tuning.
     */
    private fun pushParamsToNative() {
        if (handle == 0L) return
        val p = _params.value
        pushOne("temperature", p.temperature)
        pushOne("charge", p.charge)
        pushOne("fill", p.fill)
        pushOne("strength", p.strength)
        pushOne("screening", p.screening)
        pushOne("cutoff", p.cutoff)
        pushOne("attract_depth", p.attractDepth)
        pushOne("attract_range", p.attractRange)
        pushOne("batch", p.batch.toDouble())
        pushOne("batch_min", p.batchMin.toDouble())
        pushOne("batch_decrement", p.batchDecrement.toDouble())
        pushOne("fail_limit", p.failLimit.toDouble())
        pushOne("step_size", p.stepSize.toDouble())
        pushOne("periodic", if (p.periodic) 1.0 else 0.0)
        pushOne("cooling", if (p.coolingActive) 1.0 else 0.0)
        pushOne("auto_cools", if (p.autoCools) 1.0 else 0.0)
        pushOne("cooling_rate", p.coolingRate)
        pushOne("schedule", if (p.schedule == "cosine") 1.0 else 0.0)
        pushOne("reheat_amp", p.reheatAmp)
        pushOne("reheat_period", p.reheatPeriod.toDouble())
        pushOne("reheat_decay", p.reheatDecay)
    }

    private fun pushOne(key: String, value: Double) {
        CoulombNative.nativeSimSetParam(handle, key, value)
    }

    /**
     * Push the current viewport to the native renderer. Called after
     * `nativeSimBindSurface` (via [onAdapterInfoRefresh] from the physics
     * surface), after a reset or preset reload, and whenever the user
     * mutates the viewport through [applyViewGesture] / [setViewport] /
     * [resetViewport].
     */
    fun pushViewportToNative() {
        if (handle == 0L) return
        val v = _viewport.value
        CoulombNative.nativeSimSetViewport(handle, v.x, v.y, v.w, v.h)
    }

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
        persistenceJob?.cancel()
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

/**
 * View-mode viewport in lattice cells. The renderer draws the `(x, y, w, h)`
 * sub-rectangle of the lattice into its on-screen letterbox rect; a zero or
 * negative `w`/`h` means "render the whole lattice" (the default). A
 * non-trivial viewport is the result of pinch-zoom or two-finger pan while
 * in View mode.
 */
data class Viewport(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 0.0,
    val h: Double = 0.0,
) {
    fun isFull(): Boolean = w <= 0.0 || h <= 0.0

    /**
     * Return a viewport whose `w`/`h` are non-zero. A full-lattice viewport
     * widens to `(0, 0, lattice, lattice)` so gesture math can treat the
     * zoomed and un-zoomed cases uniformly without a nullable branch at
     * every multiply.
     */
    fun ensureSized(lattice: Double): Viewport =
        if (isFull()) Viewport(0.0, 0.0, lattice, lattice) else this
}

enum class Mode { Paint, Heat, View }

/**
 * Which layer a stroke writes into. Mirrors `coulomb-core::BrushTarget`; the
 * JNI's integer encoding (`code`) is the one the Rust side expects.
 */
enum class BrushTarget(val code: Long) { Fixed(0L), Mobile(1L) }

data class BrushSettings(
    val sign: Double = 1.0,
    val magnitude: Double = 4.0,
    val density: Double = 1.0,
    val thickness: Double = 10.0,
    val flow: Double = 1.0,
    val hardness: Double = 0.5,
    val penetrability: Double = 1.0,
    val coupling: Double = 12.0,
    val target: BrushTarget = BrushTarget.Fixed,
)

/**
 * Mirror of the params surface for the accordion drawer. Live-mutated fields
 * are pushed through nativeSimSetParam; the rest are decorative in M3a and
 * become authoritative in M4 when the drawer wires up.
 */
data class ParamsSnapshot(
    val resolution: Int = 512,
    val lineDensity: Double = 1.0,
    // Periodic boundary on by default so a painted charge on the far
    // left influences the far right; matches the Rust-side Params
    // override in nativeSimCreate.
    val periodic: Boolean = true,
    // Matches the default `nativeSimCreate` density (40_000 / (512 * 512))
    // so the first Reset reproduces the initial gas; the Rust-side default
    // is 0.30 (Python-matching) but is overridden on create.
    val fill: Double = 40_000.0 / (512.0 * 512.0),
    val temperature: Double = 5000.0,
    val strength: Double = 1.0,
    val screening: Double = 0.0,
    val cutoff: Double = 12.0,
    val charge: Double = 1.0,
    val attractDepth: Double = 0.0,
    val attractRange: Double = 1.5,
    val coolingActive: Boolean = false,
    val autoCools: Boolean = true,
    val schedule: String = "geometric",
    val coolingRate: Double = 0.97,
    val reheatAmp: Double = 1.0,
    val reheatPeriod: Int = 20_000,
    val reheatDecay: Double = 0.94,
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
        "fill" -> copy(fill = value.coerceIn(0.0, 1.0))
        "attract_depth" -> copy(attractDepth = value)
        "attract_range" -> copy(attractRange = value)
        "batch" -> copy(batch = value.toInt().coerceAtLeast(1))
        "batch_min" -> copy(batchMin = value.toInt().coerceAtLeast(1))
        "batch_decrement" -> copy(batchDecrement = value.toInt().coerceAtLeast(1))
        "fail_limit" -> copy(failLimit = value.toInt().coerceAtLeast(1))
        "step_size" -> copy(stepSize = value.toInt().coerceAtLeast(1))
        "periodic" -> copy(periodic = value != 0.0)
        "cooling" -> copy(coolingActive = value != 0.0)
        "auto_cools" -> copy(autoCools = value != 0.0)
        "cooling_rate" -> copy(coolingRate = value.coerceIn(1e-6, 1.0))
        "schedule" -> copy(schedule = if (value != 0.0) "cosine" else "geometric")
        "reheat_amp" -> copy(reheatAmp = value.coerceAtLeast(0.0))
        "reheat_period" -> copy(reheatPeriod = value.toInt().coerceAtLeast(0))
        "reheat_decay" -> copy(reheatDecay = value.coerceIn(1e-6, 1.0))
        else -> this
    }
}
