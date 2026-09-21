package com.coulombpainter

import android.os.Bundle
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

        // 512x512 mid-range default per docs/android-plan.md §5. The seed is
        // fixed at 0 so a fresh launch always reproduces the same starting
        // arrangement; M4 exposes it through the New Canvas dialog.
        simVm.ensureCreated(h = 512, w = 512, seed = 0L, nParticles = 40_000)

        setContent {
            CoulombPainterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CoulombPainterApp(simVm)
                }
            }
        }
    }
}
