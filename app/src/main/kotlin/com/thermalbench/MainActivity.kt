package com.thermalbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thermalbench.ui.navigation.ThermalBenchApp
import com.thermalbench.ui.theme.ThermalBenchTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The operator console. Optional by design: the harness runs without it, and
 * the numbers this activity displays were produced by the same engine.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ThermalBenchTheme {
                ThermalBenchApp()
            }
        }
    }
}
