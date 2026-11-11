/** A source remains online through valid PCM, explicit silence or PING, independently of playback. */
internal class DesktopReceiverLiveness(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    @Volatile var lastActivityMs: Long = clockMs()
        private set

    fun onValidActivity() { lastActivityMs = clockMs() }
    fun hasExpired(): Boolean = clockMs() - lastActivityMs > 30_000
}

/** A local restart must not turn into the sender's explicit user-stop command. */
internal enum class DesktopReceiverStopReason { USER_STOP, RESTART }
