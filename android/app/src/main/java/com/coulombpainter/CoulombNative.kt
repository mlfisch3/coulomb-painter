package com.coulombpainter

import android.view.Surface

/**
 * The Kotlin-side face of `libcoulomb_jni.so`.
 *
 * Every `external fun native*` here maps to a `Java_com_coulombpainter_CoulombNative_*`
 * symbol in `rust/coulomb-jni/src/lib.rs`. The mapping is by name; a rename
 * on either side is a hard link error at first call, not a silent no-op.
 *
 * Threading: JNI calls are safe from any thread, but the Rust side takes a
 * single mutex per handle, so contention scales with tick cadence. The
 * intended pattern is one physics-loop thread calling `tick`, and the UI
 * thread calling `stats` a few times per second; both come through this
 * class.
 */
object CoulombNative {
    init {
        System.loadLibrary("coulomb_jni")
    }

    // ---- lifecycle ----
    external fun nativeSimCreate(h: Long, w: Long, seed: Long, nParticles: Long): Long
    external fun nativeSimDestroy(handle: Long)
    external fun nativeSimPause(handle: Long)
    external fun nativeSimResume(handle: Long)

    // ---- frame ----
    external fun nativeSimTick(handle: Long, iterations: Long): Long
    external fun nativeSimFrameTextureHandle(handle: Long): Long

    // ---- surface (M3b) ----
    /**
     * Bind an AndroidExternalSurface's Surface to this sim's wgpu renderer.
     * Returns true on success. See `rust/coulomb-jni/src/renderer.rs` for
     * the ANativeWindow_fromSurface -> wgpu::Surface path.
     */
    external fun nativeSimBindSurface(handle: Long, surface: Surface): Boolean
    external fun nativeSimUnbindSurface(handle: Long)
    external fun nativeSimSurfaceResize(handle: Long, w: Long, h: Long)
    external fun nativeSimRenderFrame(handle: Long)
    /**
     * A JSON payload with adapter identity - name, backend, driver,
     * device_type - so the diagnostic overlay can show what wgpu picked
     * on this specific device.
     */
    external fun nativeSimAdapterInfoJson(handle: Long): String?

    // ---- paint ----
    external fun nativeSimPaintBegin(handle: Long, sign: Double)
    external fun nativeSimPaintStrokePoint(handle: Long, x: Double, y: Double, t: Double)
    external fun nativeSimPaintEnd(handle: Long)
    external fun nativeSimUndo(handle: Long): Boolean
    external fun nativeSimClearPaint(handle: Long)

    // ---- params ----
    external fun nativeSimSetParam(handle: Long, key: String, value: Double)
    external fun nativeSimGetParam(handle: Long, key: String): Double
    external fun nativeSimLoadParamsJson(handle: Long, json: String): Boolean
    external fun nativeSimDumpParamsJson(handle: Long): String?

    // ---- presets + I/O ----
    external fun nativeSimLoadPreset(handle: Long, name: String): Boolean
    external fun nativeSimSnapshotPng(handle: Long): ByteArray?
    external fun nativeSimSaveCmb(handle: Long, path: String): Boolean
    external fun nativeSimLoadCmb(path: String): Long

    // ---- stats ----
    /**
     * Returns a `DoubleArray(9)` in the order documented in
     * `rust/coulomb-jni/src/lib.rs::nativeSimStats`. See [Stats.from].
     */
    external fun nativeSimStats(handle: Long): DoubleArray?

    /**
     * Idiomatic Kotlin projection of the flat stats array. The unpack lives
     * here so the field-index-to-name mapping is in exactly one place; a Rust
     * reorder shows up as a wrong field value in the UI on first read.
     */
    data class Stats(
        val iteration: Long,
        val particles: Long,
        val temperature: Double,
        val energy: Double,
        val acceptance: Double,
        val movesPerSecond: Double,
        val occupancy: Double,
        val energyDrop: Double,
        val batch: Long,
        val paintCells: Long,
    ) {
        companion object {
            fun from(arr: DoubleArray?): Stats? {
                if (arr == null || arr.size < 10) return null
                return Stats(
                    iteration = arr[0].toLong(),
                    particles = arr[1].toLong(),
                    temperature = arr[2],
                    energy = arr[3],
                    acceptance = arr[4],
                    movesPerSecond = arr[5],
                    occupancy = arr[6],
                    energyDrop = arr[7],
                    batch = arr[8].toLong(),
                    paintCells = arr[9].toLong(),
                )
            }
        }
    }
}
