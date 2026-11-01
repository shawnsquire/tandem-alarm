package me.shawnsquire.tandem.sync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.test.runTest
import me.shawnsquire.tandem.fakes.FakeLinkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InviteActionReceiverTest {
    private val app: Context = RuntimeEnvironment.getApplication()

    private fun intent(action: String) = Intent(app, InviteActionReceiver::class.java).setAction(action)
        .putExtra(InviteActionReceiver.EXTRA_COMMAND_ID, "c1")
        .putExtra(InviteActionReceiver.EXTRA_OWNER_ALARM_ID, "o9")

    @Test fun `join accepts the invite`() = runTest {
        val repo = FakeLinkRepository()
        handleInviteAction(repo, intent(InviteActionReceiver.ACTION_JOIN))
        assertEquals(listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.AcceptInvite("c1", "o9")), repo.calls)
    }

    @Test fun `decline declines the invite`() = runTest {
        val repo = FakeLinkRepository()
        handleInviteAction(repo, intent(InviteActionReceiver.ACTION_DECLINE))
        assertEquals(listOf<FakeLinkRepository.Call>(FakeLinkRepository.Call.DeclineInvite("c1")), repo.calls)
    }

    @Test fun `missing extras or unknown action do nothing`() = runTest {
        val repo = FakeLinkRepository()
        handleInviteAction(repo, Intent(InviteActionReceiver.ACTION_JOIN))
        handleInviteAction(repo, intent("nope"))
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun `a failed join still returns normally`() = runTest {
        val repo = FakeLinkRepository().also { it.acceptResult = LinkResult.Failed("Sam deleted this alarm") }
        handleInviteAction(repo, intent(InviteActionReceiver.ACTION_JOIN))
        assertEquals(1, repo.calls.size)
    }
}
