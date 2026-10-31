package me.shawnsquire.tandem

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun packageNameIsExpected() {
        assertEquals("me.shawnsquire.tandem", TandemApp::class.java.`package`?.name)
    }
}
