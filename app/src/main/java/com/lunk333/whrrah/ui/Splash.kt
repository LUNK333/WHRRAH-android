package com.lunk333.whrrah.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lunk333.whrrah.R
import kotlinx.coroutines.delay

const val APP_TITLE = "We Have RaceRender At pHone"

/**
 * Continues the system launch screen (Android 12+ shows only the icon, on the same dark background —
 * values-v31/themes.xml): the logo in the same place, with the title fading in below it, then the
 * whole thing fades away. Shown on a cold start only.
 */
@Composable
fun LaunchSplash(show: Boolean) {
    var visible by remember { mutableStateOf(show) }
    var title by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!show) return@LaunchedEffect
        title = true
        delay(1300)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(400))) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
            // The system splash draws the icon's 108 dp canvas at 288 dp, centred.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(288.dp))
            AnimatedVisibility(title, Modifier.offset(y = 120.dp), enter = fadeIn(tween(350))) {
                Text(APP_TITLE, color = Color.White, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
