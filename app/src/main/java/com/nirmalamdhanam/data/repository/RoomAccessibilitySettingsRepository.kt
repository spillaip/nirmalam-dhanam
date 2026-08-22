package com.nirmalamgroup.nirmalamdhanam.data.repository

import com.nirmalamgroup.nirmalamdhanam.data.local.ConfigDao
import com.nirmalamgroup.nirmalamdhanam.data.local.DatabaseAccessGate
import com.nirmalamgroup.nirmalamdhanam.domain.repository.AccessibilitySettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomAccessibilitySettingsRepository(
    private val configDao: ConfigDao,
    private val io: CoroutineDispatcher
) : AccessibilitySettingsRepository {
    override fun observeNeurodiverseMode(): Flow<Boolean> = configDao.observe().map { it?.neurodiverseModeEnabled ?: false }
    override suspend fun setNeurodiverseMode(enabled: Boolean): Boolean = withContext(io) {
        DatabaseAccessGate.writeLock.withLock { configDao.setNeurodiverseMode(enabled) == 1 }
    }
}
