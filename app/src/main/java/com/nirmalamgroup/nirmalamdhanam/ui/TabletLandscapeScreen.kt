package com.nirmalamgroup.nirmalamdhanam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nirmalamgroup.nirmalamdhanam.ui.components.AntiPanicPortfolioContainer
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle

@Composable
fun TabletLandscapeScreen(state: FinanceDashboardState, portfolioValuePaise: Long, onTankConfirm: (String) -> Unit, onTankDismiss: (String) -> Unit, modifier: Modifier = Modifier, onNeurodiverseModeChange: (Boolean) -> Unit = {}) {
    Row(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Today’s cashflow", style = MaterialTheme.typography.headlineSmall)
            ElevatedCard { Column(Modifier.padding(16.dp)) { Text("True available cash"); Text("₹ %.2f".format(state.cash.trueAvailableCashPaise / 100.0), style = MaterialTheme.typography.headlineMedium) } }
            if (state.neurodiverseModeEnabled) NeurodiverseDailyFocus(state.safeToSpendPaise)
            Text("Core envelopes", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (state.neurodiverseModeEnabled) 12.dp else 8.dp)) { items(if (state.neurodiverseModeEnabled) minOf(3, state.envelopes.size) else state.envelopes.size) { i -> val e = state.envelopes[i]; ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text("₹ %.2f daily".format(e.dailyLimitPaise / 100.0)) }) } }
            if (!state.neurodiverseModeEnabled) NumericEntryPad()
            NeurodiverseModeToggle(state.neurodiverseModeEnabled, onNeurodiverseModeChange)
        }
        Column(Modifier.weight(.65f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(18.dp)) { Text("Autonomy runway"); Text(state.autonomyDays?.let { "%.1f days".format(it) } ?: "Building your baseline", style = MaterialTheme.typography.headlineSmall) } }
            ElevatedCard { Column(Modifier.padding(16.dp)) { Text("Emergency vault"); Text("Protected for essential continuity") } }
            AntiPanicPortfolioContainer(portfolioValuePaise, Modifier.fillMaxWidth()) { if (!state.neurodiverseModeEnabled) MutedTrendline(Modifier.fillMaxWidth().height(72.dp)) else Text("Portfolio values remain available here. Trend motion is reduced.", style = MaterialTheme.typography.bodySmall) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.holdingTransactions.size) { i -> CoolDownTankCard(state.holdingTransactions[i], onConfirm = { onTankConfirm(it.id) }, onDismiss = { onTankDismiss(it.id) }) } }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun TabletLandscapeScreenPreview() {
    TabletLandscapeScreen(
        state = FinanceDashboardState(),
        portfolioValuePaise = 0,
        onTankConfirm = {},
        onTankDismiss = {}
    )
}
@Composable private fun NeurodiverseDailyFocus(safeToSpendPaise: Long?) = ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("One thing to focus on", style = MaterialTheme.typography.labelLarge); Text("Safe to spend today", style = MaterialTheme.typography.titleMedium); Text(safeToSpendPaise?.let { "₹ %.2f".format(it / 100.0) } ?: "Set a daily envelope", style = MaterialTheme.typography.headlineSmall) } }
@Composable private fun NumericEntryPad() { var laborMode by rememberSaveable { mutableStateOf(false) }; ElevatedCard { Column(Modifier.padding(12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (laborMode) "⏳ Labor" else "₹ Amount", Modifier.weight(1f)); Switch(laborMode, { laborMode = it }) }; Text("1   2   3\n4   5   6\n7   8   9\n    0", style = MaterialTheme.typography.titleLarge) } } }
@Composable private fun MutedTrendline(modifier: Modifier) = Canvas(modifier) { val p = Path().apply { moveTo(0f, size.height * .72f); cubicTo(size.width*.2f,size.height*.9f,size.width*.55f,size.height*.12f,size.width,size.height*.28f) }; drawPath(p, Color(0xFF718096), style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
