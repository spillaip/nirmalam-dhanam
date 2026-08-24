package com.nirmalamgroup.nirmalamdhanam.ui.components

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AntiPanicPortfolioContainer(portfolioValuePaise: Long, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val guarded = modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (withTimeoutOrNull(5_000) { waitForUpOrCancellation() } == null) revealed = true
            waitForUpOrCancellation()
        }
    }.then(if (!revealed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.graphicsLayer { renderEffect = android.graphics.RenderEffect.createBlurEffect(20f, 20f, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect() } else Modifier)
        .drawWithContent { drawContent(); if (!revealed && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) drawRect(Color(0xD91A1C1E)) }
    Surface(modifier = guarded, tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp)) {
            Text("Anti-Panic Growth Portfolio", style = MaterialTheme.typography.titleMedium)
            Text("₹ %.2f".format(portfolioValuePaise / 100.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            content()
            if (revealed) Text("Daily fluctuations do not impact long-term compounding targets.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
