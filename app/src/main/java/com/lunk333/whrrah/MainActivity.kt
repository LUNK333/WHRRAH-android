package com.lunk333.whrrah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.lunk333.whrrah.ui.theme.WHRRAHTheme

// M0 placeholder: confirms the Compose + toolchain wiring is correct
// before any WHRRAH-specific work (CSV import, canvas, etc.) starts.
// See design doc §6 "First commands to run, end to end".
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WHRRAHTheme {
                Scaffold { innerPadding ->
                    Greeting(
                        name = "WHRRAH",
                        modifier = Modifier.fillMaxSize().padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    WHRRAHTheme {
        Greeting("WHRRAH")
    }
}
