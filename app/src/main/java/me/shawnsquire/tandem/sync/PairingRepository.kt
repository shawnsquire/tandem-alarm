package me.shawnsquire.tandem.sync

import kotlinx.coroutines.flow.Flow

sealed interface PairingStatus {
    data object Unpaired : PairingStatus
    data class Paired(val householdId: String, val partnerName: String?) : PairingStatus
}

interface PairingRepository {
    val status: Flow<PairingStatus>
    suspend fun createCode(): String
    suspend fun join(code: String)
    suspend fun unpair()
}
