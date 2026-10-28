package com.cuscus.wifiaudiostreaming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CuePlaybackLifetimeTest {
    private class Player {
        var finish: ((String?) -> Unit)? = null
        var starts = 0
        val releases = AtomicInteger()
    }

    private class Deadline(val fire: () -> Unit) {
        var cancelled = false
    }

    private class Fixture(
        onStart: (Player) -> Unit = {},
        onRelease: (Player) -> Unit = {},
        onCancel: () -> Unit = {},
        onSchedule: () -> Unit = {},
        synchronousTimeout: Boolean = false
    ) {
        val deadlines = mutableListOf<Deadline>()
        val failures = mutableListOf<String>()
        val lifetime = CuePlaybackLifetime<Player>(
            start = { player, finish ->
                player.finish = finish
                player.starts++
                onStart(player)
            },
            release = { player -> player.releases.incrementAndGet(); onRelease(player) },
            scheduleTimeout = { fire ->
                onSchedule()
                val deadline = Deadline(fire)
                deadlines.add(deadline)
                if (synchronousTimeout) fire()
                val cancel: () -> Unit = { deadline.cancelled = true; onCancel() }
                cancel
            },
            reportFailure = { synchronized(failures) { failures.add(it) } }
        )
    }

    @Test fun playerRemainsOwnedUntilCompletionAndTheDeadlineIsCancelled() {
        val fixture = Fixture()
        val player = Player()
        fixture.lifetime.play { player }
        assertEquals(1, fixture.lifetime.activeCount)
        assertEquals(1, player.starts)
        assertEquals(0, player.releases.get())
        player.finish!!(null)
        assertEquals(0, fixture.lifetime.activeCount)
        assertEquals(1, player.releases.get())
        assertTrue(fixture.deadlines.single().cancelled)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun playbackErrorCompletionAndStaleDeadlineCannotReleaseTwice() {
        val fixture = Fixture()
        val player = Player()
        fixture.lifetime.play { player }
        player.finish!!("phase=playback what=1 extra=2")
        player.finish!!(null)
        fixture.deadlines.single().fire()
        assertEquals(1, player.releases.get())
        assertEquals(listOf("phase=playback what=1 extra=2"), fixture.failures)
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun missingPlatformCallbacksAreBoundedByTheDeadline() {
        val fixture = Fixture()
        val player = Player()
        fixture.lifetime.play { player }
        fixture.deadlines.single().fire()
        assertEquals(1, player.releases.get())
        assertEquals(listOf("phase=timeout"), fixture.failures)
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun playerCreatedAfterTimeoutIsReleasedWithoutStarting() {
        val fixture = Fixture()
        val player = Player()
        fixture.lifetime.play {
            fixture.deadlines.single().fire()
            player
        }
        assertEquals(0, player.starts)
        assertEquals(1, player.releases.get())
        assertEquals(listOf("phase=timeout"), fixture.failures)
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun synchronousTimeoutCancelsTheHandleWhenItArrives() {
        val fixture = Fixture(synchronousTimeout = true)
        val player = Player()
        fixture.lifetime.play { player }
        assertTrue(fixture.deadlines.single().cancelled)
        assertEquals(0, player.starts)
        assertEquals(1, player.releases.get())
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun synchronousCompletionDuringStartCannotLeakTheDeadline() {
        val fixture = Fixture(onStart = { it.finish!!(null) })
        val player = Player()
        fixture.lifetime.play { player }
        assertEquals(1, player.releases.get())
        assertTrue(fixture.deadlines.single().cancelled)
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun watchdogSchedulingFailureDoesNotCreateOrRetainAPlayer() {
        val fixture = Fixture(onSchedule = { throw IllegalStateException("fixture") })
        var created = false
        fixture.lifetime.play { created = true; Player() }
        assertFalse(created)
        assertEquals(0, fixture.lifetime.activeCount)
        assertEquals(listOf("phase=timeout_schedule type=IllegalStateException"), fixture.failures)
    }

    @Test fun lateCancellationFailureIsReportedWithoutSkippingLatePlayerRelease() {
        val fixture = Fixture(
            onCancel = { throw IllegalStateException("fixture") }, synchronousTimeout = true)
        val player = Player()
        fixture.lifetime.play { player }
        assertEquals(1, player.releases.get())
        assertEquals(0, player.starts)
        assertEquals(0, fixture.lifetime.activeCount)
        assertEquals(listOf("phase=timeout", "phase=timeout_cancel type=IllegalStateException"), fixture.failures)
    }

    @Test fun createFailureRemovesItsPendingOwnerAndCancelsTheDeadline() {
        val fixture = Fixture()
        fixture.lifetime.play { throw IllegalStateException("fixture") }
        assertEquals(0, fixture.lifetime.activeCount)
        assertTrue(fixture.deadlines.single().cancelled)
        assertEquals(listOf("phase=create type=IllegalStateException"), fixture.failures)
    }

    @Test fun startFailureReleasesTheAlreadyAttachedPlayer() {
        val fixture = Fixture(onStart = { throw IllegalStateException("fixture") })
        val player = Player()
        fixture.lifetime.play { player }
        assertEquals(1, player.releases.get())
        assertEquals(0, fixture.lifetime.activeCount)
        assertTrue(fixture.deadlines.single().cancelled)
        assertEquals(listOf("phase=start type=IllegalStateException"), fixture.failures)
    }

    @Test fun releaseFailureDoesNotLeaveAnActiveOwnerOrDeadline() {
        val fixture = Fixture(onRelease = { throw IllegalStateException("fixture") })
        val player = Player()
        fixture.lifetime.play { player }
        player.finish!!(null)
        fixture.deadlines.single().fire()
        assertEquals(1, player.releases.get())
        assertEquals(0, fixture.lifetime.activeCount)
        assertTrue(fixture.deadlines.single().cancelled)
        assertEquals(listOf("phase=release type=IllegalStateException"), fixture.failures)
    }

    @Test fun concurrentCuesHaveIndependentOwnersAndDeadlines() {
        val fixture = Fixture()
        val first = Player()
        val second = Player()
        fixture.lifetime.play { first }
        fixture.lifetime.play { second }
        assertEquals(2, fixture.lifetime.activeCount)
        first.finish!!(null)
        assertEquals(1, fixture.lifetime.activeCount)
        assertTrue(fixture.deadlines[0].cancelled)
        assertFalse(fixture.deadlines[1].cancelled)
        assertEquals(0, second.releases.get())
        fixture.deadlines[1].fire()
        assertEquals(1, second.releases.get())
        assertEquals(0, fixture.lifetime.activeCount)
    }

    @Test fun racingCompletionAndTimeoutReleaseExactlyOnce() {
        val fixture = Fixture()
        val player = Player()
        fixture.lifetime.play { player }
        val gate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val completion = workers.submit { gate.await(); player.finish!!(null) }
            val timeout = workers.submit { gate.await(); fixture.deadlines.single().fire() }
            gate.countDown()
            completion.get(2, TimeUnit.SECONDS)
            timeout.get(2, TimeUnit.SECONDS)
            assertEquals(1, player.releases.get())
            assertEquals(0, fixture.lifetime.activeCount)
            assertTrue(fixture.deadlines.single().cancelled)
            assertTrue(fixture.failures.isEmpty() || fixture.failures == listOf("phase=timeout"))
        } finally {
            workers.shutdownNow()
        }
    }
}
