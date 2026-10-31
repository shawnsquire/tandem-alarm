package me.shawnsquire.tandem.sync

import me.shawnsquire.tandem.schedule.Command

/** Owner-side: validates (I3) and applies a partner command to local state, then republishes (I2). Idempotent by command id. */
interface CommandApplier {
    suspend fun apply(cmd: Command)
}
