package me.shawnsquire.tandem.sync

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TasksTest {
    @Test fun `returns the result`() = runTest {
        val src = TaskCompletionSource<String>()
        val d = async(start = CoroutineStart.UNDISPATCHED) { src.task.await() }
        src.setResult("ok")
        assertEquals("ok", d.await())
    }

    @Test fun `rethrows the task's exception`() = runTest {
        val src = TaskCompletionSource<String>()
        val d = async(start = CoroutineStart.UNDISPATCHED) { runCatching { src.task.await() } }
        src.setException(IllegalStateException("boom"))
        assertEquals("boom", d.await().exceptionOrNull()?.message)
    }

    @Test fun `a cancelled await ignores the task finishing later`() = runTest {
        val src = TaskCompletionSource<String>()
        val d = async(start = CoroutineStart.UNDISPATCHED) { src.task.await() }
        d.cancel()
        runCurrent()
        src.setResult("late")
        runCurrent()
        assertTrue(d.isCancelled)
    }
}
