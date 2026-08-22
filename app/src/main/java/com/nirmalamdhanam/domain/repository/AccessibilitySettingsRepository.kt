package com.nirmalamdhanam.domain.repository

import kotlinx.coroutines.flow.Flow

interface AccessibilitySettingsRepository {
    fun observeNeurodiverseMode(): Flow<Boolean>
    /** Returns false only when the profile has not been configured yet. */
    suspend fun setNeurodiverseMode(enabled: Boolean): Boolean
}
