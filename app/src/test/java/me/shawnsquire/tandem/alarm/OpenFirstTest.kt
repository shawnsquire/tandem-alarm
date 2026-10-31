package me.shawnsquire.tandem.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenFirstTest {
    @Test fun firstThrowsSecondUsed() {
        val tried = mutableListOf<String>()
        val r = openFirst(listOf("a", "b", "c")) { if (it == "a") error("boom") else it.also { s -> tried += s } }
        assertEquals("b", r)
        assertEquals(listOf("b"), tried)
    }

    @Test fun nullResultMovesOn() {
        assertEquals("c", openFirst(listOf("a", "b", "c")) { if (it == "c") it else null })
    }

    @Test fun allFailYieldsNull() {
        assertNull(openFirst<String>(listOf("a", "b")) { throw java.io.IOException() })
        assertNull(openFirst<String>(emptyList()) { it })
    }
}
