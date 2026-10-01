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
    /**
     * Replace the sim in place with a fresh blank canvas at the given lattice
     * geometry, particle count, and charge sign. The renderer slot stays
     * bound; the next render frame reads the new sim state. See the Rust
     * `nativeSimRecreate` doc comment for the `chargeSign` encoding (+1
     * positive, -1 negative, 0 keep previous).
     */
    external fun nativeSimRecreate(
        handle: Long,
        h: Long,
        w: Long,
        seed: Long,
        nParticles: Long,
        chargeSign: Int,
    ): Boolean
    /**
     * Replace the sim in place with a canvas built from a grayscale coverage
     * image. `cov` is `h * w` bytes, each 0..=255: 0 = clear, 255 = full line
     * charge. Decoded on the Kotlin side via `Bitmap.getPixel` + luminance so
     * the Rust side stays image-codec-free.
     */
    external fun nativeSimRebuildWithCoverage(
        handle: Long,
        h: Long,
        w: Long,
        seed: Long,
        nParticles: Long,
        chargeSign: Int,
        cov: ByteArray,
    ): Boolean

    // ---- frame ----
    external fun nativeSimTick(handle: Long, iterations: Long): Long
    /**
     * Advance the sim by exactly one iteration, regardless of pause state.
     * Used by the top-bar Step button paired with Freeze.
     */
    external fun nativeSimStep(handle: Long): Long
    external fun nativeSimFrameTextureHandle(handle: Long): Long

    // ---- backend selection (M3c GPU wire-up) ----
    /**
     * Choose the physics engine. See [Backend] for the enum values.
     * Returns the backend actually in use after the call: a requested GPU
     * switch that fails adapter negotiation returns [Backend.Cpu] and the
     * reason is retrievable via [nativeSimBackendFallbackMessage] - the UI
     * reads that once and shows a snackbar.
     */
    external fun nativeSimSetBackend(handle: Long, backendId: Int): Int

    /** Report the backend currently running `nativeSimTick`. */
    external fun nativeSimGetBackend(handle: Long): Int

    /**
     * One-shot read of the last GPU fallback reason. Returns an empty
     * string when there is nothing pending (first call after a successful
     * switch, or after the previous read cleared it).
     */
    external fun nativeSimBackendFallbackMessage(handle: Long): String?

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
     * Set the view-mode viewport in lattice cells. The renderer draws the
     * requested sub-rect of the lattice into its on-screen letterbox rect;
     * (0.0, 0.0, 0.0, 0.0) means "render the whole lattice". Values are
     * clamped against the current lattice on the Rust side so a stale rect
     * never samples out of bounds.
     */
    external fun nativeSimSetViewport(handle: Long, x: Double, y: Double, w: Double, h: Double)
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
    /**
     * Push the artist's current brush shape into the native side so the next
     * `nativeSimPaintBegin` snapshot picks it up. Without this the physics
     * kernel would fall back to `Brush::default()` (the fat-line default)
     * regardless of what the user set on the slider - the M3b bug the captain
     * called out on the S24.
     *
     * `target` is 0 for Fixed, 1 for Mobile. The raw-f64 setter cannot carry
     * an enum; the integer encoding is also what `nativeSimSetBrush` in Rust
     * expects.
     */
    external fun nativeSimSetBrush(
        handle: Long,
        magnitude: Double,
        density: Double,
        thickness: Double,
        flow: Double,
        hardness: Double,
        penetrability: Double,
        coupling: Double,
        target: Long,
    )

    // ---- physics shortcuts (ported from the Python desktop reference) ----
    /**
     * Probe the sim at effectively infinite T, measure uphill moves, and set
     * the simulation's temperature so a typical uphill move is accepted with
     * `target` probability. Returns the chosen temperature in Kelvin.
     */
    external fun nativeSimAutoTemperature(handle: Long, target: Double, samples: Long): Double
    /** Re-seed the mobile gas uniformly over free cells; the paint layer is kept. */
    external fun nativeSimAddUniformCharges(handle: Long)

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
    /**
     * Mirrors `enum Backend` in `rust/coulomb-jni/src/lib.rs`. The IDs must
     * stay in lock-step: the native side casts the i32 directly, so a reorder
     * on either side silently flips the engine selected.
     */
    enum class Backend(val id: Int) {
        Cpu(0),
        Gpu(1);

        companion object {
            fun fromId(id: Int): Backend = entries.firstOrNull { it.id == id } ?: Cpu
        }
    }

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
