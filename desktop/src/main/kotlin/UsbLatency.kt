/**
 * Chooses the latency a USB-linked peer actually gets.
 *
 * The USB link is short and predictable, so it may shorten the configured
 * target but must never lengthen it: a user who asked for 20 ms does not want
 * 60 ms because the cable default happens to be higher.
 */
internal object UsbLatency {
    fun effective(configured: Int, usbLatencyMs: Int, ready: Boolean): Int =
        if (ready) minOf(configured, usbLatencyMs) else configured
}
