package com.phoneagent

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.phoneagent.ui.PhoneAgentApp
import com.phoneagent.ui.theme.LocalThemeOverride
import com.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e("MainActivity", "enableEdgeToEdge failed", e)
        }
        setContent {
            val themeOverride = remember { mutableStateOf<Boolean?>(null) }
            CompositionLocalProvider(LocalThemeOverride provides themeOverride) {
                PhoneAgentTheme {
                    PhoneAgentApp()
                }
            }
        }
    }
}
