import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger

/** Startup/output/approval checks avoid real devices, user settings and native capture. */
internal object DesktopGuiRequestChecks {
    @JvmStatic fun main(args: Array<String>) = runChecks()

    fun runChecks() = runBlocking {
        delayedStartupAndCancellation()
        currentOutputWinsAfterRestore()
        endpointsDoNotBorrowOtherPortsOrModes()
        pendingApprovalOwnsItsHeartbeat()
        println("GUI request readiness/cancellation, current output, exact endpoint and bounded ASK checks passed")
    }

    private suspend fun delayedStartupAndCancellation() = coroutineScope {
        var device: String? = null
        val executions = AtomicInteger()
        val startup = launch {
            val selected = awaitDesktopStartupReady({ device }, { false }, pollMs = 5) ?: return@launch
            check(selected == "headphones")
            executions.incrementAndGet()
        }
        delay(350) // The previous one-shot 300 ms check had already abandoned this request.
        check(executions.get() == 0 && startup.isActive)
        device = "headphones"
        startup.join()
        device = "other-output"
        delay(20)
        check(executions.get() == 1) { "One startup intent executed more than once" }

        var stopped = false
        val cancelled = async {
            awaitDesktopStartupReady({ null as String? }, { stopped }, pollMs = 5)
        }
        stopped = true
        check(cancelled.await() == null) { "Explicit stop did not consume waiting startup intent" }
        val owner = launch { awaitDesktopStartupReady({ null as String? }, { false }, pollMs = 5) }
        owner.cancelAndJoin()
        check(owner.isCancelled)
    }

    private fun currentOutputWinsAfterRestore() {
        var chosen: String? = "headphones"
        var fallbacks = 0
        val openRestored = {
            desktopReceiverOutput<String>(null, { chosen }) { fallbacks++; "first-speaker" }
        }
        check(openRestored() == "headphones")
        chosen = "usb-output"
        check(openRestored() == "usb-output") { "Restored request retained an obsolete output" }
        chosen = null
        check(runCatching { openRestored() }.exceptionOrNull() is PlaybackOutputFailure)
        check(fallbacks == 0) { "Missing explicit output silently selected the first mixer" }
        check(desktopReceiverOutput("cli-device", null) { "default" } == "cli-device")
    }

    private fun endpointsDoNotBorrowOtherPortsOrModes() {
        val wrongPort = ServerInfo("192.0.2.3", true, 9091)
        val requested = ServerInfo("192.0.2.3", false, 9090)
        check(DesktopDiscoveredPeer.find(listOf(wrongPort, requested), requested.ip, requested.port) === requested)
        check(DesktopDiscoveredPeer.find(listOf(wrongPort), requested.ip, requested.port) == null)
        val wrongMode = requested.copy(isMulticast = true)
        check(!DesktopDiscoveredPeer.matches(wrongMode, requested.ip, requested.port, false))
        check(DesktopDiscoveredPeer.find(listOf(requested, wrongMode), requested.ip, requested.port) == null)
    }

    private suspend fun pendingApprovalOwnsItsHeartbeat() = coroutineScope {
        val packets = Channel<Unit>(Channel.UNLIMITED)
        val sends = AtomicInteger()
        val approval = async {
            awaitDesktopApproval({ delay(300); true }, {
                sends.incrementAndGet(); packets.send(Unit)
            }, intervalMs = 20, timeoutMs = 2_000)
        }
        var received = 0
        // A short receiver watchdog must remain alive throughout a substantially longer prompt.
        while (true) {
            val pending = withTimeout(150) {
                select<Boolean> {
                    approval.onAwait { false }
                    packets.onReceive { received++; true }
                }
            }
            if (!pending) break
        }
        check(approval.await() == DesktopApproval.APPROVED && received >= 5)
        val afterApproval = sends.get()
        delay(60)
        check(sends.get() == afterApproval) { "PENDING task survived approval" }

        var promptClosed = false
        val timedOut = awaitDesktopApproval({
            try { awaitCancellation() } finally { promptClosed = true }
        }, {}, intervalMs = 10, timeoutMs = 60)
        check(timedOut == DesktopApproval.TIMED_OUT && promptClosed)

        promptClosed = false
        val waiting = launch {
            awaitDesktopApproval({
                try { awaitCancellation() } finally { promptClosed = true }
            }, { sends.incrementAndGet() }, intervalMs = 10, timeoutMs = 2_000)
        }
        yield()
        waiting.cancelAndJoin()
        val afterCancel = sends.get()
        delay(40)
        check(promptClosed && sends.get() == afterCancel) { "Stopped handshake retained prompt/PENDING work" }
        var attempts = 0
        promptClosed = false
        val failure = runCatching {
            awaitDesktopApproval({
                try { awaitCancellation() } finally { promptClosed = true }
            }, {
                if (++attempts == 2) error("send failed")
            }, intervalMs = 10, timeoutMs = 60)
        }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "send failed" && promptClosed)
    }
}
