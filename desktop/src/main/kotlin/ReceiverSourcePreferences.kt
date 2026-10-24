/** Stable persistence projection: transport progress/status must never trigger a config write. */
internal data class ReceiverSourcePreferences(
    val id: String,
    val gain: Float,
    val muted: Boolean,
    val adaptive: Boolean,
    val manualLatencyMs: Int,
    val paused: Boolean
) {
    init {
        require(id.isNotBlank()) { "receiver preferences require a source id" }
        require(gain.isFinite() && gain in 0f..1f) { "receiver preference gain must be finite and between 0 and 1" }
        require(manualLatencyMs in 20..400) { "receiver preference latency must be between 20 and 400 ms" }
    }

    companion object {
        fun from(state: DesktopReceiverSourceState): ReceiverSourcePreferences = ReceiverSourcePreferences(
            state.id, state.gain, state.muted, state.adaptive, state.manualLatencyMs, state.paused
        )
    }
}
