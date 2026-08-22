package com.nirmalamdhanam.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nirmalamdhanam.data.local.TransactionEntity
import kotlin.math.max

@Composable
fun CoolDownTankCard(transaction: TransactionEntity, nowEpochMs: Long = System.currentTimeMillis(), onConfirm: (TransactionEntity) -> Unit, onDismiss: (TransactionEntity) -> Unit) {
    val context = LocalContext.current
    val hours = max(0, ((transaction.coolDownExpiryEpochMs ?: nowEpochMs) - nowEpochMs) / 3_600_000)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(transaction.merchant ?: "Pending purchase", style = MaterialTheme.typography.titleMedium)
            Text("⏳ ${hours}h left", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { context.confirmationHaptic(); onDismiss(transaction) }) { Text("Release") }
                Button(enabled = hours == 0L, onClick = { context.confirmationHaptic(); onConfirm(transaction) }) { Text("Confirm") }
            }
        }
    }
}
private fun Context.confirmationHaptic() { if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(25) }
