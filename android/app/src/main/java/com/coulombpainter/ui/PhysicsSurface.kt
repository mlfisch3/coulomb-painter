package com.coulombpainter.ui

import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.coulombpainter.CoulombNative
import com.coulombpainter.Mode
import com.coulombpainter.SimViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PhysicsSurface"

/**
 * M3b canvas: a plain [SurfaceView] hosted via [AndroidView] so the
 * SurfaceHolder callbacks are on the main thread (matching wgpu's own
 * expectations for `create_surface_unsafe`), the Surface's lifetime is
 * bounded by the SurfaceView's, and touch is delivered directly to
 * `MotionEvent` where we own the coordinate math.
 *
 * Why SurfaceView (not AndroidExternalSurface):
 *  - AndroidExternalSurface's `onChanged` / `onDestroyed` shape is
 *    version-sensitive across the Compose 1.7 stack and its callbacks fire
 *    on a background coroutine, so `sim_bind_surface` -> `render_frame`
 *    ordering costs a manual synchronisation. SurfaceView's holder
 *    callbacks are `surfaceCreated` / `surfaceChanged` / `surfaceDestroyed`
 *    on the main thread, which lines up 1:1 with the wgpu Surface's own
 *    creation/resize/drop rhythm.
 *  - The Compose overlay (top bar, bottom bar, drawer, diagnostics) still
 *    composes on top of the SurfaceView with no z-order fight because
 *    SurfaceView z-orders below the Compose window by default (we do not
 *    call setZOrderOnTop / setZOrderMediaOverlay).
 *
 * Touch flow: MotionEvent -> screenToLattice -> nativeSimPaintBegin /
 * StrokePoint / End. Multi-touch (pinch, two-finger drag) is captured on
 * the state so the diagnostic overlay can reflect it, per M3b task #3.
 */
@Composable
fun PhysicsSurface(
    vm: SimViewModel,
    lattice: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val renderJobRef = remember { object { var job: Job? = null } }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // OnTouchListener wins over the SurfaceView's default
                    // (empty) touch behaviour, and stays live even when the
                    // Compose overlay above draws icons: the SurfaceView
                    // receives events for the canvas area, Compose sees the
                    // rest.
                    setOnTouchListener { view, event ->
                        handleTouch(vm, view, event, lattice)
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            val handle = vm.handle
                            if (handle == 0L) {
                                Log.w(TAG, "surfaceCreated before sim handle allocated")
                                return
                            }
                            val bound = CoulombNative.nativeSimBindSurface(handle, h.surface)
                            if (!bound) {
                                Log.w(TAG, "nativeSimBindSurface returned false")
                                return
                            }
                            vm.onAdapterInfoRefresh()
                            renderJobRef.job?.cancel()
                            // The render pass takes the sim mutex (to copy
                            // occupancy) and then blocks on `queue.submit +
                            // present`. Running that on the main thread
                            // races the physics tick loop for the mutex
                            // and ANRs the whole app; Default keeps the
                            // main thread free for touch and Compose.
                            renderJobRef.job = scope.launch(Dispatchers.Default) {
                                while (true) {
                                    CoulombNative.nativeSimRenderFrame(handle)
                                    vm.onFrameRendered()
                                    // A tiny delay yields to the scheduler
                                    // so a slow frame does not starve the
                                    // physics tick. The swapchain's
                                    // present_mode still caps FPS.
                                    delay(1L)
                                }
                            }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) {
                            val handle = vm.handle
                            if (handle != 0L) {
                                CoulombNative.nativeSimSurfaceResize(handle, w.toLong(), ht.toLong())
                            }
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            renderJobRef.job?.cancel()
                            renderJobRef.job = null
                            val handle = vm.handle
                            if (handle != 0L) {
                                CoulombNative.nativeSimUnbindSurface(handle)
                            }
                        }
                    })
                }
            },
            update = { /* nothing to update; the ViewModel drives everything */ },
        )
    }

    DisposableEffect(vm.handle) {
        onDispose {
            renderJobRef.job?.cancel()
            renderJobRef.job = null
        }
    }
}

private fun handleTouch(
    vm: SimViewModel,
    view: View,
    event: MotionEvent,
    lattice: Int,
): Boolean {
    val mode = vm.modeSnapshot()
    if (mode == Mode.View) {
        // View mode: pinch / two-finger drag. M3b captures the gesture on
        // the VM so the diagnostic overlay shows what a viewport uniform
        // would receive; the render pipeline still shows the whole lattice
        // because the shader has no viewport transform yet. Documented in
        // the PR body.
        if (event.pointerCount >= 2) {
            val zoom = event.getPointerId(0).let { 1.0f }
            vm.onGestureTransform(0f, 0f, zoom)
        }
        return true
    }
    val sign = if (mode == Mode.Heat) 0.0 else vm.brushSignSnapshot()
    if (vm.handle == 0L) return false
    val (lx, ly) = screenToLattice(event.x, event.y, view.width, view.height, lattice)
    val t = event.eventTime.toDouble()
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            vm.paintBegin(sign)
            vm.paintPoint(lx, ly, t)
        }
        MotionEvent.ACTION_MOVE -> {
            // Push every intermediate sample too, so a fast swipe leaves a
            // continuous stroke instead of the endpoints only.
            val n = event.historySize
            for (i in 0 until n) {
                val (hx, hy) = screenToLattice(
                    event.getHistoricalX(i),
                    event.getHistoricalY(i),
                    view.width,
                    view.height,
                    lattice,
                )
                vm.paintPoint(hx, hy, event.getHistoricalEventTime(i).toDouble())
            }
            vm.paintPoint(lx, ly, t)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            vm.paintPoint(lx, ly, t)
            vm.paintEnd()
        }
    }
    return true
}

// Convert a touch position (canvas-pixel space) to lattice cells. The
// canvas maps to the entire lattice, matching frameToLattice in
// projects/coulomb-brush/anneal_gui.py.
//
// Returns (lattice_x, lattice_y) as Doubles because the paint stroke API
// takes fractional lattice positions; the CPU core rounds internally.
private fun screenToLattice(
    px: Float,
    py: Float,
    canvasW: Int,
    canvasH: Int,
    lattice: Int,
): Pair<Double, Double> {
    if (canvasW <= 0 || canvasH <= 0) return 0.0 to 0.0
    val lx = (px.toDouble() / canvasW.toDouble()) * lattice.toDouble()
    val ly = (py.toDouble() / canvasH.toDouble()) * lattice.toDouble()
    val clamped = lattice.toDouble() - 1e-6
    return lx.coerceIn(0.0, clamped) to ly.coerceIn(0.0, clamped)
}
