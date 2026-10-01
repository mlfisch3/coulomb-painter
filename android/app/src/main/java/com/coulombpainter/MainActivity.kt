package com.coulombpainter

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.coulombpainter.ui.CoulombPainterApp
import com.coulombpainter.ui.theme.CoulombPainterTheme

class MainActivity : ComponentActivity() {
    private val simVm: SimViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Captain rule (data/captain.md): parameter settings preserved across
        // app updates and across reset/new-canvas actions. Restore must land
        // before ensureCreated so the pushBrushToNative / pushParamsToNative
        // calls inside ensureCreated carry the saved values straight into the
        // fresh handle rather than overwriting them with the hard-coded
        // defaults a cold start would otherwise use.
        val restored = SettingsStore.loadBlocking(this)
        simVm.restoreSettings(restored.brush, restored.params, restored.running)

        // 512x512 mid-range default per docs/android-plan.md §5. The seed is
        // fixed at 0 so a fresh launch always reproduces the same starting
        // arrangement; M4 exposes it through the New Canvas dialog.
        simVm.ensureCreated(h = 512, w = 512, seed = 0L, nParticles = 40_000)

        // Begin observing state flows so every subsequent brush / params /
        // running change writes back to DataStore. Started after
        // ensureCreated so the initial pushes above are not themselves
        // persisted (drop(1) is also in place for defence in depth).
        simVm.startPersistence(applicationContext)

        // Thermal telemetry: forecast headroom 10 s out. Available on API 30
        // (Android 11) and above; the app supports minSdk 26, so pre-30
        // devices see NaN and no logs. M3b observes only; M5 will feed the
        // signal into the physics substep count.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        simVm.startThermalObserver { forecastSeconds ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                powerManager.getThermalHeadroom(forecastSeconds)
            } else null
        }

        setContent {
            CoulombPainterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CoulombPainterApp(simVm)
                }
            }
        }
    }
}
