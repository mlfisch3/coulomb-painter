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
import com.coulombpainter.Viewport
import kotlin.math.hypot
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
 * Layout contract (M6 fix): callers wrap this composable inside a Column
 * that puts the top bar / stats bar / bottom bar above and below it, and
 * pass `Modifier.fillMaxSize()` here. The SurfaceView then only occupies
 * the painting area, so Compose chrome never clips the SurfaceView's touch
 * region. The lattice is still letterboxed inside this surface by the
 * fragment shader ([computeDstRect] mirrors the Rust side) so a round
 * brush stroke in surface pixels maps to a round brush stroke in lattice
 * cells (H1), and view-mode pinch + two-finger pan push a sub-rect of the
 * lattice through [SimViewModel.applyViewGesture] (H11).
 *
 * Why SurfaceView (not AndroidExternalSurface):
 *  - AndroidExternalSurface's `onChanged` / `onDestroyed` shape is
 *    version-sensitive across the Compose 1.7 stack and its callbacks fire
 *    on a background coroutine, so `sim_bind_surface` -> `render_frame`
 *    ordering costs a manual synchronisation. SurfaceView's holder
 *    callbacks are `surfaceCreated` / `surfaceChanged` / `surfaceDestroyed`
 *    on the main thread, which lines up 1:1 with the wgpu Surface's own
 *    creation/resize/drop rhythm.
 */
