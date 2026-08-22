package com.nirmalamdhanam.domain.usecase

import com.nirmalamdhanam.domain.repository.AccessibilitySettingsRepository
import kotlinx.coroutines.flow.Flow

class ObserveNeurodiverseModeUseCase(private val settings: AccessibilitySettingsRepository) {
    operator fun invoke(): Flow<Boolean> = settings.observeNeurodiverseMode()
}

class SetNeurodiverseModeUseCase(private val settings: AccessibilitySettingsRepository) {
    suspend operator fun invoke(enabled: Boolean): Boolean = settings.setNeurodiverseMode(enabled)
}
