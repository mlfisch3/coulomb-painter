package com.coulombpainter

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence of [BrushSettings], [ParamsSnapshot], and the running flag.
 *
 * The m3c PR body claimed these survived app updates, but no code wrote to
 * SharedPreferences, DataStore, or SavedStateHandle; a low-memory kill or
 * update reset every slider to its hard-coded default. Captain rule in
 * `data/captain.md`: parameter settings must be preserved across app updates
 * and across reset/new-canvas actions where feasible.
 *
 * JSON is used because it keeps the schema inside the data: a Rust-side
 * param rename or a Kotlin-side field addition does not drop the whole
 * payload - the loader skips what it does not recognise and the writer
 * emits whatever the current data class holds.
 */
private val Context.coulombSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "coulomb_settings"
)

object SettingsStore {
    private const val TAG = "CoulombSettings"
    private val KEY_BRUSH = stringPreferencesKey("brush_json")
    private val KEY_PARAMS = stringPreferencesKey("params_json")
    private val KEY_RUNNING = booleanPreferencesKey("running")

    data class Loaded(
        val brush: BrushSettings,
        val params: ParamsSnapshot,
        val running: Boolean,
    )

    private fun ds(context: Context): DataStore<Preferences> =
        context.applicationContext.coulombSettingsDataStore

    /**
     * Synchronous load at app start. The DataStore first-read is bounded
     * (one disk-backed flow emission); doing it under runBlocking inside
     * `onCreate` keeps the restore path linear and avoids a first-frame
     * flash of default sliders before the saved snapshot lands.
     */
    fun loadBlocking(context: Context): Loaded {
        val fallback = Loaded(BrushSettings(), ParamsSnapshot(), running = true)
        return try {
            runBlocking {
                val prefs = ds(context).data.first()
                val brush = prefs[KEY_BRUSH]?.let { brushFromJson(it) } ?: fallback.brush
                val params = prefs[KEY_PARAMS]?.let { paramsFromJson(it) } ?: fallback.params
                val running = prefs[KEY_RUNNING] ?: fallback.running
                Loaded(brush, params, running)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed, using defaults", t)
            fallback
        }
    }

    suspend fun saveBrush(context: Context, brush: BrushSettings) {
        try {
            ds(context).edit { it[KEY_BRUSH] = brushToJson(brush).toString() }
        } catch (t: Throwable) {
            Log.w(TAG, "save brush failed", t)
        }
    }

    suspend fun saveParams(context: Context, params: ParamsSnapshot) {
        try {
            ds(context).edit { it[KEY_PARAMS] = paramsToJson(params).toString() }
        } catch (t: Throwable) {
            Log.w(TAG, "save params failed", t)
        }
    }

    suspend fun saveRunning(context: Context, running: Boolean) {
        try {
            ds(context).edit { it[KEY_RUNNING] = running }
        } catch (t: Throwable) {
            Log.w(TAG, "save running failed", t)
        }
    }

    // ---- json ----

    private fun brushToJson(b: BrushSettings): JSONObject = JSONObject().apply {
        put("sign", b.sign)
        put("magnitude", b.magnitude)
        put("density", b.density)
        put("thickness", b.thickness)
        put("flow", b.flow)
        put("hardness", b.hardness)
        put("penetrability", b.penetrability)
        put("coupling", b.coupling)
    }

    private fun brushFromJson(src: String): BrushSettings? = try {
        val o = JSONObject(src)
        val d = BrushSettings()
        BrushSettings(
            sign = o.optDouble("sign", d.sign),
            magnitude = o.optDouble("magnitude", d.magnitude),
            density = o.optDouble("density", d.density),
            thickness = o.optDouble("thickness", d.thickness),
            flow = o.optDouble("flow", d.flow),
            hardness = o.optDouble("hardness", d.hardness),
            penetrability = o.optDouble("penetrability", d.penetrability),
            coupling = o.optDouble("coupling", d.coupling),
        )
    } catch (e: JSONException) {
        Log.w(TAG, "brush json malformed: ${e.message}")
        null
    }

    private fun paramsToJson(p: ParamsSnapshot): JSONObject = JSONObject().apply {
        put("resolution", p.resolution)
        put("lineDensity", p.lineDensity)
        put("periodic", p.periodic)
        put("fill", p.fill)
        put("temperature", p.temperature)
        put("strength", p.strength)
        put("screening", p.screening)
        put("cutoff", p.cutoff)
        put("charge", p.charge)
        put("attractDepth", p.attractDepth)
        put("attractRange", p.attractRange)
        put("coolingActive", p.coolingActive)
        put("schedule", p.schedule)
        put("coolingRate", p.coolingRate)
        put("batch", p.batch)
        put("batchMin", p.batchMin)
        put("batchDecrement", p.batchDecrement)
        put("failLimit", p.failLimit)
        put("stepSize", p.stepSize)
    }

    private fun paramsFromJson(src: String): ParamsSnapshot? = try {
        val o = JSONObject(src)
        val d = ParamsSnapshot()
        ParamsSnapshot(
            resolution = o.optInt("resolution", d.resolution),
            lineDensity = o.optDouble("lineDensity", d.lineDensity),
            periodic = o.optBoolean("periodic", d.periodic),
            fill = o.optDouble("fill", d.fill),
            temperature = o.optDouble("temperature", d.temperature),
            strength = o.optDouble("strength", d.strength),
            screening = o.optDouble("screening", d.screening),
            cutoff = o.optDouble("cutoff", d.cutoff),
            charge = o.optDouble("charge", d.charge),
            attractDepth = o.optDouble("attractDepth", d.attractDepth),
            attractRange = o.optDouble("attractRange", d.attractRange),
            coolingActive = o.optBoolean("coolingActive", d.coolingActive),
            schedule = o.optString("schedule", d.schedule),
            coolingRate = o.optDouble("coolingRate", d.coolingRate),
            batch = o.optInt("batch", d.batch),
            batchMin = o.optInt("batchMin", d.batchMin),
            batchDecrement = o.optInt("batchDecrement", d.batchDecrement),
            failLimit = o.optInt("failLimit", d.failLimit),
            stepSize = o.optInt("stepSize", d.stepSize),
        )
    } catch (e: JSONException) {
        Log.w(TAG, "params json malformed: ${e.message}")
        null
    }
}