@Composable
fun PhysicsSurface(
    vm: SimViewModel,
    lattice: Int,
    touchEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val renderJobRef = remember { object { var job: Job? = null } }
    val touchEnabledRef = remember { object { var v: Boolean = true } }
    touchEnabledRef.v = touchEnabled
    // View-mode gesture baseline. Reset on every pointer-down/up so that a
    // lifted-then-replaced second finger does not jolt the viewport by the
    // gap distance between the lift and the re-touch.
    val gesture = remember { GestureState() }

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
                        // Firstmate bug #2: when the drawer is open, refuse
                        // the touch so the dismiss-tap does not also paint.
                        // The next tap after the drawer closes paints.
                        if (!touchEnabledRef.v) return@setOnTouchListener false
                        handleTouch(vm, view, event, lattice, gesture)
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
                            // A fresh wgpu Surface starts with no viewport
                            // set, so re-push whatever the VM holds (so a
                            // user zoom survives a surface recreate on
                            // orientation change, app resume, etc.).
                            vm.pushViewportToNative()
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
                            gesture.reset()
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

private class GestureState {
    var prevDist: Float = 0f
    var prevMidX: Float = 0f
    var prevMidY: Float = 0f
    var active: Boolean = false

    fun reset() {
        prevDist = 0f
        prevMidX = 0f
        prevMidY = 0f
        active = false
    }
}

private fun handleTouch(
    vm: SimViewModel,
    view: View,
    event: MotionEvent,
    lattice: Int,
    gesture: GestureState,
): Boolean {
    val mode = vm.modeSnapshot()
    val viewport = vm.viewport.value
    if (mode == Mode.View) {
        return handleViewGesture(vm, view, event, lattice, viewport, gesture)
    }
    val sign = if (mode == Mode.Heat) 0.0 else vm.brushSignSnapshot()
    if (vm.handle == 0L) return false
    val t = event.eventTime.toDouble()
    val (lx, ly, inside) = screenToLattice(
        event.x, event.y, view.width, view.height, lattice, viewport,
    )
    // A tap on the letterbox bar (outside the dst rect) must not start a
    // stroke; otherwise the paint point lands at a clamped lattice edge
    // and the user sees a surprise stripe along the border.
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (!inside) return false
            vm.paintBegin(sign)
            vm.paintPoint(lx, ly, t)
        }
        MotionEvent.ACTION_MOVE -> {
            // Push every intermediate sample too, so a fast swipe leaves a
            // continuous stroke instead of the endpoints only.
            val n = event.historySize
            for (i in 0 until n) {
                val (hx, hy, _) = screenToLattice(
                    event.getHistoricalX(i),
                    event.getHistoricalY(i),
                    view.width,
                    view.height,
                    lattice,
                    viewport,
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

private fun handleViewGesture(
    vm: SimViewModel,
    view: View,
    event: MotionEvent,
    lattice: Int,
    viewport: Viewport,
    g: GestureState,
): Boolean {
    when (event.actionMasked) {
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
            if (event.pointerCount >= 2) {
                g.prevDist = pointerDistance(event, 0, 1)
                g.prevMidX = (event.getX(0) + event.getX(1)) * 0.5f
                g.prevMidY = (event.getY(0) + event.getY(1)) * 0.5f
                g.active = g.prevDist > 1f
            }
        }
        MotionEvent.ACTION_MOVE -> {
            if (event.pointerCount >= 2 && g.active) {
                val dist = pointerDistance(event, 0, 1)
                val midX = (event.getX(0) + event.getX(1)) * 0.5f
                val midY = (event.getY(0) + event.getY(1)) * 0.5f
                val zoom = if (g.prevDist > 1f) (dist / g.prevDist).toDouble() else 1.0
                // Convert the midpoint pixel delta to lattice cells using the
                // current letterbox dst rect; a horizontal pan should move
                // the viewport by the same number of lattice cells
                // regardless of zoom level.
                val dst = computeDstRect(view.width, view.height, lattice, lattice)
                val src = viewport.ensureSized(lattice.toDouble())
                val dxPx = (midX - g.prevMidX).toDouble()
                val dyPx = (midY - g.prevMidY).toDouble()
                // Pan content under the fingers: a right drag moves the
                // viewport to the left so content follows the finger.
                val panLatX = if (dst[2] > 0f) -dxPx * src.w / dst[2].toDouble() else 0.0
                val panLatY = if (dst[3] > 0f) -dyPx * src.h / dst[3].toDouble() else 0.0
                vm.applyViewGesture(panLatX, panLatY, zoom)
                g.prevDist = dist
                g.prevMidX = midX
                g.prevMidY = midY
            }
        }
        MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            // Reindex to the pointers that remain active (excluding the one
            // leaving on ACTION_POINTER_UP) so a lifted-then-replaced finger
            // does not jolt the viewport by the gap distance.
            val gone = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP)
                event.actionIndex else -1
            val (i0, i1) = pickTwo(event, gone)
            if (i0 >= 0 && i1 >= 0) {
                g.prevDist = pointerDistance(event, i0, i1)
                g.prevMidX = (event.getX(i0) + event.getX(i1)) * 0.5f
                g.prevMidY = (event.getY(i0) + event.getY(i1)) * 0.5f
                g.active = g.prevDist > 1f
            } else {
                g.reset()
            }
        }
    }
    return true
}

private fun pointerDistance(event: MotionEvent, i0: Int, i1: Int): Float {
    val dx = event.getX(i0) - event.getX(i1)
    val dy = event.getY(i0) - event.getY(i1)
    return hypot(dx, dy)
}

private fun pickTwo(event: MotionEvent, exclude: Int): Pair<Int, Int> {
    var i0 = -1
    var i1 = -1
    for (i in 0 until event.pointerCount) {
        if (i == exclude) continue
        if (i0 < 0) i0 = i
        else if (i1 < 0) { i1 = i; break }
    }
    return i0 to i1
}

/**
 * The on-surface rectangle (x, y, w, h in surface pixels) the lattice is
 * drawn into. Mirrors `compute_dst_rect` in `rust/coulomb-jni/src/renderer.rs`
 * so the touch inverse lines up with the shader's forward sampling.
 */
private fun computeDstRect(surfW: Int, surfH: Int, latticeW: Int, latticeH: Int): FloatArray {
    if (surfW <= 0 || surfH <= 0 || latticeW <= 0 || latticeH <= 0) {
        return floatArrayOf(0f, 0f, 0f, 0f)
    }
    val sw = surfW.toFloat()
    val sh = surfH.toFloat()
    val lw = latticeW.toFloat()
    val lh = latticeH.toFloat()
    val surfAspect = sw / sh
    val latAspect = lw / lh
    return if (surfAspect > latAspect) {
        val dw = sh * latAspect
        val dx = (sw - dw) * 0.5f
        floatArrayOf(dx, 0f, dw, sh)
    } else {
        val dh = sw / latAspect
        val dy = (sh - dh) * 0.5f
        floatArrayOf(0f, dy, sw, dh)
    }
}

/**
 * Convert a touch position (SurfaceView pixel space) to lattice cells. The
 * conversion inverts the shader's sampling: fragment pixel -> dst rect ->
 * src rect -> lattice cell. Returns `(lx, ly, insideDst)`; the inside flag
 * is false when the touch landed on a letterbox bar, so paint refuses to
 * open a stroke outside the drawn image.
 */
private fun screenToLattice(
    px: Float,
    py: Float,
    canvasW: Int,
    canvasH: Int,
    lattice: Int,
    viewport: Viewport,
): Triple<Double, Double, Boolean> {
    if (canvasW <= 0 || canvasH <= 0 || lattice <= 0) return Triple(0.0, 0.0, false)
    val dst = computeDstRect(canvasW, canvasH, lattice, lattice)
    val dw = dst[2]
    val dh = dst[3]
    if (dw <= 0f || dh <= 0f) return Triple(0.0, 0.0, false)
    val tx = (px - dst[0]) / dw
    val ty = (py - dst[1]) / dh
    val src = viewport.ensureSized(lattice.toDouble())
    val lx = src.x + tx.toDouble() * src.w
    val ly = src.y + ty.toDouble() * src.h
    val clamp = lattice.toDouble() - 1e-6
    val inside = tx in 0f..1f && ty in 0f..1f
    return Triple(lx.coerceIn(0.0, clamp), ly.coerceIn(0.0, clamp), inside)
}
