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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

internal fun formatMoney(paise: Long, currencyCode: String = "INR", includeSign: Boolean = false): String =
    MoneyFormatter.format(paise, currencyCode, includeSign)

private fun dateFormatter(preference: DateFormatPreference): DateTimeFormatter = when (preference) {
    DateFormatPreference.DEVICE_LOCALE -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    DateFormatPreference.DD_MMM_YYYY -> DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
    DateFormatPreference.DD_MM_YYYY -> DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())
    DateFormatPreference.MM_DD_YYYY -> DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.getDefault())
    DateFormatPreference.YYYY_MM_DD -> DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
}

internal fun formatDate(date: LocalDate, preference: DateFormatPreference): String = date.format(dateFormatter(preference))

internal fun formatDate(epochMs: Long, preference: DateFormatPreference): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .let { formatDate(it, preference) }

internal fun dateFormatPreferenceLabel(preference: DateFormatPreference): String = when (preference) {
    DateFormatPreference.DEVICE_LOCALE -> "Device locale"
    DateFormatPreference.DD_MMM_YYYY -> "26 Aug 2026"
    DateFormatPreference.DD_MM_YYYY -> "26/08/2026"
    DateFormatPreference.MM_DD_YYYY -> "08/26/2026"
    DateFormatPreference.YYYY_MM_DD -> "2026-08-26"
}

internal data class InvestmentPerformance(val absolutePaise: Long, val absolutePercent: Double?, val xirrPercent: Double?)

internal fun cashFlowsForXirr(history: List<InvestmentBalanceSnapshotEntity>): List<Pair<Long, Long>> {
    val ordered = history.sortedBy { it.asOfEpochDay }
    val first = ordered.firstOrNull() ?: return emptyList()
    if (first.totalCostPaise <= 0L) return emptyList()
    return buildList {
        add(first.asOfEpochDay to -first.totalCostPaise)
        ordered.drop(1).filter { it.netContributionPaise != 0L }.forEach { snapshot ->
            add(snapshot.asOfEpochDay to -snapshot.netContributionPaise)
        }
        add(ordered.last().asOfEpochDay to ordered.last().currentValuePaise)
    }
}

internal fun investmentPerformance(history: List<InvestmentBalanceSnapshotEntity>): InvestmentPerformance? {
    val latest = history.maxByOrNull { it.asOfEpochDay } ?: return null
    val absolutePaise = latest.currentValuePaise - latest.totalCostPaise
    val absolutePercent = latest.totalCostPaise.takeIf { it != 0L }?.let { absolutePaise * 100.0 / it }
    return InvestmentPerformance(absolutePaise, absolutePercent, FinancialCalculations.xirrPercent(cashFlowsForXirr(history)))
}

internal fun formatPercent(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"

@Composable
internal fun OneLineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun DhanamActionText(text: String) {
    OneLineText(text = text, style = MaterialTheme.typography.labelLarge)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DhanamScreenTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = StandardBackIcon,
                    contentDescription = "Back",
                )
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = actions,
    )
}

internal val StandardBackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.42f, 18.59f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            close()
        }
    }.build()
}

