package me.shawnsquire.tandem.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Emits immediately, then every [period]; drives "rings in ..." text refreshes. */
fun tickerFlow(period: Duration = 30.seconds): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(period)
    }
}
