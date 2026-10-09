package com.nirmalamgroup.nirmalamdhanam.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import kotlin.math.ceil

@Composable
fun CoolDownTankCard(transaction: TransactionEntity, onConfirm: (TransactionEntity) -> Unit, onDismiss: (TransactionEntity) -> Unit) {
    val context = LocalContext.current
    val expiry = transaction.coolDownExpiryEpochMs
    var nowEpochMs by remember(transaction.id, expiry) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transaction.id, expiry) {
        while (expiry != null && nowEpochMs < expiry) {
            delay(minOf(60_000L, (expiry - nowEpochMs).coerceAtLeast(1L)))
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val coolingComplete = expiry == null || nowEpochMs >= expiry
    val remainingHours = if (coolingComplete || expiry == null) 0L else
        ceil((expiry - nowEpochMs) / 3_600_000.0).toLong()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(transaction.merchant ?: "Pending purchase", style = MaterialTheme.typography.titleMedium)
            Text(
                if (coolingComplete) "Ready to confirm" else "⏳ ${remainingHours}h left",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { context.confirmationHaptic(); onDismiss(transaction) }) { Text("Discard") }
                Button(enabled = coolingComplete, onClick = { context.confirmationHaptic(); onConfirm(transaction) }) { Text("Confirm") }
            }
        }
    }
}
private fun Context.confirmationHaptic() { if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(25) }
