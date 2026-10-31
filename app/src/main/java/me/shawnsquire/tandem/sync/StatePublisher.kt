package me.shawnsquire.tandem.sync

/** Mirrors local alarm/ring state to Firestore (I2: owner is the only writer of its own data). */
interface StatePublisher {
    fun start()
}
