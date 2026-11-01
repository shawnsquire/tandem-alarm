package me.shawnsquire.tandem.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class VibrationArbiterTest {
    private var starts = 0
    private var cancels = 0
    private val arbiter = VibrationArbiter({ starts++ }, { cancels++ })

    @Test
    fun staleOwnerStopDoesNotCancelNewOwner() {
        val a = Any()
        val b = Any()
        arbiter.start(a)
        arbiter.start(b)

        arbiter.stop(a)
        assertEquals(0, cancels)

        arbiter.stop(b)
        assertEquals(1, cancels)
        assertEquals(2, starts)
    }

    @Test
    fun vibrationStopsOnlyWhenTheLastOwnerStops() {
        val a = Any()
        val b = Any()
        arbiter.start(a)
        arbiter.start(b) // a merging session vibrates before it learns it plays nothing

        arbiter.stop(b)
        assertEquals(0, cancels)

        arbiter.stop(a)
        assertEquals(1, cancels)
    }

    @Test
    fun stopOfAnOwnerThatNeverStartedIsANoOp() {
        val a = Any()
        arbiter.start(a)
        arbiter.stop(Any())
        assertEquals(0, cancels)
    }

    @Test
    fun stopIsIdempotent() {
        val a = Any()
        arbiter.start(a)
        arbiter.stop(a)
        arbiter.stop(a)
        assertEquals(1, cancels)
    }
}
