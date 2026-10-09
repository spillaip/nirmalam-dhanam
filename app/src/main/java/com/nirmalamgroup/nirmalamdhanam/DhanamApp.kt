package com.nirmalamgroup.nirmalamdhanam

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiClient
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiInsight
import com.nirmalamgroup.nirmalamdhanam.data.ai.NirmalamAiPreferences
import com.nirmalamgroup.nirmalamdhanam.data.ai.prepareNirmalamAiContext
import com.nirmalamgroup.nirmalamdhanam.data.local.*
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.AccountRolePolicy
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CoolDownTankInterceptorUseCase
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.MoneyFormatter
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialCalculations
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentPerformanceMetric
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.InvestmentDeltaEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimelineBuilder
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.ExplainableInsightEngine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialHealthCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.GoalProgressCalculator
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.FinancialTimeMachine
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalFinancialCopilot
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.LocalDayClock
import com.nirmalamgroup.nirmalamdhanam.domain.usecase.CopilotResult
import com.nirmalamgroup.nirmalamdhanam.ui.components.CoolDownTankCard
import com.nirmalamgroup.nirmalamdhanam.ui.components.NeurodiverseModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

private val NirmalamLightColors = lightColorScheme(
    primary = Color(0xFF176B4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4E5),
    onPrimaryContainer = Color(0xFF0A3828),
    secondary = Color(0xFF4C665A),
    secondaryContainer = Color(0xFFD7E8DE),
    tertiary = Color(0xFF53643B),
    tertiaryContainer = Color(0xFFDCE8C3),
    background = Color(0xFFF8FBF8),
    surface = Color(0xFFF8FBF8),
    surfaceVariant = Color(0xFFE2EAE5),
    outlineVariant = Color(0xFFC3CEC7)
)

private val NirmalamDarkColors = darkColorScheme(
    primary = Color(0xFFA8DCC3),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF0B5139),
    onPrimaryContainer = Color(0xFFC4F1DA),
    secondary = Color(0xFFB8CCC0),
    secondaryContainer = Color(0xFF334B40),
    tertiary = Color(0xFFC0D2A5),
    tertiaryContainer = Color(0xFF3D4C28),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF3F4943),
    outlineVariant = Color(0xFF404943)
)

@Composable
internal fun NirmalamMvpApp(viewModel: NirmalamMvpViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    
    var pendingMetrics by remember { mutableStateOf<List<InvestmentPerformanceMetric>>(emptyList()) }
    val createPdfFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { dest ->
            PdfReportExporter(context).exportPerformanceReport(dest, state.currencyCode, pendingMetrics)
        }
        pendingMetrics = emptyList()
    }

    val colorScheme = if (isSystemInDarkTheme()) NirmalamDarkColors else NirmalamLightColors
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize()) {
            if (state.isUnlocked) MvpHome(
                state,
                { name, product, assetClass, target, openingBalance, cashKind, onCreated -> viewModel.createAccount(name, product, assetClass, target, openingBalance, cashKind, onCreated) },
                viewModel::updateAccount, viewModel::archiveInvestmentAccount, viewModel::saveInvestmentBalance, viewModel::updateInvestmentBalance, viewModel::contributeToInvestment, viewModel::deleteInvestmentSnapshot, viewModel::deleteTransaction, viewModel::updateTransaction, viewModel::recordTransaction, viewModel::setNeurodiverseMode, viewModel::setCurrency, viewModel::setDateFormatPreference, viewModel::setSavedLedgerView, viewModel::saveNirmalamAi, viewModel::disableNirmalamAi, viewModel::requestNirmalamAiInsight, viewModel::exportInterchangeReport, viewModel::importInterchangeReport, viewModel::createGoal, viewModel::archiveGoal, viewModel::setGoalAllocation, viewModel::removeGoalAllocation, viewModel::exportNdfBackup, viewModel::importNdfBackup, viewModel::saveCategory, viewModel::updateCategory, viewModel::deleteCategory, viewModel::savePayee, viewModel::updatePayee, viewModel::deletePayee, viewModel::exportTransactionsCsv, viewModel::importTransactionsCsv, viewModel::confirmTransactionsCsvImport, viewModel::cancelTransactionsCsvImport, viewModel::removeStarterData, viewModel::confirmPurchase, viewModel::discardPurchase, viewModel::setShowInvestmentPerformance,
                { metrics ->
                    pendingMetrics = metrics
                    createPdfFile.launch("nivesha-performance-report.pdf")
                },
                viewModel::clearMessage
            )
            else UnlockScreen(state.isLoading, state.message, viewModel::unlock, viewModel::clearMessage)
        }
    }
}

@Composable
private fun UnlockScreen(loading: Boolean, message: String?, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Nirmalam Dhanam", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Your finances stay encrypted on this device.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { OneLineText("Passphrase") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onUnlock(passphrase); passphrase = "" }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { DhanamActionText(if (loading) "Unlocking…" else "Unlock") }
        message?.let { Spacer(Modifier.height(12.dp)); AssistChip(onClick = onDismiss, label = { OneLineText(it, style = MaterialTheme.typography.labelMedium) }) }
    }
}

