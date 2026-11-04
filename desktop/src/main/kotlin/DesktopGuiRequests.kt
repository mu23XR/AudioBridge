import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A GUI request waits for enumeration without becoming a new auto-reconnect policy. */
internal suspend fun <T : Any> awaitDesktopStartupReady(
    ready: () -> T?,
    cancelled: () -> Boolean,
    pollMs: Long = 50
): T? {
    require(pollMs > 0)
    while (true) {
        currentCoroutineContext().ensureActive()
        if (cancelled()) return null
        ready()?.let { return it }
        delay(pollMs)
    }
}

/** A present provider owns the GUI choice; its missing device must not select a different output. */
internal fun <T : Any> desktopReceiverOutput(
    fixed: T?, current: (() -> T?)?, fallback: () -> T?
): T = (if (current != null) current() else fixed ?: fallback())
    ?: throw PlaybackOutputFailure("The selected audio output is unavailable")

internal object DesktopDiscoveredPeer {
    fun matches(peer: ServerInfo, ip: String, port: Int, multicast: Boolean): Boolean =
        peer.ip == ip && peer.port == port && peer.isMulticast == multicast

    /** A conflicting mode needs explicit probing/pairing, rather than arbitrary list order. */
    fun find(peers: Collection<ServerInfo>, ip: String, port: Int): ServerInfo? {
        val matching = peers.filter { it.ip == ip && it.port == port }
        val mode = matching.map { it.isMulticast }.distinct().singleOrNull() ?: return null
        return matching.firstOrNull { matches(it, ip, port, mode) }
    }
}

internal enum class DesktopApproval { APPROVED, DENIED, TIMED_OUT }

/** The prompt and PENDING sender have the same bounded owner as the server handshake. */
internal suspend fun awaitDesktopApproval(
    request: suspend () -> Boolean,
    sendPending: suspend () -> Unit,
    intervalMs: Long = 2_000,
    timeoutMs: Long = 30_000
): DesktopApproval {
    require(intervalMs > 0 && timeoutMs > intervalMs)
    val allowed = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            // Send the first response before showing a possibly slow prompt.
            sendPending()
            val pending = launch {
                while (isActive) {
                    delay(intervalMs)
                    sendPending()
                }
            }
            try { request() } finally { pending.cancelAndJoin() }
        }
    }
    return when (allowed) {
        true -> DesktopApproval.APPROVED
        false -> DesktopApproval.DENIED
        null -> DesktopApproval.TIMED_OUT
    }
}
